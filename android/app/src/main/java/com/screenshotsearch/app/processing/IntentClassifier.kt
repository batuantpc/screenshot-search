package com.screenshotsearch.app.processing

import com.screenshotsearch.app.data.ClassificationResult
import com.screenshotsearch.app.data.ContentTopic
import com.screenshotsearch.app.data.IntentCategory
import com.screenshotsearch.app.search.TextNormalizer

object IntentClassifier {
    /**
     * Kural sozlugu her anlamli degistiginde artar. Duzeltme denetim olaylari bu surumu
     * saklar; boylece "hangi surum bu hatayi yapiyordu" sonradan cevaplanabilir ve
     * surumler arasi kalite olculur.
     * v5: Ingilizce kapsama. Web holdout v1 kor baseline %55,8'de Ingilizce sayfalarin
     * OTHER'a coktugunu gosterdi; Ingilizce domain kaynak kurallari + icerik marker'lari
     * eklendi. Ayri Ingilizce holdout v2'de %60,4 -> %91,7, v1'de %55,8 -> %71,0;
     * Turkce seed/dev/test setleri %100'de korundu.
     * v6: Kullanici geri bildirimi. (1) `Bilim` kategorisi eklendi - kara delik/fotosentez
     * gibi icerik `Diger`e dusuyordu. (2) Turkce ek toleransi: tam-kelime eslesmesi
     * "futbolcu"yu "futbol" ile eslestiremiyordu; kurate edilmis ek listesi eklendi.
     */
    const val VERSION = 8
    private const val SOURCE_HEADER_LINE_COUNT = 12

    /** Ek toleransi yalniz bu uzunluktan itibaren acilir; kisa govdeler yanlis eslesir. */
    private const val MIN_SUFFIX_MARKER_LENGTH = 6

    /**
     * Ek toleransina KAPALI govdeler. Bunlar gunluk Turkce metinde tesadufen gecen zayif
     * sinyallerdir (mekan konusu, niyet kelimelerinden turetildigi icin). Ornek: karma bir
     * sehir subreddit akisindaki "mahallesi"/"restoranlari" gecisleri butun akisi Mekan'a
     * kaydiriyordu. Guclu, konuya ozgu govdeler (futbol, basketbol, konser...) toleransli kalir.
     */
    private val exactOnlyMarkers = setOf("mahalle", "restoran", "restaurant")

    /**
     * Kabul edilen ekler. Turkce isim cekim/yapim ekleri (normalize sonrasi: i->i, u->u,
     * s->s, c->c, g->g) ve Ingilizce cogul "s"/"es". Liste bilerek dar tutuldu; genisletmeden
     * once eval setleri yeniden kosulmali.
     */
    private val allowedSuffixes = setOf(
        // NOT: Ingilizce cogul ("s"/"es") bilerek YOK. Bu tolerans Turkce eklemeli
        // morfoloji icin var; "s" acildiginda "landmark" -> "landmarks" gibi tesadufi
        // kelimeler karma Reddit akislarini Mekan'a kaydiriyordu. Ingilizce cogullar
        // gerekiyorsa marker listesine acikca yazilir ("recipes", "albums", "seasons").
        // hal ekleri
        "i", "u", "e", "a", "si", "su", "yi", "yu", "ye", "ya",
        "de", "da", "te", "ta", "den", "dan", "ten", "tan",
        "in", "un", "nin", "nun", "ni", "nu", "na", "ne",
        "nda", "nde", "ndan", "nden",
        // cogul ve iyelik
        "ler", "lar", "leri", "lari", "im", "um", "imiz", "unuz",
        // yapim ekleri
        "ci", "cu", "li", "lu", "lik", "luk", "ce", "ca",
        // bildirme
        "dir", "dur", "tir", "tur",
    )

    fun classify(
        ocrText: String,
        displayName: String,
        layout: OcrLayout? = null,
        visualLabels: List<VisualLabel> = emptyList(),
    ): ClassificationResult {
        // Yerleşim varsa sınıflandırma status bar / navigasyon gürültüsünden
        // arındırılmış metinle çalışır; yoksa eski düz metin yolu korunur.
        val analysisText = layout?.contentText()?.takeIf(String::isNotBlank) ?: ocrText
        val normalized = TextNormalizer.normalize(analysisText)
        // Kaynak URL'si status bar sınırında kalabilir. Konuyu kaynak yüzeyinden ayırmak için
        // tam OCR yalnızca güçlü kaynak sinyallerinde kullanılır; içerik puanları kırpılmış
        // analysisText ile çalışmaya devam eder.
        val sourceProfile = detectSourceProfile(ocrText)
        val topicCandidate = classifyTopic(
            normalized = normalized,
            ocrText = analysisText,
            visualLabels = visualLabels,
            sourceProfile = sourceProfile,
        )
        val topicDecision = TopicSuggestionPolicy.decide(
            candidate = topicCandidate,
            sourceHint = sourceProfile?.hint,
            contentText = analysisText,
        )
        val topic = topicDecision.topic
        // Screenshot'ın içeriği, kullanıcının onu NEDEN kaydettiğini kanıtlamaz. Bir restoran
        // ekranı "git", ürün ekranı "al" veya tarif "dene" demek zorunda değildir. Otomatik
        // niyet uydurmak yerine alan boş bırakılır; kullanıcı isterse detaydan kendisi seçer.
        val intent = IntentCategory.UNCLEAR
        return ClassificationResult(
            title = chooseTitle(
                ocrText = analysisText,
                sourceText = ocrText,
                displayName = displayName,
                // Başlık çıkarımı, kullanıcıya gösterilen yüksek kesinlikli etiketten bağımsızdır.
                // Örneğin bir Reddit sorusunu koleksiyona koymasak da soru cümlesi başlık olabilir.
                topic = topicCandidate,
                layout = layout,
            ),
            intent = intent,
            topic = topic,
            // Niyet puanı geçmişte yanlışlıkla konu güveni gibi kullanılıyordu. Arama
            // merkezli üründe bu alan, kullanıcıya gösterilen konu önerisinin güvenidir.
            confidence = topicDecision.confidence,
            sensitive = sensitiveMarkers.any { containsMarker(normalized, it) },
            sourceHint = sourceProfile?.hint,
        )
    }

    private fun score(text: String, markers: List<String>): Int = markers.count {
        containsMarker(text, it)
    }

    private fun containsMarker(text: String, marker: String): Boolean {
        val normalizedMarker = TextNormalizer.normalize(marker)
        if (normalizedMarker.isBlank()) return false
        if ('₺' in normalizedMarker) return normalizedMarker in text
        if (" $normalizedMarker " in " $text ") return true
        return containsWithSuffix(text, normalizedMarker)
    }

    /**
     * Turkce eklemeli bir dildir: "futbol" marker'i "futbolcu", "takim" ise "takimi"
     * kelimesini tam-kelime eslesmesiyle yakalayamaz. Onceden bu, listeye elle cekimli
     * form eklenerek yamaniyordu ("tarif" + "tarifi", "corba" + "corbasi") - eksik ve
     * kirilgan bir cozum.
     *
     * Burada govdenin ardindan yalnizca **kurate edilmis** bir ek listesi kabul edilir.
     * "Herhangi 1-4 harf" yaklasimi Ingilizce metinde yanlis eslesme uretirdi:
     * "season" + "ing" (tarif sayfalarindaki "seasoning") -> Dizi, "final" + "ly" -> Spor.
     * Ek listesi bunlari disarida birakir. Ayrica kisa govdelerde ek toleransi kapalidir
     * ("mac" -> "machine", "lig" -> "light" gibi kazalari onlemek icin).
     */
    private fun containsWithSuffix(text: String, marker: String): Boolean {
        if (marker.length < MIN_SUFFIX_MARKER_LENGTH) return false
        if (marker in exactOnlyMarkers) return false
        val haystack = " $text "
        val needle = " $marker"
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            val start = index + needle.length
            val end = haystack.indexOf(' ', start)
            if (end > start) {
                val suffix = haystack.substring(start, end)
                if (suffix in allowedSuffixes) return true
            }
            index = haystack.indexOf(needle, index + 1)
        }
        return false
    }

    /**
     * Kaynağı içerik konusundan ayrı tanır. `topicPrior`, yalnız o kaynağın tek bir
     * semantik alana ait olduğu durumda güçlüdür (TCMB/Finans, Fotomaç/Spor).
     * YouTube ve Reddit gibi her konuyu barındıran yüzeyler yalnız zayıf bir geri
     * dönüş taşır; açık içerik sinyali aynı puanda bile önce gelir.
     */
    private fun detectSourceProfile(sourceText: String): SourceProfile? {
        val sourceNormalized = TextNormalizer.normalize(sourceText)
        val headerNormalized = TextNormalizer.normalize(
            sourceText.lineSequence().take(SOURCE_HEADER_LINE_COUNT).joinToString(" "),
        )
        if (
            listOf("wikipedia", "vikipedi").any { containsMarker(sourceNormalized, it) } &&
            listOf(
                "wiki anasayfa",
                "wikipedia anasayfa",
                "vikipedi ye hos geldiniz",
                "haftanin seckin maddesi",
            ).any {
                containsMarker(sourceNormalized, it)
            }
        ) {
            return SourceProfile(hint = "Wikipedia", forcedTopic = ContentTopic.OTHER)
        }
        return sourceRules.firstOrNull { rule -> rule.matches(headerNormalized) }?.profile
            ?: sourceRules.firstOrNull { rule -> rule.matches(sourceNormalized) }?.profile
    }

    private fun classifyTopic(
        normalized: String,
        ocrText: String,
        visualLabels: List<VisualLabel> = emptyList(),
        sourceProfile: SourceProfile? = null,
    ): ContentTopic {
        // Karma içerikli giriş sayfalarında o an görünen tek bir kart, bütün screenshot'ın
        // konusu değildir. Belirli bir içerik sayfası olmayan bu yüzeyleri zorla etiketleme.
        sourceProfile?.forcedTopic?.let { return it }

        val musicScore = score(normalized, musicTopicMarkers) +
            if (
                musicTitlePattern.containsMatchIn(ocrText) &&
                (
                    musicContextMarkers.any { containsMarker(normalized, it) } ||
                        sourceProfile?.hint == "YouTube"
                    )
            ) 2 else 0
        val movieScore = score(normalized, movieTopicMarkers) +
            (if (mediaTitlePattern.containsMatchIn(ocrText)) 3 else 0) +
            score(normalized, strongMovieTopicMarkers) * 2
        val politicsScore = score(normalized, politicsTopicMarkers).let { matches ->
            // Haber/gündem/son dakika çoğu haber sitesinin sabit kabuğu. En az bir
            // somut siyaset işareti varsa içerik sinyalini bu jenerik kabuktan güçlü tut.
            if (matches > 0) matches * 2 + 1 else 0
        }
        val financeScore = score(normalized, financeTopicMarkers).let { matches ->
            // Kur/fiyat ekranlarında ₺ ve rakamlar ürün sinyali gibi görünebilir. Somut bir
            // finans terimi bu yüzeysel alışveriş işaretinden daha değerlidir.
            when (matches) {
                0 -> 0
                1 -> 1
                else -> matches * 2 + 1
            }
        }
        val bookScore = score(normalized, bookTopicMarkers) +
            score(normalized, strongBookTopicMarkers) * 3
        val gameScore = score(normalized, gameTopicMarkers) +
            score(normalized, gameTryMarkers) * 3
        val strongSportMatches = score(normalized, strongSportTopicMarkers)
        val weakSportMatches = score(normalized, weakSportTopicMarkers)
        val sportScore = when {
            strongSportMatches > 0 -> strongSportMatches * 2 + weakSportMatches
            // "final", "takım", "transfer" ve "lig" tek başına spor kanıtı değildir.
            // Bunlar uygulama chrome'unda ve gündelik metinde sık geçer; iki bağımsız
            // zayıf işaret yoksa kategori uydurma.
            weakSportMatches >= 2 -> weakSportMatches
            else -> 0
        }
        val topicScores = linkedMapOf(
            ContentTopic.RECIPE to score(normalized, recipeTopicMarkers),
            ContentTopic.SERIES to score(normalized, seriesTopicMarkers),
            ContentTopic.MOVIE to movieScore,
            ContentTopic.MUSIC to musicScore,
            ContentTopic.AI to score(normalized, aiTopicMarkers),
            // Bilim, AI'dan SONRA gelir: yapay zeka icerigi kendi kategorisinde kalsin,
            // genel bilim (fizik/biyoloji/astronomi) ise Diger'e dusmesin.
            ContentTopic.SCIENCE to score(normalized, scienceTopicMarkers),
            ContentTopic.BOOK to bookScore,
            // Siyaset, genel haber kabuğundan önce gelir; eşit puanda içerik konusu kazanır.
            ContentTopic.POLITICS to politicsScore,
            ContentTopic.FINANCE to financeScore,
            ContentTopic.NEWS to score(normalized, newsTopicMarkers),
            ContentTopic.PLACE to score(normalized, placeTopicMarkers),
            ContentTopic.PRODUCT to score(normalized, buyMarkers),
            ContentTopic.SPORT to sportScore,
            ContentTopic.GAME to gameScore,
            // Video ve sosyal birer kaynak/format geri dönüşüdür. Semantik konu aynı
            // puandaysa map sırası gereği Tarif/Müzik/Oyun gibi kullanıcı koleksiyonu kazanır.
            ContentTopic.VIDEO to score(normalized, videoTopicMarkers),
            ContentTopic.SOCIAL to score(normalized, socialTopicMarkers),
        )

        // Dikey yayınlar güvenilir bir konu önceliği sağlar (Fotomaç -> Spor, TCMB ->
        // Finans). Genel paylaşım yüzeyleri ise yalnızca zayıf bir format geri dönüşüdür;
        // YouTube'daki tarif veya Reddit'teki pasta içerik konusunu korur.
        sourceProfile?.topicPrior?.let { topic ->
            topicScores[topic] = maxOf(topicScores[topic] ?: 0, sourceProfile.priorScore)
        }

        val textWinner = topicScores.maxByOrNull { it.value }?.takeIf { it.value > 0 }

        // Açık metinsel konu, genel görsel etiketten daha güvenilirdir. Yalnız kazanan
        // YouTube/Reddit/Instagram gibi bir format geri dönüşüyse görseldeki semantik
        // içerik onu geçebilir.
        if (textWinner != null && textWinner.key !in formatFallbackTopics) {
            return textWinner.key
        }
        visualTopic(visualLabels)?.let { return it }
        return textWinner?.key ?: ContentTopic.OTHER
    }

    private fun visualTopic(labels: List<VisualLabel>): ContentTopic? = labels
        .mapNotNull { label ->
            val rule = visualTopicRules[label.name.lowercase()] ?: return@mapNotNull null
            if (label.confidence >= rule.minConfidence) rule.topic to label.confidence else null
        }
        .maxByOrNull { (_, confidence) -> confidence }
        ?.first

    private fun chooseTitle(
        ocrText: String,
        sourceText: String,
        displayName: String,
        topic: ContentTopic,
        layout: OcrLayout? = null,
    ): String {
        if (topic == ContentTopic.RECIPE) {
            chooseRecipeTitle(ocrText)?.let { return it }
        }
        if (topic == ContentTopic.MOVIE) {
            chooseMovieTitle(sourceText)?.let { return it }
        }
        if (topic == ContentTopic.MUSIC) {
            chooseMusicTitle(ocrText)?.let { return it }
        }
        if (topic == ContentTopic.BOOK) {
            chooseBookTitle(ocrText)?.let { return it }
        }
        if (topic == ContentTopic.AI) {
            chooseAiTitle(ocrText)?.let { return it }
        }
        if (topic == ContentTopic.SOCIAL) {
            chooseSocialTitle(sourceText)?.let { return it }
        }
        // Konuya özel desen bulunamadıysa görsel hiyerarşi konuşur: en büyük
        // puntolu, uygun bölgedeki metin bloğu başlık adayıdır.
        layout?.titleCandidates()
            ?.map(::cleanTitleCandidate)
            ?.filter(::isUsefulTitle)
            ?.let(::preferComplete)
            ?.let { return it }
        val candidate = ocrText
            .lineSequence()
            .take(40)
            .map(String::trim)
            .map(::cleanTitleCandidate)
            .filter(::isUsefulTitle)
            .toList()
            .let(::preferComplete)
        if (candidate != null) return candidate
        // Kullanilabilir baslik yok (ornek: bot duvari, 404, bos OCR). Dosya adi jenerik
        // "Screenshot_20260721_120000_..." bicimindeyse onu baslik yapmak cirkin ve
        // anlamsizdi; UI'nin temiz "Basliksiz screenshot" metnini gostermesi icin bos
        // donuyoruz. Dosya adi gercekten anlamli bir sey tasiyorsa (nadir) onu kullaniriz.
        val fromName = displayName.substringBeforeLast('.').trim()
        return if (genericScreenshotName.matches(fromName)) "" else fromName
    }

    /**
     * Kirpilmamis adayi tercih eder, yoksa kirpilmisa razi olur.
     *
     * Uc nokta ile biten satirlar arayuzun KENDI kirptigi metinlerdir (tarayici sekmesi,
     * daraltilmis baslik) ve "Kurt kuzuyu y..." gibi yarim gorunur. Ama bunlari tamamen
     * ELEMEK daha kotu sonuc veriyordu: ayni ekranda daha iyi bir aday yoksa siniflandirici
     * "Guest-7BF8DFB9F" gibi anlamsiz bir forum kullanici adina dusuyordu. Yarim ama anlamli
     * bir baslik, anlamsiz bir tam baslıktan iyidir.
     */
    private fun preferComplete(candidates: List<String>): String? {
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { !truncatedTailPattern.containsMatchIn(it) }
            ?: candidates.first()
    }

    private fun chooseRecipeTitle(ocrText: String): String? = ocrText
        .lineSequence()
        .take(60)
        .map(String::trim)
        .map(::cleanTitleCandidate)
        .firstNotNullOfOrNull { line ->
            recipeTitlePattern.matchEntire(line)?.groupValues?.get(1)
                ?: recipeSectionPattern.matchEntire(line)?.groupValues?.get(1)?.let { "$it tarifi" }
                ?: recipeIngredientsPattern.matchEntire(line)?.groupValues?.get(1)?.let { "$it tarifi" }
        }

    private fun chooseMovieTitle(ocrText: String): String? {
        val lines = ocrText
            .lineSequence()
            .take(60)
            .map(String::trim)
            .toList()

        // Wikipedia başlığındaki "(film)" ayrımı en güvenli açık kalıptır.
        lines.asSequence()
            .map(::cleanTitleCandidate)
            .firstOrNull { candidate ->
                TextNormalizer.normalize(candidate).endsWith("(film)") &&
                    isUsefulTitle(candidate)
            }
            ?.let { return it }

        // "Dune: Part Three | Official Teaser" gibi gerçek medya başlıkları.
        lines.firstNotNullOfOrNull { rawLine ->
            if ('|' !in rawLine) return@firstNotNullOfOrNull null
            val right = TextNormalizer.normalize(rawLine.substringAfter('|'))
            if (mediaTitleSuffixes.none { containsMarker(right, it) }) {
                return@firstNotNullOfOrNull null
            }
            cleanTitleCandidate(rawLine.substringBefore('|')).takeIf(::isUsefulTitle)
        }?.let { return it }

        val normalizedText = TextNormalizer.normalize(ocrText)
        if (containsMarker(normalizedText, "beyazperde")) {
            val headlineIndex = lines.indexOfFirst { line ->
                containsMarker(TextNormalizer.normalize(line), "hakkinda bilmeniz gereken")
            }
            if (headlineIndex >= 0) {
                return lines
                    .subList((headlineIndex - 1).coerceAtLeast(0), (headlineIndex + 2).coerceAtMost(lines.size))
                    .joinToString(" ")
                    .let(::truncateTitle)
                    .takeIf(::isUsefulTitle)
            }
        }
        if (movieMetadataSources.none { containsMarker(normalizedText, it) }) return null

        val yearIndex = lines.indices.firstOrNull { index ->
            index in 1..15 && movieYearPattern.containsMatchIn(lines[index])
        } ?: return null

        // IMDb/Letterboxd/Rotten Tomatoes'ta başlık yıl satırının hemen üstündedir.
        // Aradaki küçük yaş/TV rozeti için en fazla iki anlamsız satır atlanır.
        var cursor = yearIndex - 1
        var skipped = 0
        while (
            cursor >= 0 &&
            !isMovieTitlePart(cleanTitleCandidate(lines[cursor])) &&
            skipped < 2
        ) {
            cursor -= 1
            skipped += 1
        }
        if (cursor < 0) return null

        val parts = mutableListOf(cleanTitleCandidate(lines[cursor]))
        if (parts.first().split(' ').size == 1 && cursor > 0) {
            val previous = cleanTitleCandidate(lines[cursor - 1])
            if (isMovieTitlePart(previous)) parts.add(0, previous)
        }
        return parts.joinToString(" ").let(::truncateTitle).takeIf(::isUsefulTitle)
    }

    private fun isMovieTitlePart(candidate: String): Boolean {
        if (!isUsefulTitle(candidate)) return false
        val normalized = TextNormalizer.normalize(candidate)
        if (normalized in movieChromeLines) return false
        if (movieYearPattern.containsMatchIn(candidate)) return false
        return candidate.split(' ').size <= 8
    }

    private fun chooseMusicTitle(ocrText: String): String? = ocrText
        .lineSequence()
        .take(60)
        .map(String::trim)
        .map(::cleanTitleCandidate)
        .firstOrNull { line -> musicTitlePattern.matches(line) && isUsefulTitle(line) }

    private fun chooseBookTitle(ocrText: String): String? {
        val lines = ocrText.lineSequence().take(60).map(String::trim).toList()
        val ebookIndex = lines.indexOfFirst { containsMarker(TextNormalizer.normalize(it), "e kitap") }
        if (ebookIndex < 3) return null

        val fragments = lines
            .subList((ebookIndex - 7).coerceAtLeast(0), ebookIndex - 2)
            .map(::cleanTitleCandidate)
            .filter { fragment -> fragment.length >= 3 && fragment.count(Char::isLetter) >= 3 }
            .takeLast(5)
        return fragments.joinToString(" ").takeIf(::isUsefulTitle)
    }

    private fun chooseAiTitle(ocrText: String): String? {
        val lines = ocrText.lineSequence().take(60).map(String::trim).toList()
        val titleIndex = lines.indices
            .filter { index ->
                if (index >= 15) return@filter false
                val normalized = TextNormalizer.normalize(lines[index])
                aiTitleSignals.any { containsMarker(normalized, it) }
            }
            .maxByOrNull { index ->
                val candidate = cleanTitleCandidate(lines[index])
                val normalized = TextNormalizer.normalize(candidate)
                val startsWithSignal = aiTitleSignals.any { signal ->
                    val marker = TextNormalizer.normalize(signal)
                    normalized == marker || normalized.startsWith("$marker ")
                }
                (if (startsWithSignal) 1_000 else 0) +
                    candidate.length.coerceAtMost(60) -
                    (if (candidate.length > 60) 100 else 0) -
                    index
            }
            ?: -1
        if (titleIndex < 0) return null

        return lines
            .subList(titleIndex, (titleIndex + 3).coerceAtMost(lines.size))
            .map(::cleanTitleCandidate)
            .filter(::isUsefulTitle)
            .distinctBy(TextNormalizer::normalize)
            .joinToString(" ")
            .take(100)
            .takeIf(::isUsefulTitle)
    }

    private fun chooseSocialTitle(ocrText: String): String? {
        val normalizedText = TextNormalizer.normalize(ocrText)
        if (!containsMarker(normalizedText, "reddit")) return null

        val lines = ocrText
            .lineSequence()
            .take(100)
            .map(String::trim)
            .map(::cleanTitleCandidate)
            .toList()

        // AskReddit soruları OCR'da iki satıra bölünebilir. Soru işaretinin bulunduğu
        // satır küçük harfle başlıyorsa önceki satırı da başlığa ekle.
        lines.indexOfFirst { it.trimEnd().endsWith('?') }
            .takeIf { it >= 0 }
            ?.let { index ->
                val start = if (
                    index > 0 &&
                    lines[index].firstOrNull()?.isLowerCase() == true
                ) {
                    index - 1
                } else {
                    index
                }
                lines.subList(start, index + 1)
                    .joinToString(" ")
                    .let(::truncateTitle)
                    .trim()
                    .takeIf { candidate ->
                        candidate.count(Char::isLetter) >= 4 &&
                            !isSocialChrome(candidate)
                    }
            }?.let { return it }

        return lines.firstOrNull { line ->
            isUsefulTitle(line) && line.length >= 25 && !isSocialChrome(line)
        }
    }

    private fun isSocialChrome(line: String): Boolean {
        val normalized = TextNormalizer.normalize(line)
        return socialChromeMarkers.any { marker ->
            containsMarker(normalized, marker) ||
                normalized.startsWith(TextNormalizer.normalize(marker))
        }
    }

    private fun truncateTitle(value: String, maxLength: Int = 100): String {
        val clean = value.trim()
        if (clean.length <= maxLength) return clean
        val prefix = clean.take(maxLength - 1)
        val boundary = prefix.lastIndexOf(' ').takeIf { it >= maxLength / 2 } ?: prefix.length
        return prefix.take(boundary).trimEnd(' ', '.', ',', ';', ':', '-') + "…"
    }

    private fun cleanTitleCandidate(line: String): String {
        val breadcrumbTail = if ('>' in line) {
            line.split('>').map(String::trim).lastOrNull(String::isNotBlank) ?: line
        } else {
            line
        }
        return breadcrumbTail
            .trim(' ', '<', '>', '|', '•', '·')
            .replace(leadingSearchGlyphPattern, "")
            .replace(whitespacePattern, " ")
            // OCR satir sonlarina sik sik noktalama artigi birakiyor ("... der k;").
            // Baslikta bu, kirik bir uygulama izlenimi veriyor.
            .trimEnd(' ', ';', ':', ',', '·', '|', '-', '–')
    }

    private fun isUsefulTitle(line: String): Boolean {
        val letterCount = line.count(Char::isLetter)
        if (line.length !in 5..100 || letterCount < 4) return false
        if (statusBarPattern.matches(line) || urlPattern.containsMatchIn(line)) return false
        // Tek parcali, rakam iceren uzun diziler kimliktir, baslik degil: forum kullanici
        // adi ("Guest-7BF8DFB9F"), cihaz kodu, siparis numarasi. Bunlar baslik olarak
        // secilince kart tamamen anlamsizlasiyordu. Kisa olanlar ("GPT-5.6") korunur.
        if (' ' !in line && line.length >= 8 && line.any(Char::isDigit)) return false
        if (line.firstOrNull()?.isLetterOrDigit() != true) return false
        if ('%' in line && line.length < 24) return false
        if (letterCount.toFloat() / line.length < 0.45f) return false

        val normalized = TextNormalizer.normalize(line)
        if (normalized in genericTitleLines) return false
        if ("guncellenme" in normalized || dateLinePattern.containsMatchIn(normalized)) return false
        if (relativeTimePattern.matches(normalized)) return false
        if (
            normalized.split(' ').size == 1 &&
            line.firstOrNull()?.isLowerCase() == true &&
            line.lastOrNull()?.let { it in ".!?,;:" } == true
        ) return false

        val uppercaseRatio = line.count(Char::isUpperCase).toFloat() / letterCount
        if (line.length <= 15 && uppercaseRatio >= 0.8f) return false
        return true
    }

    private val statusBarPattern = "^[0-9]{1,2}[:.]?[0-9]{0,2}\\s*[%·|]?$".toRegex()
    private val urlPattern = "(?i)(https?://|www\\.|[a-z0-9-]+\\.(com|net|org|tr)(/|\\b))".toRegex()
    private val leadingSearchGlyphPattern = "^[Qq]\\s+(?=\\p{Ll})".toRegex()
    private val recipeTitlePattern = "^(.+?\\s+tarifi):?$".toRegex(RegexOption.IGNORE_CASE)
    private val recipeSectionPattern = (
        "^(.+?)(?:nın|nin|nun|nün|ın|in|un|ün)\\s+(?:hamuru|köftesi|sosu)\\s+için:?$"
        ).toRegex(RegexOption.IGNORE_CASE)
    private val recipeIngredientsPattern = "^(.+?)\\s+için\\s+malzemeler:?$".toRegex(RegexOption.IGNORE_CASE)
    private val musicTitlePattern = "^.{2,60}\\s+-\\s+.{2,80}$".toRegex(RegexOption.MULTILINE)
    private val mediaTitlePattern = (
        "^.{2,100}\\|\\s*(?:official\\s+)?(?:trailer|teaser|fragman)"
        ).toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val whitespacePattern = "\\s+".toRegex()
    private val dateLinePattern = (
        "\\b(ocak|subat|mart|nisan|mayis|haziran|temmuz|agustos|eylul|ekim|kasim|aralik)\\b.*\\b20[0-9]{2}\\b"
        ).toRegex()
    private val relativeTimePattern = "^[0-9]+\\s+(dakika|saat|gun|hafta|ay|yil)\\s+once$".toRegex()

    /** Arayuzun kendi kirptigi metin: "Kurt kuzuyu y...", "Prompting guidance…" */
    private val truncatedTailPattern = "(\\.{3}|…)\\s*$".toRegex()

    /** "Screenshot_20260721_120000_...", "IMG_1234", "PXL_..." gibi jenerik dosya adlari. */
    private val genericScreenshotName =
        "(?i)(screenshot|screen_shot|img|image|pxl|photo|capture|ekran)[_-].*".toRegex()
    private val genericTitleLines = setOf(
        "anasayfa",
        "ana sayfa",
        "google news",
        "haberler",
        "haberler gundem",
        "instagram",
        "reddit",
        "youmind",
        "sozcu",
        "filmler",
        "play trailer",
        "trailer",
        "fragman",
    )
    private val mediaTitleSuffixes = listOf("official", "trailer", "teaser", "fragman")
    private val movieMetadataSources = listOf(
        "imdb",
        "letterboxd",
        "rotten tomatoes",
        "rottentomatoes",
    )
    private val movieYearPattern = "\\b(?:19|20)[0-9]{2}\\b".toRegex()
    private val movieChromeLines = setOf(
        "imdb",
        "article talk",
        "play trailer",
        "play trailer with",
        "trailer",
        "fragman",
    )
    private val aiTitleSignals = listOf("prompting", "prompt", "gpt", "chatgpt", "yapay zeka", "claude")

    private data class VisualTopicRule(val topic: ContentTopic, val minConfidence: Float)

    private data class SourceProfile(
        val hint: String,
        val topicPrior: ContentTopic? = null,
        val priorScore: Int = 0,
        val forcedTopic: ContentTopic? = null,
    )

    private data class SourceRule(
        val markers: List<String>,
        val profile: SourceProfile,
    ) {
        fun matches(text: String): Boolean = markers.any { containsMarker(text, it) }
    }

    // ML Kit base labeler İngilizce etiket üretir; eşleşmeyen adlar sessizce yok sayılır.
    // Eşikler bilinçli yüksek: görsel sinyal yalnızca metin sessizken karar verir.
    private val visualTopicRules = buildMap {
        listOf("food", "dish", "cuisine", "dessert", "cake", "baking", "cooking", "pizza", "salad", "brunch")
            .forEach { put(it, VisualTopicRule(ContentTopic.RECIPE, 0.65f)) }
        listOf("games", "video game", "gaming")
            .forEach { put(it, VisualTopicRule(ContentTopic.GAME, 0.70f)) }
        listOf("stadium", "football", "soccer", "basketball", "baseball")
            .forEach { put(it, VisualTopicRule(ContentTopic.SPORT, 0.70f)) }
        listOf("guitar", "piano", "musical instrument", "drum", "concert")
            .forEach { put(it, VisualTopicRule(ContentTopic.MUSIC, 0.70f)) }
        put("book", VisualTopicRule(ContentTopic.BOOK, 0.75f))
    }

    // Mekan konusu gezi/ansiklopedi dilini de tanir; niyet isaretleri (adres, rezervasyon)
    // tek basina yetmiyordu - Vikipedi'deki "turistik bolge" sayfalari OTHER'a dusuyordu.
    private val placeTopicMarkers = listOf(
        "cami",
        "camii",
        "mosque",
        "muzesi",
        "museum",
        "kalesi",
        "sarayi",
        "unesco",
        "turistik",
        "antik kent",
        "tarihi kule",
        "tarihi meydan",
        "gezilecek",
        "wikivoyage",
        "vikigezgin",
        "milli parki",
        "otel",
        "plaj",
        "tourist attraction",
        "national park",
        "things to do",
        // Ingilizce mekan / cografya dili
        "canyon",
        "landmark",
        "world heritage",
        "amphitheatre",
        "monument",
        "waterfall",
        "lonely planet",
        "attractions",
        "sightseeing",
        "town",
        "village",
        "district",
        "province",
        "ilcesi",
        "beldesi",
        "open air museum",
    ) + listOf(
        "adres",
        "konum",
        "harita",
        "maps",
        "restoran",
        "restaurant",
        "kafe",
        "cafe",
        "kahve",
        "mahalle",
        "sokak",
        "cadde",
        "rezervasyon",
        "tripadvisor",
    )

    private val goMarkers = listOf(
        "adres",
        "konum",
        "harita",
        "maps",
        "restoran",
        "restaurant",
        "kafe",
        "cafe",
        "kahve",
        "mahalle",
        "sokak",
        "cadde",
        "rezervasyon",
        "tripadvisor",
    )
    private val tryMarkers = listOf(
        "tarif",
        "malzemeler",
        "hazirlanisi",
        "yumurta",
        "firin",
        "derece",
        "dakika pisir",
        "antrenman",
        "egzersiz",
        "tekrar",
        "oyun",
        "oyna",
        "gameplay",
        "block blast",
        "hemen indir",
        "hemen yukle",
        "install",
    )
    private val buyMarkers = listOf(
        "apple com",
        "zara com",
        "urunler",
        "tuketici urunleri",
        "smartphones",
        "₺",
        " tl",
        "fiyat",
        "indirim",
        "sepet",
        "beden",
        "kargo",
        "siparis",
        "stok",
        "kupon",
        "trendyol",
        "hepsiburada",
        "satin al",
        // Ingilizce alisveris dili
        "add to cart",
        "buy now",
        "add to bag",
        "in stock",
        "out of stock",
        "free shipping",
        "add to basket",
        "shop now",
        "best buy",
        "mens shoes",
        "womens shoes",
        "sneakers",
        "select styles",
    )
    private val explicitBuyMarkers = listOf(
        "sepete ekle",
        "satin al",
        "siparis ver",
        "hemen al",
        "beden sec",
    )
    private val readWatchMarkers = listOf(
        "kitap",
        "film",
        "dizi",
        "youtube",
        "video",
        "podcast",
        "makale",
        "haber",
        "gundem",
        "bolum",
        "izle",
        "oku",
        "shorts",
        "abone ol",
        "muzik",
        "sarki",
        "goruntuleme",
        "guidance",
        "guide",
        "kilavuz",
        "rehber",
        "forum",
        "reddit",
        "cevap yaz",
    )
    private val rememberMarkers = listOf(
        "not",
        "bilgi",
        "belge",
        "toplanti",
        "sunum",
        "mesaj",
        "konusma",
    )
    private val recipeTopicMarkers = listOf(
        "yemektarifleri",
        "nefis yemek",
        "yemek tarifi",
        "yemek tarifleri",
        "tarif",
        "tarifi",
        "malzemeler",
        "hazirlanisi",
        "yumurta",
        "firin",
        "homemade",
        "cake",
        "kebap",
        "kebab",
        "corba",
        "corbasi",
        "hamuru icin",
        "koftesi icin",
        "sosu icin",
        // Ingilizce tarif dili
        "recipe",
        "recipes",
        "ingredients",
        "directions",
        // NOT: "instructions" eklenmedi - cok genel. DNA sayfasindaki "genetic instructions"
        // bilim icerigini Tarif'e kaydiriyordu; "directions"/"ingredients" zaten yeterli.
        "prep time",
        "cook time",
        "servings",
        "preheat",
        "allrecipes",
        "serious eats",
        "yiyecek",
        "ekmegi",
        "tatlisi",
        "hamur isi",
        "atistirmalik",
        "geleneksel yemek",
        "dessert",
        "cuisine",
        "noodle",
        "pastry",
        "tatli",
    )
    private val movieTopicMarkers = listOf(
        "beyazperde",
        "letterboxd",
        "rotten tomatoes",
        "tomatometer",
        "box office",
        "vizyona",
        "basrol",
        "film",
        "sinema",
        "vizyon",
        "yonetmen",
        "senarist",
        "imdb",
        "belgesel",
        "muzikal",
        // Ingilizce film dili. NOT: "directed by"/"starring"/"screenplay" film+dizi ortak
        // kredi terimleridir; movie'ye eklemek Severance gibi dizileri MOVIE'ye kaydiriyordu.
        // Bu yuzden yalniz filme ozgu terimler tutuldu; film sayfalari zaten domain +
        // "(film)" + "film" ile ayrisir.
        "rogerebert",
        "roger ebert",
        "film review",
        "criterion collection",
        "themoviedb",
        "the movie db",
    )
    private val strongMovieTopicMarkers = listOf(
        "warner bros",
        "cikis tarihi",
        "official teaser",
        "official tea",
        "official trailer",
    )
    private val seriesTopicMarkers = listOf(
        "tv series",
        "season",
        "seasons",
        "episodes",
        "tvmaze",
        "miniseries",
        "netflix",
        // NOT: "television series" (Rotten Tomatoes "TV Shows" navi) ve "hbo" (RT "where to
        // watch" listesi) film sayfalarini SERIES'e kaydiriyordu; ikisi de eklenmedi.
        "dizisi",
        "dizi",
        "sezon",
        "episode",
        "bolum",
    )
    private val newsTopicMarkers = listOf(
        "techcrunch",
        "the verge",
        "engadget",
        "arstechnica",
        "haberler",
        "muhabir",
        "breaking news",
        "bbc",
        "ntv",
        "sozcu",
        "hurriyet",
        "cnn",
        "reuters",
        "webrazzi",
        "haber",
        "gundem",
        "guncellenme",
        "son dakika",
        "haber ajansi",
        // Ingilizce haber dili / yayinlar
        "npr",
        "al jazeera",
        "aljazeera",
        "associated press",
        // NOT: "the guardian" eklenmedi. Yayin ADI zayif kanittir - Mastodon/Reddit
        // akisinda paylasilmis bir Guardian linki butun akisi Haber'e kaydiriyordu.
        // Yayin DOMAIN'i (theguardian com) zaten kaynak kuralinda ve guclu kanit.
        "world news",
        "top news",
        "top stories",
        "correspondent",
        "explainer",
        "live coverage",
        "min read",
    )
    private val politicsTopicMarkers = listOf(
        "siyaset",
        "siyasetci",
        "devlet gorevlisi",
        "cumhurbaskani",
        "basbakan",
        "sansolye",
        "milletvekili",
        "meclis",
        "parlamento",
        "secim",
        "hukumet",
        "siyasi parti",
        "disisleri bakani",
        "adalet bakani",
        "icisleri bakani",
        // Ingilizce siyaset dili
        "politics",
        "congress",
        "parliament",
        "senate",
        "senator",
        "lawmaker",
        "legislation",
        "prime minister",
        "white house",
        "european parliament",
        "house of representatives",
        "political party",
        "foreign minister",
        "politico",
        "united nations",
        "birlesmis milletler",
        "nato",
        "anayasa",
        "constitution",
    )
    private val strongSportTopicMarkers = listOf(
        "spor kulubu",
        "futbol",
        "football",
        "basketbol",
        "premier league",
        "sky sports",
        "fikstur",
        "teknik direktor",
        "puan durumu",
        "fifa",
        "uefa",
        // Ingilizce spor dili / ligler
        "nba",
        "nfl",
        "mlb",
        "nhl",
        "espn",
        "playoffs",
        "quarterback",
        "touchdown",
        "offseason",
        "soccer",
        "basketball",
        "regular season",
        "champions league",
        // Sporcu biyografisi / kulup dili. Ansiklopedi sayfalarinda konu genellikle
        // "professional footballer ... midfielder ... national team" cumlesinde saklidir;
        // yalin "football" tam-kelime eslesmesi "footballer"i yakalamiyordu.
        "footballer",
        "midfielder",
        "goalkeeper",
        "striker",
        "winger",
        "la liga",
        "bundesliga",
        "serie a",
        "world cup",
        "olympic",
        "olympics",
        "athlete",
        "futbolcu",
        "milli takim",
        "sampiyonluk",
        "musabaka",
        "wimbledon",
        "motogp",
        "formula 1",
        "grand prix",
        "dakar rally",
        "cycling",
        "tennis",
        "peloton",
        "championships",
    )
    private val weakSportTopicMarkers = listOf(
        "transfer",
        "mac",
        "lig",
        "takim",
        "gol",
        "yari final",
        "final",
        "karsilasma",
        "league",
        "standings",
        "championship",
        "roster",
        "national team",
        "tournament",
    )
    private val gameTopicMarkers = listOf(
        "ign",
        "gamespot",
        "fromsoftware",
        "bandai namco",
        "com games",
        "steam",
        "steampowered",
        "itch io",
        "playstation",
        "xbox",
        "nintendo",
        "epic games",
        "video game",
        "video oyunu",
        "oyun",
        "gameplay",
        "block blast",
        "hemen oyna",
        "high score",
        "level up",
        "install game",
        // Ingilizce oyun dili
        "role playing game",
        "rpg",
        "open world",
        "first person shooter",
        "multiplayer",
        "video game series",
        "online game",
        "game platform",
        "game creation",
    )
    private val gameTryMarkers = listOf(
        "block blast",
        "hemen oyna",
        "hemen indir",
        "hemen yukle",
        "install game",
    )
    private val aiTopicMarkers = listOf(
        "arxiv",
        "pytorch",
        "huggingface",
        "hugging face",
        "machine learning",
        "deep learning",
        "neural network",
        "language model",
        "llm",
        "anthropic",
        "deepmind",
        "mistral",
        "yapay zeka",
        "artificial intelligence",
        "openai",
        "chatgpt",
        "gpt 5",
        "prompting",
        "prompt",
        "modelguide",
        "youmind",
        "claude",
        "gemini",
    )
    /**
     * Bilim: fizik, biyoloji, astronomi, kimya, yer bilimleri gibi alanlar. Bu kategori
     * eklenmeden once kara delik / fotosentez / DNA gibi sayfalarin evi yoktu ve `Diğer`
     * cop kutusuna dusuyordu. Genel "arastirma/calisma" gibi cok anlamli terimler bilerek
     * eklenmedi: AI makaleleri (arxiv) ve haber sayfalari onlari da tasiyor.
     */
    private val scienceTopicMarkers = listOf(
        // Turkce
        "bilim",
        "bilimsel",
        "fizik",
        "kimya",
        "biyoloji",
        "astronomi",
        "gok bilimi",
        "gezegen",
        "yildiz sistemi",
        "kara delik",
        "evrim",
        "genetik",
        "molekul",
        "hucre",
        "atom",
        "kuantum",
        "uzay teleskobu",
        "samanyolu",
        "deprem",
        "volkan",
        // NOT: "iklim degisikligi" eklenmedi - "Cevre, Sehircilik ve Iklim Degisikligi
        // Bakanligi" basligi mgm.gov.tr hava durumu sayfalarinin sabit kabugunda geciyor
        // ve utility sayfalarini Bilim'e kaydiriyordu.
        // Ingilizce
        "science",
        "scientific",
        "physics",
        "chemistry",
        "biology",
        "astronomy",
        "astrophysics",
        "black hole",
        "galaxy",
        // NOT: yalin "planet" eklenmedi - "Lonely Planet" seyahat markasi Mekan
        // sayfalarini Bilim'e kaydiriyordu. Yerine belirsizligi olmayan terimler:
        "exoplanet",
        "solar system",
        "quantum",
        "molecule",
        "molecular",
        "photosynthesis",
        "evolution",
        "genetics",
        "chromosome",
        "organism",
        "species",
        "neuroscience",
        "geology",
        "volcano",
        "earthquake",
        "climate change",
        "periodic table",
        "nasa",
        "telescope",
    )
    private val bookTopicMarkers = listOf(
        "kitapyurdu",
        "romani",
        "open library",
        "kitaplarim",
        "goodreads",
        "gutenberg",
        "kitabevi",
        "okuma listesi",
        "my books",
        "paperback",
        "hardcover",
        "e kitap",
        "ebook",
        "isbn",
        "yayinevi",
        "kitap ozeti",
        // Ingilizce kitap dili. NOT: "author" cok genel (dizi yaraticisi, makale yazari,
        // gazeteci) - The Wire dizisini BOOK'a kaydiriyordu; eklenmedi.
        "novel",
        "fiction",
        "nonfiction",
        "paperback edition",
        "first edition",
        "bestseller",
        "novella",
    )
    private val strongBookTopicMarkers = listOf(
        "book by",
        "e kitap",
        "ebook",
        "tarihi romandir",
        "romanidir",
        "eseridir",
        "victor hugo nun eseri",
        "novel by",
    )
    private val financeTopicMarkers = listOf(
        "bloomberg",
        "bloomberght",
        "merkez bankasi",
        "central bank",
        "para politikasi",
        "enflasyon",
        "makroekonomik",
        "finans",
        "borsa",
        "halka arz",
        "usd try",
        "eur try",
        "doviz",
        "doviz kuru",
        "kur tablosu",
        "turk lirasi",
        "para birimi",
        "altin ons",
        "coinmarketcap",
        "bitcoin",
        "kripto",
        "cryptocurrency",
        "iban structure",
        // Ingilizce finans dili
        "inflation",
        "monetary policy",
        "federal reserve",
        "board of governors",
        "interest rate",
        "interest rates",
        "stock market",
        "stock exchange",
        "nasdaq",
        "dow jones",
        "s p 500",
        "earnings",
        "recession",
        "bond yield",
        "economics",
        "economy",
        "gdp",
        "marketwatch",
        "cnbc",
        "investment",
        "investors",
        "precious metals",
        "portfolio",
        "commodity",
    )
    private val musicTopicMarkers = listOf(
        "soundcloud",
        "last fm",
        "bandcamp",
        "singer",
        "songwriter",
        "discography",
        "diskografi",
        "konser",
        "muzik",
        "sarki",
        "sanatci",
        "album",
        "spotify",
        "official audio",
        "lyrics",
        "klip",
        "parca",
        // Ingilizce muzik dili / yayinlar
        "albums",
        "pitchfork",
        "billboard",
        "best new album",
        "tracklist",
        "studio album",
        "hot 100",
        "music",
        "electronic music",
        "musical duo",
        "music duo",
        "band",
    )
    private val musicContextMarkers = listOf(
        "youtube",
        "muzik",
        "sarki",
        "spotify",
        "klip",
        "mix",
        "parca",
    )
    private val videoTopicMarkers = listOf(
        "shorts",
        "otomatik seslendirme",
        "abone ol",
        "abonelikler",
        "remix",
        "goruntuleme",
        "youtube",
        "video",
        "reels",
        "tiktok",
    )
    private val socialTopicMarkers = listOf(
        "hacker news",
        "ycombinator",
        "mastodon",
        "gonderiler",
        "upvote",
        "eksi sozluk",
        "eksisozluk",
        "instagram",
        "twitter",
        "x com",
        "reels",
        "tiktok",
        "reddit",
        "forum",
        "konu sahibi",
        "cevap yaz",
    )
    private val formatFallbackTopics = setOf(ContentTopic.VIDEO, ContentTopic.SOCIAL)
    private val socialChromeMarkers = listOf(
        "come for the cats",
        "welcome to reddit",
        "become a redditor",
        "start exploring",
        "popular tumu users",
        "askreddit pics funny movies",
    )
    private val sensitiveMarkers = listOf(
        "dogrulama kodu",
        "tek kullanimlik sifre",
        "otp",
        "iban",
        "banka",
        "hesap numarasi",
        "kart numarasi",
        "tckn",
        "tc kimlik",
        "parola",
        "sifre",
        "porn",
        "pornstar",
        "pornstars",
        "porno",
        "brazzers",
        "erotik",
        "cinsellik",
        "cinselligi",
        "yetiskin",
    )
    private val sourceRules = listOf(
        // Dikey kaynaklar: kaynağın kendisi güçlü konu kanıtıdır.
        SourceRule(
            markers = listOf("tcmb gov tr", "turkiye cumhuriyet merkez bankasi"),
            profile = SourceProfile("TCMB", ContentTopic.FINANCE, 5),
        ),
        SourceRule(
            markers = listOf("bloomberght com", "bloomberg ht"),
            profile = SourceProfile("Bloomberg HT", ContentTopic.FINANCE, 5),
        ),
        SourceRule(
            markers = listOf("doviz com", "coinmarketcap com", "iban com structure"),
            profile = SourceProfile("Finans", ContentTopic.FINANCE, 5),
        ),
        SourceRule(
            markers = listOf("fotomac com tr", "skysports com", "sky sports"),
            profile = SourceProfile("Spor yayını", ContentTopic.SPORT, 4),
        ),
        SourceRule(
            markers = listOf("tabii com", "trtizle com"),
            profile = SourceProfile("tabii", ContentTopic.SERIES, 4),
        ),
        SourceRule(
            markers = listOf("theguardian com", "aa com tr", "anadolu ajansi"),
            profile = SourceProfile("Haber yayını", ContentTopic.NEWS, 4),
        ),
        SourceRule(
            markers = listOf(
                "reuters com",
                "bbc com",
                "ntv com tr",
                "sozcu com tr",
                "hurriyet com tr",
                "cnn com",
                "webrazzi com",
                "techcrunch com",
                "theverge com",
                "engadget com",
                "arstechnica com",
            ),
            profile = SourceProfile("Haber yayını", ContentTopic.NEWS, 4),
        ),
        SourceRule(
            markers = listOf(
                "letterboxd com",
            ),
            profile = SourceProfile("Film yayını", ContentTopic.MOVIE, 4),
        ),
        SourceRule(
            markers = listOf("imdb com", "rottentomatoes com", "beyazperde com"),
            profile = SourceProfile("Film / dizi yayını"),
        ),
        SourceRule(
            markers = listOf("goodreads com", "kitapyurdu com", "gutenberg org", "openlibrary org"),
            profile = SourceProfile("Kitap yayını", ContentTopic.BOOK, 4),
        ),
        SourceRule(
            markers = listOf("pytorch org", "huggingface co", "openai com", "anthropic com"),
            profile = SourceProfile("Yapay zeka", ContentTopic.AI, 4),
        ),
        SourceRule(
            markers = listOf("trendyol com", "hepsiburada com", "amazon com"),
            profile = SourceProfile("Alışveriş", ContentTopic.PRODUCT, 4),
        ),
        SourceRule(
            markers = listOf("google maps", "maps google com"),
            profile = SourceProfile("Google Maps", ContentTopic.PLACE, 4),
        ),

        // Ingilizce/uluslararasi dikey kaynaklar. Ruleset baslangicta Turkce agirlikliydi;
        // web holdout v1 kor baseline'i (%55,8) Ingilizce sayfalarin OTHER'a coktugunu gosterdi.
        // Buradaki domainler taninmis genel yayinlardir, tek bir holdout kaydina gore secilmemistir.
        SourceRule(
            markers = listOf(
                "federalreserve gov", "ecb europa eu", "cnbc com", "marketwatch com",
                "wsj com", "ft com", "investing com", "tradingview com", "imf org",
                "worldbank org", "bloomberg com",
            ),
            profile = SourceProfile("Finans", ContentTopic.FINANCE, 5),
        ),
        SourceRule(
            markers = listOf(
                "apnews com", "npr org", "aljazeera com", "cbsnews com", "nytimes com",
                "washingtonpost com", "nbcnews com", "abcnews go com", "foxnews com",
            ),
            profile = SourceProfile("Haber yayını", ContentTopic.NEWS, 4),
        ),
        SourceRule(
            markers = listOf("politico com"),
            profile = SourceProfile("Siyaset yayını", ContentTopic.POLITICS, 4),
        ),
        SourceRule(
            markers = listOf(
                "espn com", "nba com", "nfl com", "mlb com", "nhl com",
                "premierleague com", "uefa com",
            ),
            profile = SourceProfile("Spor yayını", ContentTopic.SPORT, 4),
        ),
        SourceRule(
            markers = listOf("rogerebert com", "themoviedb org"),
            profile = SourceProfile("Film yayını", ContentTopic.MOVIE, 4),
        ),
        SourceRule(
            markers = listOf("pitchfork com", "billboard com"),
            profile = SourceProfile("Müzik yayını", ContentTopic.MUSIC, 4),
        ),
        SourceRule(
            markers = listOf(
                "apple com", "samsung com", "dyson com", "bestbuy com",
                "ikea com", "logitech com", "nike com", "adidas com", "lego com",
            ),
            profile = SourceProfile("Ürün", ContentTopic.PRODUCT, 4),
        ),
        SourceRule(
            markers = listOf("lonelyplanet com", "wikivoyage org"),
            profile = SourceProfile("Gezi rehberi", ContentTopic.PLACE, 4),
        ),

        // Çok konulu yüzeyler: ana kategori değil, yalnızca format geri dönüşü.
        SourceRule(
            markers = listOf("youtube", "youtu be"),
            profile = SourceProfile("YouTube", ContentTopic.VIDEO, 2),
        ),
        SourceRule(
            markers = listOf("twitch tv", "vimeo com", "peertube"),
            profile = SourceProfile("Video platformu", ContentTopic.VIDEO, 2),
        ),
        SourceRule(
            markers = listOf("reddit", "old reddit com"),
            profile = SourceProfile("Reddit", ContentTopic.SOCIAL, 2),
        ),
        SourceRule(
            markers = listOf("mastodon"),
            profile = SourceProfile("Mastodon", ContentTopic.SOCIAL, 2),
        ),
        SourceRule(
            markers = listOf("eksi sozluk", "eksisozluk"),
            profile = SourceProfile("Ekşi Sözlük", ContentTopic.SOCIAL, 2),
        ),
        SourceRule(
            markers = listOf("lobste rs", "news ycombinator com", "hacker news"),
            profile = SourceProfile("Forum", ContentTopic.SOCIAL, 2),
        ),
        SourceRule(
            markers = listOf("instagram", "reels"),
            profile = SourceProfile("Instagram", ContentTopic.SOCIAL, 2),
        ),
        SourceRule(
            markers = listOf("twitter", "x com"),
            profile = SourceProfile("X", ContentTopic.SOCIAL, 2),
        ),
        SourceRule(
            markers = listOf("tiktok"),
            profile = SourceProfile("TikTok", ContentTopic.VIDEO, 2),
        ),
        SourceRule(
            markers = listOf("wikipedia", "vikipedi"),
            profile = SourceProfile("Wikipedia"),
        ),
    )
}
