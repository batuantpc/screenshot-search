package com.screenshotsearch.app.processing

import android.content.Context
import androidx.core.net.toUri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions

/**
 * Cihaz üzerinde çalışan bundled ML Kit görsel etiketleyici.
 * OCR'ın kör olduğu, üzerinde yazı taşımayan görseller için ikinci sinyal üretir.
 */
class ImageLabelProcessor(
    private val context: Context,
) : AutoCloseable {
    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(MIN_CONFIDENCE)
            .build(),
    )

    fun process(
        contentUri: String,
        onComplete: (Result<List<VisualLabel>>) -> Unit,
    ) {
        val image = runCatching {
            InputImage.fromFilePath(context, contentUri.toUri())
        }.getOrElse { error ->
            onComplete(Result.failure(error))
            return
        }

        labeler.process(image)
            .addOnSuccessListener { labels ->
                onComplete(
                    Result.success(
                        labels
                            .sortedByDescending { it.confidence }
                            .take(MAX_LABELS)
                            .map { VisualLabel(name = it.text, confidence = it.confidence) },
                    ),
                )
            }
            .addOnFailureListener { error -> onComplete(Result.failure(error)) }
    }

    override fun close() {
        labeler.close()
    }

    private companion object {
        const val MIN_CONFIDENCE = 0.55f
        const val MAX_LABELS = 10
    }
}
