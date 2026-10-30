package com.screenshotsearch.app.eval

import com.screenshotsearch.app.processing.IntentClassifier
import com.screenshotsearch.app.processing.TopicDecision
import com.screenshotsearch.app.processing.TopicModel
import java.io.File
import java.util.Locale
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Üründeki yüksek kesinlikli önerileri ve eski deney modelini karşılaştırmak için döküm üretir.
 *
 * Deney modeli artık ürün yolunda değildir; bu test geçmiş yaklaşımı sabit setlerde ölçülebilir
 * tutar. `rule_topic` sütunu şu an kullanıcının göreceği güvenli öneridir.
 *
 * Cikti: `.private-data/eval/rule-predictions.tsv` (Git'e girmez)
 */
class RulePredictionDumpTest {

    @Test
    fun `saf kural tahminlerini doker`() {
        val evalDir = resolve(".private-data/eval")
        assumeTrue("Ozel eval verisi yok, atlandi", evalDir != null)
        val modelFile = resolve("android/app/src/test/resources/models/topic-model-v1.bin")
        assumeTrue("Konu modeli yok, atlandi", modelFile?.isFile == true)
        val model = modelFile!!.inputStream().use(TopicModel::fromStream)
        assumeTrue("Konu modeli okunamadi, atlandi", model != null)

        val out = StringBuilder(
            "id\texpected\trule_topic\tmodel_topic\tmodel_confidence\tmodel_margin\tpolicy_topic\n",
        )
        var count = 0
        evalDir!!.listFiles { f -> f.extension == "eval" }.orEmpty().sorted().forEach { file ->
            if (file.name == "rule-predictions.tsv") return@forEach
            EvalHarness.parse(file.readText()).forEach { case ->
                val rules = IntentClassifier.classify(
                    case.ocrText,
                    case.displayName,
                    case.layout,
                    case.visualLabels,
                )
                val prediction = model!!.predict(case.ocrText)
                val policy = TopicDecision.decide(rules.topic, prediction, case.ocrText)
                out.append(case.id).append('\t')
                    .append(case.expectedTopic.name).append('\t')
                    .append(rules.topic.name).append('\t')
                    .append(prediction?.topic?.name.orEmpty()).append('\t')
                    .append(prediction?.confidence?.let(::decimal).orEmpty()).append('\t')
                    .append(prediction?.margin?.let(::decimal).orEmpty()).append('\t')
                    .append(policy.name).append('\n')
                count += 1
            }
        }
        val target = File(evalDir, "rule-predictions.tsv")
        target.writeText(out.toString())
        println("saf kural tahmini: $count kayit -> ${target.absolutePath}")
    }

    private fun decimal(value: Float): String = String.format(Locale.ROOT, "%.6f", value)

    private fun resolve(relative: String): File? {
        var current: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(5) {
            val candidate = current?.resolve(relative)
            if (candidate?.exists() == true) return candidate
            current = current?.parentFile
        }
        return null
    }
}
