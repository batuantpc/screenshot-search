package com.screenshotsearch.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.screenshotsearch.app.data.ContentTopic
import com.screenshotsearch.app.data.ScreenshotItem

@Composable
internal fun SearchScreen(
    query: String,
    items: List<ScreenshotItem>,
    topicCounts: Map<ContentTopic, Int>,
    resultCount: Int,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    selectedTopic: ContentTopic?,
    onClearTopic: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSelectTopic: (ContentTopic) -> Unit,
    onSelectItem: (Long) -> Unit,
    onQuickUpdateTopic: (Long, ContentTopic) -> Unit,
    onLoadMore: () -> Unit,
) {
    val suggestedCollections = remember(topicCounts) {
        activeTopicCounts(topicCounts).take(5)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Yazmaya başlayınca tanıtım kartı gizlenir: klavye açıkken dikey alan kısıtlı
        // ve kullanıcının tek derdi sonuçları görmek. Boş sorguda kart geri gelir.
        if (selectedTopic != null || query.isBlank()) {
            item(key = "search-context") {
                if (selectedTopic == null) {
                    SearchIntroCard()
                } else {
                    SearchTopicHeader(topic = selectedTopic, onClearTopic = onClearTopic)
                }
            }
        }
        // Üstteki tanıtım kartı ilk harfte kaybolur. Sabit key olmazsa arama alanı yeni bir
        // LazyColumn öğesi sanılıp yeniden kurulur ve klavye odağı ilk harften sonra düşer.
        item(key = "search-field") {
            GlassSearchField(
                value = query,
                onValueChange = onQueryChange,
            )
        }
        if (selectedTopic == null && query.isBlank() && suggestedCollections.isNotEmpty()) {
            item(key = "search-suggestions") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column {
                        Text(
                            "Önerilen koleksiyonlar",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Yalnız açık kanıt bulunan etiketler.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(suggestedCollections, key = { (topic, _) -> topic.name }) { (topic, count) ->
                            SearchCollectionCard(
                                topic = topic,
                                count = count,
                                onClick = { onSelectTopic(topic) },
                            )
                        }
                    }
                }
            }
        }
        item(key = "search-results-header") {
            SearchResultsHeader(
                title = when {
                    selectedTopic != null -> selectedTopic.label
                    query.isBlank() -> "Son eklenenler"
                    else -> "Arama sonuçları"
                },
                count = resultCount,
            )
        }
        if (items.isEmpty()) {
            item(key = "search-empty") {
                EmptySearchCard(query = query)
            }
        } else {
            items(items, key = ScreenshotItem::id) { item ->
                ScreenshotRow(
                    item = item,
                    onClick = { onSelectItem(item.id) },
                    onCorrectTopic = { topic -> onQuickUpdateTopic(item.id, topic) },
                    matchQuery = query.takeIf(String::isNotBlank),
                )
            }
            if (hasMore || isLoadingMore) {
                item(key = "load-more") {
                    OutlinedButton(
                        onClick = onLoadMore,
                        enabled = hasMore && !isLoadingMore,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (isLoadingMore) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Yükleniyor")
                        } else {
                            Text("Daha fazla göster")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchIntroCard() {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(12.dp, shape)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f),
                        MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ),
            )
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f), shape),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 35.dp, y = (-45).dp)
                .size(150.dp)
                .background(
                    Brush.radialGradient(
                        listOf(
                            Color(0xFF59C8FF).copy(alpha = 0.34f),
                            Color.Transparent,
                        ),
                    ),
                    CircleShape,
                ),
        )
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "SCREENSHOT ARAMA",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Bir parçasını hatırlaman yeter.",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Arama kategoriye değil, screenshot’tan okunan gerçek metne bakar.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GlassSearchField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(12.dp, shape)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f),
                        MaterialTheme.colorScheme.surface,
                    ),
                ),
            )
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.42f), shape)
            .padding(horizontal = 17.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Box(modifier = Modifier.weight(1f)) {
            if (value.isBlank()) {
                Text(
                    "İsim, cümle, mekan veya tarih yaz",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun SearchCollectionCard(
    topic: ContentTopic,
    count: Int,
    onClick: () -> Unit,
) {
    val accent = topicAccent(topic)
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .width(142.dp)
            .shadow(7.dp, shape)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.32f),
                        MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ),
            )
            .border(1.dp, accent.copy(alpha = 0.42f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(topicEmoji(topic), fontSize = 22.sp)
        Column {
            Text(
                topic.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "$count kayıt",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SearchResultsHeader(title: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
        ) {
            Text(
                count.toString(),
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun EmptySearchCard(query: String) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.colorScheme.surface,
                    ),
                ),
            )
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.26f), shape)
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🔎", fontSize = 34.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            if (query.isBlank()) "Henüz gösterilecek kayıt yok" else "Bunu bulamadık",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            if (query.isBlank()) {
                "Yeni screenshotlar işlendiğinde burada görünür."
            } else {
                "Daha kısa veya ayırt edici tek bir kelime deneyebilirsin."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SearchTopicHeader(
    topic: ContentTopic,
    onClearTopic: () -> Unit,
) {
    val accent = topicAccent(topic)
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(12.dp, shape)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.48f),
                        MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ),
            )
            .border(1.dp, accent.copy(alpha = 0.42f), shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .background(Color.White.copy(alpha = 0.11f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(topicEmoji(topic), fontSize = 32.sp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    topic.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Bu etikete sahip kayıtlar. Etiketler yardımcıdır; arama OCR metnini tarar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onClearTopic) {
                Text("Tümü", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun ScreenshotRow(
    item: ScreenshotItem,
    onClick: () -> Unit,
    onCorrectTopic: ((ContentTopic) -> Unit)? = null,
    matchQuery: String? = null,
) {
    val accent = if (item.topic == ContentTopic.OTHER) {
        MaterialTheme.colorScheme.primary
    } else {
        topicAccent(item.topic)
    }
    val shape = RoundedCornerShape(22.dp)
    var showTopicMenu by rememberSaveable(item.id) { mutableStateOf(false) }
    val matchExplanation = remember(item.ocrText, matchQuery, item.sensitive) {
        matchQuery?.let { query ->
            searchMatchExplanation(
                ocrText = item.ocrText,
                query = query,
                sensitiveHidden = item.sensitive,
            )
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (matchQuery != null) 138.dp else 122.dp)
                .offset(x = 5.dp, y = 7.dp)
                .clip(shape)
                .background(accent.copy(alpha = 0.23f)),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (matchQuery != null) 138.dp else 122.dp)
                .shadow(9.dp, shape)
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.surface,
                        ),
                    ),
                )
                .border(1.dp, accent.copy(alpha = 0.34f), shape)
                .clickable(onClick = onClick)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScreenshotThumbnail(
                contentUri = item.contentUri,
                modifier = Modifier
                    .size(width = 76.dp, height = 92.dp)
                    .clip(RoundedCornerShape(17.dp)),
                obscured = item.sensitive,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    screenshotDisplayTitle(
                        title = item.title,
                        processed = item.processed,
                        sensitiveHidden = item.sensitive,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        TopicBadge(
                            topic = item.topic,
                            userCorrected = item.userTopicCorrected,
                            onClick = onCorrectTopic?.takeIf { item.processed }?.let {
                                { showTopicMenu = true }
                            },
                        )
                        DropdownMenu(
                            expanded = showTopicMenu,
                            onDismissRequest = { showTopicMenu = false },
                        ) {
                            ContentTopic.entries.forEach { topic ->
                                DropdownMenuItem(
                                    text = { Text("${topicEmoji(topic)}  ${topic.label}") },
                                    onClick = {
                                        showTopicMenu = false
                                        onCorrectTopic?.invoke(topic)
                                    },
                                )
                            }
                        }
                    }
                    Text(
                        listOfNotNull(
                            item.sourceHint?.let { source -> "↗ $source" },
                            formatDate(item.capturedAt),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (matchQuery != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        matchExplanation.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = accent,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            if (item.topic != ContentTopic.OTHER) {
                Text(
                    topicEmoji(item.topic),
                    fontSize = 21.sp,
                )
            }
        }
    }
}

@Composable
private fun TopicBadge(
    topic: ContentTopic,
    userCorrected: Boolean,
    onClick: (() -> Unit)? = null,
) {
    val accent = topicAccent(topic)
    Surface(
        modifier = if (onClick != null) {
            Modifier
                .minimumInteractiveComponentSize()
                .semantics {
                    contentDescription = if (topic == ContentTopic.OTHER) {
                        "Konu etiketi eklemek için dokun."
                    } else {
                        "${topic.label} etiketi. Değiştirmek için dokun."
                    }
                    role = Role.Button
                }
                .clickable(onClick = onClick)
        } else {
            Modifier
        },
        shape = RoundedCornerShape(8.dp),
        color = accent.copy(alpha = 0.14f),
    ) {
        Text(
            when {
                topic == ContentTopic.OTHER -> if (onClick != null) "Etiket ekle  +" else "Etiketsiz"
                userCorrected -> if (onClick != null) "${topic.label}  ▾" else topic.label
                else -> if (onClick != null) "${topic.label} önerisi  ▾" else "${topic.label} önerisi"
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontWeight = FontWeight.Bold,
        )
    }
}
