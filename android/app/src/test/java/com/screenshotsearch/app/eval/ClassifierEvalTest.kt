package com.screenshotsearch.app.eval

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Sınıflandırıcı doğruluğunu ölçen değerlendirme koşusu.
 *
 * - `seed-set.eval` sentetik regresyon setidir, Git'tedir ve %100 kalmak zorundadır.
 * - `.private-data/eval` altındaki .eval dosyaları gerçek cihaz örnekleridir; Git dışıdır, rapor
 *   olarak yazdırılır ama build'i kırmaz. Bağımsız doğruluk ölçümü orada yapılır.
 */
class ClassifierEvalTest {
    @Test
    fun `seed set stays fully correct`() {
        val content = requireNotNull(javaClass.classLoader?.getResource(SEED_RESOURCE)) {
            "$SEED_RESOURCE bulunamadı"
        }.readText()
        val cases = EvalHarness.parse(content)
        assertTrue("Seed set boş olmamalı", cases.isNotEmpty())

        val outcomes = EvalHarness.run(cases)
        println(EvalHarness.report("seed-set", outcomes))

        val failures = outcomes.filter {
            !it.topicCorrect || it.intentCorrect == false || it.sensitiveCorrect == false
        }
        if (failures.isNotEmpty()) {
            fail("Seed set regresyonu: ${failures.joinToString { it.case.id }}")
        }
    }

    @Test
    fun `private device eval sets are reported`() {
        val evalDir = findPrivateEvalDir()
        if (evalDir == null) {
            println("Özel eval seti yok (.private-data/eval). Gerçek cihaz ölçümü için ekleyin.")
            return
        }

        val files = evalDir.listFiles { file -> file.extension == "eval" }.orEmpty().sorted()
        if (files.isEmpty()) {
            println("${evalDir.absolutePath} altında .eval dosyası yok.")
            return
        }

        files.forEach { file ->
            val outcomes = EvalHarness.run(EvalHarness.parse(file.readText()))
            println(EvalHarness.report(file.nameWithoutExtension, outcomes))
        }
    }

    private fun findPrivateEvalDir(): File? {
        System.getenv("SCREENSHOT_SEARCH_EVAL_DIR")?.let { override ->
            return File(override).takeIf(File::isDirectory)
        }
        // Test çalışma dizini android/app olur; repo köküne doğru yukarı yürü.
        var current: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(5) {
            val candidate = current?.resolve(".private-data/eval")
            if (candidate?.isDirectory == true) return candidate
            current = current?.parentFile
        }
        return null
    }

    private companion object {
        const val SEED_RESOURCE = "eval/seed-set.eval"
    }
}
