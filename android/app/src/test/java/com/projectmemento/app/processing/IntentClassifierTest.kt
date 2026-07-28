package com.projectmemento.app.processing

import com.projectmemento.app.data.ContentTopic
import com.projectmemento.app.data.IntentCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentClassifierTest {
    @Test
    fun `recipe screenshot gets a topic but does not invent intent`() {
        val result = IntentClassifier.classify(
            ocrText = "Limonlu pasta tarifi\n2 yumurta\n180 derece fırında 35 dakika",
            displayName = "Screenshot_1.png",
        )

        assertEquals(IntentCategory.UNCLEAR, result.intent)
        assertEquals(ContentTopic.RECIPE, result.topic)
        assertEquals("Limonlu pasta tarifi", result.title)
        assertFalse(result.sensitive)
    }

    @Test
    fun `place screenshot gets a topic but does not invent intent`() {
        val result = IntentClassifier.classify(
            ocrText = "Moda Kahvaltı\nCaferağa Mahallesi Rıhtım Cadde 12\nRezervasyon",
            displayName = "Screenshot_2.png",
        )

        assertEquals(IntentCategory.UNCLEAR, result.intent)
        assertEquals(ContentTopic.PLACE, result.topic)
    }

    @Test
    fun `shopping screenshot gets a topic but does not invent intent`() {
        val result = IntentClassifier.classify(
            ocrText = "Mavi koşu ayakkabısı\n1.499 TL\nSepette yüzde 20 indirim",
            displayName = "Screenshot_3.png",
        )

        assertEquals(IntentCategory.UNCLEAR, result.intent)
        assertEquals(ContentTopic.PRODUCT, result.topic)
    }

    @Test
    fun `otp screenshot is marked sensitive`() {
        val result = IntentClassifier.classify(
            ocrText = "OTP doğrulama kodu 481921\nBu şifreyi kimseyle paylaşmayın",
            displayName = "Screenshot_4.png",
        )

        assertTrue(result.sensitive)
    }

    @Test
    fun `title skips status bar and url then uses breadcrumb tail`() {
        val result = IntentClassifier.classify(
            ocrText = """
                10:58
                2% m.sinemalar.com/mobileweb
                Anasayfa > Tüm Filmler > Iron Maiden: Burning Ambition
                Belgesel
            """.trimIndent(),
            displayName = "Screenshot_movie.jpg",
        )

        assertEquals("Iron Maiden: Burning Ambition", result.title)
        assertEquals(ContentTopic.MOVIE, result.topic)
    }

    @Test
    fun `title skips news chrome and date to find headline`() {
        val result = IntentClassifier.classify(
            ocrText = """
                10:57
                2% sozcu.com.tr/suriye-den-yal
                SÖZCÜ
                STERLİNA
                Haberler - Gündem
                Google News
                14 Temmuz 2026 - 21:05 Güncellenme: 15 Temmuz 2026 - 08:33
                Suriye'den Yalova'ya plastik şişeyle getirmişler
            """.trimIndent(),
            displayName = "Screenshot_news.jpg",
        )

        assertEquals("Suriye'den Yalova'ya plastik şişeyle getirmişler", result.title)
        assertEquals(ContentTopic.NEWS, result.topic)
    }

    @Test
    fun `title ignores malformed toolbar text before recipe section`() {
        val result = IntentClassifier.classify(
            ocrText = """
                10:59
                = Semek
                Com
                Hızlı Ocak Tarifleri >
                Beyti kebabının hamuru için:
            """.trimIndent(),
            displayName = "Screenshot_recipe.jpg",
        )

        assertEquals("Beyti kebabı tarifi", result.title)
        assertEquals(ContentTopic.RECIPE, result.topic)
    }

    @Test
    fun `world cup schedule is not mistaken for recipe because of bugun`() {
        val result = IntentClassifier.classify(
            ocrText = """
                11:54 B
                Q dünya kupası maçları
                Genel Bakış Maçlar
                2026 FIFA Dünya Kupası Bugün, 22:00
                Maç öncesi analiz
                Puan Durumu
            """.trimIndent(),
            displayName = "Screenshot_matches.jpg",
        )

        assertEquals("dünya kupası maçları", result.title)
        assertEquals(IntentCategory.UNCLEAR, result.intent)
        assertEquals(ContentTopic.SPORT, result.topic)
    }

    @Test
    fun `series screenshot is separate from movie topic`() {
        val result = IntentClassifier.classify(
            ocrText = """
                Yeni bölüm: Kızılcık Şerbeti
                Dizi
                3. Sezon 12. Bölüm
            """.trimIndent(),
            displayName = "Screenshot_series.jpg",
        )

        assertEquals(ContentTopic.SERIES, result.topic)
        assertEquals(IntentCategory.UNCLEAR, result.intent)
    }

    @Test
    fun `short form clip stays uncategorized because format is not a topic`() {
        val result = IntentClassifier.classify(
            ocrText = """
                Otomatik seslendirme
                @ornekhesap
                Abone ol
                Shorts
                Paylaş
            """.trimIndent(),
            displayName = "Screenshot_short.jpg",
        )

        assertEquals(ContentTopic.OTHER, result.topic)
        assertEquals(IntentCategory.UNCLEAR, result.intent)
    }

    @Test
    fun `game advertisement beats short video format`() {
        val result = IntentClassifier.classify(
            ocrText = """
                elimination
                Block Blast
                YouTube
                Shorts
                Paylaş
            """.trimIndent(),
            displayName = "Screenshot_game_ad.jpg",
        )

        assertEquals(ContentTopic.GAME, result.topic)
        assertEquals(IntentCategory.UNCLEAR, result.intent)
    }

    @Test
    fun `remix action alone does not create a topic`() {
        val result = IntentClassifier.classify(
            ocrText = """
                Do You Love This?
                Abone ol
                Shorts
                Paylaş
                Remix
            """.trimIndent(),
            displayName = "Screenshot_short_remix.jpg",
        )

        assertEquals(ContentTopic.OTHER, result.topic)
        assertEquals(IntentCategory.UNCLEAR, result.intent)
    }

    @Test
    fun `song page becomes music and read watch`() {
        val result = IntentClassifier.classify(
            ocrText = """
                Sanatçı - Şarkı Adı
                @MuzikOfficial 22 Mn görüntüleme
                Abone ol
                Mix
                Bu şarkı çok güzel
            """.trimIndent(),
            displayName = "Screenshot_music.jpg",
        )

        assertEquals("Sanatçı - Şarkı Adı", result.title)
        assertEquals(ContentTopic.MUSIC, result.topic)
        assertEquals(IntentCategory.UNCLEAR, result.intent)
    }

    @Test
    fun `movie teaser title beats earlier ocr noise`() {
        val result = IntentClassifier.classify(
            ocrText = """
                yuI1yOl IVIUILIVIS NNIIIIII yal dtUIgI hdUb
                Dune: Part Three | Official Teaser
                Yeni film 2026 yılında vizyona girecek
                YouTube
            """.trimIndent(),
            displayName = "Screenshot_teaser.jpg",
        )

        assertEquals("Dune: Part Three", result.title)
        assertEquals(ContentTopic.MOVIE, result.topic)
        assertEquals(IntentCategory.UNCLEAR, result.intent)
    }

    @Test
    fun `movie metadata title beats trailer buttons and source chrome`() {
        val letterboxd = IntentClassifier.classify(
            ocrText = """
                letterboxd.com/film/dune-part-two/
                Dune: Part Two
                2024 DIRECTED BY
                Denis Villeneuve
                TRAILER 167 mins
            """.trimIndent(),
            displayName = "Screenshot_letterboxd.jpg",
        )
        val imdb = IntentClassifier.classify(
            ocrText = """
                imdb.com/title/tt0111161/
                IMDb
                The Shawshank
                Redemption
                1994 13+ 2h 22m
                Play trailer 2:11
            """.trimIndent(),
            displayName = "Screenshot_imdb.jpg",
        )
        val rottenTomatoes = IntentClassifier.classify(
            ocrText = """
                rottentomatoes.com/m/dune_part_two
                Retten
                Tomatöes
                Dune: Part Two
                ćtv
                PG-13, 2024, 2h 46m, Sci-Fi
                Tomatometer
            """.trimIndent(),
            displayName = "Screenshot_rt.jpg",
        )

        assertEquals("Dune: Part Two", letterboxd.title)
        assertEquals("The Shawshank Redemption", imdb.title)
        assertEquals("Dune: Part Two", rottenTomatoes.title)
    }

    @Test
    fun `ai prompt guide gets a useful topic title and reading intent`() {
        val result = IntentClassifier.classify(
            ocrText = """
                developers.openai.com
                OpenAI Developers
                Prompting guidance for
                GPT-5.6 Sol
                Use this guide when adapting prompts and tool descriptions.
                stacks to GPT-5.6 Sol or the GPT-5.6 family with modelguide details.
            """.trimIndent(),
            displayName = "Screenshot_ai_guide.jpg",
        )

        assertEquals(ContentTopic.AI, result.topic)
        assertEquals(IntentCategory.UNCLEAR, result.intent)
        assertTrue(result.title.startsWith("Prompting guidance for GPT-5.6 Sol"))
    }

    @Test
    fun `ebook store navigation does not turn a book into a game`() {
        val result = IntentClassifier.classify(
            ocrText = """
                Oyunlar
                Erotik Çekimi
                Artırmanın 50
                Yolu: +18
                Fotoğraflar
                İçermektedir!
                Ece Gürsoy
                Kendine Aşık Yayımcılık
                E-kitap
                Satın al (₺49,90)
            """.trimIndent(),
            displayName = "Screenshot_ebook.jpg",
        )

        assertEquals(ContentTopic.BOOK, result.topic)
        assertEquals(IntentCategory.UNCLEAR, result.intent)
        assertEquals("Erotik Çekimi Artırmanın 50 Yolu: +18 Fotoğraflar İçermektedir!", result.title)
        assertTrue(result.sensitive)
    }

    @Test
    fun `reddit text avoids sport substring false positive`() {
        val result = IntentClassifier.classify(
            ocrText = """
                reddit.com
                r/vlandiya
                Sizce erkekler neden bu kadar fazla cinselliği arzuluyor?
                Size bu kadar evrensel bilgi verdim.
            """.trimIndent(),
            displayName = "Screenshot_reddit.jpg",
        )

        assertEquals(ContentTopic.OTHER, result.topic)
        assertEquals(IntentCategory.UNCLEAR, result.intent)
        assertTrue(result.sensitive)
        assertEquals("Sizce erkekler neden bu kadar fazla cinselliği arzuluyor?", result.title)
    }

    @Test
    fun `person name and profile buttons are not sport evidence`() {
        val unknownPhoto = IntentClassifier.classify(
            ocrText = "Örnek Kullanıcı\nTakip Et\nMesaj",
            displayName = "Screenshot_profile.jpg",
        )
        val instagramPhoto = IntentClassifier.classify(
            ocrText = "Instagram\nornekhesap\nTakip Et\nMesaj",
            displayName = "Screenshot_instagram_profile.jpg",
        )

        assertEquals(ContentTopic.OTHER, unknownPhoto.topic)
        assertEquals(ContentTopic.OTHER, instagramPhoto.topic)
    }

    @Test
    fun `single generic final word does not create sport topic`() {
        val result = IntentClassifier.classify(
            ocrText = "Final proje teslim tarihi cuma günü olarak güncellendi",
            displayName = "Screenshot_note.jpg",
        )

        assertEquals(ContentTopic.OTHER, result.topic)
    }

    @Test
    fun `reddit navigation does not create a collection and question becomes title`() {
        val result = IntentClassifier.classify(
            ocrText = """
                old.reddit.com/r/AskReddit/
                Come for the cats, stay for the empathy.
                POPULAR TÜMÜ USERS ASKREDDİT PİCS FUNNY MOVIES GAMING WORLDNEWS NEWS
                Welcome to Reddit.
                BECOME A REDDİTOR
                What's the most dramatic example you've seen of a company laying
                off a "load-bearing" employee and watching everything fall apart?
                (self.AskReddit)
            """.trimIndent(),
            displayName = "Screenshot_askreddit.jpg",
        )

        assertEquals(ContentTopic.OTHER, result.topic)
        assertEquals(IntentCategory.UNCLEAR, result.intent)
        assertTrue(result.title.startsWith("What's the most dramatic example"))
        assertTrue(result.title.contains("load-bearing"))
    }

    @Test
    fun `source is metadata while explicit semantic topic stays primary`() {
        fun layout(source: String, content: String) = OcrLayout(
            imageWidth = 1080,
            imageHeight = 2340,
            lines = listOf(
                OcrLine(0, source, 80, 69, 700, 113),
                OcrLine(1, content, 50, 900, 1000, 1000),
            ),
        )

        val mastodon = IntentClassifier.classify(
            ocrText = "mastodon.social/explore\nHaberler\nThe Guardian",
            displayName = "Screenshot_mastodon.jpg",
            layout = layout("mastodon.social/explore", "Haberler\nThe Guardian"),
        )
        val redditFood = IntentClassifier.classify(
            ocrText = "reddit.com/r/food/\nr/food\n[Homemade] Chocolate Cake",
            displayName = "Screenshot_reddit_food.jpg",
            layout = layout("reddit.com/r/food/", "r/food\n[Homemade] Chocolate Cake"),
        )

        assertEquals(ContentTopic.OTHER, mastodon.topic)
        assertEquals(ContentTopic.OTHER, redditFood.topic)
        assertEquals("Mastodon", mastodon.sourceHint)
        assertEquals("Reddit", redditFood.sourceHint)
    }

    @Test
    fun `youtube recipe and music stay in semantic collections`() {
        val recipe = IntentClassifier.classify(
            ocrText = """
                youtube.com/results?search_query=mercimek+corbasi+tarifi
                Mercimek Çorbası Tarifi
                Evde kolay ve nefis yemek
            """.trimIndent(),
            displayName = "Screenshot_youtube_recipe.jpg",
        )
        val music = IntentClassifier.classify(
            ocrText = """
                youtube.com/watch?v=dQw4w9WgXcQ
                Rick Astley - Never Gonna Give You Up
                Official music video
            """.trimIndent(),
            displayName = "Screenshot_youtube_music.jpg",
        )

        assertEquals(ContentTopic.RECIPE, recipe.topic)
        assertEquals("YouTube", recipe.sourceHint)
        assertEquals(ContentTopic.MUSIC, music.topic)
        assertEquals("YouTube", music.sourceHint)
    }

    @Test
    fun `vertical source prior beats ad and navigation noise`() {
        val news = IntentClassifier.classify(
            ocrText = """
                aa.com.tr/tr
                REKLAM Yapay Zeka Film Yarışması
                Anadolu Ajansı
                2026 YKS sonuçları açıklandı
                Haberler Eğitim
            """.trimIndent(),
            displayName = "Screenshot_aa.jpg",
        )
        val sport = IntentClassifier.classify(
            ocrText = """
                fotomac.com.tr
                TÜRKİYE'NİN 1 NUMARALI SPOR SİTESİ
                DIŞ HABER
                Fenerbahçe'de o ismin menajeri Türkiye'ye geldi
            """.trimIndent(),
            displayName = "Screenshot_fotomac.jpg",
        )
        val series = IntentClassifier.classify(
            ocrText = """
                trtizle.com
                tabii
                Orijinal diziler, en prestijli UEFA maçları ve binlerce saatlik içerik
                Gassal Görünmez El
            """.trimIndent(),
            displayName = "Screenshot_tabii.jpg",
        )

        assertEquals(ContentTopic.NEWS, news.topic)
        assertEquals(ContentTopic.SPORT, sport.topic)
        assertEquals(ContentTopic.SERIES, series.topic)
    }

    @Test
    fun `finance is separate from product and generic other`() {
        val centralBank = IntentClassifier.classify(
            ocrText = """
                tcmb.gov.tr
                TÜRKİYE CUMHURİYET MERKEZ BANKASI
                Enflasyon ve Makroekonomik Görünüm
                Fiyat istikrarı
            """.trimIndent(),
            displayName = "Screenshot_tcmb.jpg",
        )
        val currency = IntentClassifier.classify(
            ocrText = """
                Türk lirası
                Türkiye Cumhuriyeti'nin resmî para birimi
                ₺ TL döviz kuru
            """.trimIndent(),
            displayName = "Screenshot_lira.jpg",
        )

        assertEquals(ContentTopic.FINANCE, centralBank.topic)
        assertEquals(IntentCategory.UNCLEAR, centralBank.intent)
        assertEquals("TCMB", centralBank.sourceHint)
        assertEquals(ContentTopic.FINANCE, currency.topic)
    }

    @Test
    fun `mixed wikipedia homepage is not the featured sports card`() {
        val result = IntentClassifier.classify(
            ocrText = """
                tr.wikipedia.org/wiki/Anasayfa
                Vikipedi
                Seçkin madde
                FIFA Dünya Kupası final karşılaşması
                Tarihte bugün
            """.trimIndent(),
            displayName = "Screenshot_wikipedia_home.jpg",
        )

        assertEquals(ContentTopic.OTHER, result.topic)
        assertEquals("Wikipedia", result.sourceHint)
    }

    @Test
    fun `book descriptions beat generic page terms`() {
        val sapiens = IntentClassifier.classify(
            ocrText = """
                en.wikipedia.org/wiki/Sapiens
                Sapiens: A Brief History of Humankind
                is a 2011 book by the historian Yuval Noah Harari
            """.trimIndent(),
            displayName = "Screenshot_sapiens.jpg",
        )
        val sefiller = IntentClassifier.classify(
            ocrText = """
                tr.wikipedia.org/wiki/Sefiller
                Sefiller
                Victor Hugo'nun eseri
                tarafından yazılan tarihi romandır
            """.trimIndent(),
            displayName = "Screenshot_sefiller.jpg",
        )

        assertEquals(ContentTopic.BOOK, sapiens.topic)
        assertEquals(ContentTopic.BOOK, sefiller.topic)
    }

    @Test
    fun `movie editorial page uses article headline instead of generic film chrome`() {
        val result = IntentClassifier.classify(
            ocrText = """
                beyazperde.com
                BEYAZPERDE'DE BU HAFTA
                Filmler
                Christopher Nolan'ın Uyarlamasını İzlemeden
                Önce "The Odyssey" Hakkında Bilmeniz Gereken
                Her Şey
            """.trimIndent(),
            displayName = "Screenshot_beyazperde.jpg",
        )

        assertEquals(ContentTopic.MOVIE, result.topic)
        assertEquals(
            "Christopher Nolan'ın Uyarlamasını İzlemeden Önce \"The Odyssey\" Hakkında Bilmeniz Gereken Her Şey",
            result.title,
        )
    }

    @Test
    fun `instagram image quote remains searchable without a forced collection`() {
        val result = IntentClassifier.classify(
            ocrText = """
                koyarsa.
                Instagram
                21 saat önce
                Ana sayfa
                Bilge bir Kurt der ki
                KÖR EBE OYNAMAK
                Görseller telif hakkına tabi olabilir. Daha Fazla Bilgi
                Paylaş
            """.trimIndent(),
            displayName = "Screenshot_quote.jpg",
        )

        assertEquals(ContentTopic.OTHER, result.topic)
        assertEquals(IntentCategory.UNCLEAR, result.intent)
        assertEquals("Bilge bir Kurt der ki", result.title)
    }

    @Test
    fun `forum discussion remains searchable without a forced collection`() {
        val result = IntentClassifier.classify(
            ocrText = """
                donanimhaber.com
                Konu Sahibi
                Cevap Yaz
                Kurt kuzuyu yemeye karar verince...
            """.trimIndent(),
            displayName = "Screenshot_forum.jpg",
        )

        assertEquals(ContentTopic.OTHER, result.topic)
        assertEquals(IntentCategory.UNCLEAR, result.intent)
    }

    @Test
    fun `politician knowledge panel becomes politics without inventing intent`() {
        val result = IntentClassifier.classify(
            ocrText = """
                Angela Merkel
                Devlet görevlisi ve eski Almanya Şansölyesi
                Genel Bakış
                Yaş 71 yıl
                Kitaplar
                TV şovları
            """.trimIndent(),
            displayName = "Screenshot_person.jpg",
        )

        assertEquals(ContentTopic.POLITICS, result.topic)
        assertEquals(IntentCategory.UNCLEAR, result.intent)
        assertEquals("Angela Merkel", result.title)
    }

    @Test
    fun `political content beats generic news site chrome`() {
        val result = IntentClassifier.classify(
            ocrText = """
                Son dakika
                Haberler - Gündem
                Meclis seçim yasasını görüşüyor
                Hükümet ve siyasi partilerden ilk açıklamalar
            """.trimIndent(),
            displayName = "Screenshot_politics_news.jpg",
        )

        assertEquals(ContentTopic.POLITICS, result.topic)
        assertEquals(IntentCategory.UNCLEAR, result.intent)
    }
}
