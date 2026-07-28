package com.projectmemento.app.data

enum class IntentCategory(val label: String) {
    GO("Git"),
    TRY("Dene"),
    BUY("Al / Bak"),
    READ_WATCH("Oku / İzle"),
    REMEMBER("Hatırla"),
    UNCLEAR("Belirsiz"),
}

enum class ContentTopic(val label: String) {
    RECIPE("Tarif"),
    MOVIE("Film"),
    SERIES("Dizi"),
    NEWS("Haber"),
    POLITICS("Siyaset"),
    FINANCE("Finans"),
    PLACE("Mekan"),
    PRODUCT("Ürün"),
    SPORT("Spor"),
    GAME("Oyun"),
    AI("Yapay Zeka"),
    SCIENCE("Bilim"),
    BOOK("Kitap"),
    MUSIC("Müzik"),
    VIDEO("Video"),
    SOCIAL("Sosyal"),
    OTHER("Kategorisiz"),
}

enum class ScreenshotStatus(val label: String) {
    NEW("Yeni"),
    INTERESTED("İlgileniyorum"),
    LATER("Sonra göster"),
    DONE("Tamamlandı"),
    ARCHIVED("Arşivlendi"),
}

data class ScreenshotItem(
    val id: Long,
    val mediaStoreId: Long,
    val contentUri: String,
    val capturedAt: Long,
    val displayName: String,
    val relativePath: String?,
    val ocrText: String,
    val title: String,
    val intent: IntentCategory,
    val topic: ContentTopic,
    val confidence: Float,
    val status: ScreenshotStatus,
    val processed: Boolean,
    val sensitive: Boolean,
    val userCorrected: Boolean,
    val userTopicCorrected: Boolean,
    val lastSurfacedAt: Long?,
    val sourceHint: String?,
)

/** Keyset pagination cursor. Offset kullanılmaz; araya yeni screenshot girse de sayfa atlamaz. */
data class ScreenshotCursor(
    val capturedAt: Long,
    val id: Long,
)

data class ScreenshotPage(
    val items: List<ScreenshotItem>,
    val nextCursor: ScreenshotCursor?,
    val totalCount: Int,
)

data class LibrarySummary(
    val totalCount: Int = 0,
    val processedCount: Int = 0,
    val pendingCount: Int = 0,
    val topicCounts: Map<ContentTopic, Int> = emptyMap(),
)

data class LibraryLoad(
    val summary: LibrarySummary,
    val homeItems: List<ScreenshotItem>,
    val page: ScreenshotPage,
)

data class DiscoveredScreenshot(
    val mediaStoreId: Long,
    val contentUri: String,
    val capturedAt: Long,
    val displayName: String,
    val relativePath: String?,
)

/** Görsel etiket backfill kuyruğundaki bir kayıt (eski sürümde işlenmiş, etiketi yok). */
data class UnlabeledItem(
    val id: Long,
    val contentUri: String,
    val displayName: String,
    val ocrText: String,
    val ocrLayout: String,
    val userCorrected: Boolean,
    val userTopicCorrected: Boolean,
)

data class ClassificationResult(
    val title: String,
    val intent: IntentCategory,
    val topic: ContentTopic,
    val confidence: Float,
    val sensitive: Boolean,
    val sourceHint: String?,
)
