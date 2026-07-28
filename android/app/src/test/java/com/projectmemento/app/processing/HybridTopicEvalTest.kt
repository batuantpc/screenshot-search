package com.projectmemento.app.processing

import com.projectmemento.app.data.ContentTopic
import com.projectmemento.app.eval.EvalHarness
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Ürün öneri politikasını, geri çekilmiş deney modeliyle sabit setlerde karşılaştırır.
 *
 * Bu tek başına ürün doğruluğu gate'i değildir: kamuya açık native set eski Android
 * ekranlarından gelir ve modern kullanıcı arşivini tam temsil etmez. Yine de model export'u
 * veya eşik değişikliği öncesi sabit setlerdeki gerilemeyi görünür kılar.
 */
class HybridTopicEvalTest {
    @Test
    fun `fixed eval sets report rule model and hybrid margins`() {
        val modelFile = resolve("android/app/src/test/resources/models/topic-model-v1.bin")
        val evalDir = resolve(".private-data/eval")
        assumeTrue(modelFile != null && evalDir?.isDirectory == true)
        val model = modelFile!!.inputStream().use(TopicModel::fromStream)
        assumeTrue(model != null)

        val margins = listOf(0.05f, 0.10f, 0.15f, 0.20f)
        FIXED_EVAL_FILES.forEach { name ->
            val file = evalDir!!.resolve("$name.eval")
            if (!file.isFile) return@forEach
            val cases = EvalHarness.parse(file.readText())
            if (cases.isEmpty()) return@forEach
            val productPredictions = cases.associateWith { case ->
                IntentClassifier.classify(
                    case.ocrText,
                    case.displayName,
                    case.layout,
                    case.visualLabels,
                ).topic
            }
            val ruleHits = productPredictions.count { (case, topic) -> topic == case.expectedTopic }
            val semanticPredictions = productPredictions.count { (_, topic) ->
                topic != ContentTopic.OTHER
            }
            val semanticHits = productPredictions.count { (case, topic) ->
                topic != ContentTopic.OTHER && topic == case.expectedTopic
            }
            val expectedOther = cases.count { it.expectedTopic == ContentTopic.OTHER }
            val otherFalsePositives = productPredictions.count { (case, topic) ->
                case.expectedTopic == ContentTopic.OTHER && topic != ContentTopic.OTHER
            }
            val modelHits = cases.count { case ->
                model!!.predict(case.ocrText)?.topic == case.expectedTopic
            }
            val policyPredictions = cases.associateWith { case ->
                val ruleTopic = requireNotNull(productPredictions[case])
                TopicDecision.decide(
                    ruleTopic = ruleTopic,
                    model = model!!.predict(case.ocrText),
                    contentText = case.ocrText,
                )
            }
            val policyHits = policyPredictions.count { (case, predicted) ->
                predicted == case.expectedTopic
            }
            val hybridHits = margins.associateWith { margin ->
                cases.count { case ->
                    val ruleTopic = requireNotNull(productPredictions[case])
                    val prediction = model!!.predict(case.ocrText)
                    val topic = when {
                        prediction == null -> ruleTopic
                        !TopicDecision.hasEnoughSemanticContent(case.ocrText) -> ruleTopic
                        ruleTopic == ContentTopic.OTHER -> prediction.topic
                        prediction.margin >= margin -> prediction.topic
                        else -> ruleTopic
                    }
                    topic == case.expectedTopic
                }
            }
            println(
                buildString {
                    append("$name n=${cases.size} | product=${percent(ruleHits, cases.size)}")
                    append(" | suggestion-precision=${percent(semanticHits, semanticPredictions)}")
                    append(" | suggestion-coverage=${percent(semanticPredictions, cases.size)}")
                    if (expectedOther > 0) {
                        append(" | OTHER-FP=${percent(otherFalsePositives, expectedOther)}")
                    }
                    append(" | model=${percent(modelHits, cases.size)}")
                    append(" | legacy-policy=${percent(policyHits, cases.size)}")
                    hybridHits.forEach { (margin, hits) ->
                        append(" | hybrid@${"%.2f".format(margin)}=${percent(hits, cases.size)}")
                    }
                },
            )
            if (name == "native-ui-v1") {
                val perTopic = cases.groupBy { it.expectedTopic }
                    .toSortedMap(compareBy(ContentTopic::name))
                    .map { (topic, topicCases) ->
                        val hits = topicCases.count { productPredictions[it] == topic }
                        "${topic.name}=${percent(hits, topicCases.size)}"
                    }
                val confusion = cases
                    .map { case -> case.expectedTopic to requireNotNull(productPredictions[case]) }
                    .filter { (expected, predicted) -> expected != predicted }
                    .groupingBy { it }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .take(12)
                    .joinToString { (pair, count) -> "${pair.first.name}->${pair.second.name}:$count" }
                println("native-ui-v1 product per-topic | ${perTopic.joinToString(" | ")}")
                println("native-ui-v1 product confusions | $confusion")
            }
        }
    }

    private fun percent(hit: Int, total: Int): String =
        if (total == 0) "$hit/$total (n/a)"
        else "$hit/$total (${"%.1f".format(hit * 100.0 / total)}%)"

    private fun resolve(relative: String): File? {
        var current: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(5) {
            val candidate = current?.resolve(relative)
            if (candidate?.exists() == true) return candidate
            current = current?.parentFile
        }
        return null
    }

    private companion object {
        val FIXED_EVAL_FILES = listOf(
            "device-verified",
            "en-v1-holdout",
            "web-corpus-test",
            "web-holdout-v1",
            "native-ui-v1",
        )
    }
}
