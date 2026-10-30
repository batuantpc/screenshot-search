package com.screenshotsearch.app.search

import com.screenshotsearch.app.data.ContentTopic
import com.screenshotsearch.app.data.IntentCategory
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

data class SearchSpec(
    val originalQuery: String,
    val terms: List<String> = emptyList(),
    val intent: IntentCategory? = null,
    val topic: ContentTopic? = null,
    val capturedFrom: Long? = null,
    val capturedUntil: Long? = null,
)

class ScreenshotQueryParser(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    fun parse(query: String, nowMillis: Long = System.currentTimeMillis()): SearchSpec {
        val normalized = TextNormalizer.normalize(query)
        val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        val dateRange = parseDateRange(normalized, now)

        val terms = normalized
            .split(' ')
            .filter { token -> token.length >= 2 && token !in ignoredTokens }
            .distinct()

        return SearchSpec(
            originalQuery = query,
            terms = terms,
            capturedFrom = dateRange?.first,
            capturedUntil = dateRange?.second,
        )
    }

    private fun parseDateRange(normalized: String, now: ZonedDateTime): Pair<Long, Long>? = when {
        "gecen ay" in normalized -> {
            val thisMonth = now.withDayOfMonth(1).toLocalDate().atStartOfDay(zoneId)
            thisMonth.minusMonths(1).toInstant().toEpochMilli() to
                thisMonth.toInstant().toEpochMilli()
        }

        "bu ay" in normalized -> {
            val start = now.withDayOfMonth(1).toLocalDate().atStartOfDay(zoneId)
            start.toInstant().toEpochMilli() to now.toInstant().toEpochMilli()
        }

        "gecen hafta" in normalized -> {
            val startOfThisWeek = now
                .minusDays((now.dayOfWeek.value - 1).toLong())
                .toLocalDate()
                .atStartOfDay(zoneId)
            startOfThisWeek.minusWeeks(1).toInstant().toEpochMilli() to
                startOfThisWeek.toInstant().toEpochMilli()
        }

        "bu hafta" in normalized -> {
            val start = now
                .minusDays((now.dayOfWeek.value - 1).toLong())
                .toLocalDate()
                .atStartOfDay(zoneId)
            start.toInstant().toEpochMilli() to now.toInstant().toEpochMilli()
        }

        "gecen yil" in normalized -> {
            val thisYear = now.withDayOfYear(1).toLocalDate().atStartOfDay(zoneId)
            thisYear.minusYears(1).toInstant().toEpochMilli() to
                thisYear.toInstant().toEpochMilli()
        }

        "bu yil" in normalized -> {
            val start = now.withDayOfYear(1).toLocalDate().atStartOfDay(zoneId)
            start.toInstant().toEpochMilli() to now.toInstant().toEpochMilli()
        }

        "son 30 gun" in normalized -> {
            now.minusDays(30).toInstant().toEpochMilli() to now.toInstant().toEpochMilli()
        }

        "son 7 gun" in normalized -> {
            now.minusDays(7).toInstant().toEpochMilli() to now.toInstant().toEpochMilli()
        }

        "bugun" in normalized -> {
            val start = now.toLocalDate().atStartOfDay(zoneId)
            start.toInstant().toEpochMilli() to now.toInstant().toEpochMilli()
        }

        "dun" in normalized -> {
            val today = now.toLocalDate().atStartOfDay(zoneId)
            today.minusDays(1).toInstant().toEpochMilli() to today.toInstant().toEpochMilli()
        }

        else -> null
    }

    private companion object {
        val ignoredTokens = setOf(
            "ara",
            "aradigim",
            "arsivimde",
            "bana",
            "benim",
            "gecen",
            "bu",
            "bir",
            "da",
            "de",
            "ay",
            "ayki",
            "30",
            "bugun",
            "dun",
            "gun",
            "once",
            "son",
            "hafta",
            "haftaki",
            "yil",
            "kaydettigimiz",
            "kaydettigim",
            "kaydetmistim",
            "kaydetmistin",
            "kaydetmisim",
            "aldigim",
            "screenshot",
            "screenshotlar",
            "screenshotlarda",
            "screenshotlarim",
            "screenshotlari",
            "ss",
            "ekran",
            "goruntusu",
            "goruntuleri",
            "nerede",
            "neredeydi",
            "bul",
            "bulabilir",
            "bulur",
            "misin",
            "miydi",
            "olan",
            "icinde",
            "icin",
            "ile",
            "ve",
        )
    }
}
