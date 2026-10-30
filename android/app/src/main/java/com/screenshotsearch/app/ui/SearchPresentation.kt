package com.screenshotsearch.app.ui

import com.screenshotsearch.app.search.TextNormalizer

internal fun screenshotDisplayTitle(
    title: String,
    processed: Boolean,
    sensitiveHidden: Boolean,
): String = when {
    sensitiveHidden -> "Hassas screenshot"
    title.isNotBlank() -> title
    processed -> "Başlıksız screenshot"
    else -> "Analiz ediliyor…"
}

internal fun searchMatchExplanation(
    ocrText: String,
    query: String,
    sensitiveHidden: Boolean,
): String = when {
    sensitiveHidden -> "Hassas içerik · açmak için dokun"
    else -> bestMatchingOcrLine(ocrText, query)
        ?.let { "Eşleşen metin: “$it”" }
        ?: "Tarih veya etiket bilgisiyle eşleşti"
}

/** Arama kartında, OCR'ın ilk satırı yerine kullanıcının sorgusunu açıklayan satırı gösterir. */
internal fun bestMatchingOcrLine(ocrText: String, query: String): String? {
    val terms = TextNormalizer.normalize(query)
        .split(' ')
        .filter { term -> term.length >= 2 && term !in SEARCH_FILLER_WORDS }
        .distinct()
    if (terms.isEmpty()) return null

    return ocrText
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .map { line ->
            val normalizedLine = TextNormalizer.normalize(line)
            val score = terms.sumOf { term ->
                when {
                    normalizedLine.split(' ').any { token -> token == term } -> 3
                    normalizedLine.contains(term) -> 1
                    else -> 0
                }
            }
            line to score
        }
        .filter { (_, score) -> score > 0 }
        .maxWithOrNull(
            compareBy<Pair<String, Int>> { (_, score) -> score }
                .thenByDescending { (line, _) -> line.length },
        )
        ?.first
}

private val SEARCH_FILLER_WORDS = setOf(
    "aldigim",
    "ay",
    "ara",
    "bu",
    "bul",
    "ekran",
    "gecen",
    "gun",
    "gorseli",
    "goruntusu",
    "hangi",
    "hafta",
    "kaydetmistim",
    "kaydettigim",
    "nerede",
    "neredeydi",
    "once",
    "screenshot",
    "screenshotlar",
    "ss",
    "su",
)
