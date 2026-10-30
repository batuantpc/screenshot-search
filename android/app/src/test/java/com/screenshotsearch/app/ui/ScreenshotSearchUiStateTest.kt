package com.screenshotsearch.app.ui

import com.screenshotsearch.app.data.ContentTopic
import com.screenshotsearch.app.data.IntentCategory
import com.screenshotsearch.app.data.ScreenshotItem
import com.screenshotsearch.app.data.ScreenshotStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotSearchUiStateTest {
    @Test
    fun `topic correction updates every visible copy and preserves unrelated rows`() {
        val target = item(id = 7, topic = ContentTopic.MOVIE)
        val unrelated = item(id = 9, topic = ContentTopic.RECIPE)
        val state = ScreenshotSearchUiState(
            homeItems = listOf(target, unrelated),
            visibleItems = listOf(target),
            selectedItem = target,
        )

        val corrected = state.withCorrectedTopic(7, ContentTopic.POLITICS)

        assertEquals(ContentTopic.POLITICS, corrected.homeItems.first().topic)
        assertEquals(ContentTopic.POLITICS, corrected.visibleItems.single().topic)
        assertEquals(ContentTopic.POLITICS, corrected.selectedItem?.topic)
        assertTrue(corrected.homeItems.first().userTopicCorrected)
        assertTrue(corrected.visibleItems.single().userTopicCorrected)
        assertTrue(corrected.selectedItem?.userTopicCorrected == true)
        assertEquals(unrelated, corrected.homeItems.last())
        assertFalse(corrected.homeItems.last().userTopicCorrected)
    }

    @Test
    fun `unknown or unchanged correction keeps the same state instance`() {
        val target = item(id = 7, topic = ContentTopic.MOVIE)
        val state = ScreenshotSearchUiState(homeItems = listOf(target))

        assertSame(state, state.withCorrectedTopic(99, ContentTopic.NEWS))
        assertSame(state, state.withCorrectedTopic(7, ContentTopic.MOVIE))
    }

    private fun item(id: Long, topic: ContentTopic) = ScreenshotItem(
        id = id,
        mediaStoreId = id,
        contentUri = "content://media/$id",
        capturedAt = 1_000L + id,
        displayName = "Screenshot_$id.png",
        relativePath = "Pictures/Screenshots/",
        ocrText = "örnek",
        title = "Örnek $id",
        intent = IntentCategory.REMEMBER,
        topic = topic,
        confidence = 0.8f,
        status = ScreenshotStatus.NEW,
        processed = true,
        sensitive = false,
        userCorrected = false,
        userTopicCorrected = false,
        lastSurfacedAt = null,
        sourceHint = null,
    )
}
