package com.screenshotsearch.app.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.screenshotsearch.app.data.ContentTopic
import com.screenshotsearch.app.data.IntentCategory
import com.screenshotsearch.app.data.ScreenshotItem
import com.screenshotsearch.app.data.ScreenshotStatus
import kotlinx.coroutines.launch

/** RenderEffect blur yalnızca Android 12+ üzerinde çalışır. */
internal val BLUR_SUPPORTED = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DetailScreen(
    item: ScreenshotItem,
    onBack: () -> Unit,
    onUpdateStatus: (ScreenshotStatus) -> Unit,
    onUpdateIntent: (IntentCategory) -> Unit,
    onUpdateTopic: (ContentTopic) -> Unit,
) {
    var showOcrText by rememberSaveable(item.id) { mutableStateOf(false) }
    var showTopicPicker by rememberSaveable(item.id) { mutableStateOf(false) }
    var showIntentPicker by rememberSaveable(item.id) { mutableStateOf(false) }
    var showStatusActions by rememberSaveable(item.id) { mutableStateOf(false) }
    val detailSnackbarHostState = remember { SnackbarHostState() }
    val detailScope = rememberCoroutineScope()

    fun offerUndo(message: String, undo: () -> Unit) {
        detailScope.launch {
            detailSnackbarHostState.currentSnackbarData?.dismiss()
            val result = detailSnackbarHostState.showSnackbar(
                message = message,
                actionLabel = "Geri al",
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) undo()
        }
    }

    fun changeStatus(target: ScreenshotStatus) {
        val previous = item.status
        if (target == previous) return
        onUpdateStatus(target)
        offerUndo("Durum “${target.label}” olarak değiştirildi") {
            onUpdateStatus(previous)
        }
    }

    fun changeTopic(target: ContentTopic) {
        val previous = item.topic
        if (target == previous) {
            showTopicPicker = false
            return
        }
        onUpdateTopic(target)
        showTopicPicker = false
        offerUndo("Kategori “${target.label}” olarak değiştirildi") {
            onUpdateTopic(previous)
        }
    }

    fun changeIntent(target: IntentCategory) {
        val previous = item.intent
        if (target == previous) {
            showIntentPicker = false
            return
        }
        onUpdateIntent(target)
        showIntentPicker = false
        offerUndo("Niyet “${target.label}” olarak değiştirildi") {
            onUpdateIntent(previous)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(detailSnackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = {
                    Text(
                        "Kayıt detayı",
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                DetailImageHero(item = item)
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.topic != ContentTopic.OTHER) {
                        item {
                            MetaPill(
                                topicEmoji(item.topic),
                                if (item.userTopicCorrected) item.topic.label else "${item.topic.label} önerisi",
                                topicAccent(item.topic),
                            )
                        }
                    }
                    if (item.userCorrected) {
                        item { MetaPill("◎", item.intent.label, MaterialTheme.colorScheme.tertiary) }
                    }
                    item { MetaPill("◷", formatDate(item.capturedAt), MaterialTheme.colorScheme.primary) }
                    item.sourceHint?.let { source ->
                        item { MetaPill("↗", source, MaterialTheme.colorScheme.secondary) }
                    }
                }
            }
            if (item.sensitive) {
                item {
                    SensitiveNotice()
                }
            }
            item {
                DetailSectionCard(
                    title = "Konu etiketi · isteğe bağlı",
                    subtitle = when {
                        item.topic == ContentTopic.OTHER ->
                            "Yeterli kanıt bulunamadı. Arama yine okunan bütün metinde çalışır."
                        item.userTopicCorrected ->
                            "Senin etiketin: ${item.topic.label}."
                        else ->
                            "Otomatik öneri: ${item.topic.label}. Yanlışsa değiştirebilirsin."
                    },
                    accent = topicAccent(item.topic),
                ) {
                    OutlinedButton(
                        onClick = { showTopicPicker = !showTopicPicker },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(
                            if (showTopicPicker) "Kategori seçeneklerini kapat"
                            else if (item.topic == ContentTopic.OTHER) "Konu etiketi ekle"
                            else "${topicEmoji(item.topic)}  Etiketi değiştir",
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (showTopicPicker) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            ContentTopic.entries.forEach { topic ->
                                FilterChip(
                                    selected = item.topic == topic,
                                    onClick = { changeTopic(topic) },
                                    label = { Text("${topicEmoji(topic)} ${topic.label}") },
                                )
                            }
                        }
                    }
                }
            }
            item {
                DetailSectionCard(
                    title = "Neden kaydettin? · isteğe bağlı",
                    subtitle = if (item.userCorrected) {
                        "Senin seçimin: ${item.intent.label}."
                    } else {
                        "Bunu screenshot’tan güvenilir biçimde anlayamayız; istersen sen seçebilirsin."
                    },
                    accent = MaterialTheme.colorScheme.tertiary,
                ) {
                    OutlinedButton(
                        onClick = { showIntentPicker = !showIntentPicker },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(
                            if (showIntentPicker) "Seçenekleri kapat"
                            else if (item.userCorrected) "Seçimini değiştir"
                            else "Neden kaydettiğini ekle",
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (showIntentPicker) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            IntentCategory.entries.forEach { intent ->
                                FilterChip(
                                    selected = item.intent == intent,
                                    onClick = { changeIntent(intent) },
                                    label = { Text(intent.label) },
                                )
                            }
                        }
                    }
                }
            }
            if (item.ocrText.isNotBlank()) {
                item {
                    DetailSectionCard(
                        title = "Okunan metin",
                        subtitle = "İstersen cihazındaki OCR sonucunu açabilirsin.",
                        accent = MaterialTheme.colorScheme.secondary,
                    ) {
                        OutlinedButton(
                            onClick = { showOcrText = !showOcrText },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text(if (showOcrText) "Metni gizle" else "Okunan metni göster")
                        }
                        if (showOcrText) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.24f),
                                        RoundedCornerShape(16.dp),
                                    )
                                    .padding(14.dp),
                            ) {
                                Text(
                                    item.ocrText,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 19.sp,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            item {
                DetailSectionCard(
                    title = "İsteğe bağlı düzenleme",
                    subtitle = "Durum: ${item.status.label}",
                    accent = MaterialTheme.colorScheme.outline,
                ) {
                    TextButton(
                        onClick = { showStatusActions = !showStatusActions },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (showStatusActions) "Durum seçeneklerini kapat"
                            else "Sonra göster, tamamla veya arşivle",
                        )
                    }
                    if (showStatusActions) {
                        Button(
                            onClick = { changeStatus(ScreenshotStatus.INTERESTED) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text("✦  İlgileniyorum", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { changeStatus(ScreenshotStatus.LATER) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text("◷  Sonra göster")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { changeStatus(ScreenshotStatus.DONE) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Text("✓ Tamamlandı")
                            }
                            OutlinedButton(
                                onClick = { changeStatus(ScreenshotStatus.ARCHIVED) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Text("□ Arşivle")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailImageHero(item: ScreenshotItem) {
    val accent = topicAccent(item.topic)
    val shape = RoundedCornerShape(30.dp)
    // Hassas görsel detayda da bulanık açılır; kullanıcı bilerek dokununca görünür.
    // Kayıt bazında tutulur; ekran kapanınca sıfırlanır.
    var sensitiveRevealed by remember(item.id) { mutableStateOf(false) }
    var showFullscreen by remember(item.id) { mutableStateOf(false) }
    val hidden = item.sensitive && !sensitiveRevealed

    if (showFullscreen) {
        FullscreenScreenshotDialog(
            item = item,
            onDismiss = { showFullscreen = false },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.68f)
            .shadow(16.dp, shape)
            .clip(shape)
            .background(Color(0xFF080B12))
            .border(1.dp, accent.copy(alpha = 0.48f), shape),
    ) {
        ScreenshotThumbnail(
            contentUri = item.contentUri,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            obscured = hidden,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = if (hidden) {
                        "Hassas screenshot. Göstermek için dokun."
                    } else {
                        "Screenshotı tam ekran aç"
                    }
                    role = Role.Button
                }
                .clickable {
                    if (hidden) sensitiveRevealed = true else showFullscreen = true
                }
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.3f to Color.Transparent,
                            0.72f to Color(0xFF080B12).copy(alpha = 0.48f),
                            1f to Color(0xFF080B12).copy(alpha = 0.96f),
                        ),
                    ),
                ),
        )
        if (hidden) {
            Text(
                "Hassas \u00b7 g\u00f6stermek i\u00e7in dokun",
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = 34.dp),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.92f),
                fontWeight = FontWeight.Bold,
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (item.sensitive && sensitiveRevealed) {
                HeroActionPill(
                    label = "Gizle",
                    onClick = { sensitiveRevealed = false },
                )
            }
            HeroActionPill(
                label = if (hidden) "Göster" else "Tam ekran",
                onClick = {
                    if (hidden) sensitiveRevealed = true else showFullscreen = true
                },
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Text(
                when {
                    hidden -> "🔒"
                    item.topic != ContentTopic.OTHER -> topicEmoji(item.topic)
                    else -> "🔎"
                },
                fontSize = 30.sp,
            )
            Spacer(Modifier.height(7.dp))
            Text(
                screenshotDisplayTitle(
                    title = item.title.ifBlank { item.displayName },
                    processed = item.processed,
                    sensitiveHidden = hidden,
                ),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (hidden) {
                    "İçerik gizli · ${formatDate(item.capturedAt)}"
                } else {
                    listOfNotNull(
                        item.intent.label.takeIf { item.userCorrected },
                        item.sourceHint?.let { source -> "↗ $source" },
                        formatDate(item.capturedAt),
                    ).joinToString(" · ")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.76f),
            )
        }
    }
}

@Composable
private fun HeroActionPill(
    label: String,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF080B12).copy(alpha = 0.78f))
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun FullscreenScreenshotDialog(
    item: ScreenshotItem,
    onDismiss: () -> Unit,
) {
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var translation by remember(item.id) { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF05070C))
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(item.id) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 5f)
                            val maxX = size.width * (nextScale - 1f) / 2f
                            val maxY = size.height * (nextScale - 1f) / 2f
                            translation = if (nextScale == 1f) {
                                Offset.Zero
                            } else {
                                Offset(
                                    x = (translation.x + pan.x).coerceIn(-maxX, maxX),
                                    y = (translation.y + pan.y).coerceIn(-maxY, maxY),
                                )
                            }
                            scale = nextScale
                        }
                    }
                    .pointerInput(item.id) {
                        detectTapGestures(
                            onDoubleTap = {
                                scale = 1f
                                translation = Offset.Zero
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                ScreenshotThumbnail(
                    contentUri = item.contentUri,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = translation.x,
                            translationY = translation.y,
                        ),
                    contentScale = ContentScale.Fit,
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .background(Color(0xFF080B12).copy(alpha = 0.82f), CircleShape),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Tam ekranı kapat",
                    tint = Color.White,
                )
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(18.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF080B12).copy(alpha = 0.84f),
            ) {
                Text(
                    text = if (scale > 1.05f) {
                        "Sürükleyerek gez · iki dokunuşla sıfırla"
                    } else {
                        "İki parmakla yakınlaştır"
                    },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.86f),
                )
            }
        }
    }
}

@Composable
private fun MetaPill(
    icon: String,
    text: String,
    accent: Color,
) {
    val shape = RoundedCornerShape(13.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        accent.copy(alpha = 0.26f),
                        MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ),
            )
            .border(1.dp, accent.copy(alpha = 0.34f), shape)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon)
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SensitiveNotice() {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.13f))
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.34f), shape)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🔒", fontSize = 20.sp)
        Text(
            "Hassas olabilir; otomatik geri getirmeye dahil edilmeyecek.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun DetailSectionCard(
    title: String,
    subtitle: String,
    accent: Color,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, shape)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.18f),
                        MaterialTheme.colorScheme.surface,
                    ),
                ),
            )
            .border(1.dp, accent.copy(alpha = 0.28f), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}
