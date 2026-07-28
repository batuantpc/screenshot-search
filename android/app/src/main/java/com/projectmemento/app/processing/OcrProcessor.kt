package com.projectmemento.app.processing

import android.content.Context
import androidx.core.net.toUri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** Düz OCR metni ve yerleşim bilgisini birlikte taşıyan işleme sonucu. */
data class OcrCapture(
    val text: String,
    val layout: OcrLayout,
)

/**
 * OCR işleyicisi.
 *
 * Hatanın kalıcı mı geçici mi olduğuna BURADA karar verilmez; çağıran taraf
 * [com.projectmemento.app.media.MediaFailure.classify] ile ölçer. Sebep: aynı ayrım görsel
 * etiketleme kuyruğunda da gerekiyor ve exception mesajına bakmak kırılgan.
 */
class OcrProcessor(
    private val context: Context,
) : AutoCloseable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun process(
        contentUri: String,
        onComplete: (Result<OcrCapture>) -> Unit,
    ) {
        val image = runCatching {
            InputImage.fromFilePath(context, contentUri.toUri())
        }.getOrElse { error ->
            onComplete(Result.failure(error))
            return
        }

        recognizer.process(image)
            .addOnSuccessListener { result ->
                onComplete(Result.success(result.toCapture(image.width, image.height)))
            }
            .addOnFailureListener { error -> onComplete(Result.failure(error)) }
    }

    private fun Text.toCapture(imageWidth: Int, imageHeight: Int): OcrCapture {
        val lines = buildList {
            textBlocks.forEachIndexed { blockIndex, block ->
                block.lines.forEach { line ->
                    val box = line.boundingBox ?: return@forEach
                    add(
                        OcrLine(
                            block = blockIndex,
                            text = line.text,
                            left = box.left,
                            top = box.top,
                            right = box.right,
                            bottom = box.bottom,
                        ),
                    )
                }
            }
        }
        return OcrCapture(
            text = text.orEmpty(),
            layout = OcrLayout(imageWidth = imageWidth, imageHeight = imageHeight, lines = lines),
        )
    }

    override fun close() {
        recognizer.close()
    }
}
