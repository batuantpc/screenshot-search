package com.projectmemento.app.ui

import com.projectmemento.app.data.ContentTopic
import com.projectmemento.app.data.IntentCategory
import com.projectmemento.app.data.ScreenshotItem
import com.projectmemento.app.data.ScreenshotStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class TopicCollectionsTest {
    @Test
    fun `collections contain only processed topics that exist in the library`() {
        val items = listOf(
            item(id = 1, topic = ContentTopic.RECIPE),
            item(id = 2, topic = ContentTopic.RECIPE),
            item(id = 3, topic = ContentTopic.POLITICS),
            item(id = 4, topic = ContentTopic.NEWS, processed = false),
            item(id = 5, topic = ContentTopic.OTHER),
        )

        assertEquals(
            listOf(ContentTopic.RECIPE to 2, ContentTopic.POLITICS to 1),
            activeTopicCounts(items),
        )
    }

    private fun item(
        id: Long,
        topic: ContentTopic,
        processed: Boolean = true,
    ) = ScreenshotItem(
        id = id,
        mediaStoreId = id,
        contentUri = "content://test/$id",
        capturedAt = id,
        displayName = "Screenshot_$id.png",
        relativePath = "Pictures/Screenshots/",
        ocrText = "test",
        title = "Test screenshot",
        intent = IntentCategory.UNCLEAR,
        topic = topic,
        confidence = 1f,
        status = ScreenshotStatus.NEW,
        processed = processed,
        sensitive = false,
        userCorrected = false,
        userTopicCorrected = false,
        lastSurfacedAt = null,
        sourceHint = null,
    )
}
