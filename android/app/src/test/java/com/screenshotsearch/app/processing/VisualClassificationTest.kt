package com.screenshotsearch.app.processing

import com.screenshotsearch.app.data.ContentTopic
import com.screenshotsearch.app.data.IntentCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualClassificationTest {
    @Test
    fun `food photo without text does not create an unsupported collection`() {
        val result = IntentClassifier.classify(
            ocrText = "",
            displayName = "Screenshot_food.jpg",
            visualLabels = listOf(
                VisualLabel("Food", 0.92f),
                VisualLabel("Dessert", 0.81f),
            ),
        )

        assertEquals(ContentTopic.OTHER, result.topic)
        assertEquals(IntentCategory.UNCLEAR, result.intent)
    }

    @Test
    fun `generic game visual alone does not create a collection`() {
        val result = IntentClassifier.classify(
            ocrText = "skor 12450",
            displayName = "Screenshot_game.jpg",
            visualLabels = listOf(VisualLabel("Games", 0.83f)),
        )

        assertEquals(ContentTopic.OTHER, result.topic)
        assertEquals(IntentCategory.UNCLEAR, result.intent)
    }

    @Test
    fun `strong text topic beats visual label`() {
        val result = IntentClassifier.classify(
            ocrText = "Son dakika haber ajansı bildirdi",
            displayName = "Screenshot_news_food.jpg",
            visualLabels = listOf(VisualLabel("Food", 0.95f)),
        )

        assertEquals(ContentTopic.NEWS, result.topic)
    }

    @Test
    fun `visual label and social chrome are insufficient for a collection`() {
        // Görsel etiketleri arama ve gelecekteki öneriler için saklanabilir; tek başına otomatik
        // koleksiyon açmak için yeterince güvenilir değillerdir.
        val result = IntentClassifier.classify(
            ocrText = "Instagram\nBeğen Yorum Paylaş",
            displayName = "Screenshot_insta_food.jpg",
            visualLabels = listOf(VisualLabel("Food", 0.88f)),
        )

        assertEquals(ContentTopic.OTHER, result.topic)
    }

    @Test
    fun `low confidence visual label is ignored`() {
        val result = IntentClassifier.classify(
            ocrText = "",
            displayName = "Screenshot_blur.jpg",
            visualLabels = listOf(VisualLabel("Food", 0.58f)),
        )

        assertEquals(ContentTopic.OTHER, result.topic)
    }

    @Test
    fun `unknown labels are ignored silently`() {
        val result = IntentClassifier.classify(
            ocrText = "",
            displayName = "Screenshot_misc.jpg",
            visualLabels = listOf(
                VisualLabel("Sky", 0.97f),
                VisualLabel("Smile", 0.90f),
            ),
        )

        assertEquals(ContentTopic.OTHER, result.topic)
    }

    @Test
    fun `visual labels serialize and parse round trip`() {
        val labels = listOf(
            VisualLabel("Food", 0.92f),
            VisualLabel("Video game", 0.7f),
        )

        assertEquals(labels, VisualLabels.parse(VisualLabels.serialize(labels)))
    }

    @Test
    fun `empty labels serialize to none sentinel and parse back empty`() {
        val serialized = VisualLabels.serialize(emptyList())

        assertEquals(VisualLabels.NONE, serialized)
        assertTrue(VisualLabels.parse(serialized).isEmpty())
        assertTrue(VisualLabels.parse(null).isEmpty())
        assertTrue(VisualLabels.parse("").isEmpty())
    }
}
