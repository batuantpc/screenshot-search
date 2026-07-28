package com.projectmemento.app.processing

/** ML Kit Image Labeling'den gelen tek bir görsel etiket. */
data class VisualLabel(
    val name: String,
    val confidence: Float,
)

/**
 * Görsel etiketlerin veritabanı ve eval dosyası formatı.
 * Satır başına `Ad|guven`; hiç etiket bulunamadıysa `-` sentineli yazılır ki
 * "henüz etiketlenmedi" ('') durumundan ayırt edilebilsin.
 */
object VisualLabels {
    const val NONE = "-"
    private const val SEPARATOR = '|'

    fun serialize(labels: List<VisualLabel>): String =
        if (labels.isEmpty()) {
            NONE
        } else {
            labels.joinToString("\n") { label ->
                "${label.name.replace(SEPARATOR, ' ').replace('\n', ' ')}$SEPARATOR${label.confidence}"
            }
        }

    fun parse(value: String?): List<VisualLabel> {
        if (value.isNullOrBlank() || value.trim() == NONE) return emptyList()
        return value.lineSequence().mapNotNull { line ->
            val separator = line.lastIndexOf(SEPARATOR)
            if (separator <= 0) return@mapNotNull null
            val confidence = line.substring(separator + 1).toFloatOrNull() ?: return@mapNotNull null
            VisualLabel(name = line.substring(0, separator).trim(), confidence = confidence)
        }.toList()
    }
}
