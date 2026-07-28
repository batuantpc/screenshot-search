package com.projectmemento.app.processing

import com.projectmemento.app.data.ContentTopic
import com.projectmemento.app.search.TextNormalizer

/**
 * Ham konu adayını kullanıcıya gösterilecek, yüksek kesinlikli bir öneriye çevirir.
 *
 * Memento'nun ana vaadi aramadır; konu etiketi yardımcı metadatadır. Bu nedenle yanlış bir
 * koleksiyon üretmektense [ContentTopic.OTHER] ile çekilmek bilinçli ürün davranışıdır.
 * Politika yalnız dikey ve güvenilir bir kaynak eşleşmesi ya da açık içerik kanıtı varsa
 * otomatik konu gösterir. Instagram/YouTube/Reddit gibi çok konulu kaynaklar tek başına
 * içerik konusu sayılmaz; kaynak adı ayrı [sourceHint] alanında kalır.
 */
object TopicSuggestionPolicy {

    data class Decision(
        val topic: ContentTopic,
        val confidence: Float,
    )

    fun decide(
        candidate: ContentTopic,
        sourceHint: String?,
        contentText: String,
    ): Decision {
        if (candidate == ContentTopic.OTHER) return Decision(ContentTopic.OTHER, ABSTAIN_CONFIDENCE)
        if (trustedSourceSupports(sourceHint, candidate)) {
            return Decision(candidate, TRUSTED_SOURCE_CONFIDENCE)
        }

        val normalized = " ${TextNormalizer.normalize(contentText)} "
        val evidence = evidenceFor(candidate)
        val hasStrongEvidence = evidence.strong.any { marker -> contains(normalized, marker) }
        val supportCount = evidence.support.count { marker -> contains(normalized, marker) }
        return if (hasStrongEvidence || supportCount >= evidence.requiredSupport) {
            Decision(
                topic = candidate,
                confidence = if (hasStrongEvidence) STRONG_EVIDENCE_CONFIDENCE else SUPPORT_CONFIDENCE,
            )
        } else {
            Decision(ContentTopic.OTHER, ABSTAIN_CONFIDENCE)
        }
    }

    private fun trustedSourceSupports(sourceHint: String?, candidate: ContentTopic): Boolean =
        when (sourceHint) {
            "TCMB", "Bloomberg HT", "Finans" -> candidate == ContentTopic.FINANCE
            "Haber yayını" -> candidate == ContentTopic.NEWS
            "Siyaset yayını" -> candidate == ContentTopic.POLITICS
            "Spor yayını" -> candidate == ContentTopic.SPORT
            "Film yayını" -> candidate == ContentTopic.MOVIE
            "Film / dizi yayını" ->
                candidate == ContentTopic.MOVIE || candidate == ContentTopic.SERIES
            "tabii" -> candidate == ContentTopic.SERIES
            "Müzik yayını" -> candidate == ContentTopic.MUSIC
            "Ürün", "Alışveriş" -> candidate == ContentTopic.PRODUCT
            "Gezi rehberi", "Google Maps" -> candidate == ContentTopic.PLACE
            "Kitap yayını" -> candidate == ContentTopic.BOOK
            "Yapay zeka" -> candidate == ContentTopic.AI
            else -> false
        }

    private fun contains(normalizedText: String, marker: String): Boolean {
        val normalizedMarker = TextNormalizer.normalize(marker)
        return " $normalizedMarker " in normalizedText
    }

    private fun evidenceFor(topic: ContentTopic): Evidence = when (topic) {
        ContentTopic.RECIPE -> Evidence(
            strong = setOf(
                "tarifi", "tarifleri", "malzemeler", "hazırlanışı", "yapılışı",
                "ingredients", "recipe",
            ),
            support = setOf(
                "tarif", "yemek", "pişirin", "fırında", "çorba", "hamur", "servis edin",
                "cook", "bake", "tablespoon", "teaspoon",
            ),
        )

        ContentTopic.MOVIE -> Evidence(
            strong = setOf(
                "imdb", "letterboxd", "rottentomatoes", "beyazperde", "sinemalar com",
                "official trailer", "official teaser",
            ),
            support = setOf(
                "film", "sinema", "vizyon", "yönetmen", "director", "oyuncular", "cast",
                "fragman", "trailer",
            ),
        )

        ContentTopic.SERIES -> Evidence(
            strong = setOf("episode", "sezon", "bölüm"),
            support = setOf("dizi", "series", "season", "netflix", "hbo", "disney"),
        )

        ContentTopic.NEWS -> Evidence(
            strong = setOf("breaking news", "son dakika", "reuters", "associated press"),
            support = setOf("haber", "haberler", "gündem", "news", "ajansı", "muhabir"),
        )

        ContentTopic.POLITICS -> Evidence(
            strong = setOf(
                "tbmm", "parlamento", "parliament", "election", "seçim sonuçları",
                "devlet görevlisi",
            ),
            support = setOf(
                "siyaset", "siyasi", "meclis", "hükümet", "cumhurbaşkanı", "başbakan",
                "bakan", "milletvekili", "parti", "oy", "şansölye", "devlet görevlisi",
            ),
        )

        ContentTopic.FINANCE -> Evidence(
            strong = setOf("bitcoin", "ethereum", "nasdaq", "bist", "merkez bankası"),
            support = setOf(
                "finans", "borsa", "döviz", "hisse", "yatırım", "enflasyon", "faiz",
                "portföy", "türk lirası", "exchange rate", "stock market",
            ),
        )

        ContentTopic.PLACE -> Evidence(
            strong = setOf("google maps", "yol tarifi", "maps"),
            support = setOf(
                "adres", "konum", "restoran", "restaurant", "kafe", "cafe", "otel", "hotel",
                "rezervasyon", "rota", "yakınımda", "mahalle", "mahallesi", "cadde",
            ),
        )

        ContentTopic.PRODUCT -> Evidence(
            strong = setOf("sepete ekle", "sepette", "satın al", "add to cart", "buy now"),
            support = setOf(
                "ürün", "indirim", "stok", "beden", "kargo", "fiyat", "sepet", "teslimat",
                "shopping", "shop",
            ),
        )

        ContentTopic.SPORT -> Evidence(
            strong = setOf(
                "futbol", "basketbol", "nba", "nfl", "uefa", "fifa", "espn", "premier league",
            ),
            support = setOf(
                "maç", "gol", "puan durumu", "fikstür", "takım", "oyuncu", "transfer",
                "standings", "playoffs", "tournament",
            ),
        )

        ContentTopic.GAME -> Evidence(
            strong = setOf("gameplay", "playstation", "xbox", "nintendo", "steam", "block blast"),
            support = setOf(
                "oyun", "gaming", "level", "görev", "skor", "yüksek skor", "multiplayer",
                "karakter", "boss",
            ),
        )

        ContentTopic.AI -> Evidence(
            strong = setOf(
                "yapay zeka", "artificial intelligence", "chatgpt", "openai", "claude",
                "gemini", "machine learning",
            ),
            support = setOf("prompt", "model", "llm", "üretken", "assistant"),
        )

        ContentTopic.SCIENCE -> Evidence(
            strong = setOf("nasa", "kara delik", "black hole", "bilimsel araştırma"),
            support = setOf(
                "bilim", "science", "fizik", "physics", "kimya", "biyoloji", "astronomi",
                "genetik", "gezegen", "deney",
            ),
        )

        ContentTopic.BOOK -> Evidence(
            strong = setOf("isbn", "goodreads", "e kitap", "ebook"),
            support = setOf(
                "kitap", "roman", "yazar", "author", "yayınevi", "publisher", "sayfa",
                "okuma listesi", "book", "historian", "romandır", "yazılan",
            ),
        )

        ContentTopic.MUSIC -> Evidence(
            strong = setOf(
                "spotify", "apple music", "youtube music", "lyrics", "official music video",
            ),
            support = setOf(
                "müzik", "şarkı", "albüm", "sanatçı", "artist", "konser", "playlist",
                "dinle",
            ),
        )

        // Bunlar içerik konusu değil, çoğunlukla kaynak/format sinyalidir. Kaynak adı
        // sourceHint olarak aranabilir; otomatik koleksiyon üretmez.
        ContentTopic.VIDEO,
        ContentTopic.SOCIAL,
        ContentTopic.OTHER,
        -> Evidence()
    }

    private data class Evidence(
        val strong: Set<String> = emptySet(),
        val support: Set<String> = emptySet(),
        val requiredSupport: Int = 2,
    )

    private const val TRUSTED_SOURCE_CONFIDENCE = 0.94f
    private const val STRONG_EVIDENCE_CONFIDENCE = 0.88f
    private const val SUPPORT_CONFIDENCE = 0.78f
    private const val ABSTAIN_CONFIDENCE = 0.10f
}
