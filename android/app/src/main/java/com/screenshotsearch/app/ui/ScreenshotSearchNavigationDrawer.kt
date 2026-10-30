package com.screenshotsearch.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.screenshotsearch.app.data.ContentTopic

@Composable
internal fun ScreenshotSearchNavigationDrawerContent(
    state: ScreenshotSearchUiState,
    onClose: () -> Unit,
    onSelectTab: (MainTab) -> Unit,
    onSelectTopic: (ContentTopic) -> Unit,
    onSendFeedback: () -> Unit,
    onSetWeeklyReminderEnabled: (Boolean) -> Unit,
    versionName: String,
) {
    val topicCounts = activeTopicCounts(state.librarySummary.topicCounts)

    ModalDrawerSheet(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(min = 288.dp, max = 328.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, top = 20.dp, end = 12.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "screenshot-search",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${state.librarySummary.processedCount} screenshot aranabilir",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Menüyü kapat")
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    DrawerSectionLabel("Gezin")
                }
                item {
                    NavigationDrawerItem(
                        label = { Text("Screenshot ara") },
                        selected = state.selectedTab == MainTab.SEARCH && state.selectedTopic == null,
                        onClick = { onSelectTab(MainTab.SEARCH) },
                        icon = { Icon(Icons.Default.Search, contentDescription = null) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                item {
                    NavigationDrawerItem(
                        label = { Text("Son eklenenler") },
                        selected = state.selectedTab == MainTab.HOME,
                        onClick = { onSelectTab(MainTab.HOME) },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                if (topicCounts.isNotEmpty()) {
                    item {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
                        DrawerSectionLabel("Önerilen koleksiyonlar")
                    }
                }
                items(topicCounts, key = { (topic, _) -> topic.name }) { (topic, count) ->
                    NavigationDrawerItem(
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(topic.label, modifier = Modifier.weight(1f))
                                Text(
                                    text = count.toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        selected = state.selectedTopic == topic,
                        onClick = { onSelectTopic(topic) },
                        icon = {
                            Text(
                                text = topicEmoji(topic),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }

            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Default.Notifications,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("Haftalık hatırlatma", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Eski kayıtlardan kısa bir seçki",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.weeklyReminderEnabled,
                    onCheckedChange = onSetWeeklyReminderEnabled,
                )
            }
            Surface(
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                shape = RoundedCornerShape(20.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "OCR ve arama telefonunda yapılır",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Kapali test surumu: deneyenlerin geri donus yapabilecegi tek yol bu.
            // Uygulama disari veri gondermedigi icin otomatik hata raporu da yok;
            // geri bildirim tamamen kullanicinin kendi istegiyle olur.
            NavigationDrawerItem(
                label = { Text("Geri bildirim gönder") },
                selected = false,
                onClick = onSendFeedback,
                icon = {
                    Icon(
                        Icons.Default.Email,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                    )
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
            Text(
                "Test sürümü $versionName · sorunları ve fikirlerini yaz",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 28.dp, end = 24.dp, bottom = 14.dp),
            )
        }
    }
}

@Composable
private fun DrawerSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
}
