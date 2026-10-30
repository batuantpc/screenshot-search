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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.screenshotsearch.app.data.ContentTopic
import com.screenshotsearch.app.data.ScreenshotItem
import com.screenshotsearch.app.reminder.ResurfacingPolicy

private const val HOME_RECENT_LIMIT = 6

@Composable
internal fun HomeScreen(
    state: ScreenshotSearchUiState,
    onSelectItem: (Long) -> Unit,
    onSelectTopic: (ContentTopic) -> Unit,
    onOpenSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onQuickUpdateTopic: (Long, ContentTopic) -> Unit,
) {
    if (state.librarySummary.totalCount == 0 && state.isBusy) {
        EmptyLoadingState()
        return
    }

    val processed = state.homeItems.filter(ScreenshotItem::processed)
    val resurfaced = remember(processed, state.reminderRoundIds, state.reminderRoundAt) {
        ResurfacingPolicy.selectForHome(
            items = processed,
            roundIds = state.reminderRoundIds,
            roundAtMillis = state.reminderRoundAt,
            nowMillis = System.currentTimeMillis(),
        )
    }
    val recentItems = remember(state.homeItems) { state.homeItems.take(HOME_RECENT_LIMIT) }
    val suggestedTopicCounts = remember(state.librarySummary.topicCounts) {
        activeTopicCounts(state.librarySummary.topicCounts)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            HomeHero(
                processedCount = state.librarySummary.processedCount,
                pendingCount = state.librarySummary.pendingCount,
                query = state.query,
                onQueryChange = onQueryChange,
                onSubmitSearch = onSubmitSearch,
            )
        }

        item {
            SectionHeader(
                title = "Son eklenenler",
                subtitle = "${state.librarySummary.totalCount} kayıttan en yenileri.",
            )
        }

        if (state.librarySummary.totalCount == 0) {
            item {
                Text(
                    "Erişebildiğimiz screenshot bulunamadı. Kısmi izin verdiysen fotoğraf seçimini genişletebilirsin.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            item {
                RecentGallery(
                    items = recentItems,
                    onSelectItem = onSelectItem,
                )
            }
            if (state.librarySummary.totalCount > HOME_RECENT_LIMIT) {
                item {
                    OutlinedButton(
                        onClick = onOpenSearch,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Tüm screenshotları gör")
                    }
                }
            }
        }

        if (suggestedTopicCounts.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Önerilen koleksiyonlar",
                    subtitle = "Yalnız açık kanıt bulunan otomatik etiketler.",
                )
                TopicSummary(
                    topicCounts = state.librarySummary.topicCounts,
                    onSelectTopic = onSelectTopic,
                )
            }
        }

        if (resurfaced.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Yeniden keşfet",
                    subtitle = "Bir süredir bakmadığın kayıtlardan seçtiklerimiz.",
                )
            }
            items(resurfaced, key = { item -> "resurfaced-${item.id}" }) { item ->
                ScreenshotRow(
                    item = item,
                    onClick = { onSelectItem(item.id) },
                    onCorrectTopic = { topic -> onQuickUpdateTopic(item.id, topic) },
                )
            }
        }
    }
}

@Composable
private fun HomeHero(
    processedCount: Int,
    pendingCount: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
) {
    val shape = RoundedCornerShape(30.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(18.dp, shape)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF2836A5),
                        Color(0xFF166BD0),
                        Color(0xFF42A9C8),
                    ),
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.22f), shape),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 42.dp, y = (-54).dp)
                .size(190.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF8CEBFF).copy(alpha = 0.58f),
                            Color.Transparent,
                        ),
                    ),
                    CircleShape,
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = (-55).dp, y = 76.dp)
                .size(180.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF776BFF).copy(alpha = 0.62f),
                            Color.Transparent,
                        ),
                    ),
                    CircleShape,
                ),
        )
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                "CİHAZINDA ARAMA",
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFFD9E5FF),
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Hatırladığın kadarını yaz.",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = FontFamily.Serif,
                    fontSize = 31.sp,
                ),
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (pendingCount > 0) "$pendingCount screenshot daha hazırlanıyor."
                else "$processedCount screenshot metniyle aranabilir.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.78f),
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(10.dp, RoundedCornerShape(17.dp))
                    .clip(RoundedCornerShape(17.dp))
                    .background(Color(0xFF0B1730).copy(alpha = 0.68f))
                    .border(
                        1.dp,
                        Color.White.copy(alpha = 0.18f),
                        RoundedCornerShape(17.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IconButton(
                    onClick = onSubmitSearch,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Screenshotlarında ara",
                        tint = Color(0xFFC7D7FF),
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isBlank()) {
                        Text(
                            "Bir isim, kelime veya tarih…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.72f),
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Screenshotlarında ara" },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSubmitSearch() }),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyLoadingState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            )
            .padding(32.dp),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(82.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Son screenshotların hazırlanıyor…",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "İlk sonuçlar geldikçe burada görünür.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TopicSummary(
    topicCounts: Map<ContentTopic, Int>,
    onSelectTopic: (ContentTopic) -> Unit,
) {
    val activeTopics = remember(topicCounts) { activeTopicCounts(topicCounts) }
    LazyRow(
        contentPadding = PaddingValues(top = 14.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(activeTopics, key = { it.first.name }) { (topic, count) ->
            QuickTopicCard(
                topic = topic,
                count = count,
                onClick = { onSelectTopic(topic) },
            )
        }
    }
}

@Composable
private fun QuickTopicCard(
    topic: ContentTopic,
    count: Int,
    onClick: () -> Unit,
) {
    val accent = topicAccent(topic)
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .width(106.dp)
            .height(124.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = 5.dp)
                .size(width = 78.dp, height = 22.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.7f), Color.Transparent),
                    ),
                    CircleShape,
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(86.dp)
                .shadow(12.dp, shape)
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.38f),
                            MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ),
                )
                .border(1.dp, accent.copy(alpha = 0.36f), shape),
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-4).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = topicEmoji(topic),
                fontSize = 36.sp,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = topic.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$count kayıt",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecentGallery(
    items: List<ScreenshotItem>,
    onSelectItem: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowItems.forEach { item ->
                    ScreenshotVisualCard(
                        item = item,
                        onClick = { onSelectItem(item.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ScreenshotVisualCard(
    item: ScreenshotItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = topicAccent(item.topic)
    val shape = RoundedCornerShape(24.dp)
    Surface(
        modifier = modifier
            .aspectRatio(0.78f)
            .shadow(12.dp, shape)
            .border(1.dp, accent.copy(alpha = 0.48f), shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ScreenshotThumbnail(
                contentUri = item.contentUri,
                modifier = Modifier.fillMaxSize(),
                // Ana ekran herkesin gözü önünde; hassas kayıt listede asla açık görünmez.
                obscured = item.sensitive,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        // Perde bilerek "fotograf altyazisi" kadar koyu: screenshot'lar
                        // metin dolu oldugu icin zayif bir gradyanda kartin kendi yazisi
                        // basligin arkasindan sizip ikisini birden okunmaz yapiyordu.
                        // Basligin oturdugu alt ucte neredeyse tam opaklik sart.
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.30f to Color.Transparent,
                                0.52f to Color(0xFF090B12).copy(alpha = 0.45f),
                                0.68f to Color(0xFF090B12).copy(alpha = 0.82f),
                                0.80f to Color(0xFF090B12).copy(alpha = 0.94f),
                                1f to Color(0xFF090B12).copy(alpha = 0.99f),
                            ),
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    // Metin blogunun KENDI zemini. Kart geneline yayilan gradyanin dogru
                    // yukseklikte yeterince koyu olmasina guvenmek kirilgandi: iki satirlik
                    // basliklar daha yukaridan basliyor ve orada gradyan henuz seffafken
                    // screenshot'in kendi yazisi basligin arkasindan siziyordu. Zemin blogun
                    // kendi yuksekligine oturunca baslik kac satir olursa olsun okunur kalir.
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF090B12).copy(alpha = 0.62f),
                                Color(0xFF090B12).copy(alpha = 0.97f),
                            ),
                        ),
                    )
                    .padding(14.dp),
            ) {
                Text(
                    text = screenshotDisplayTitle(
                        title = item.title,
                        processed = item.processed,
                        sensitiveHidden = item.sensitive,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = when {
                        item.topic != ContentTopic.OTHER ->
                            "${topicEmoji(item.topic)} ${item.topic.label} önerisi · ${formatDate(item.capturedAt)}"
                        item.sourceHint != null ->
                            "↗ ${item.sourceHint} · ${formatDate(item.capturedAt)}"
                        else -> "Metni hazır · ${formatDate(item.capturedAt)}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.76f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
