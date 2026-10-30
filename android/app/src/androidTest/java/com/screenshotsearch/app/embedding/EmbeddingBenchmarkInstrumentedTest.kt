package com.screenshotsearch.app.embedding

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.screenshotsearch.app.data.ContentTopic
import com.screenshotsearch.app.data.IntentCategory
import com.screenshotsearch.app.processing.EmbeddingMath
import com.screenshotsearch.app.processing.IntentClassifier
import com.screenshotsearch.app.processing.OcrLayout
import com.screenshotsearch.app.processing.TextVectorizer
import com.screenshotsearch.app.processing.VisualLabel
import com.screenshotsearch.app.processing.VisualLabels
import java.io.File
import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Öğrenilmiş embedding'i ana APK'ya eklemeden Galaxy A23 üzerinde karşılaştırır.
 *
 * Girdi dosyaları hedef uygulamanın external files alanındaki `embedding-eval`
 * klasörüne bırakılır. Test yoksa normal instrumented koşuda sessizce skip olur.
 * Model yalnız androidTest APK'sının asset'idir; ürün APK boyutunu etkilemez.
 */
@RunWith(AndroidJUnit4::class)
class EmbeddingBenchmarkInstrumentedTest {
    @Test
    fun compareSemanticEmbeddingWithRulesAndHashedNgrams() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val testContext = instrumentation.context
        val root = File(requireNotNull(targetContext.getExternalFilesDir(null)), EVAL_DIR)
        val devFile = File(root, DEV_FILE)
        val testFile = File(root, TEST_FILE)
        assumeTrue("Embedding dev eval dosyası yok: $devFile", devFile.isFile)
        assumeTrue("Embedding test eval dosyası yok: $testFile", testFile.isFile)

        val devCases = parseEval(devFile.readText())
        val testCases = parseEval(testFile.readText())
        assertEquals(77, devCases.size)
        assertEquals(45, testCases.size)

        val ruleScore = scoreRules(testCases)
        val lexicalModel = TextVectorizer(devCases.map(EvalCase::embeddingText))
        val lexicalScore = scoreKnn(
            train = devCases.map { EmbeddedCase(it, lexicalModel.embed(it.embeddingText())) },
            test = testCases.map { EmbeddedCase(it, lexicalModel.embed(it.embeddingText())) },
        )

        // Instrumentation context'inin applicationContext'i bazı cihazlarda null olur.
        // Test asset'ini hedef uygulamanın cache'ine kopyalayıp geçerli uygulama
        // context'iyle açmak MediaPipe'ın telemetry başlatıcısındaki NPE'yi önler.
        val modelFile = File(targetContext.cacheDir, MODEL_ASSET)
        testContext.assets.open(MODEL_ASSET).use { input ->
            modelFile.outputStream().use(input::copyTo)
        }
        val initializationStart = SystemClock.elapsedRealtimeNanos()
        val semanticEmbedder = TextEmbedder.createFromFile(targetContext, modelFile)
        val initializationMs = elapsedMs(initializationStart)
        val semanticLatencies = mutableListOf<Double>()
        val semanticVectors = try {
            buildMap {
                (devCases + testCases).forEach { case ->
                    val startedAt = SystemClock.elapsedRealtimeNanos()
                    val vector = semanticEmbedder
                        .embed(case.embeddingText())
                        .embeddingResult()
                        .embeddings()
                        .first()
                        .floatEmbedding()
                    semanticLatencies += elapsedMs(startedAt)
                    put(case.id, vector)
                }
            }
        } finally {
            semanticEmbedder.close()
            modelFile.delete()
        }

        val semanticScore = scoreKnn(
            train = devCases.map { EmbeddedCase(it, requireNotNull(semanticVectors[it.id])) },
            test = testCases.map { EmbeddedCase(it, requireNotNull(semanticVectors[it.id])) },
        )
        val dimensions = semanticVectors.values.firstOrNull()?.size ?: 0
        assertTrue("Semantic embedding boş dönmemeli", dimensions > 0)

        root.mkdirs()
        File(root, REPORT_FILE).writeText(
            renderReport(
                ruleScore = ruleScore,
                lexicalScore = lexicalScore,
                semanticScore = semanticScore,
                dimensions = dimensions,
                initializationMs = initializationMs,
                semanticLatencies = semanticLatencies,
            ),
        )
    }

    private fun scoreRules(cases: List<EvalCase>): Score {
        var topicHits = 0
        var intentHits = 0
        cases.forEach { case ->
            val result = IntentClassifier.classify(
                ocrText = case.ocrText,
                displayName = case.displayName,
                layout = case.layout,
                visualLabels = case.visualLabels,
            )
            if (result.topic == case.topic) topicHits += 1
            if (result.intent == case.intent) intentHits += 1
        }
        return Score(topicHits, intentHits, cases.size)
    }

    private fun scoreKnn(
        train: List<EmbeddedCase>,
        test: List<EmbeddedCase>,
    ): Score {
        var topicHits = 0
        var intentHits = 0
        test.forEach { query ->
            val neighbors = train
                .map { candidate ->
                    Neighbor(candidate, EmbeddingMath.cosine(query.vector, candidate.vector))
                }
                .sortedByDescending(Neighbor::similarity)
                .take(NEIGHBOR_COUNT)
            val topic = weightedVote(neighbors) { it.evalCase.topic }
            val intent = weightedVote(neighbors) { it.evalCase.intent }
            if (topic == query.evalCase.topic) topicHits += 1
            if (intent == query.evalCase.intent) intentHits += 1
        }
        return Score(topicHits, intentHits, test.size)
    }

    private fun <T : Any> weightedVote(
        neighbors: List<Neighbor>,
        label: (EmbeddedCase) -> T,
    ): T = neighbors
        .groupBy { label(it.embeddedCase) }
        .maxWithOrNull(
            compareBy<Map.Entry<T, List<Neighbor>>> { entry ->
                entry.value.sumOf { it.similarity.coerceAtLeast(0f).toDouble() }
            }.thenBy { entry -> entry.value.maxOf(Neighbor::similarity) },
        )
        ?.key
        ?: error("Komşu bulunamadı")

    private fun renderReport(
        ruleScore: Score,
        lexicalScore: Score,
        semanticScore: Score,
        dimensions: Int,
        initializationMs: Double,
        semanticLatencies: List<Double>,
    ): String {
        val sortedLatencies = semanticLatencies.sorted()
        return buildString {
            appendLine("# Screenshot Search on-device embedding benchmark")
            appendLine()
            appendLine("- Cihaz: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("- Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("- Eğitim/development: 77; test: 45")
            appendLine("- kNN: cosine, k=$NEIGHBOR_COUNT, similarity-weighted vote")
            appendLine("- Model: MediaPipe Universal Sentence Encoder, $dimensions boyut")
            appendLine("- Model açılışı: ${"%.1f".format(initializationMs)} ms")
            appendLine(
                "- Embed gecikmesi: ortalama ${"%.1f".format(semanticLatencies.average())} ms; " +
                    "p50 ${"%.1f".format(percentile(sortedLatencies, 0.50))} ms; " +
                    "p95 ${"%.1f".format(percentile(sortedLatencies, 0.95))} ms",
            )
            appendLine()
            appendLine("| Yöntem | Topic | Intent |")
            appendLine("| --- | ---: | ---: |")
            appendLine(ruleScore.row("Mevcut kurallar"))
            appendLine(lexicalScore.row("Hashed n-gram kNN"))
            appendLine(semanticScore.row("Semantic embedding kNN"))
            appendLine()
            appendLine(
                "> Bu 45 örnek, ilk büyük kural ayarından sonra ayrıldığı için bağımsız ürün " +
                    "testi değildir. Yalnız temsil/model karşılaştırması için kullanılır.",
            )
        }
    }

    private fun percentile(sorted: List<Double>, quantile: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val index = ((sorted.lastIndex * quantile).roundToLong()).toInt()
        return sorted[index]
    }

    private fun elapsedMs(startedAt: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000.0

    private fun parseEval(content: String): List<EvalCase> {
        val cases = mutableListOf<EvalCase>()
        val lines = content.lines()
        var index = 0
        while (index < lines.size) {
            val header = lines[index].trim()
            if (!header.startsWith(CASE_PREFIX)) {
                index += 1
                continue
            }
            val id = header.removePrefix(CASE_PREFIX).trim()
            val fields = mutableMapOf<String, String>()
            index += 1
            while (index < lines.size && !lines[index].trim().startsWith(SECTION_PREFIX)) {
                val separator = lines[index].indexOf(':')
                if (separator > 0) {
                    fields[lines[index].substring(0, separator).trim()] =
                        lines[index].substring(separator + 1).trim()
                }
                index += 1
            }
            val sections = mutableMapOf<String, String>()
            while (index < lines.size && lines[index].trim() != CASE_END) {
                val section = lines[index].trim().removePrefix(SECTION_PREFIX)
                index += 1
                val body = mutableListOf<String>()
                while (
                    index < lines.size &&
                    lines[index].trim() != CASE_END &&
                    !lines[index].trim().startsWith(SECTION_PREFIX)
                ) {
                    body += lines[index]
                    index += 1
                }
                sections[section] = body.joinToString("\n").trim('\n')
            }
            require(index < lines.size) { "$id: '$CASE_END' bulunamadı" }
            index += 1
            cases += EvalCase(
                id = id,
                displayName = fields["display"].orEmpty(),
                ocrText = sections["ocr"].orEmpty(),
                layout = OcrLayout.deserialize(sections["layout"]),
                visualLabels = VisualLabels.parse(sections["labels"]),
                topic = enumValueOf(requireNotNull(fields["topic"])),
                intent = enumValueOf(requireNotNull(fields["intent"])),
            )
        }
        return cases
    }

    private data class EvalCase(
        val id: String,
        val displayName: String,
        val ocrText: String,
        val layout: OcrLayout?,
        val visualLabels: List<VisualLabel>,
        val topic: ContentTopic,
        val intent: IntentCategory,
    ) {
        fun embeddingText(): String = ocrText
            .ifBlank { displayName }
            .take(MAX_EMBEDDING_CHARS)
    }

    private data class EmbeddedCase(
        val evalCase: EvalCase,
        val vector: FloatArray,
    )

    private data class Neighbor(
        val embeddedCase: EmbeddedCase,
        val similarity: Float,
    )

    private data class Score(
        val topicHits: Int,
        val intentHits: Int,
        val total: Int,
    ) {
        fun row(label: String): String =
            "| $label | $topicHits/$total (${"%.1f".format(topicHits * 100.0 / total)}%) | " +
                "$intentHits/$total (${"%.1f".format(intentHits * 100.0 / total)}%) |"
    }

    private companion object {
        const val EVAL_DIR = "embedding-eval"
        const val DEV_FILE = "web-corpus-dev.eval"
        const val TEST_FILE = "web-corpus-test.eval"
        const val REPORT_FILE = "embedding-benchmark-report.md"
        const val MODEL_ASSET = "universal_sentence_encoder.tflite"
        const val CASE_PREFIX = "=== case "
        const val CASE_END = "=== end"
        const val SECTION_PREFIX = "--- "
        const val NEIGHBOR_COUNT = 3
        const val MAX_EMBEDDING_CHARS = 2_000
    }
}
