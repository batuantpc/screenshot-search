package com.screenshotsearch.app.processing

import com.screenshotsearch.app.data.ContentTopic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicDecisionTest {
    @Test
    fun `person name cannot become sport even when model is overconfident`() {
        val prediction = TopicModel.Prediction(
            topic = ContentTopic.SPORT,
            confidence = 0.94f,
            margin = 0.88f,
        )

        assertFalse(TopicDecision.hasEnoughSemanticContent("Örnek Kullanıcı"))
        assertEquals(
            ContentTopic.OTHER,
            TopicDecision.decide(ContentTopic.OTHER, prediction, "Örnek Kullanıcı"),
        )
    }

    @Test
    fun `username and profile chrome cannot override social source`() {
        val prediction = TopicModel.Prediction(
            topic = ContentTopic.SPORT,
            confidence = 0.91f,
            margin = 0.81f,
        )

        assertEquals(
            ContentTopic.SOCIAL,
            TopicDecision.decide(
                ContentTopic.SOCIAL,
                prediction,
                "ornekhesap Takip Et Mesaj",
            ),
        )
    }

    @Test
    fun `rich unknown content may still be promoted by model`() {
        val prediction = TopicModel.Prediction(
            topic = ContentTopic.RECIPE,
            confidence = 0.72f,
            margin = 0.46f,
        )
        val text = "Mercimekleri yıkayın soğan ve salça ile yirmi dakika pişirin"

        assertTrue(TopicDecision.hasEnoughSemanticContent(text))
        assertEquals(
            ContentTopic.RECIPE,
            TopicDecision.decide(ContentTopic.OTHER, prediction, text),
        )
    }

    @Test
    fun `uncertain model abstains when rules find nothing`() {
        val prediction = TopicModel.Prediction(
            topic = ContentTopic.SPORT,
            confidence = 0.20f,
            margin = 0.03f,
        )
        val text = "Bugün arkadaşlarla çekildiğimiz güzel bir fotoğraf ve kısa bir not"

        assertEquals(
            ContentTopic.OTHER,
            TopicDecision.decide(ContentTopic.OTHER, prediction, text),
        )
    }
}
