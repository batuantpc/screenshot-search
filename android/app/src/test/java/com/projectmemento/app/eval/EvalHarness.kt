package com.projectmemento.app.eval

import com.projectmemento.app.data.ContentTopic
import com.projectmemento.app.data.IntentCategory
import com.projectmemento.app.processing.IntentClassifier
import com.projectmemento.app.processing.OcrLayout
import com.projectmemento.app.processing.VisualLabel
import com.projectmemento.app.processing.VisualLabels

/**
 * Tek bir değerlendirme örneği. Altın etiketler zorunlu olarak konu içerir;
 * niyet ve hassasiyet etiketi olan örneklerde o boyutlar da puanlanır.
 */
data class EvalCase(
    val id: String,
    val displayName: String,
    val ocrText: String,
    val layout: OcrLayout?,
    val visualLabels: List<VisualLabel>,
    val expectedTopic: ContentTopic,
    val expectedIntent: IntentCategory?,
    val expectedSensitive: Boolean?,
)

data class EvalOutcome(
    val case: EvalCase,
    val predictedTopic: ContentTopic,
    val predictedIntent: IntentCategory,
    val predictedSensitive: Boolean,
    val predictedTitle: String,
) {
    val topicCorrect: Boolean get() = predictedTopic == case.expectedTopic
    val intentCorrect: Boolean? get() = case.expectedIntent?.let { it == predictedIntent }
    val sensitiveCorrect: Boolean? get() = case.expectedSensitive?.let { it == predictedSensitive }
}

/**
 * Sınıflandırıcı değerlendirme koşucusu.
 *
 * Dosya formatı (insan eliyle düzenlenebilir, bağımlılıksız):
 * ```
 * === case ornek-kimlik
 * display: Screenshot_1.png
 * topic: RECIPE
 * intent: TRY
 * sensitive: false
 * --- ocr
 * <çok satırlı ham OCR metni>
 * --- layout
 * <OcrLayout.serialize çıktısı, opsiyonel>
 * --- labels
 * <VisualLabels.serialize çıktısı: Ad|guven satırları, opsiyonel>
 * === end
 * ```
 */
object EvalHarness {
    private const val CASE_PREFIX = "=== case "
    private const val CASE_END = "=== end"
    private const val SECTION_PREFIX = "--- "
    private const val OCR_SECTION = "ocr"
    private const val LAYOUT_SECTION = "layout"
    private const val LABELS_SECTION = "labels"

    fun parse(content: String): List<EvalCase> {
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
                val line = lines[index].trim()
                require(line != CASE_END) { "$id: '$SECTION_PREFIX$OCR_SECTION' bölümü eksik" }
                val separator = line.indexOf(':')
                if (separator > 0) {
                    fields[line.substring(0, separator).trim()] = line.substring(separator + 1).trim()
                }
                index += 1
            }

            val sections = mutableMapOf<String, String>()
            while (index < lines.size && lines[index].trim() != CASE_END) {
                val sectionName = lines[index].trim().removePrefix(SECTION_PREFIX)
                index += 1
                val body = mutableListOf<String>()
                while (
                    index < lines.size &&
                    lines[index].trim() != CASE_END &&
                    !lines[index].trim().startsWith(SECTION_PREFIX)
                ) {
                    body.add(lines[index])
                    index += 1
                }
                sections[sectionName] = body.joinToString("\n").trim('\n')
            }
            require(index < lines.size) { "$id: '$CASE_END' bulunamadı" }
            index += 1

            cases.add(
                EvalCase(
                    id = id,
                    displayName = fields["display"] ?: "Screenshot_eval.png",
                    ocrText = sections[OCR_SECTION].orEmpty(),
                    layout = OcrLayout.deserialize(sections[LAYOUT_SECTION]),
                    visualLabels = VisualLabels.parse(sections[LABELS_SECTION]),
                    expectedTopic = enumValueOf<ContentTopic>(
                        requireNotNull(fields["topic"]) { "$id: 'topic' alanı zorunlu" },
                    ),
                    expectedIntent = fields["intent"]?.let { enumValueOf<IntentCategory>(it) },
                    expectedSensitive = fields["sensitive"]?.toBooleanStrictOrNull(),
                ),
            )
        }
        return cases
    }

    fun run(cases: List<EvalCase>): List<EvalOutcome> = cases.map { case ->
        val result = IntentClassifier.classify(case.ocrText, case.displayName, case.layout, case.visualLabels)
        EvalOutcome(
            case = case,
            predictedTopic = result.topic,
            predictedIntent = result.intent,
            predictedSensitive = result.sensitive,
            predictedTitle = result.title,
        )
    }

    fun report(name: String, outcomes: List<EvalOutcome>): String = buildString {
        appendLine("==== Eval raporu: $name (${outcomes.size} örnek) ====")
        appendLine(accuracyLine("Konu", outcomes.map { it.topicCorrect }))
        appendLine(accuracyLine("Niyet", outcomes.mapNotNull { it.intentCorrect }))
        appendLine(accuracyLine("Hassasiyet", outcomes.mapNotNull { it.sensitiveCorrect }))

        val confusion = outcomes
            .filterNot { it.topicCorrect }
            .groupingBy { it.case.expectedTopic to it.predictedTopic }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
        if (confusion.isNotEmpty()) {
            appendLine("Konu karışıklıkları (beklenen -> tahmin):")
            confusion.forEach { (pair, count) ->
                appendLine("  ${pair.first.label} -> ${pair.second.label}: $count")
            }
        }

        val failures = outcomes.filter {
            !it.topicCorrect || it.intentCorrect == false || it.sensitiveCorrect == false
        }
        if (failures.isNotEmpty()) {
            appendLine("Hatalı örnekler:")
            failures.forEach { outcome ->
                appendLine(
                    "  ${outcome.case.id}: beklenen ${outcome.case.expectedTopic.label}" +
                        (outcome.case.expectedIntent?.let { "/${it.label}" } ?: "") +
                        " — tahmin ${outcome.predictedTopic.label}/${outcome.predictedIntent.label}" +
                        " — başlık '${outcome.predictedTitle}'",
                )
            }
        }
    }

    private fun accuracyLine(dimension: String, results: List<Boolean>): String {
        if (results.isEmpty()) return "$dimension: etiketli örnek yok"
        val correct = results.count { it }
        val percent = correct * 100.0 / results.size
        return "$dimension: $correct/${results.size} (${"%.1f".format(percent)}%)"
    }
}
