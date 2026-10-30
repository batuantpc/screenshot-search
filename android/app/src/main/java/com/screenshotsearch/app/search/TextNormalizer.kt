package com.screenshotsearch.app.search

import java.text.Normalizer
import java.util.Locale

object TextNormalizer {
    private val turkish = Locale.forLanguageTag("tr-TR")

    fun normalize(value: String): String = Normalizer
        .normalize(value.lowercase(turkish), Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
        .replace('ı', 'i')
        .replace("[^a-z0-9₺]+".toRegex(), " ")
        .trim()
        .replace("\\s+".toRegex(), " ")
}
