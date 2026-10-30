package com.screenshotsearch.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import com.screenshotsearch.app.search.SearchSpec
import com.screenshotsearch.app.search.TextNormalizer

class ScreenshotDatabase(
    context: Context,
    databaseName: String = DATABASE_NAME,
) : SQLiteOpenHelper(
    context,
    databaseName,
    null,
    DATABASE_VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE screenshot_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                media_store_id INTEGER NOT NULL UNIQUE,
                content_uri TEXT NOT NULL,
                captured_at INTEGER NOT NULL,
                display_name TEXT NOT NULL,
                relative_path TEXT,
                ocr_text TEXT NOT NULL DEFAULT '',
                ocr_layout TEXT NOT NULL DEFAULT '',
                image_labels TEXT NOT NULL DEFAULT '',
                search_text TEXT NOT NULL DEFAULT '',
                title TEXT NOT NULL DEFAULT '',
                intent TEXT NOT NULL DEFAULT 'UNCLEAR',
                topic TEXT NOT NULL DEFAULT 'OTHER',
                confidence REAL NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'NEW',
                processed INTEGER NOT NULL DEFAULT 0,
                sensitive INTEGER NOT NULL DEFAULT 0,
                user_corrected INTEGER NOT NULL DEFAULT 0,
                user_topic_corrected INTEGER NOT NULL DEFAULT 0,
                last_surfaced_at INTEGER,
                source_hint TEXT,
                missing_since INTEGER,
                ocr_attempts INTEGER NOT NULL DEFAULT 0,
                ocr_failure_kind TEXT,
                ocr_failure_at INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(CREATE_CORRECTION_EVENTS)
        db.execSQL("CREATE INDEX idx_correction_screenshot ON correction_events(screenshot_id)")
        db.execSQL("CREATE INDEX idx_screenshot_captured_at ON screenshot_items(captured_at DESC)")
        db.execSQL("CREATE INDEX idx_screenshot_page ON screenshot_items(captured_at DESC, id DESC)")
        db.execSQL("CREATE INDEX idx_screenshot_intent ON screenshot_items(intent)")
        db.execSQL("CREATE INDEX idx_screenshot_topic ON screenshot_items(topic)")
        db.execSQL("CREATE INDEX idx_screenshot_processed ON screenshot_items(processed)")
        db.execSQL("CREATE INDEX idx_screenshot_availability ON screenshot_items(missing_since, ocr_failure_kind)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE screenshot_items ADD COLUMN topic TEXT NOT NULL DEFAULT 'OTHER'")
            db.execSQL("ALTER TABLE screenshot_items ADD COLUMN user_topic_corrected INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX idx_screenshot_topic ON screenshot_items(topic)")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE screenshot_items ADD COLUMN ocr_layout TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE screenshot_items ADD COLUMN image_labels TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 8) {
            // Duzeltme denetim gecmisi: modelin ilk tahmini, guven skoru, siniflandirici
            // surumu ve zaman ayri olay olarak saklanir. Yanlis genellemeyi aciklamak ve
            // surumler arasi model kalitesini olcmek icin.
            runCatching {
                db.execSQL(CREATE_CORRECTION_EVENTS)
                db.execSQL("CREATE INDEX idx_correction_screenshot ON correction_events(screenshot_id)")
            }
        }
        if (oldVersion < 7) {
            // OCR denemesi sayaci. Gecici bir hata (bellek darligi, ML Kit mesgul) kaydi
            // kalici olarak bos metinle "islendi" yapmasin diye; kuyruk tekrar deniyor.
            runCatching {
                db.execSQL(
                    "ALTER TABLE screenshot_items ADD COLUMN ocr_attempts INTEGER NOT NULL DEFAULT 0",
                )
            }
        }
        if (oldVersion < 6) {
            // Cihazdan silinen screenshotların ölü kayıtlarını işaretlemek için. Kaydı silmiyoruz:
            // kullanıcı düzeltmeleri sınıflandırıcının eğitim sinyali, görsel gitse de değerli.
            // Not: bazi v5 kurulumlarinda kolon zaten var (eski paralel hat); hatayi yutuyoruz.
            runCatching {
                db.execSQL("ALTER TABLE screenshot_items ADD COLUMN missing_since INTEGER")
            }
        }
        if (oldVersion < 9) {
            // Okunamayan görsellerin SEBEBİNİ saklamak için. Teşhis raporu bunu sayıyor:
            // "kayıt neden boş?" sorusunun cevabı sahada başka türlü alınamıyordu.
            runCatching {
                db.execSQL("ALTER TABLE screenshot_items ADD COLUMN ocr_failure_kind TEXT")
            }
            runCatching {
                db.execSQL("ALTER TABLE screenshot_items ADD COLUMN ocr_failure_at INTEGER")
            }
            // Eski surumun BOS metinle "islenmis" saydigi kayitlar kor siniflandirilmisti.
            // Onlari yeniden kuyruga al: artik gorsel gercekten okunabiliyorsa dogru
            // siniflanacak, okunamiyorsa arsivlenip kullaniciya hic gosterilmeyecek.
            runCatching {
                db.execSQL(
                    """
                    UPDATE screenshot_items
                    SET processed = 0, ocr_attempts = 0
                    WHERE processed = 1 AND missing_since IS NULL
                      AND ocr_text = '' AND user_corrected = 0 AND user_topic_corrected = 0
                    """.trimIndent(),
                )
            }
            runCatching {
                db.execSQL(
                    "CREATE INDEX idx_screenshot_availability ON screenshot_items(missing_since, ocr_failure_kind)",
                )
            }
        }
        if (oldVersion < 10) {
            runCatching {
                db.execSQL(
                    "CREATE INDEX idx_screenshot_page ON screenshot_items(captured_at DESC, id DESC)",
                )
            }
        }
    }

    fun insertDiscovered(items: List<DiscoveredScreenshot>): Int {
        if (items.isEmpty()) return 0
        var inserted = 0
        writableDatabase.transaction {
            items.forEach { item ->
                val values = ContentValues().apply {
                    put("media_store_id", item.mediaStoreId)
                    put("content_uri", item.contentUri)
                    put("captured_at", item.capturedAt)
                    put("display_name", item.displayName)
                    put("relative_path", item.relativePath)
                }
                val rowId = insertWithOnConflict(
                    "screenshot_items",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
                if (rowId != -1L) {
                    inserted += 1
                } else {
                    // Tarama bir kaydı yeniden görüyorsa fiziksel olarak "silinmiş" değildir.
                    // Yalnızca missing_since temizlenir. OCR erişim hatası AYRI bir durumdur ve
                    // her otomatik taramada temizlenmez; aksi halde bulut placeholder'ı her app
                    // açılışında tekrar kuyruğa girip aynı hatayı üretirdi. Kullanıcı yenilediğinde
                    // [resetOcrAttempts] bu kayıtlara kontrollü olarak yeni bir şans verir.
                    execSQL(
                        """
                        UPDATE screenshot_items
                        SET missing_since = NULL,
                            content_uri = ?,
                            captured_at = ?,
                            display_name = ?,
                            relative_path = ?
                        WHERE media_store_id = ?
                        """.trimIndent(),
                        arrayOf<Any?>(
                            item.contentUri,
                            item.capturedAt,
                            item.displayName,
                            item.relativePath,
                            item.mediaStoreId,
                        ),
                    )
                }
            }
        }
        return inserted
    }

    /**
     * Siradaki islenmemis kayit.
     *
     * Siralama once deneme sayisina bakar: hic denenmemisler bastadir, bir kez basarisiz
     * olmus kayit kuyrugun sonuna duser. Boylece gecici bir hata sonrasi yeniden deneme
     * kendiliginden araliklanir, tight loop olusmaz. [MAX_OCR_ATTEMPTS] kez denenmis
     * kayıtlar kuyruktan çıkar; repository onları kör sınıflandırma yapmadan erişilemez
     * olarak arşivler.
     */
    fun nextPending(): ScreenshotItem? = readableDatabase.query(
        "screenshot_items",
        ALL_COLUMNS,
        "processed = 0 AND ocr_attempts < $MAX_OCR_ATTEMPTS AND $AVAILABLE",
        null,
        null,
        null,
        "ocr_attempts ASC, captured_at DESC",
        "1",
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.toItem() else null
    }

    /**
     * Görseli açılamayan kaydı erişilemez olarak işaretler.
     *
     * `missing_since` yalnızca MediaStore'dan gerçekten kaybolan kayıtlar içindir. Erişim
     * reddi, bulut placeholder'ı ve geçici denemelerin tükenmesi `ocr_failure_kind` içinde
     * tutulur. Böylece otomatik tarama bu satırı yanlışlıkla "geri gelmiş" sayıp tekrar tekrar
     * kuyruğa sokmaz; kullanıcı yenilemesi ise kontrollü bir retry yapabilir.
     */
    fun markUnavailable(id: Long, kind: String, failedAt: Long) {
        writableDatabase.execSQL(
            """
            UPDATE screenshot_items
            SET ocr_failure_kind = ?, ocr_failure_at = ?, processed = 1
            WHERE id = ?
            """.trimIndent(),
            arrayOf<Any>(kind, failedAt, id),
        )
    }

    /**
     * Teşhis özeti: sahada "neden boş/yanlış?" sorusunu içerik göndermeden cevaplar.
     *
     * Yalnızca SAYILAR döner — hiçbir OCR metni, dosya adı veya görsel içermez.
     */
    fun diagnostics(): Map<String, Int> = readableDatabase.rawQuery(
        """
        SELECT
            COUNT(*) AS toplam,
            SUM(CASE WHEN processed = 1 AND $AVAILABLE THEN 1 ELSE 0 END) AS gorunen,
            SUM(CASE WHEN processed = 0 AND $AVAILABLE THEN 1 ELSE 0 END) AS bekleyen,
            SUM(CASE WHEN missing_since IS NOT NULL OR ocr_failure_kind IS NOT NULL THEN 1 ELSE 0 END) AS arsivli,
            SUM(CASE WHEN missing_since IS NOT NULL THEN 1 ELSE 0 END) AS silinmis,
            SUM(CASE WHEN ocr_failure_kind = 'unreadable' THEN 1 ELSE 0 END) AS okunamadi,
            SUM(CASE WHEN ocr_failure_kind = 'access_denied' THEN 1 ELSE 0 END) AS erisim_engeli,
            SUM(CASE WHEN ocr_failure_kind = 'retry_exhausted' THEN 1 ELSE 0 END) AS deneme_tukendi,
            SUM(CASE WHEN processed = 1 AND $AVAILABLE AND ocr_text = '' THEN 1 ELSE 0 END) AS ocr_bos,
            SUM(CASE WHEN user_corrected = 1 OR user_topic_corrected = 1 THEN 1 ELSE 0 END) AS kullanici_duzeltti
        FROM screenshot_items
        """.trimIndent(),
        null,
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use emptyMap()
        linkedMapOf(
            "toplam" to cursor.getInt(0),
            "gorunen" to cursor.getInt(1),
            "bekleyen" to cursor.getInt(2),
            "arsivli" to cursor.getInt(3),
            "silinmis" to cursor.getInt(4),
            "okunamadi" to cursor.getInt(5),
            "erisim_engeli" to cursor.getInt(6),
            "deneme_tukendi" to cursor.getInt(7),
            "ocr_bos" to cursor.getInt(8),
            "kullanici_duzeltti" to cursor.getInt(9),
        )
    }

    /** Basarisiz OCR denemesini sayar; kayit islenmemis kalir ve tekrar denenir. */
    fun recordOcrFailure(id: Long) {
        writableDatabase.execSQL(
            "UPDATE screenshot_items SET ocr_attempts = ocr_attempts + 1 WHERE id = ?",
            arrayOf(id.toString()),
        )
    }

    /**
     * Deneme hakki tukenmis kayitlari islenmis sayar.
     *
     * Aksi halde arayuzde sonsuza kadar "Analiz ediliyor..." yazardi. Metin bos kalir;
     * kullanici yenile derse [resetOcrAttempts] sayaci sifirlar ve kayit kuyruga doner.
     */
    fun exhaustedPending(): List<ScreenshotItem> = readableDatabase.query(
        "screenshot_items",
        ALL_COLUMNS,
        "processed = 0 AND ocr_attempts >= $MAX_OCR_ATTEMPTS AND $AVAILABLE",
        null,
        null,
        null,
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.toItem())
        }
    }

    /**
     * Kullanıcı elle yenilediğinde başarısız kayıtlara tek kontrollü yeni şans verir.
     * Otomatik MediaStore taraması bu alanları temizlemez.
     */
    fun resetOcrAttempts(): Int {
        val values = ContentValues().apply {
            put("ocr_attempts", 0)
            put("processed", 0)
            putNull("ocr_failure_kind")
            putNull("ocr_failure_at")
        }
        return writableDatabase.update(
            "screenshot_items",
            values,
            """
            (
                (processed = 0 AND ocr_attempts > 0)
                OR ocr_failure_kind IS NOT NULL
            ) AND $PRESENT
            """.trimIndent(),
            null,
        )
    }

    fun markProcessed(
        id: Long,
        ocrText: String,
        ocrLayout: String,
        imageLabels: String,
        classification: ClassificationResult,
    ) {
        val correctionFlags = readableDatabase.query(
            "screenshot_items",
            arrayOf("user_corrected", "user_topic_corrected"),
            "id = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return
            val intentCorrected =
                cursor.getInt(cursor.getColumnIndexOrThrow("user_corrected")) == 1
            val topicCorrected =
                cursor.getInt(cursor.getColumnIndexOrThrow("user_topic_corrected")) == 1
            intentCorrected to topicCorrected
        }
        val (intentCorrected, topicCorrected) = correctionFlags
        val values = ContentValues().apply {
            put("ocr_text", ocrText)
            put("ocr_layout", ocrLayout)
            put("image_labels", imageLabels)
            put("search_text", searchableText(classification, ocrText))
            put("title", classification.title)
            if (!intentCorrected) put("intent", classification.intent.name)
            if (!topicCorrected) put("topic", classification.topic.name)
            put("confidence", classification.confidence)
            put("processed", 1)
            put("ocr_attempts", 0)
            putNull("ocr_failure_kind")
            putNull("ocr_failure_at")
            put("sensitive", if (classification.sensitive) 1 else 0)
            put("source_hint", classification.sourceHint)
        }
        writableDatabase.update("screenshot_items", values, "id = ?", arrayOf(id.toString()))
    }

    /** Görsel etiketi eksik ilk işlenmiş kaydı döndürür (eski sürümden kalan backfill kuyruğu). */
    fun nextUnlabeled(): UnlabeledItem? = readableDatabase.query(
        "screenshot_items",
        arrayOf("id", "content_uri", "display_name", "ocr_text", "ocr_layout", "user_corrected", "user_topic_corrected"),
        "processed = 1 AND image_labels = '' AND $AVAILABLE",
        null,
        null,
        null,
        "captured_at DESC",
        "1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) return null
        UnlabeledItem(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            contentUri = cursor.getString(cursor.getColumnIndexOrThrow("content_uri")),
            displayName = cursor.getString(cursor.getColumnIndexOrThrow("display_name")),
            ocrText = cursor.getString(cursor.getColumnIndexOrThrow("ocr_text")),
            ocrLayout = cursor.getString(cursor.getColumnIndexOrThrow("ocr_layout")),
            userCorrected = cursor.getInt(cursor.getColumnIndexOrThrow("user_corrected")) == 1,
            userTopicCorrected = cursor.getInt(cursor.getColumnIndexOrThrow("user_topic_corrected")) == 1,
        )
    }

    /** Backfill sonucunu yazar; kullanıcı düzeltmeleri korunur. */
    fun applyImageLabels(
        item: UnlabeledItem,
        imageLabels: String,
        classification: ClassificationResult,
    ) {
        val values = ContentValues().apply {
            put("image_labels", imageLabels)
            put("search_text", searchableText(classification, item.ocrText))
            put("title", classification.title)
            put("confidence", classification.confidence)
            put("sensitive", if (classification.sensitive) 1 else 0)
            put("source_hint", classification.sourceHint)
            if (!item.userCorrected) put("intent", classification.intent.name)
            if (!item.userTopicCorrected) put("topic", classification.topic.name)
        }
        writableDatabase.update("screenshot_items", values, "id = ?", arrayOf(item.id.toString()))
    }

    fun reclassifyProcessed(
        classifier: (
            ocrText: String,
            ocrLayout: String,
            imageLabels: String,
            displayName: String,
        ) -> ClassificationResult,
    ): Int {
        val storedItems = readableDatabase.query(
            "screenshot_items",
            arrayOf(
                "id",
                "display_name",
                "ocr_text",
                "ocr_layout",
                "image_labels",
                "user_corrected",
                "user_topic_corrected",
            ),
            // Arsivlenmis (cihazdan silinmis) kayitlar kullaniciya hic gosterilmiyor;
            // onlari yeniden siniflamak tamamen bosuna is. Gercek olcumde fark buyuk:
            // 2056 kayitlik bir DB'de yalnizca 18'i goruntuleniyordu ve model surumu
            // degisiminde cihaz 20+ dakika %100+ CPU ile bos yere calisiyordu.
            "processed = 1 AND $AVAILABLE AND " +
                "(ocr_text != '' OR image_labels NOT IN ('', '-'))",
            null,
            null,
            null,
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        StoredClassificationInput(
                            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                            displayName = cursor.getString(cursor.getColumnIndexOrThrow("display_name")),
                            ocrText = cursor.getString(cursor.getColumnIndexOrThrow("ocr_text")),
                            ocrLayout = cursor.getString(cursor.getColumnIndexOrThrow("ocr_layout")),
                            imageLabels = cursor.getString(cursor.getColumnIndexOrThrow("image_labels")),
                            userCorrected = cursor.getInt(cursor.getColumnIndexOrThrow("user_corrected")) == 1,
                            userTopicCorrected = cursor.getInt(
                                cursor.getColumnIndexOrThrow("user_topic_corrected"),
                            ) == 1,
                        ),
                    )
                }
            }
        }

        if (storedItems.isEmpty()) return 0
        writableDatabase.transaction {
            storedItems.forEach { item ->
                val classification = classifier(item.ocrText, item.ocrLayout, item.imageLabels, item.displayName)
                val values = ContentValues().apply {
                    put("search_text", searchableText(classification, item.ocrText))
                    put("title", classification.title)
                    put("confidence", classification.confidence)
                    put("sensitive", if (classification.sensitive) 1 else 0)
                    put("source_hint", classification.sourceHint)
                    if (!item.userCorrected) put("intent", classification.intent.name)
                    if (!item.userTopicCorrected) put("topic", classification.topic.name)
                }
                update("screenshot_items", values, "id = ?", arrayOf(item.id.toString()))
            }
        }
        return storedItems.size
    }

    /**
     * Screenshot listesini offset yerine `(captured_at, id)` cursor'u ile sayfalar.
     *
     * Offset pagination sırasında yeni bir screenshot eklenirse satırlar kayabilir ve kayıt
     * atlanabilir/iki kez gelebilir. Keyset cursor bu sorunu önler ve büyük kütüphanede sorgu
     * maliyetini sayfa numarası büyüdükçe artırmaz.
     */
    fun page(
        spec: SearchSpec? = null,
        cursor: ScreenshotCursor? = null,
        limit: Int = DEFAULT_PAGE_SIZE,
    ): ScreenshotPage {
        require(limit in 1..MAX_PAGE_SIZE) { "Page size must be between 1 and $MAX_PAGE_SIZE" }
        val search = buildSearchWhere(spec)
        val totalCount = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM screenshot_items WHERE ${search.clause}",
            search.args.takeIf(List<String>::isNotEmpty)?.toTypedArray(),
        ).use { countCursor ->
            if (countCursor.moveToFirst()) countCursor.getInt(0) else 0
        }

        val where = mutableListOf(search.clause)
        val args = search.args.toMutableList()
        cursor?.let {
            where += "(captured_at < ? OR (captured_at = ? AND id < ?))"
            args += it.capturedAt.toString()
            args += it.capturedAt.toString()
            args += it.id.toString()
        }

        val fetched = readableDatabase.query(
            "screenshot_items",
            ALL_COLUMNS,
            where.joinToString(" AND "),
            args.takeIf { it.isNotEmpty() }?.toTypedArray(),
            null,
            null,
            "captured_at DESC, id DESC",
            (limit + 1).toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toItem())
            }
        }
        val items = fetched.take(limit)
        val nextCursor = if (fetched.size > limit) {
            items.lastOrNull()?.let { ScreenshotCursor(it.capturedAt, it.id) }
        } else {
            null
        }
        return ScreenshotPage(items = items, nextCursor = nextCursor, totalCount = totalCount)
    }

    /** Küçük, bilinçli ve sınırlı dahili sorgular için geriye uyumlu yardımcı. */
    fun list(spec: SearchSpec? = null, limit: Int = DEFAULT_PAGE_SIZE): List<ScreenshotItem> =
        page(spec = spec, limit = limit).items

    fun librarySummary(): LibrarySummary {
        val totals = readableDatabase.rawQuery(
            """
            SELECT
                COUNT(*),
                SUM(CASE WHEN processed = 1 THEN 1 ELSE 0 END),
                SUM(CASE WHEN processed = 0 THEN 1 ELSE 0 END)
            FROM screenshot_items
            WHERE $AVAILABLE
            """.trimIndent(),
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) Triple(0, 0, 0) else {
                Triple(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2))
            }
        }
        val topics = readableDatabase.rawQuery(
            """
            SELECT topic, COUNT(*)
            FROM screenshot_items
            WHERE $AVAILABLE AND processed = 1
            GROUP BY topic
            """.trimIndent(),
            null,
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val topic = enumValueOrDefault(cursor.getString(0), ContentTopic.OTHER)
                    put(topic, cursor.getInt(1))
                }
            }
        }
        return LibrarySummary(
            totalCount = totals.first,
            processedCount = totals.second,
            pendingCount = totals.third,
            topicCounts = topics,
        )
    }

    fun listByIds(ids: Collection<Long>): List<ScreenshotItem> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(DB_QUERY_CHUNK).flatMap { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            readableDatabase.query(
                "screenshot_items",
                ALL_COLUMNS,
                "$AVAILABLE AND id IN ($placeholders)",
                chunk.map(Long::toString).toTypedArray(),
                null,
                null,
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.toItem())
                }
            }
        }
    }

    /** Haftalık hatırlatma politikasına büyük kütüphaneden yalnızca anlamlı adayları verir. */
    fun resurfacingCandidates(nowMillis: Long, limit: Int = RESURFACING_CANDIDATE_LIMIT): List<ScreenshotItem> {
        val newestCapture = nowMillis - SEVEN_DAYS_MILLIS
        val newestSurface = nowMillis - FOURTEEN_DAYS_MILLIS
        return readableDatabase.query(
            "screenshot_items",
            ALL_COLUMNS,
            """
            $AVAILABLE
                AND processed = 1
                AND sensitive = 0
                AND captured_at <= ?
                AND status NOT IN ('DONE', 'ARCHIVED')
                AND (last_surfaced_at IS NULL OR last_surfaced_at <= ?)
            """.trimIndent(),
            arrayOf(newestCapture.toString(), newestSurface.toString()),
            null,
            null,
            """
            CASE WHEN status = 'LATER' THEN 1 ELSE 0 END DESC,
            CASE WHEN status = 'INTERESTED' THEN 1 ELSE 0 END DESC,
            CASE WHEN intent IN ('GO', 'TRY', 'BUY') THEN 1 ELSE 0 END DESC,
            confidence DESC,
            captured_at ASC
            """.trimIndent(),
            limit.toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toItem())
            }
        }
    }

    fun updateStatus(id: Long, status: ScreenshotStatus) {
        val values = ContentValues().apply { put("status", status.name) }
        writableDatabase.update("screenshot_items", values, "id = ?", arrayOf(id.toString()))
    }

    fun updateIntent(id: Long, intent: IntentCategory, classifierVersion: Int, nowMillis: Long) {
        writableDatabase.transaction {
            logCorrection(id, "intent", intent.name, classifierVersion, nowMillis)
            val values = ContentValues().apply {
                put("intent", intent.name)
                put("user_corrected", 1)
            }
            update("screenshot_items", values, "id = ?", arrayOf(id.toString()))
        }
    }

    fun updateTopic(id: Long, topic: ContentTopic, classifierVersion: Int, nowMillis: Long) {
        writableDatabase.transaction {
            logCorrection(id, "topic", topic.name, classifierVersion, nowMillis)
            val values = ContentValues().apply {
                put("topic", topic.name)
                put("user_topic_corrected", 1)
            }
            update("screenshot_items", values, "id = ?", arrayOf(id.toString()))
        }
    }

    /**
     * Bir duzeltmeyi denetim gecmisine yazar.
     *
     * Onceki degeri UPDATE'ten ONCE mevcut satirdan okur: bu, modelin en son tahminidir
     * (kural tabanli ilk tahmin veya onceki duzeltme). Ayni alan ikinci kez duzeltilirse
     * her duzeltme ayri olay olur; zincir tam gorunur. [SQLiteDatabase.transaction]
     * icinde cagrilmali.
     */
    private fun SQLiteDatabase.logCorrection(
        screenshotId: Long,
        field: String,
        newValue: String,
        classifierVersion: Int,
        nowMillis: Long,
    ) {
        val column = if (field == "topic") "topic" else "intent"
        val previous = query(
            "screenshot_items",
            arrayOf(column, "confidence", "user_corrected", "user_topic_corrected"),
            "id = ?",
            arrayOf(screenshotId.toString()),
            null, null, null, "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return
            val alreadyCorrected = if (field == "topic") {
                cursor.getInt(cursor.getColumnIndexOrThrow("user_topic_corrected")) == 1
            } else {
                cursor.getInt(cursor.getColumnIndexOrThrow("user_corrected")) == 1
            }
            Triple(
                cursor.getString(cursor.getColumnIndexOrThrow(column)),
                cursor.getFloat(cursor.getColumnIndexOrThrow("confidence")),
                alreadyCorrected,
            )
        }
        val (previousValue, previousConfidence, wasUserValue) = previous
        // Degisiklik yoksa (ayni degere "duzeltme") olay yazma.
        if (previousValue == newValue) return
        val event = ContentValues().apply {
            put("screenshot_id", screenshotId)
            put("field", field)
            put("previous_value", previousValue)
            put("new_value", newValue)
            put("previous_confidence", previousConfidence)
            // Onceki deger kullanicinin kendi duzeltmesi miydi, yoksa model tahmini mi.
            put("previous_was_user", if (wasUserValue) 1 else 0)
            put("classifier_version", classifierVersion)
            put("corrected_at", nowMillis)
        }
        insert("correction_events", null, event)
    }

    fun markSurfaced(ids: List<Long>, surfacedAt: Long) {
        if (ids.isEmpty()) return
        writableDatabase.transaction {
            val values = ContentValues().apply { put("last_surfaced_at", surfacedAt) }
            ids.forEach { id ->
                update("screenshot_items", values, "id = ?", arrayOf(id.toString()))
            }
        }
    }

    fun latestCapturedAt(): Long? = readableDatabase.rawQuery(
        "SELECT MAX(captured_at) FROM screenshot_items WHERE $AVAILABLE",
        null,
    ).use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
    }

    /** Uzlaştırma için: hâlâ var sayılan tüm kayıtların MediaStore kimlikleri. */
    fun liveMediaStoreIds(): Map<Long, Long> = readableDatabase.query(
        "screenshot_items",
        arrayOf("id", "media_store_id"),
        PRESENT,
        null,
        null,
        null,
        null,
    ).use { cursor ->
        buildMap {
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val mediaIndex = cursor.getColumnIndexOrThrow("media_store_id")
            while (cursor.moveToNext()) {
                put(cursor.getLong(mediaIndex), cursor.getLong(idIndex))
            }
        }
    }

    /**
     * Görseli cihazdan silinmiş kayıtları işaretler.
     *
     * Satır silinmiyor: kullanıcının o kayıt üzerindeki konu/niyet düzeltmesi düzeltme
     * hafızasının eğitim sinyali ve görsel gitse bile değerini koruyor.
     */
    fun markMissing(ids: Collection<Long>, missingAt: Long): Int {
        if (ids.isEmpty()) return 0
        val values = ContentValues().apply { put("missing_since", missingAt) }
        var updated = 0
        writableDatabase.transaction {
            ids.forEach { id ->
                updated += update("screenshot_items", values, "id = ?", arrayOf(id.toString()))
            }
        }
        return updated
    }

    private fun Cursor.toItem(): ScreenshotItem = ScreenshotItem(
        id = getLong(getColumnIndexOrThrow("id")),
        mediaStoreId = getLong(getColumnIndexOrThrow("media_store_id")),
        contentUri = getString(getColumnIndexOrThrow("content_uri")),
        capturedAt = getLong(getColumnIndexOrThrow("captured_at")),
        displayName = getString(getColumnIndexOrThrow("display_name")),
        relativePath = getStringOrNull("relative_path"),
        ocrText = getString(getColumnIndexOrThrow("ocr_text")),
        title = getString(getColumnIndexOrThrow("title")),
        intent = enumValueOrDefault(getString(getColumnIndexOrThrow("intent")), IntentCategory.UNCLEAR),
        topic = enumValueOrDefault(getString(getColumnIndexOrThrow("topic")), ContentTopic.OTHER),
        confidence = getFloat(getColumnIndexOrThrow("confidence")),
        status = enumValueOrDefault(getString(getColumnIndexOrThrow("status")), ScreenshotStatus.NEW),
        processed = getInt(getColumnIndexOrThrow("processed")) == 1,
        sensitive = getInt(getColumnIndexOrThrow("sensitive")) == 1,
        userCorrected = getInt(getColumnIndexOrThrow("user_corrected")) == 1,
        userTopicCorrected = getInt(getColumnIndexOrThrow("user_topic_corrected")) == 1,
        lastSurfacedAt = getLongOrNull("last_surfaced_at"),
        sourceHint = getStringOrNull("source_hint"),
    )

    private fun Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.getLongOrNull(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
        runCatching { enumValueOf<T>(value) }.getOrDefault(default)

    private fun buildSearchWhere(spec: SearchSpec?): SearchWhere {
        val where = mutableListOf(AVAILABLE)
        val args = mutableListOf<String>()
        spec?.capturedFrom?.let {
            where += "captured_at >= ?"
            args += it.toString()
        }
        spec?.capturedUntil?.let {
            where += "captured_at < ?"
            args += it.toString()
        }
        spec?.intent?.let {
            where += "intent = ?"
            args += it.name
        }
        spec?.topic?.let {
            where += "topic = ?"
            args += it.name
        }
        spec?.terms?.takeIf(List<String>::isNotEmpty)?.let { terms ->
            // Doğal dil sorgusu otomatik etiketlere güvenmez. OCR bir kelimeyi kaçırmış
            // olsa bile kalan ayırt edici kelimeler sonucu bulabilsin diye terimler OR'dur.
            // Tarih ve kullanıcının açıkça dokunduğu koleksiyon filtresi yine AND kalır.
            where += terms.joinToString(prefix = "(", postfix = ")", separator = " OR ") {
                "search_text LIKE ?"
            }
            args += terms.map { term -> "%${TextNormalizer.normalize(term)}%" }
        }
        return SearchWhere(where.joinToString(" AND "), args)
    }

    private fun searchableText(
        classification: ClassificationResult,
        ocrText: String,
    ): String = TextNormalizer.normalize(
        "${classification.title} ${classification.sourceHint.orEmpty()} $ocrText",
    )

    private data class StoredClassificationInput(
        val id: Long,
        val displayName: String,
        val ocrText: String,
        val ocrLayout: String,
        val imageLabels: String,
        val userCorrected: Boolean,
        val userTopicCorrected: Boolean,
    )

    private data class SearchWhere(
        val clause: String,
        val args: List<String>,
    )

    private companion object {
        const val DATABASE_NAME = "screenshot_search.db"
        private const val CREATE_CORRECTION_EVENTS = """
            CREATE TABLE correction_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                screenshot_id INTEGER NOT NULL,
                field TEXT NOT NULL,
                previous_value TEXT NOT NULL,
                new_value TEXT NOT NULL,
                previous_confidence REAL NOT NULL DEFAULT 0,
                previous_was_user INTEGER NOT NULL DEFAULT 0,
                classifier_version INTEGER NOT NULL DEFAULT 0,
                corrected_at INTEGER NOT NULL
            )
        """
        // 6: missing_since kolonu. Bu hat bir ara "cihazdaki v5 DB'ye uymak icin" migrationsiz
        // v5'e ziplamisti; o yuzden sahada kolonlu ve kolonsuz iki farkli v5 var. v6 migration'i
        // ALTER'i deneyip "duplicate column" hatasini yutarak ikisini de ayni semaya getirir.
        // 9: ocr_failure_kind/ocr_failure_at. Okunamayan gorsel artik bos metinle
        // "islenmis" sayilmiyor; sebebi kaydedilip kayit arsivleniyor.
        // 10: keyset pagination icin captured_at + id bileşik indeksi.
        const val DATABASE_VERSION = 10

        /** MediaStore'dan fiziksel olarak silinmemiş kayıtlar. */
        const val PRESENT = "missing_since IS NULL"

        /** Kullanıcıya gösterilebilen ve işleme kuyruklarına girebilen kayıtlar. */
        const val AVAILABLE = "$PRESENT AND ocr_failure_kind IS NULL"

        /** Bir screenshot icin OCR kac kez denenir. */
        const val MAX_OCR_ATTEMPTS = 3
        const val DEFAULT_PAGE_SIZE = 60
        const val MAX_PAGE_SIZE = 200
        const val RESURFACING_CANDIDATE_LIMIT = 100
        const val DB_QUERY_CHUNK = 400
        const val SEVEN_DAYS_MILLIS = 7L * 24 * 60 * 60 * 1_000
        const val FOURTEEN_DAYS_MILLIS = 14L * 24 * 60 * 60 * 1_000
        val ALL_COLUMNS = arrayOf(
            "id",
            "media_store_id",
            "content_uri",
            "captured_at",
            "display_name",
            "relative_path",
            "ocr_text",
            "title",
            "intent",
            "topic",
            "confidence",
            "status",
            "processed",
            "sensitive",
            "user_corrected",
            "user_topic_corrected",
            "last_surfaced_at",
            "source_hint",
        )
    }
}
