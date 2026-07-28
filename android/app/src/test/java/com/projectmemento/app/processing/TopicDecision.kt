package com.projectmemento.app.processing

import com.projectmemento.app.data.ContentTopic

/**
 * Kural tabanli siniflandirici ile ogrenen model arasindaki karar politikasi.
 *
 * Neden hibrit (ikisinden biri degil): kurallar tanidik uygulama/yuzey sinyallerinde guclu,
 * model ise kurallarin `Diger` dedigi uzun kuyrukta yararli. Gecmisteki daha yuksek model
 * rakamlari holdout dosyalarinin egitime de girdigi satir-bazli olcumden geliyordu ve urun
 * iddiasi olarak kullanilamaz. 28 Temmuz'daki uygulama-bazli ayrilmis 300 native ekran
 * benchmark'i, OCR/metin politikasinin test yarisinda yalnizca %38,4 dogruluga ulastigini
 * gosterdi. Bu nedenle karar katmani ozellikle kanitsiz tahminleri reddeder.
 *
 * Uc kural:
 *  1. Semantik icerik yetersizse model susar. Kisi adi ve profil chrome'u konu degildir.
 *  2. Kurallar `Diger` diyorsa model ancak en az guven ve marj esigini gecerse konusur.
 *     Kurallarin baskin hata bicimi taniyamadigi icerigi `Diger` cop kutusuna atmasidir,
 *     ancak softmax'in zorunlu cevabi da tek basina kanit sayilmaz.
 *  3. Kurallar bir konu bulduysa model ancak YETERINCE AYIRT EDICIYSE devralir. Olcumde
 *     ham olasilik degil MARJ (1. ile 2. sinif farki) dogru sinyal cikti; 17 sinifli
 *     softmax'ta ham olasilik dogal olarak dusuk kalir.
 *
 * Kullanici duzeltmeleri bu katmanin USTUNDE uygulanir; burasi onlari asla ezmez.
 */
object TopicDecision {

    /**
     * Kurallar bir konu bulduysa modelin devralmasi icin gereken en az marj.
     *
     * Sizintisiz model gercek cihaz orneklerinde kurallardan daha zayif oldugu icin esik
     * muhafazakar tutulur. Native benchmark, metin modelinin semantik ekranlarda cok sik
     * `Diger`e cekildigini; esiksiz devralmanin ise kanitsiz yanlis kategori urettigini
     * gosterdi. Model taninmis bir kural kararini ancak belirgin guven ve marjla devralir.
     */
    const val MIN_MARGIN = 0.15f
    const val MIN_CONFIDENCE = 0.30f
    const val OTHER_MIN_MARGIN = 0.08f
    const val OTHER_MIN_CONFIDENCE = 0.24f

    private const val MIN_CONTENT_TOKENS = 4
    private const val MIN_CONTENT_LETTERS = 24

    /**
     * Modelin "bir şey söylemek zorunda" olan softmax çıktısını ürün kararına çevirir.
     *
     * Bir kişi adı, kullanıcı adı veya iki kelimelik fotoğraf başlığı konu kanıtı değildir.
     * Önceki politika kurallar OTHER dediğinde modeli koşulsuz kabul ettiği için eğitimdeki
     * sporcu adlarından öğrenilen karakter parçaları `Örnek Kullanıcı` gibi bir adı Spor'a
     * taşıyabiliyordu. Yetersiz içerikte doğru davranış tahmin uydurmak değil, OTHER'da
     * kalmaktır.
     */
    fun decide(
        ruleTopic: ContentTopic,
        model: TopicModel.Prediction?,
        contentText: String,
    ): ContentTopic {
        if (model == null) return ruleTopic
        if (!hasEnoughSemanticContent(contentText)) return ruleTopic
        if (ruleTopic == ContentTopic.OTHER) {
            return if (
                model.confidence >= OTHER_MIN_CONFIDENCE &&
                model.margin >= OTHER_MIN_MARGIN
            ) {
                model.topic
            } else {
                ruleTopic
            }
        }
        return if (
            model.confidence >= MIN_CONFIDENCE &&
            model.margin >= MIN_MARGIN
        ) {
            model.topic
        } else {
            ruleTopic
        }
    }

    internal fun hasEnoughSemanticContent(text: String): Boolean {
        val tokens = TOKEN.findAll(text)
            .map { it.value.lowercase() }
            .filterNot(PROFILE_AND_UI_CHROME_TOKENS::contains)
            .toList()
        val letterCount = tokens.sumOf { token -> token.count(Char::isLetter) }
        return tokens.size >= MIN_CONTENT_TOKENS && letterCount >= MIN_CONTENT_LETTERS
    }

    private val TOKEN = "[\\p{L}\\p{N}][\\p{L}\\p{N}_'-]*".toRegex()
    private val PROFILE_AND_UI_CHROME_TOKENS = setOf(
        "takip",
        "et",
        "ediliyor",
        "takipçi",
        "takipci",
        "gönderi",
        "gonderi",
        "gönderiler",
        "gonderiler",
        "mesaj",
        "beğen",
        "begen",
        "paylaş",
        "paylas",
        "follow",
        "following",
        "followers",
        "posts",
        "message",
        "like",
        "share",
        "home",
        "ana",
        "sayfa",
        "geri",
        "back",
        "next",
        "devam",
        "cancel",
        "iptal",
        "menu",
        "more",
        "daha",
        "login",
        "signin",
        "signup",
    )
}
