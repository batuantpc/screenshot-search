package com.projectmemento.app.data

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.edit
import com.projectmemento.app.media.MediaFailure
import com.projectmemento.app.media.ScreenshotMediaScanner
import com.projectmemento.app.permissions.PhotoAccess
import com.projectmemento.app.permissions.PhotoAccessLevel
import com.projectmemento.app.processing.ImageLabelProcessor
import com.projectmemento.app.processing.IntentClassifier
import com.projectmemento.app.processing.OcrLayout
import com.projectmemento.app.processing.OcrProcessor
import com.projectmemento.app.processing.VisualLabel
import com.projectmemento.app.processing.VisualLabels
import com.projectmemento.app.search.SearchSpec
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ScreenshotRepository(
    context: Context,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val database = ScreenshotDatabase(appContext)
    private val mediaScanner = ScreenshotMediaScanner(appContext)
    private val ocrProcessor = OcrProcessor(appContext)
    private val imageLabelProcessor = ImageLabelProcessor(appContext)
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val processing = AtomicBoolean(false)
    private var observerRegistered = false

    /**
     * Bu işleme turunda okunamadığı için arşivlenen kayıt sayısı.
     *
     * Neden sayaç: her hatada ayrı uyarı basmak, buluta boşaltılmış bir kütüphanede ekranı
     * yüzlerce kez aynı mesajla dolduruyordu. Kuyruk boşalınca TEK bir özet veriyoruz.
     */
    private val unreadableInRun = AtomicInteger(0)
    private val exhaustedInRun = AtomicInteger(0)

    @Volatile
    private var accessLevel: PhotoAccessLevel = PhotoAccessLevel.NONE

    var onDataChanged: (() -> Unit)? = null
    var onBusyChanged: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onDiagnosticsChanged: ((Map<String, Int>) -> Unit)? = null

    private val mediaObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            refresh(retryFailedOcr = false)
        }
    }

    fun setAccess(accessLevel: PhotoAccessLevel) {
        this.accessLevel = accessLevel
        resetScanWatermarkIfAccessWidened(accessLevel)
        if (accessLevel == PhotoAccessLevel.NONE) {
            stopObserving()
            return
        }
        startObserving()
        refresh(retryFailedOcr = false)
    }

    fun refresh(retryFailedOcr: Boolean = false) {
        notifyBusy(true)
        executor.execute {
            runCatching {
                refreshDerivedMetadataIfNeeded()
                val previousScan = preferences.getLong(LAST_SCAN_SECONDS, 0L)
                if (previousScan == 0L) {
                    // Ilk tam tarama SINIRSIZ olmak zorunda. Sabit limitli surum kutuphanenin
                    // yalnizca en yeni N kaydini goruyordu; gerisi hic kesfedilmiyor ve sonraki
                    // taramalar tarih filtreli oldugu icin bir daha ASLA gorulmuyordu.
                    // Gercek olcekte dogrulandi: 966 dosyanin 346'si sessizce kayboldu.
                    mediaScanner.scanAll { batch -> database.insertDiscovered(batch) }
                } else {
                    val addedAfter = previousScan - SCAN_OVERLAP_SECONDS
                    database.insertDiscovered(
                        mediaScanner.scan(
                            limit = INCREMENTAL_SCAN_LIMIT,
                            addedAfterSeconds = addedAfter,
                        ),
                    )
                }
                // Yalnizca kullanicinin bastigi yenileme, OCR hakki tukenmis kayitlari
                // tekrar kuyruga alir. App resume/MediaStore observer bunu yapmaz.
                if (retryFailedOcr) database.resetOcrAttempts()
                reconcileDeletedScreenshots()
                preferences.edit {
                    putLong(LAST_SCAN_SECONDS, System.currentTimeMillis() / 1_000L)
                }
            }.onFailure { error ->
                // Ham exception metni kullaniciya gitmez; ayrinti logcat'e.
                Log.w(LOG_TAG, "Tarama basarisiz", error)
                notifyError("Screenshotlar okunamadı. Yenilemeyi tekrar dene.")
            }
            notifyDataChanged()
            processPending()
        }
    }

    /**
     * Erişim genişlediğinde artımlı tarama işaretini sıfırlar.
     *
     * Tarama yalnızca "son taramadan sonra eklenen" MediaStore kayıtlarına bakıyor. Kullanıcı
     * seçili fotoğraflara yenilerini eklediğinde ya da tam erişime geçtiğinde, yeni erişilebilir
     * olan ESKİ tarihli screenshotlar bu filtreye takılıp hiç görünmezdi.
     */
    private fun resetScanWatermarkIfAccessWidened(newLevel: PhotoAccessLevel) {
        val previous = preferences.getString(LAST_ACCESS_LEVEL, null)
        val levelChanged = previous != newLevel.name
        // Kısmi erişimde seviye aynı kalsa bile kullanıcı seçime yeni fotoğraf eklemiş
        // olabilir; kapsamı sistemden okuyamadığımız için PARTIAL'da her zaman tam tarıyoruz.
        val needsFullScan = levelChanged || newLevel == PhotoAccessLevel.PARTIAL
        if (!needsFullScan) return

        preferences.edit {
            putString(LAST_ACCESS_LEVEL, newLevel.name)
            if (newLevel != PhotoAccessLevel.NONE) putLong(LAST_SCAN_SECONDS, 0L)
        }
    }

    /**
     * Cihazdan silinen screenshotların kayıtlarını işaretler.
     *
     * Yalnızca TAM erişimde çalışır. Kısmi erişimde (Android 14+ "seçili fotoğraflar")
     * MediaStore zaten sadece seçilenleri döndürür; seçilmemiş bir görseli "silinmiş"
     * saymak kullanıcının kütüphanesini sessizce boşaltırdı.
     */
    private fun reconcileDeletedScreenshots() {
        if (accessLevel != PhotoAccessLevel.FULL) return
        val stored = database.liveMediaStoreIds()
        if (stored.isEmpty()) return

        // Sorgu başarısız olursa null gelir; "silinmiş" saymak yerine hiçbir şey yapmıyoruz.
        val alive = mediaScanner.existingIds(stored.keys) ?: return

        // Sorgu sürerken kullanıcı izni FULL'dan PARTIAL'a düşürmüş olabilir; o durumda
        // MediaStore artık yalnızca seçili fotoğrafları döndürür ve sonuç yanıltıcıdır.
        if (PhotoAccess.current(appContext) != PhotoAccessLevel.FULL) return

        val missingRowIds = stored.filterKeys { it !in alive }.values
        if (missingRowIds.isEmpty()) return

        val marked = database.markMissing(missingRowIds, System.currentTimeMillis())
        Log.i(LOG_TAG, "Cihazdan silinen $marked screenshot kaydı arşivlendi.")
    }

    private fun refreshDerivedMetadataIfNeeded() {
        val storedVersion = preferences.getInt(DERIVED_METADATA_VERSION_KEY, 0)
        if (storedVersion >= DERIVED_METADATA_VERSION) return

        reclassifyLibrary()
        preferences.edit {
            putInt(DERIVED_METADATA_VERSION_KEY, DERIVED_METADATA_VERSION)
        }
    }

    private fun reclassifyLibrary() {
        database.reclassifyProcessed { ocrText, ocrLayout, imageLabels, displayName ->
            classifyRefined(
                ocrText = ocrText,
                displayName = displayName,
                layout = OcrLayout.deserialize(ocrLayout),
                labels = VisualLabels.parse(imageLabels),
            )
        }
    }

    /** Açıklanabilir ve yüksek kesinlikli sınıflandırma yolu. */
    private fun classifyRefined(
        ocrText: String,
        displayName: String,
        layout: OcrLayout?,
        labels: List<VisualLabel>,
    ): ClassificationResult = IntentClassifier.classify(ocrText, displayName, layout, labels)

    fun loadInitial(
        spec: SearchSpec?,
        reminderRoundIds: List<Long>,
        nowMillis: Long,
        onLoaded: (LibraryLoad) -> Unit,
    ) {
        executor.execute {
            val loaded = runCatching {
                val page = database.page(spec = spec, limit = UI_PAGE_SIZE)
                val recent = database.page(limit = HOME_RECENT_LIMIT).items
                val reminderRound = database.listByIds(reminderRoundIds)
                val resurfacing = database.resurfacingCandidates(nowMillis)
                LibraryLoad(
                    summary = database.librarySummary(),
                    homeItems = (recent + reminderRound + resurfacing).distinctBy(ScreenshotItem::id),
                    page = page,
                )
            }
                .onFailure { error -> notifyError(error.message ?: "Screenshotlar yüklenemedi.") }
                .getOrElse {
                    LibraryLoad(
                        summary = LibrarySummary(),
                        homeItems = emptyList(),
                        page = ScreenshotPage(emptyList(), null, 0),
                    )
                }
            mainHandler.post { onLoaded(loaded) }
        }
    }

    fun loadNext(
        spec: SearchSpec?,
        cursor: ScreenshotCursor,
        onLoaded: (ScreenshotPage) -> Unit,
    ) {
        executor.execute {
            val page = runCatching {
                database.page(spec = spec, cursor = cursor, limit = UI_PAGE_SIZE)
            }
                .onFailure { error -> notifyError(error.message ?: "Daha fazla screenshot yüklenemedi.") }
                .getOrElse { ScreenshotPage(emptyList(), cursor, 0) }
            mainHandler.post { onLoaded(page) }
        }
    }

    /**
     * Teşhis sayaçlarını okur (yalnızca sayı; hiçbir screenshot içeriği yok).
     *
     * Sahada "kayıt neden boş, neden yanlış kategoride?" sorusunun cevabı bu sayılarda:
     * okunamayan görsel mi, erişim engeli mi, yoksa gerçekten sınıflandırma hatası mı.
     */
    fun diagnostics(onReady: (Map<String, Int>) -> Unit) {
        executor.execute {
            val counts = runCatching { database.diagnostics() }.getOrDefault(emptyMap())
            mainHandler.post { onReady(counts) }
        }
    }

    fun updateStatus(itemId: Long, status: ScreenshotStatus) {
        executor.execute {
            database.updateStatus(itemId, status)
            notifyDataChanged()
        }
    }

    fun updateIntent(itemId: Long, intent: IntentCategory) {
        executor.execute {
            database.updateIntent(itemId, intent, IntentClassifier.VERSION, System.currentTimeMillis())
            notifyDataChanged()
        }
    }

    fun updateTopic(itemId: Long, topic: ContentTopic) {
        executor.execute {
            database.updateTopic(itemId, topic, IntentClassifier.VERSION, System.currentTimeMillis())
            notifyDataChanged()
        }
    }

    private fun processPending() {
        if (!processing.compareAndSet(false, true)) return
        processNext()
    }

    private fun processNext() {
        executor.execute {
            val pending = database.nextPending()
            if (pending == null) {
                retireExhaustedPending()
                reportUnreadableSummary()
                backfillNextImageLabels()
                return@execute
            }

            ocrProcessor.process(pending.contentUri) { ocrResult ->
                imageLabelProcessor.process(pending.contentUri) { labelResult ->
                    executor.execute {
                        // Görsel etiketleme yardımcı sinyaldir; hatası akışı durdurmaz.
                        // Hata halinde '' kalır ki model indikten sonra backfill tekrar denesin.
                        val labels = labelResult.getOrDefault(emptyList())
                        val labelsSerialized = labelResult.fold(
                            onSuccess = { VisualLabels.serialize(it) },
                            onFailure = { "" },
                        )
                        val capture = ocrResult.getOrNull()
                        if (capture == null) {
                            val failure = MediaFailure.classify(
                                appContext,
                                pending.contentUri,
                                ocrResult.exceptionOrNull(),
                            )
                            when (failure) {
                                // Gorsel okunamiyor (silinmis / buluta tasinip yereli bosaltilmis /
                                // bayat MediaStore kaydi) ya da erisim kalkmis. Ikisi de kalici.
                                //
                                // KRITIK: kaydi BOS metinle "islenmis" saymiyoruz. Eskiden oyleydi ve
                                // bos metinle siniflandirma yapiliyordu - model korken kendinden emin
                                // yanlis kategori uretiyordu ("olanlar da yanlis yerlerde"). Simdi
                                // kayit arsivlenir: kullaniciya hic gosterilmez, yeniden siniflamaya
                                // girmez, ama satir korunur (kullanici duzeltmeleri egitim sinyali).
                                is MediaFailure.Unreadable, is MediaFailure.AccessDenied -> {
                                    database.markUnavailable(
                                        id = pending.id,
                                        kind = failure.kind,
                                        failedAt = System.currentTimeMillis(),
                                    )
                                    unreadableInRun.incrementAndGet()
                                    notifyDataChanged()
                                    processNext()
                                    return@execute
                                }
                                // Gecici (bellek darligi, ML Kit mesgul, gecici IO): kayit
                                // islenmemis kalir, sayac artar, kuyrugun sonuna duser.
                                is MediaFailure.Transient -> {
                                    database.recordOcrFailure(pending.id)
                                    notifyDataChanged()
                                    // Kuyrukta tek kayit varsa attempts siralamasi onu yine aninda
                                    // secerdi. Main thread'i bloklamadan kisa bir geri cekilme uygula.
                                    mainHandler.postDelayed(
                                        {
                                            if (!executor.isShutdown) processNext()
                                        },
                                        OCR_RETRY_DELAY_MS,
                                    )
                                    return@execute
                                }
                            }
                        }
                        // Buraya yalnizca OCR GERCEKTEN basarili oldugunda geliniyor; hatanin
                        // her turu yukarida ele alinip donuyor. Eskiden bos metinle de buraya
                        // dusuluyordu ve kor siniflandirma yapiliyordu.
                        database.markProcessed(
                            id = pending.id,
                            ocrText = capture.text,
                            ocrLayout = capture.layout.serialize(),
                            imageLabels = labelsSerialized,
                            classification = classifyRefined(
                                ocrText = capture.text,
                                displayName = pending.displayName,
                                layout = capture.layout,
                                labels = labels,
                            ),
                        )
                        notifyDataChanged()
                        processNext()
                    }
                }
            }
        }
    }

    /** Deneme hakkı tükenen kayıtları kör sınıflandırma yapmak yerine erişilemez arşive alır. */
    private fun retireExhaustedPending() {
        val exhausted = database.exhaustedPending()
        if (exhausted.isEmpty()) return
        exhausted.forEach { item ->
            database.markUnavailable(
                id = item.id,
                kind = "retry_exhausted",
                failedAt = System.currentTimeMillis(),
            )
        }
        exhaustedInRun.addAndGet(exhausted.size)
        Log.w(LOG_TAG, "${exhausted.size} screenshot birkac denemede okunamadi; erisilemez olarak arsivlendi.")
        notifyDataChanged()
    }

    /** Eski sürümde işlenmiş kayıtlara görsel etiketleri sonradan tamamlar. */
    private fun backfillNextImageLabels() {
        executor.execute {
            val target = database.nextUnlabeled()
            if (target == null) {
                processing.set(false)
                notifyBusy(false)
                notifyDataChanged()
                notifyDiagnosticsChanged()
                return@execute
            }

            imageLabelProcessor.process(target.contentUri) { result ->
                executor.execute {
                    result.fold(
                        onSuccess = { labels ->
                            database.applyImageLabels(
                                item = target,
                                imageLabels = VisualLabels.serialize(labels),
                                classification = classifyRefined(
                                    ocrText = target.ocrText,
                                    displayName = target.displayName,
                                    layout = OcrLayout.deserialize(target.ocrLayout),
                                    labels = labels,
                                ),
                            )
                            notifyDataChanged()
                            backfillNextImageLabels()
                        },
                        onFailure = { error ->
                            // Kalicilik OLCULUR: eskiden yalnizca FileNotFoundException kalici
                            // sayiliyordu, oysa okunamayan gorsel IOException atiyor. Buluta
                            // bosaltilmis TEK bir gorsel butun backfill kuyrugunu kilitliyordu.
                            if (MediaFailure.classify(appContext, target.contentUri, error).isPermanent) {
                                // Görsel okunamıyor; kaydı etiketsiz işaretle ki kuyruk tıkanmasın.
                                database.applyImageLabels(
                                    item = target,
                                    imageLabels = VisualLabels.NONE,
                                    classification = classifyRefined(
                                        ocrText = target.ocrText,
                                        displayName = target.displayName,
                                        layout = OcrLayout.deserialize(target.ocrLayout),
                                        labels = emptyList(),
                                    ),
                                )
                                backfillNextImageLabels()
                            } else {
                                // Model muhtemelen henüz inmedi; kuyruk sonraki açılışta devam eder.
                                Log.w(LOG_TAG, "Görsel etiketleme ertelendi: ${error.message}")
                                processing.set(false)
                                notifyBusy(false)
                                notifyDataChanged()
                            }
                        },
                    )
                }
            }
        }
    }

    private fun startObserving() {
        if (observerRegistered) return
        appContext.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            mediaObserver,
        )
        observerRegistered = true
    }

    private fun stopObserving() {
        if (!observerRegistered) return
        appContext.contentResolver.unregisterContentObserver(mediaObserver)
        observerRegistered = false
    }

    private fun notifyDataChanged() {
        mainHandler.post { onDataChanged?.invoke() }
    }

    private fun notifyBusy(isBusy: Boolean) {
        mainHandler.post { onBusyChanged?.invoke(isBusy) }
    }

    /**
     * Tur sonunda okunamayan kayıtlar için TEK özet uyarı.
     *
     * Kullanıcıya ham exception mesajı ASLA gösterilmez. Önceki sürüm ML Kit'in
     * "The image URI couldn't be resolved." metnini doğrudan ekrana basıyordu: kullanıcı için
     * anlamsız, İngilizce ve her denemede tekrarlanıyordu. Teknik ayrıntı logcat'e ve
     * teşhis raporuna gider ([ScreenshotDatabase.diagnostics]).
     */
    private fun reportUnreadableSummary() {
        val unavailable = unreadableInRun.getAndSet(0)
        val exhausted = exhaustedInRun.getAndSet(0)
        when {
            unavailable > 0 && exhausted > 0 -> notifyError(
                "$unavailable screenshot açılamadı, $exhausted screenshot analiz edilemedi. Yenileyerek tekrar deneyebilirsin.",
            )
            unavailable == 1 -> notifyError(
                "1 screenshot açılamadı: dosya cihazda yok, bulutta veya izin dışında olabilir.",
            )
            unavailable > 1 -> notifyError(
                "$unavailable screenshot açılamadı: dosyalar cihazda yok, bulutta veya izin dışında olabilir.",
            )
            exhausted == 1 -> notifyError(
                "1 screenshot analiz edilemedi. Yenileyerek tekrar deneyebilirsin.",
            )
            exhausted > 1 -> notifyError(
                "$exhausted screenshot analiz edilemedi. Yenileyerek tekrar deneyebilirsin.",
            )
        }
    }

    /** İşleme turu tamamlandığında tek ve güncel teşhis snapshot'ı yayınlar. */
    private fun notifyDiagnosticsChanged() {
        val counts = runCatching { database.diagnostics() }.getOrDefault(emptyMap())
        mainHandler.post { onDiagnosticsChanged?.invoke(counts) }
    }

    private fun notifyError(message: String) {
        mainHandler.post { onError?.invoke(message) }
    }

    override fun close() {
        stopObserving()
        ocrProcessor.close()
        imageLabelProcessor.close()
        database.close()
        executor.shutdown()
    }

    private companion object {
        const val LOG_TAG = "MementoRepo"
        const val PREFERENCES = "memento_sync"
        const val LAST_SCAN_SECONDS = "last_scan_seconds"
        const val LAST_ACCESS_LEVEL = "last_access_level"
        const val DERIVED_METADATA_VERSION_KEY = "derived_metadata_version"
        const val DERIVED_METADATA_VERSION = 37
        const val SCAN_OVERLAP_SECONDS = 10L
        const val OCR_RETRY_DELAY_MS = 1_500L
        // Ilk tam tarama artik sinirsiz (bkz. ScreenshotMediaScanner.scanAll); sabit limit
        // kutuphanenin bir kismini sessizce kaybediyordu. Artimli tarama yalniz son taramadan
        // sonra eklenenlere bakar, orada makul bir tavan guvenli.
        const val INCREMENTAL_SCAN_LIMIT = 300
        const val UI_PAGE_SIZE = 60
        const val HOME_RECENT_LIMIT = 6
    }
}
