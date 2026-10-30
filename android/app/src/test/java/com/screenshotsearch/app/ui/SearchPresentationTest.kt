package com.screenshotsearch.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchPresentationTest {
    @Test
    fun `query aware snippet selects matching line instead of first OCR line`() {
        val ocr = "Instagram\n15 Temmuz\nKolay kebap tarifi ve malzemeleri"

        assertEquals(
            "Kolay kebap tarifi ve malzemeleri",
            bestMatchingOcrLine(ocr, "geçen ay kaydettiğim kebap tarifi"),
        )
    }

    @Test
    fun `conversation filler is ignored while matching`() {
        val ocr = "Ana sayfa\nKadıköy'de yeni kahve mekanı"

        assertEquals(
            "Kadıköy'de yeni kahve mekanı",
            bestMatchingOcrLine(ocr, "aldığım Kadıköy mekan screenshotı neredeydi"),
        )
    }

    @Test
    fun `no textual evidence returns null instead of misleading first line`() {
        assertNull(bestMatchingOcrLine("Instagram\nTakip ediliyor", "geçen ay film"))
    }

    @Test
    fun `sensitive card masks OCR title and match evidence`() {
        assertEquals(
            "Hassas screenshot",
            screenshotDisplayTitle(
                title = "Özel banka bilgileri",
                processed = true,
                sensitiveHidden = true,
            ),
        )
        assertEquals(
            "Hassas içerik · açmak için dokun",
            searchMatchExplanation(
                ocrText = "Kart numarası 1234",
                query = "kart numarası",
                sensitiveHidden = true,
            ),
        )
    }
}
