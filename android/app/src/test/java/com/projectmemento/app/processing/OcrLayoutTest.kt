package com.projectmemento.app.processing

import com.projectmemento.app.data.ContentTopic
import com.projectmemento.app.data.IntentCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrLayoutTest {
    // 1080x2408 = Galaxy A23 ekranı; gerçek cihaz oranlarıyla test ediyoruz.
    private val screenWidth = 1080
    private val screenHeight = 2408

    private fun line(
        text: String,
        top: Int,
        height: Int,
        block: Int = 0,
        left: Int = 40,
        right: Int = 1040,
    ) = OcrLine(block = block, text = text, left = left, top = top, right = right, bottom = top + height)

    @Test
    fun `content lines drop status bar and navigation zones`() {
        val layout = OcrLayout(
            imageWidth = screenWidth,
            imageHeight = screenHeight,
            lines = listOf(
                line("10:58", top = 20, height = 40),
                line("Gerçek içerik satırı", top = 600, height = 48, block = 1),
                line("< O III", top = 2350, height = 40, block = 2),
            ),
        )

        assertEquals(listOf("Gerçek içerik satırı"), layout.contentLines().map(OcrLine::text))
        assertEquals("Gerçek içerik satırı", layout.contentText())
    }

    @Test
    fun `title candidates rank biggest text in prime zone first`() {
        val layout = OcrLayout(
            imageWidth = screenWidth,
            imageHeight = screenHeight,
            lines = listOf(
                line("sekme metni kucuk", top = 250, height = 34, block = 0),
                line("Büyük Gerçek Başlık", top = 700, height = 92, block = 1),
                line("açıklama satırı normal puntoda devam ediyor", top = 850, height = 40, block = 2),
            ),
        )

        assertEquals("Büyük Gerçek Başlık", layout.titleCandidates().first())
    }

    @Test
    fun `adjacent same block lines with similar height merge into one candidate`() {
        val layout = OcrLayout(
            imageWidth = screenWidth,
            imageHeight = screenHeight,
            lines = listOf(
                line("İki satıra bölünmüş", top = 700, height = 80, block = 1),
                line("uzun haber başlığı", top = 800, height = 78, block = 1),
                line("küçük alt metin", top = 950, height = 36, block = 1),
            ),
        )

        assertEquals("İki satıra bölünmüş uzun haber başlığı", layout.titleCandidates().first())
    }

    @Test
    fun `serialization round trip preserves layout`() {
        val original = OcrLayout(
            imageWidth = screenWidth,
            imageHeight = screenHeight,
            lines = listOf(
                line("Pipe | içeren satır", top = 500, height = 44, block = 0),
                line("İkinci satır", top = 600, height = 40, block = 1),
            ),
        )

        val restored = OcrLayout.deserialize(original.serialize())

        assertEquals(original, restored)
    }

    @Test
    fun `deserialize rejects blank and malformed payloads`() {
        assertNull(OcrLayout.deserialize(null))
        assertNull(OcrLayout.deserialize(""))
        assertNull(OcrLayout.deserialize("garip veri"))
        assertNull(OcrLayout.deserialize("2|1080|2408\n0|1|2|3|4|gelecek format"))
    }

    @Test
    fun `classifier uses layout to ignore status bar noise and pick visual title`() {
        val layout = OcrLayout(
            imageWidth = screenWidth,
            imageHeight = screenHeight,
            lines = listOf(
                line("11:54 %48", top = 20, height = 40, block = 0),
                line("instagram.com", top = 180, height = 36, block = 1),
                line("Ev Yapımı Limonata Nasıl Yapılır", top = 640, height = 88, block = 2),
                line("2 limon 1 litre su şeker", top = 780, height = 42, block = 3),
                line("Malzemeler ve hazırlanışı yorumlarda", top = 850, height = 42, block = 3),
            ),
        )
        val ocrText = layout.lines.joinToString("\n", transform = OcrLine::text)

        val result = IntentClassifier.classify(ocrText, "Screenshot_layout.jpg", layout)

        assertEquals(ContentTopic.RECIPE, result.topic)
        assertEquals(IntentCategory.UNCLEAR, result.intent)
        assertEquals("Ev Yapımı Limonata Nasıl Yapılır", result.title)
    }

    @Test
    fun `layout title falls back to generic scan when big text is unusable`() {
        val layout = OcrLayout(
            imageWidth = screenWidth,
            imageHeight = screenHeight,
            lines = listOf(
                line("SÖZCÜ", top = 300, height = 96, block = 0),
                line("Kadıköy sahilinde yeni düzenleme başladı", top = 900, height = 44, block = 1),
            ),
        )
        val ocrText = layout.lines.joinToString("\n", transform = OcrLine::text)

        val result = IntentClassifier.classify(ocrText, "Screenshot_headline.jpg", layout)

        assertEquals("Kadıköy sahilinde yeni düzenleme başladı", result.title)
    }

    @Test
    fun `empty image dimensions keep every line`() {
        val layout = OcrLayout(
            imageWidth = 0,
            imageHeight = 0,
            lines = listOf(line("tek satır", top = 10, height = 20)),
        )

        assertEquals(1, layout.contentLines().size)
        assertTrue(layout.titleCandidates().isNotEmpty())
    }
}
