package com.screenshotsearch.app.processing

import com.screenshotsearch.app.data.ContentTopic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicSuggestionPolicyTest {
    @Test
    fun `person name and profile chrome abstain instead of guessing sport`() {
        val result = IntentClassifier.classify(
            ocrText = "Instagram\nornekhesap\nTakip Et\nMesaj",
            displayName = "Screenshot_profile.jpg",
        )

        assertEquals(ContentTopic.OTHER, result.topic)
        assertEquals("Instagram", result.sourceHint)
        assertTrue(result.confidence < 0.5f)
    }

    @Test
    fun `clear recipe language produces a confident suggestion`() {
        val result = IntentClassifier.classify(
            ocrText = "Limonlu pasta tarifi\nMalzemeler\n2 yumurta\n180 derece",
            displayName = "Screenshot_recipe.jpg",
        )

        assertEquals(ContentTopic.RECIPE, result.topic)
        assertTrue(result.confidence >= 0.8f)
    }

    @Test
    fun `known game title beats short video source format`() {
        val result = IntentClassifier.classify(
            ocrText = "Block Blast\nYouTube\nShorts\nPlay now",
            displayName = "Screenshot_game_ad.jpg",
        )

        assertEquals(ContentTopic.GAME, result.topic)
    }

    @Test
    fun `trusted vertical publisher can support its semantic collection`() {
        val result = IntentClassifier.classify(
            ocrText = "fotomac.com.tr\nFenerbahçe transfer haberi",
            displayName = "Screenshot_sport.jpg",
        )

        assertEquals(ContentTopic.SPORT, result.topic)
        assertTrue(result.confidence >= 0.9f)
    }

    @Test
    fun `generic visual label alone abstains`() {
        val result = IntentClassifier.classify(
            ocrText = "",
            displayName = "Screenshot_food.jpg",
            visualLabels = listOf(VisualLabel("Food", 0.96f)),
        )

        assertEquals(ContentTopic.OTHER, result.topic)
    }
}
