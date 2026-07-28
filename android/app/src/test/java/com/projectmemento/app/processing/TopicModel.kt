package com.projectmemento.app.processing

import com.projectmemento.app.data.ContentTopic
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Deney/benchmark için tutulan karakter n-gram + lineer konu sınıflandırıcısı.
 *
 * Neden kural sozlugu yerine bu:
 *  - Turkce eklemeli: "futbolcu"/"futbolun"/"futbolda" ayni n-gram'lari paylasir; elle
 *    cekimli form veya ek listesi yazmaya gerek kalmaz.
 *  - OCR gurultusune dayanikli: bozuk "Retten Tomatoes" bile dogru n-gram'lari tasir.
 *  - Dil bagimsiz: TR ve EN tek modelde.
 *  - Kucuk ve hizli: seyrek nokta carpimi, runtime bagimliligi yok.
 *  - Aciklanabilir: lineer agirliklar hangi n-gram'in karari surukledigini soyleyebilir.
 *
 * Model `tools/train-topic-model.py --export` ile üretilir. Ürün yolunda kullanılmaz ve APK'ye
 * gömülmez; native ekran ölçümlerindeki düşük kesinliği nedeniyle yalnız karşılaştırma testidir.
 *
 * ONEMLI: ozellik cikarimi scikit-learn'un `TfidfVectorizer(analyzer="char_wb")`
 * davranisiyla BIREBIR ayni olmak zorundadir; aksi halde cihaz modelin egitildiginden
 * farkli bir uzayda calisir. Ayrintilar [ngrams] icinde aciklandi.
 */
class TopicModel private constructor(
    private val classes: List<ContentTopic>,
    private val vocabulary: HashMap<String, Int>,
    private val idf: FloatArray,
    private val coefficients: Array<FloatArray>,
    private val intercepts: FloatArray,
    private val minN: Int,
    private val maxN: Int,
) {

    /**
     * @param confidence en yuksek sinifin softmax olasiligi.
     * @param margin en yuksek ile ikinci sinif arasindaki olasilik farki. 17 sinifli bir
     *   problemde ham olasilik dogal olarak dusuk kalir (medyan ~0,25); "ne kadar ayirt
     *   edici" sorusunu marj cok daha iyi cevaplar. Olcumde marj tabanli esik ham guvene
     *   gore belirgin sekilde daha iyi sonuc verdi.
     */
    data class Prediction(
        val topic: ContentTopic,
        val confidence: Float,
        val margin: Float,
    )

    /** Metni siniflandirir; model bos metinde karar uretmez. */
    fun predict(text: String): Prediction? {
        val features = features(text) ?: return null
        var bestIndex = 0
        var bestScore = Float.NEGATIVE_INFINITY
        val scores = FloatArray(classes.size)
        for (index in classes.indices) {
            var sum = intercepts[index]
            val row = coefficients[index]
            for ((featureIndex, value) in features) {
                sum += row[featureIndex] * value
            }
            scores[index] = sum
            if (sum > bestScore) {
                bestScore = sum
                bestIndex = index
            }
        }
        val probabilities = softmax(scores)
        var top = 0f
        var second = 0f
        for (value in probabilities) {
            if (value > top) {
                second = top
                top = value
            } else if (value > second) {
                second = value
            }
        }
        return Prediction(classes[bestIndex], top, top - second)
    }

    /** TF-IDF vektorunu (indeks -> deger) seyrek olarak uretir, L2 normalize eder. */
    private fun features(text: String): List<Pair<Int, Float>>? {
        val counts = HashMap<Int, Int>()
        for (gram in ngrams(text)) {
            val index = vocabulary[gram] ?: continue
            counts[index] = (counts[index] ?: 0) + 1
        }
        if (counts.isEmpty()) return null

        // sublinear_tf=True -> tf = 1 + ln(count); ardindan idf ile carpilir.
        val values = ArrayList<Pair<Int, Float>>(counts.size)
        var norm = 0.0
        for ((index, count) in counts) {
            val weight = ((1.0 + ln(count.toDouble())) * idf[index]).toFloat()
            values.add(index to weight)
            norm += weight.toDouble() * weight
        }
        if (norm <= 0.0) return null
        val inverse = (1.0 / sqrt(norm)).toFloat()
        return values.map { (index, weight) -> index to weight * inverse }
    }

    /**
     * scikit-learn `char_wb` n-gram'lari.
     *
     * Davranis (sklearn `_char_wb_ngrams`): bosluklar tek bosluga indirilir, metin
     * kelimelere bolunur, her kelimenin BASINA VE SONUNA bosluk eklenir, sonra n
     * uzunlugunda pencere kaydirilir. Kelime n'den kisaysa yalnizca bir kez sayilir.
     *
     * Kucuk harfe cevirme Locale.ROOT ile yapilir: Python'un str.lower() davranisiyla
     * ayni olmasi icin. Turkce locale kullanilsaydi "I" -> "ı" olurdu ve egitimdeki
     * "I" -> "i" ile uyusmazdi (train/serve skew).
     */
    private fun ngrams(text: String): List<String> {
        val normalized = text.lowercase(Locale.ROOT).replace(WHITESPACE, " ")
        val result = ArrayList<String>()
        for (rawWord in normalized.split(' ')) {
            if (rawWord.isEmpty()) continue
            val word = " $rawWord "
            for (n in minN..maxN) {
                if (word.length < n) {
                    result.add(word)
                    break
                }
                var offset = 0
                while (offset + n <= word.length) {
                    result.add(word.substring(offset, offset + n))
                    offset += 1
                }
            }
        }
        return result
    }

    private fun softmax(scores: FloatArray): FloatArray {
        var max = Float.NEGATIVE_INFINITY
        for (value in scores) if (value > max) max = value
        val exps = DoubleArray(scores.size) { Math.exp((scores[it] - max).toDouble()) }
        val sum = exps.sum()
        if (sum <= 0.0) return FloatArray(scores.size)
        return FloatArray(scores.size) { (exps[it] / sum).toFloat() }
    }

    companion object {
        private val WHITESPACE = "\\s+".toRegex()

        /** Test ve arac kullanimi icin: Android Context olmadan yukler. */
        fun fromStream(stream: java.io.InputStream): TopicModel? =
            DataInputStream(stream.buffered()).use { input -> read(input) }

        private fun read(input: DataInputStream): TopicModel? {
            val magic = ByteArray(4).also(input::readFully)
            if (String(magic) != "MTM1") return null
            val classCount = input.readIntLe()
            val featureCount = input.readIntLe()
            val maxN = input.readIntLe()
            val minN = input.readIntLe()

            val classes = ArrayList<ContentTopic>(classCount)
            repeat(classCount) {
                val name = input.readShortString()
                classes.add(
                    runCatching { enumValueOf<ContentTopic>(name) }.getOrDefault(ContentTopic.OTHER),
                )
            }

            val vocabulary = HashMap<String, Int>(featureCount * 2)
            val idf = FloatArray(featureCount)
            repeat(featureCount) { index ->
                vocabulary[input.readShortString()] = index
                idf[index] = input.readFloatLe()
            }

            val coefficients = Array(classCount) { FloatArray(featureCount) }
            val rowBytes = ByteArray(featureCount * 4)
            for (classIndex in 0 until classCount) {
                input.readFully(rowBytes)
                val buffer = ByteBuffer.wrap(rowBytes).order(ByteOrder.LITTLE_ENDIAN)
                for (featureIndex in 0 until featureCount) {
                    coefficients[classIndex][featureIndex] = buffer.float
                }
            }
            val intercepts = FloatArray(classCount) { input.readFloatLe() }

            return TopicModel(
                classes = classes,
                vocabulary = vocabulary,
                idf = idf,
                coefficients = coefficients,
                intercepts = intercepts,
                minN = minN,
                maxN = maxN,
            )
        }

        private fun DataInputStream.readIntLe(): Int {
            val bytes = ByteArray(4).also(::readFully)
            return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
        }

        private fun DataInputStream.readFloatLe(): Float {
            val bytes = ByteArray(4).also(::readFully)
            return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).float
        }

        private fun DataInputStream.readShortString(): String {
            val bytes = ByteArray(2).also(::readFully)
            val length = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            return String(ByteArray(length).also(::readFully), Charsets.UTF_8)
        }
    }
}
