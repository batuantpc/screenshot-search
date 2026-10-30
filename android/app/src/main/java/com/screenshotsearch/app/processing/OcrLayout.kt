package com.screenshotsearch.app.processing

/**
 * ML Kit'ten gelen tek bir OCR satırı ve görüntü üzerindeki konumu.
 * Koordinatlar piksel cinsindendir; block aynı metin bloğundaki satırları gruplar.
 */
data class OcrLine(
    val block: Int,
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * Bir screenshot'ın yerleşim bilgisi taşıyan OCR sonucu.
 *
 * Düz metinden farkı: satırların ekran bölgesi (status bar / navigasyon çubuğu)
 * ve punto büyüklüğü (kutu yüksekliği) bilinir. Sınıflandırma bu sayede sistem
 * arayüzü gürültüsünü dışlar, başlık seçimi görsel hiyerarşiyi kullanır.
 */
data class OcrLayout(
    val imageWidth: Int,
    val imageHeight: Int,
    val lines: List<OcrLine>,
) {
    /** Status bar ve navigasyon bölgeleri dışında kalan satırlar, okuma sırasında. */
    fun contentLines(): List<OcrLine> {
        if (imageHeight <= 0) return lines
        val statusBarLimit = imageHeight * STATUS_BAR_ZONE
        val navigationLimit = imageHeight * (1f - NAVIGATION_ZONE)
        return lines.filter { line ->
            line.text.isNotBlank() && line.centerY > statusBarLimit && line.centerY < navigationLimit
        }
    }

    /** Sınıflandırmaya verilecek, sistem arayüzünden arındırılmış metin. */
    fun contentText(): String = contentLines().joinToString("\n") { it.text }

    /**
     * Görsel hiyerarşiye göre sıralanmış başlık adayları.
     * Aynı bloktaki benzer puntolu ardışık satırlar tek adayda birleştirilir;
     * adaylar punto büyüklüğü ve ekrandaki dikey konuma göre puanlanır.
     */
    fun titleCandidates(): List<String> {
        val content = contentLines().filter { it.height > 0 }
        if (content.isEmpty()) return emptyList()

        val heights = content.map { it.height }.sorted()
        val medianHeight = heights[heights.size / 2].toFloat().coerceAtLeast(1f)

        val groups = mutableListOf<MutableList<OcrLine>>()
        content.forEach { line ->
            val current = groups.lastOrNull()
            val previous = current?.lastOrNull()
            val mergeable = previous != null &&
                previous.block == line.block &&
                heightRatio(previous.height, line.height) <= MERGE_HEIGHT_RATIO &&
                (line.top - previous.bottom) <= previous.height * MERGE_GAP_FACTOR &&
                current.sumOf { it.text.length } + line.text.length <= MERGE_LENGTH_LIMIT
            if (mergeable) current.add(line) else groups.add(mutableListOf(line))
        }

        return groups
            .map { group -> group to scoreGroup(group, medianHeight) }
            .sortedByDescending { (_, score) -> score }
            .map { (group, _) -> group.joinToString(" ") { it.text.trim() } }
    }

    private fun scoreGroup(group: List<OcrLine>, medianHeight: Float): Float {
        val tallest = group.maxOf { it.height }.toFloat()
        val relativeSize = (tallest / medianHeight).coerceIn(0.4f, 3.5f)
        val verticalRatio = if (imageHeight > 0) group.first().centerY / imageHeight else 0.5f
        val positionBonus = when {
            verticalRatio < CHROME_ZONE -> -0.5f
            verticalRatio <= PRIME_ZONE_END -> 0.6f
            verticalRatio > LOW_ZONE_START -> -0.4f
            else -> 0f
        }
        return relativeSize + positionBonus
    }

    private fun heightRatio(first: Int, second: Int): Float {
        val high = maxOf(first, second).toFloat()
        val low = minOf(first, second).coerceAtLeast(1).toFloat()
        return high / low
    }

    fun serialize(): String = buildString {
        append(FORMAT_VERSION).append(SEPARATOR).append(imageWidth).append(SEPARATOR).append(imageHeight)
        lines.forEach { line ->
            append('\n')
            append(line.block).append(SEPARATOR)
            append(line.left).append(SEPARATOR)
            append(line.top).append(SEPARATOR)
            append(line.right).append(SEPARATOR)
            append(line.bottom).append(SEPARATOR)
            append(line.text.replace('\n', ' '))
        }
    }

    companion object {
        private const val FORMAT_VERSION = "1"
        private const val SEPARATOR = '|'
        private const val STATUS_BAR_ZONE = 0.045f
        private const val NAVIGATION_ZONE = 0.06f
        private const val CHROME_ZONE = 0.06f
        private const val PRIME_ZONE_END = 0.62f
        private const val LOW_ZONE_START = 0.80f
        private const val MERGE_HEIGHT_RATIO = 1.4f
        private const val MERGE_GAP_FACTOR = 0.9f
        private const val MERGE_LENGTH_LIMIT = 110

        fun deserialize(value: String?): OcrLayout? {
            if (value.isNullOrBlank()) return null
            val rows = value.lineSequence().iterator()
            val header = rows.next().split(SEPARATOR)
            if (header.size != 3 || header[0] != FORMAT_VERSION) return null

            val width = header[1].toIntOrNull() ?: return null
            val height = header[2].toIntOrNull() ?: return null
            val lines = buildList {
                rows.forEachRemaining { row ->
                    val fields = row.split(SEPARATOR, limit = 6)
                    if (fields.size != 6) return@forEachRemaining
                    val numbers = fields.take(5).map { it.toIntOrNull() ?: return@forEachRemaining }
                    add(
                        OcrLine(
                            block = numbers[0],
                            left = numbers[1],
                            top = numbers[2],
                            right = numbers[3],
                            bottom = numbers[4],
                            text = fields[5],
                        ),
                    )
                }
            }
            return OcrLayout(imageWidth = width, imageHeight = height, lines = lines)
        }
    }
}
