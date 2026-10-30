package com.screenshotsearch.app.ui

import com.screenshotsearch.app.data.ContentTopic
import com.screenshotsearch.app.data.LibrarySummary
import com.screenshotsearch.app.data.ScreenshotCursor
import com.screenshotsearch.app.data.ScreenshotItem
import com.screenshotsearch.app.permissions.PhotoAccessLevel

enum class MainTab {
    HOME,
    SEARCH,
}

data class ScreenshotSearchUiState(
    val accessLevel: PhotoAccessLevel = PhotoAccessLevel.NONE,
    val isBusy: Boolean = false,
    val isLoadingPage: Boolean = false,
    val librarySummary: LibrarySummary = LibrarySummary(),
    /** Ana ekran için küçük set: son 6 + hatırlatma adayları. Tüm kütüphane belleğe alınmaz. */
    val homeItems: List<ScreenshotItem> = emptyList(),
    val visibleItems: List<ScreenshotItem> = emptyList(),
    val nextPageCursor: ScreenshotCursor? = null,
    val resultCount: Int = 0,
    val selectedItem: ScreenshotItem? = null,
    val selectedTab: MainTab = MainTab.HOME,
    val selectedTopic: ContentTopic? = null,
    val weeklyReminderEnabled: Boolean = false,
    // Son bildirim turunda secilen kayitlar; ana ekran bildirimin vaadiyle ayni seti
    // gostersin diye tasiniyor.
    val reminderRoundIds: List<Long> = emptyList(),
    val reminderRoundAt: Long = 0L,
    val query: String = "",
    val errorMessage: String? = null,
    /**
     * Teşhis sayaçları (yalnızca sayı, hiç içerik yok).
     *
     * Geri bildirim taslağına eklenir: sahada "kayıt neden boş / neden yanlış kategoride?"
     * sorusu ancak bu sayılarla cevaplanabiliyor — cihaza fiziksel erişim olmadan.
     */
    val diagnostics: Map<String, Int> = emptyMap(),
)

/**
 * Liste üzerindeki kategori düzeltmesini tüm görünür kopyalara tek atomik state değişimiyle
 * uygular. Aynı kayıt ana ekran, arama ve açık detayda bulunabildiği için üç ayrı güncelleme
 * yapılması kısa süreli çelişkili etiketler gösteriyordu.
 */
internal fun ScreenshotSearchUiState.withCorrectedTopic(
    itemId: Long,
    topic: ContentTopic,
): ScreenshotSearchUiState {
    val current = visibleItems.firstOrNull { it.id == itemId }
        ?: homeItems.firstOrNull { it.id == itemId }
        ?: selectedItem?.takeIf { it.id == itemId }
        ?: return this
    if (current.topic == topic) return this

    fun ScreenshotItem.corrected() =
        if (id == itemId) copy(topic = topic, userTopicCorrected = true) else this

    return copy(
        visibleItems = visibleItems.map(ScreenshotItem::corrected),
        homeItems = homeItems.map(ScreenshotItem::corrected),
        selectedItem = selectedItem?.corrected(),
    )
}

/** Menüler ve öneriler yalnızca gerçekten oluşmuş koleksiyonları paylaşır. */
internal fun activeTopicCounts(items: List<ScreenshotItem>): List<Pair<ContentTopic, Int>> =
    ContentTopic.entries
        .filterNot { topic -> topic == ContentTopic.OTHER }
        .map { topic -> topic to items.count { item -> item.processed && item.topic == topic } }
        .filter { (_, count) -> count > 0 }
        .sortedWith(
            compareByDescending<Pair<ContentTopic, Int>> { (_, count) -> count }
                .thenBy { (topic, _) -> topic.ordinal },
        )

internal fun activeTopicCounts(counts: Map<ContentTopic, Int>): List<Pair<ContentTopic, Int>> =
    counts
        .filter { (topic, count) -> topic != ContentTopic.OTHER && count > 0 }
        .toList()
        .sortedWith(
            compareByDescending<Pair<ContentTopic, Int>> { (_, count) -> count }
                .thenBy { (topic, _) -> topic.ordinal },
        )
