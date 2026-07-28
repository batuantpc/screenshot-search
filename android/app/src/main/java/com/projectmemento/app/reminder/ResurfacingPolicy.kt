package com.projectmemento.app.reminder

import com.projectmemento.app.data.IntentCategory
import com.projectmemento.app.data.ScreenshotItem
import com.projectmemento.app.data.ScreenshotStatus
import java.util.concurrent.TimeUnit

object ResurfacingPolicy {
    private val actionIntents = setOf(
        IntentCategory.GO,
        IntentCategory.TRY,
        IntentCategory.BUY,
    )

    fun select(
        items: List<ScreenshotItem>,
        nowMillis: Long,
        limit: Int = 3,
    ): List<ScreenshotItem> {
        val newestEligibleCapture = nowMillis - TimeUnit.DAYS.toMillis(7)
        val newestEligibleSurface = nowMillis - TimeUnit.DAYS.toMillis(14)

        return items
            .asSequence()
            .filter(ScreenshotItem::processed)
            .filterNot(ScreenshotItem::sensitive)
            .filter { it.capturedAt <= newestEligibleCapture }
            .filter { it.status !in setOf(ScreenshotStatus.DONE, ScreenshotStatus.ARCHIVED) }
            .filter { item ->
                item.lastSurfacedAt == null || item.lastSurfacedAt <= newestEligibleSurface
            }
            .sortedWith(
                compareByDescending<ScreenshotItem> { it.status == ScreenshotStatus.LATER }
                    .thenByDescending { it.status == ScreenshotStatus.INTERESTED }
                    .thenByDescending { it.intent in actionIntents }
                    .thenByDescending(ScreenshotItem::confidence)
                    .thenBy(ScreenshotItem::capturedAt),
            )
            .take(limit)
            .toList()
    }

    /**
     * Ana ekranin gosterdigi seti dondurur.
     *
     * Bildirim bir tur secip [select] ile isaretledigi anda o kayitlarin
     * `lastSurfacedAt` degeri "az once" olur; [select] yeniden calistirilirsa ayni
     * kayitlar "yeni gosterildi" diye ELENIR. Yani bildirime dokunan kullanici,
     * bildirimin vaat ettiginden farkli screenshotlar gorurdu.
     *
     * Bu yuzden bildirim turunun kimlikleri saklaniyor ve tur taze oldugu surece ana
     * ekran birebir onlari gosteriyor. Kullanici bir kaydi tamamladiysa/arsivlediyse ya
     * da gorsel silindiyse o kayit turdan dusuyor; tur tamamen tukenirse politika
     * yeniden calisiyor.
     */
    fun selectForHome(
        items: List<ScreenshotItem>,
        roundIds: List<Long>,
        roundAtMillis: Long,
        nowMillis: Long,
        limit: Int = 3,
    ): List<ScreenshotItem> {
        val roundIsFresh = roundAtMillis > 0L &&
            nowMillis - roundAtMillis <= TimeUnit.DAYS.toMillis(ROUND_FRESH_DAYS)
        if (roundIsFresh && roundIds.isNotEmpty()) {
            val byId = items.associateBy(ScreenshotItem::id)
            val stillRelevant = roundIds
                .mapNotNull(byId::get)
                .filterNot(ScreenshotItem::sensitive)
                .filter { it.status !in setOf(ScreenshotStatus.DONE, ScreenshotStatus.ARCHIVED) }
            if (stillRelevant.isNotEmpty()) return stillRelevant.take(limit)
        }
        return select(items, nowMillis, limit)
    }

    /** Bildirim turu bu kadar gun boyunca ana ekranda gecerlidir. */
    const val ROUND_FRESH_DAYS = 7L
}
