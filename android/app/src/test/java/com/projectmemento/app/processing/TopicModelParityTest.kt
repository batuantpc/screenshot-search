package com.projectmemento.app.processing

import com.projectmemento.app.eval.EvalHarness
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Egitim/cikarim paritesi testi.
 *
 * En sinsi hata sinifi: modelin cihazda, egitildiginden FARKLI bir ozellik uzayinda
 * calismasi (train/serve skew). Python `TfidfVectorizer(analyzer="char_wb")` ile
 * Kotlin [TopicModel.ngrams] birebir ayni n-gram'lari uretmezse model sessizce bozulur
 * ve bunu hicbir dogruluk raporu ele vermez.
 *
 * Bu test model meta dosyasındaki yalnız eğitim dataset'lerinde koşar. Holdout/test dosyalarını
 * parite hesabına katmak hem yanlış bir beklenti üretir hem de eğitim sızıntısını normalleştirirdi.
 *
 * Model yalnız yerel benchmark kaynağıdır; APK'ye/Git'e paketlenmez. Özel eval verisi de
 * Git'e girmez. Artefakt yoksa test bilinçli olarak atlanır.
 */
class TopicModelParityTest {

    @Test
    fun `kotlin featurizer python ile ayni sonucu uretir`() {
        val modelFile = resolve("android/app/src/test/resources/models/topic-model-v1.bin")
        val metaFile = resolve("android/app/src/test/resources/models/topic-model-v1.meta.json")
        val evalDir = resolve(".private-data/eval")
        assumeTrue(
            "Model, meta veya eval verisi yok, test atlandi",
            modelFile != null && metaFile != null && evalDir != null,
        )

        val model = modelFile!!.inputStream().use(TopicModel::fromStream)
        assertTrue("Model yuklenemedi", model != null)

        val trainingDatasets = parseTrainingDatasets(metaFile!!.readText())
        assumeTrue("Meta dosyasinda training_datasets yok", trainingDatasets.isNotEmpty())
        var total = 0
        var correct = 0
        evalDir!!.listFiles { file ->
            file.extension == "eval" && file.nameWithoutExtension in trainingDatasets
        }.orEmpty().sorted().forEach { file ->
            EvalHarness.parse(file.readText()).forEach { case ->
                val prediction = model!!.predict(case.ocrText) ?: return@forEach
                total += 1
                if (prediction.topic == case.expectedTopic) correct += 1
            }
        }

        assumeTrue("Eval ornegi bulunamadi", total > 0)
        val accuracy = 100.0 * correct / total
        println("TopicModel parite: $correct/$total = %.2f%%".format(accuracy))

        // Bu bir genelleme ölçümü değil, yalnız train/serve parity kontrolüdür.
        assertTrue(
            "Parite kaybi: $correct/$total = %.2f%% (beklenen >=%%95). ".format(accuracy) +
                "Kotlin n-gram uretimi sklearn char_wb ile uyusmuyor olabilir.",
            accuracy >= 95.0,
        )
    }

    private fun parseTrainingDatasets(meta: String): Set<String> {
        val body = Regex(
            """"training_datasets"\s*:\s*\{(.*?)}""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(meta)?.groupValues?.get(1).orEmpty()
        return Regex(""""([^"]+)"\s*:\s*\d+""")
            .findAll(body)
            .map { it.groupValues[1] }
            .toSet()
    }

    /** Test calisma dizini android/app; repo kokune dogru yukari yurur. */
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
