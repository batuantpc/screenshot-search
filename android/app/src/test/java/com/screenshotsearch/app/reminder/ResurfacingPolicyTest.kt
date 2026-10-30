package com.screenshotsearch.app.reminder

import com.screenshotsearch.app.data.ContentTopic
import com.screenshotsearch.app.data.IntentCategory
import com.screenshotsearch.app.data.ScreenshotItem
import com.screenshotsearch.app.data.ScreenshotStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ResurfacingPolicyTest {
    private val now = 1_800_000_000_000L

    @Test
    fun `excludes recent sensitive finished and recently surfaced items`() {
        val eligible = item(id = 1, ageDays = 20)
        val recent = item(id = 2, ageDays = 2)
        val sensitive = item(id = 3, ageDays = 20, sensitive = true)
        val done = item(id = 4, ageDays = 20, status = ScreenshotStatus.DONE)
        val archived = item(id = 5, ageDays = 20, status = ScreenshotStatus.ARCHIVED)
        val recentlySurfaced = item(
            id = 6,
            ageDays = 20,
            lastSurfacedAt = now - TimeUnit.DAYS.toMillis(4),
        )

        val selected = ResurfacingPolicy.select(
            listOf(eligible, recent, sensitive, done, archived, recentlySurfaced),
            now,
        )

        assertEquals(listOf(1L), selected.map(ScreenshotItem::id))
    }

    @Test
    fun `prioritizes later then interested then actionable intent`() {
        val ordinary = item(id = 1, ageDays = 30, confidence = 0.99f)
        val actionable = item(id = 2, ageDays = 30, intent = IntentCategory.TRY)
        val interested = item(id = 3, ageDays = 30, status = ScreenshotStatus.INTERESTED)
        val later = item(id = 4, ageDays = 30, status = ScreenshotStatus.LATER)

        val selected = ResurfacingPolicy.select(
            listOf(ordinary, actionable, interested, later),
            now,
        )

        assertEquals(listOf(4L, 3L, 2L), selected.map(ScreenshotItem::id))
    }

    @Test
    fun `allows an item after fourteen days since previous resurfacing`() {
        val item = item(
            id = 1,
            ageDays = 45,
            lastSurfacedAt = now - TimeUnit.DAYS.toMillis(15),
        )

        assertTrue(ResurfacingPolicy.select(listOf(item), now).isNotEmpty())
    }

    @Test
    fun `home shows the same items the notification announced`() {
        // Bildirim turu bu ucunu secti ve markSurfaced ile "az once gosterildi" yapti.
        val justSurfaced = now - TimeUnit.MINUTES.toMillis(5)
        val a = item(id = 1, ageDays = 30, lastSurfacedAt = justSurfaced)
        val b = item(id = 2, ageDays = 30, lastSurfacedAt = justSurfaced)
        val c = item(id = 3, ageDays = 30, lastSurfacedAt = justSurfaced)
        val other = item(id = 9, ageDays = 30)
        val items = listOf(a, b, c, other)

        // Ham politika onlari eler: bu, bildirime dokunan kullanicinin yasadigi hataydi.
        assertEquals(listOf(9L), ResurfacingPolicy.select(items, now).map(ScreenshotItem::id))

        val home = ResurfacingPolicy.selectForHome(
            items = items,
            roundIds = listOf(1L, 2L, 3L),
            roundAtMillis = justSurfaced,
            nowMillis = now,
        )

        assertEquals(listOf(1L, 2L, 3L), home.map(ScreenshotItem::id))
    }

    @Test
    fun `home drops round items the user already finished or archived`() {
        val justSurfaced = now - TimeUnit.MINUTES.toMillis(5)
        val done = item(id = 1, ageDays = 30, status = ScreenshotStatus.DONE, lastSurfacedAt = justSurfaced)
        val archived = item(id = 2, ageDays = 30, status = ScreenshotStatus.ARCHIVED, lastSurfacedAt = justSurfaced)
        val open = item(id = 3, ageDays = 30, lastSurfacedAt = justSurfaced)

        val home = ResurfacingPolicy.selectForHome(
            items = listOf(done, archived, open),
            roundIds = listOf(1L, 2L, 3L),
            roundAtMillis = justSurfaced,
            nowMillis = now,
        )

        assertEquals(listOf(3L), home.map(ScreenshotItem::id))
    }

    @Test
    fun `home falls back to the policy once the round goes stale`() {
        val staleRound = now - TimeUnit.DAYS.toMillis(ResurfacingPolicy.ROUND_FRESH_DAYS + 1)
        val roundItem = item(id = 1, ageDays = 40, lastSurfacedAt = staleRound)
        val fresh = item(id = 2, ageDays = 40)

        val home = ResurfacingPolicy.selectForHome(
            items = listOf(roundItem, fresh),
            roundIds = listOf(1L),
            roundAtMillis = staleRound,
            nowMillis = now,
        )

        // Tur bayatladi, politika yeniden konusuyor. Tur kaydi hala 14 gunluk sogumada
        // oldugu icin geri gelmiyor: ROUND_FRESH_DAYS (7) sogumadan (14) kisa oldugundan
        // "az once gosterilen kayit yeniden gosterilir" penceresi hic olusmuyor.
        assertEquals(listOf(2L), home.map(ScreenshotItem::id))
    }

    @Test
    fun `home uses the policy when no round has run yet`() {
        val eligible = item(id = 1, ageDays = 30)

        val home = ResurfacingPolicy.selectForHome(
            items = listOf(eligible),
            roundIds = emptyList(),
            roundAtMillis = 0L,
            nowMillis = now,
        )

        assertEquals(listOf(1L), home.map(ScreenshotItem::id))
    }

    private fun item(
        id: Long,
        ageDays: Long,
        intent: IntentCategory = IntentCategory.REMEMBER,
        status: ScreenshotStatus = ScreenshotStatus.NEW,
        confidence: Float = 0.8f,
        sensitive: Boolean = false,
        lastSurfacedAt: Long? = null,
    ) = ScreenshotItem(
        id = id,
        mediaStoreId = id,
        contentUri = "content://test/$id",
        capturedAt = now - TimeUnit.DAYS.toMillis(ageDays),
        displayName = "Screenshot_$id.png",
        relativePath = "Pictures/Screenshots",
        ocrText = "test",
        title = "Test $id",
        intent = intent,
        topic = ContentTopic.OTHER,
        confidence = confidence,
        status = status,
        processed = true,
        sensitive = sensitive,
        userCorrected = false,
        userTopicCorrected = false,
        lastSurfacedAt = lastSurfacedAt,
        sourceHint = null,
    )
}
