package com.screenshotsearch.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.screenshotsearch.app.data.ContentTopic
import com.screenshotsearch.app.data.IntentCategory
import com.screenshotsearch.app.data.ScreenshotRepository
import com.screenshotsearch.app.data.ScreenshotStatus
import com.screenshotsearch.app.permissions.PhotoAccess
import com.screenshotsearch.app.permissions.PhotoAccessLevel
import com.screenshotsearch.app.reminder.ReminderPreferences
import com.screenshotsearch.app.reminder.ReminderScheduler
import com.screenshotsearch.app.search.ScreenshotQueryParser
import com.screenshotsearch.app.search.SearchSpec
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ScreenshotSearchViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ScreenshotRepository(application)
    private val queryParser = ScreenshotQueryParser()
    private val reminderPreferences = ReminderPreferences(application)
    private var loadGeneration = 0L
    private var searchJob: Job? = null
    private var dataReloadJob: Job? = null

    var uiState by mutableStateOf(
        ScreenshotSearchUiState(
            accessLevel = PhotoAccess.current(application),
            weeklyReminderEnabled = reminderPreferences.enabled,
            reminderRoundIds = reminderPreferences.lastRoundIds,
            reminderRoundAt = reminderPreferences.lastRoundAt,
        ),
    )
        private set

    init {
        repository.onDataChanged = ::scheduleReload
        repository.onBusyChanged = { isBusy -> uiState = uiState.copy(isBusy = isBusy) }
        repository.onError = { message -> uiState = uiState.copy(errorMessage = message) }
        repository.onDiagnosticsChanged = { counts -> uiState = uiState.copy(diagnostics = counts) }
        if (uiState.accessLevel != PhotoAccessLevel.NONE) {
            repository.setAccess(uiState.accessLevel)
        } else {
            refreshDiagnostics()
        }
        ReminderScheduler.sync(application, reminderPreferences.enabled)
    }

    fun onAppResumed() {
        val accessLevel = PhotoAccess.current(getApplication())
        // Bildirim turu arka planda degismis olabilir; kullanici bildirime dokunarak
        // geldiyse ana ekran o turu gosterebilsin diye her donuste tazeleniyor.
        uiState = uiState.copy(
            accessLevel = accessLevel,
            reminderRoundIds = reminderPreferences.lastRoundIds,
            reminderRoundAt = reminderPreferences.lastRoundAt,
        )
        repository.setAccess(accessLevel)
        if (accessLevel == PhotoAccessLevel.NONE) reload()
    }

    fun refresh() {
        if (uiState.accessLevel != PhotoAccessLevel.NONE) {
            repository.refresh(retryFailedOcr = true)
        }
    }

    fun selectTab(tab: MainTab) {
        uiState = uiState.copy(selectedTab = tab, selectedItem = null, selectedTopic = null)
        if (tab == MainTab.SEARCH) reload()
    }

    fun updateQuery(query: String) {
        val searchIsVisible = uiState.selectedTab == MainTab.SEARCH
        uiState = uiState.copy(
            query = query,
            selectedTopic = null,
            isLoadingPage = searchIsVisible,
        )
        if (!searchIsVisible) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            reload()
        }
    }

    /** Ana ekrandaki gerçek arama alanından klavye araması veya ok düğmesiyle gelir. */
    fun submitSearch() {
        uiState = uiState.copy(
            selectedTab = MainTab.SEARCH,
            selectedTopic = null,
            selectedItem = null,
        )
        reload()
    }

    fun selectTopic(topic: ContentTopic) {
        uiState = uiState.copy(
            selectedTab = MainTab.SEARCH,
            selectedTopic = topic,
            query = "",
            selectedItem = null,
        )
        reload()
    }

    fun clearTopic() {
        uiState = uiState.copy(selectedTopic = null, query = "")
        reload()
    }

    fun selectItem(itemId: Long) {
        val selected = uiState.homeItems.firstOrNull { it.id == itemId }
            ?: uiState.visibleItems.firstOrNull { it.id == itemId }
        uiState = uiState.copy(selectedItem = selected)
    }

    fun closeDetail() {
        uiState = uiState.copy(selectedItem = null)
    }

    fun updateStatus(status: ScreenshotStatus) {
        val selected = uiState.selectedItem ?: return
        uiState = uiState.copy(selectedItem = selected.copy(status = status))
        repository.updateStatus(selected.id, status)
    }

    fun updateIntent(intent: IntentCategory) {
        val selected = uiState.selectedItem ?: return
        uiState = uiState.copy(
            selectedItem = selected.copy(intent = intent, userCorrected = true),
        )
        repository.updateIntent(selected.id, intent)
    }

    fun updateTopic(topic: ContentTopic) {
        val selected = uiState.selectedItem ?: return
        uiState = uiState.copy(
            selectedItem = selected.copy(topic = topic, userTopicCorrected = true),
        )
        repository.updateTopic(selected.id, topic)
    }

    fun quickUpdateTopic(itemId: Long, topic: ContentTopic) {
        val corrected = uiState.withCorrectedTopic(itemId, topic)
        if (corrected === uiState) return
        uiState = corrected
        repository.updateTopic(itemId, topic)
    }

    fun setWeeklyReminderEnabled(enabled: Boolean) {
        reminderPreferences.enabled = enabled
        ReminderScheduler.sync(getApplication(), enabled)
        uiState = uiState.copy(weeklyReminderEnabled = enabled)
    }

    fun dismissError() {
        uiState = uiState.copy(errorMessage = null)
    }

    /**
     * Teşhis sayaçlarını tazeler.
     *
     * Bilinçli olarak [reload] içinde DEĞİL. İşleme turu tamamlandığında repository tek bir
     * aggregate sorguyla [ScreenshotSearchUiState.diagnostics] alanını ayrıca günceller.
     */
    private fun refreshDiagnostics() {
        repository.diagnostics { counts -> uiState = uiState.copy(diagnostics = counts) }
    }

    fun loadMore() {
        val cursor = uiState.nextPageCursor ?: return
        if (uiState.isLoadingPage) return
        val generation = loadGeneration
        val spec = currentSearchSpec()
        uiState = uiState.copy(isLoadingPage = true)
        repository.loadNext(spec, cursor) { page ->
            if (generation != loadGeneration) return@loadNext
            uiState = uiState.copy(
                visibleItems = (uiState.visibleItems + page.items).distinctBy { it.id },
                nextPageCursor = page.nextCursor,
                resultCount = page.totalCount.takeIf { it > 0 } ?: uiState.resultCount,
                isLoadingPage = false,
            )
        }
    }

    private fun scheduleReload() {
        dataReloadJob?.cancel()
        dataReloadJob = viewModelScope.launch {
            // OCR turunda her kayit ayri callback uretir. Kisa debounce, 1000 kayitta
            // yuzlerce ayni DB sayfasinin kuyruga girmesini engeller.
            delay(DATA_RELOAD_DEBOUNCE_MS)
            reload()
        }
    }

    private fun reload() {
        searchJob?.cancel()
        val generation = ++loadGeneration
        val spec = currentSearchSpec()
        uiState = uiState.copy(isLoadingPage = true)
        repository.loadInitial(
            spec = spec,
            reminderRoundIds = uiState.reminderRoundIds,
            nowMillis = System.currentTimeMillis(),
        ) { loaded ->
            if (generation != loadGeneration) return@loadInitial
            val selectedId = uiState.selectedItem?.id
            val updatedSelected = selectedId?.let { id ->
                loaded.homeItems.firstOrNull { it.id == id }
                    ?: loaded.page.items.firstOrNull { it.id == id }
            }
            uiState = uiState.copy(
                librarySummary = loaded.summary,
                homeItems = loaded.homeItems,
                visibleItems = loaded.page.items,
                nextPageCursor = loaded.page.nextCursor,
                resultCount = loaded.page.totalCount,
                selectedItem = updatedSelected,
                isLoadingPage = false,
            )
        }
    }

    private fun currentSearchSpec(): SearchSpec? = when {
        uiState.selectedTopic != null -> SearchSpec(
            originalQuery = uiState.selectedTopic?.label.orEmpty(),
            topic = uiState.selectedTopic,
        )
        uiState.query.isBlank() -> null
        else -> queryParser.parse(uiState.query)
    }

    override fun onCleared() {
        searchJob?.cancel()
        dataReloadJob?.cancel()
        repository.close()
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 220L
        const val DATA_RELOAD_DEBOUNCE_MS = 120L
    }
}
