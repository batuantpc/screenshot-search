package com.screenshotsearch.app.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ScreenshotQueryParserTest {
    private val zone = ZoneId.of("Europe/Istanbul")
    private val parser = ScreenshotQueryParser(zone)
    private val now = ZonedDateTime.of(2026, 7, 14, 12, 0, 0, 0, zone)
        .toInstant()
        .toEpochMilli()

    @Test
    fun `last month recipe query searches OCR instead of trusting auto labels`() {
        val spec = parser.parse("geçen ayki pasta tarifi", now)

        assertNull(spec.intent)
        assertNull(spec.topic)
        assertEquals(listOf("pasta", "tarifi"), spec.terms)
        assertTrue(spec.capturedFrom != null)
        assertTrue(spec.capturedUntil != null)
    }

    @Test
    fun `place query keeps location term`() {
        val spec = parser.parse("Kadıköy kahveci", now)

        assertNull(spec.intent)
        assertNull(spec.topic)
        assertEquals(listOf("kadikoy", "kahveci"), spec.terms)
    }

    @Test
    fun `shopping query keeps visual descriptors`() {
        val spec = parser.parse("mavi spor ayakkabı", now)

        assertEquals(listOf("mavi", "spor", "ayakkabi"), spec.terms)
    }

    @Test
    fun `news query keeps category word as searchable OCR evidence`() {
        val spec = parser.parse("geçen hafta kaydettiğim haber", now)

        assertNull(spec.topic)
        assertNull(spec.intent)
        assertEquals(listOf("haber"), spec.terms)
    }

    @Test
    fun `music query remains a text search`() {
        val spec = parser.parse("geçen ay kaydettiğim müzik", now)

        assertNull(spec.topic)
        assertEquals(listOf("muzik"), spec.terms)
    }

    @Test
    fun `shorts query remains a text search`() {
        val spec = parser.parse("kaydettiğim shorts videosu", now)

        assertNull(spec.topic)
        assertEquals(listOf("shorts", "videosu"), spec.terms)
    }

    @Test
    fun `game query keeps descriptive words`() {
        val spec = parser.parse("kaydettiğim oyunu dene", now)

        assertEquals(listOf("oyunu", "dene"), spec.terms)
    }

    @Test
    fun `ai query keeps model and prompt words`() {
        val spec = parser.parse("kaydettiğim yapay zeka prompt rehberi", now)

        assertEquals(listOf("yapay", "zeka", "prompt", "rehberi"), spec.terms)
    }

    @Test
    fun `book query remains a text search`() {
        val spec = parser.parse("geçen ay kaydettiğim kitap", now)

        assertEquals(listOf("kitap"), spec.terms)
    }

    @Test
    fun `politics query keeps both person and subject words`() {
        val spec = parser.parse("Angela Merkel siyaset", now)

        assertEquals(listOf("angela", "merkel", "siyaset"), spec.terms)
    }

    @Test
    fun `finance query searches finance and currency words`() {
        val spec = parser.parse("geçen ayki döviz ve finans screenshotları", now)

        assertNull(spec.topic)
        assertEquals(listOf("doviz", "finans"), spec.terms)
    }

    @Test
    fun `today and yesterday queries become date only filters`() {
        val today = parser.parse("bugün aldığım screenshot", now)
        val yesterday = parser.parse("dün kaydettiğim ss", now)

        assertTrue(today.terms.isEmpty())
        assertTrue(yesterday.terms.isEmpty())
        assertTrue(today.capturedFrom != null && today.capturedUntil != null)
        assertTrue(yesterday.capturedFrom != null && yesterday.capturedUntil != null)
    }
}
