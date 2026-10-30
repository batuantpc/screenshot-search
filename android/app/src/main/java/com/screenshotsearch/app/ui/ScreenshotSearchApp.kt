package com.screenshotsearch.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.screenshotsearch.app.BuildConfig
import com.screenshotsearch.app.data.ContentTopic
import com.screenshotsearch.app.data.IntentCategory
import com.screenshotsearch.app.data.ScreenshotItem
import com.screenshotsearch.app.data.ScreenshotStatus
import com.screenshotsearch.app.permissions.PhotoAccessLevel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenshotSearchApp(
    state: ScreenshotSearchUiState,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onSelectTab: (MainTab) -> Unit,
    onSelectTopic: (ContentTopic) -> Unit,
    onClearTopic: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onSelectItem: (Long) -> Unit,
    onCloseDetail: () -> Unit,
    onUpdateStatus: (ScreenshotStatus) -> Unit,
    onUpdateIntent: (IntentCategory) -> Unit,
    onUpdateTopic: (ContentTopic) -> Unit,
    onQuickUpdateTopic: (Long, ContentTopic) -> Unit,
    onLoadMore: () -> Unit,
    onSetWeeklyReminderEnabled: (Boolean) -> Unit,
    onDismissError: () -> Unit,
) {
    if (state.accessLevel == PhotoAccessLevel.NONE) {
        PermissionScreen(onRequestPermission = onRequestPermission)
        return
    }

    val feedbackContext = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val closeDrawer: () -> Unit = {
        coroutineScope.launch { drawerState.close() }
        Unit
    }
    BackHandler(enabled = drawerState.isOpen, onBack = closeDrawer)
    BackHandler(
        enabled = !drawerState.isOpen && state.selectedItem != null,
        onBack = onCloseDetail,
    )

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.errorMessage) {
        val message = state.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onDismissError()
    }

    if (state.selectedItem != null) {
        DetailScreen(
            item = state.selectedItem,
            onBack = onCloseDetail,
            onUpdateStatus = onUpdateStatus,
            onUpdateIntent = onUpdateIntent,
            onUpdateTopic = onUpdateTopic,
        )
        return
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ScreenshotSearchNavigationDrawerContent(
                state = state,
                onClose = closeDrawer,
                onSelectTab = { tab ->
                    onSelectTab(tab)
                    closeDrawer()
                },
                onSelectTopic = { topic ->
                    onSelectTopic(topic)
                    closeDrawer()
                },
                onSendFeedback = {
                    closeDrawer()
                    openFeedbackPage(feedbackContext)
                },
                onSetWeeklyReminderEnabled = onSetWeeklyReminderEnabled,
                versionName = BuildConfig.VERSION_NAME,
            )
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                        actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                    navigationIcon = {
                        IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menüyü aç")
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = when {
                                    state.selectedTopic != null -> state.selectedTopic.label
                                    state.selectedTab == MainTab.SEARCH -> "Ara"
                                    else -> "screenshot-search"
                                },
                                fontWeight = FontWeight.Bold,
                            )
                            if (state.accessLevel == PhotoAccessLevel.PARTIAL) {
                                Text(
                                    "Yalnızca seçtiğin fotoğraflar",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onRefresh, enabled = !state.isBusy) {
                            Icon(Icons.Default.Refresh, contentDescription = "Yeni screenshotları tara")
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationBarItem(
                        selected = state.selectedTab == MainTab.HOME,
                        onClick = { onSelectTab(MainTab.HOME) },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text("Ana sayfa") },
                    )
                    NavigationBarItem(
                        selected = state.selectedTab == MainTab.SEARCH,
                        onClick = { onSelectTab(MainTab.SEARCH) },
                        icon = { Icon(Icons.Default.Search, contentDescription = null) },
                        label = { Text("Ara") },
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                if (state.isBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                when (state.selectedTab) {
                    MainTab.HOME -> HomeScreen(
                        state = state,
                        onSelectItem = onSelectItem,
                        onSelectTopic = onSelectTopic,
                        onOpenSearch = { onSelectTab(MainTab.SEARCH) },
                        onQueryChange = onQueryChange,
                        onSubmitSearch = onSubmitSearch,
                        onQuickUpdateTopic = onQuickUpdateTopic,
                    )

                    MainTab.SEARCH -> SearchScreen(
                        query = state.query,
                        items = state.visibleItems,
                        topicCounts = state.librarySummary.topicCounts,
                        resultCount = state.resultCount,
                        hasMore = state.nextPageCursor != null,
                        isLoadingMore = state.isLoadingPage,
                        selectedTopic = state.selectedTopic,
                        onClearTopic = onClearTopic,
                        onQueryChange = onQueryChange,
                        onSelectTopic = onSelectTopic,
                        onSelectItem = onSelectItem,
                        onQuickUpdateTopic = onQuickUpdateTopic,
                        onLoadMore = onLoadMore,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionScreen(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f),
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            )
            .padding(24.dp),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .shadow(18.dp, RoundedCornerShape(28.dp))
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF3446C4), Color(0xFF42A9C8)),
                        ),
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Create,
                    contentDescription = null,
                    modifier = Modifier.size(42.dp),
                    tint = Color.White,
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Aldığın screenshotlar kaybolmasın.",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Uygulama onları cihazında okuyup aranabilir bir hafızaya dönüştürür.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(22.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.76f))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.26f),
                        RoundedCornerShape(24.dp),
                    )
                    .padding(14.dp),
            ) {
                PrivacyPoint("Yalnızca screenshot olarak algılanan görseller arşivine eklenir.")
                PrivacyPoint("Görsellerin cihazdan çıkmaz; OCR ve arama indeksi telefonda çalışır.")
                PrivacyPoint("Konu etiketi yalnız açık kanıt varsa önerilir; arama etikete bağlı değildir.")
                PrivacyPoint("Destekleyen telefonlarda yalnızca seçtiğin görsellere izin verebilirsin.")
            }
            Spacer(Modifier.height(26.dp))
            Button(
                onClick = onRequestPermission,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(
                    "Screenshot erişimini seç",
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Bu izni telefon ayarlarından istediğin zaman değiştirebilirsin.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PrivacyPoint(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier
                .size(24.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    RoundedCornerShape(8.dp),
                )
                .padding(4.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun ScreenshotThumbnail(
    contentUri: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    /**
     * Screenshot'lar uzun (1080x2340); kare/dikey bir karta ortadan kirpilinca sayfanin
     * KIMLIGINI tasiyan ust kismi (baslik, site basligi, uygulama basligi) kayboluyor ve
     * kartlar rastgele bir metin bandina donusuyordu. Ust hizalama karti taninabilir yapar.
     */
    alignment: Alignment = Alignment.TopCenter,
    // Hassas içerik: görsel yüklenir ama tanınmayacak kadar bulanık çizilir.
    obscured: Boolean = false,
) {
    // Coil tek ImageLoader üzerinden yükler: bellek + disk cache, hedef boyuta göre
    // downsample ve scroll'da otomatik iptal. Eski BitmapFactory yolu bunların üçünden
    // de yoksundu ve kaydırma takılmalarının ana kaynağıydı.
    var loaded by remember(contentUri) { mutableStateOf(false) }
    var failed by remember(contentUri) { mutableStateOf(false) }
    val platformContext = LocalPlatformContext.current
    val request = remember(contentUri, platformContext) {
        ImageRequest.Builder(platformContext)
            .data(contentUri)
            .build()
    }

    Box(modifier = modifier.clip(RoundedCornerShape(10.dp))) {
        if (!loaded || failed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Create, contentDescription = null)
            }
        }
        if (!failed) {
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (obscured && BLUR_SUPPORTED) Modifier.blur(26.dp) else Modifier),
                contentScale = contentScale,
                alignment = alignment,
                onState = { state ->
                    loaded = state is AsyncImagePainter.State.Success
                    failed = state is AsyncImagePainter.State.Error
                },
            )
        }
        if (obscured) {
            // Blur yalnızca Android 12+ üzerinde çalışır; altında perde neredeyse
            // opak olur ki içerik hiçbir koşulda seçilemesin.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Color.Black.copy(alpha = if (BLUR_SUPPORTED) 0.28f else 0.93f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text("\uD83D\uDD12", fontSize = 30.sp)
            }
        }
    }
}

internal fun topicEmoji(topic: ContentTopic): String = when (topic) {
    ContentTopic.RECIPE -> "🍜"
    ContentTopic.MOVIE -> "🎬"
    ContentTopic.SERIES -> "📺"
    ContentTopic.NEWS -> "📰"
    ContentTopic.POLITICS -> "🏛️"
    ContentTopic.FINANCE -> "📈"
    ContentTopic.PLACE -> "📍"
    ContentTopic.PRODUCT -> "🛍️"
    ContentTopic.SPORT -> "⚽"
    ContentTopic.GAME -> "🎮"
    ContentTopic.AI -> "✨"
    ContentTopic.SCIENCE -> "🔬"
    ContentTopic.BOOK -> "📚"
    ContentTopic.MUSIC -> "🎵"
    ContentTopic.VIDEO -> "▶️"
    ContentTopic.SOCIAL -> "💬"
    ContentTopic.OTHER -> "🗂️"
}

internal fun topicAccent(topic: ContentTopic): Color = when (topic) {
    ContentTopic.RECIPE -> Color(0xFFE56B4A)
    ContentTopic.MOVIE -> Color(0xFF6C63D9)
    ContentTopic.SERIES -> Color(0xFF9A5AC4)
    ContentTopic.NEWS -> Color(0xFF3977C3)
    ContentTopic.POLITICS -> Color(0xFF6B72B8)
    ContentTopic.FINANCE -> Color(0xFF1D8B83)
    ContentTopic.PLACE -> Color(0xFF26876D)
    ContentTopic.PRODUCT -> Color(0xFFC45A89)
    ContentTopic.SPORT -> Color(0xFF36834A)
    ContentTopic.GAME -> Color(0xFF4B78D1)
    ContentTopic.AI -> Color(0xFF7B5AC8)
    ContentTopic.SCIENCE -> Color(0xFF2E7FA8)
    ContentTopic.BOOK -> Color(0xFF9B653F)
    ContentTopic.MUSIC -> Color(0xFFD14C8F)
    ContentTopic.VIDEO -> Color(0xFFE05252)
    ContentTopic.SOCIAL -> Color(0xFFB56A20)
    ContentTopic.OTHER -> Color(0xFF6E7480)
}

internal fun formatDate(epochMillis: Long): String = DATE_FORMATTER.format(
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()),
)

private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

/** Public sürüm geri bildirimi: harici tarayıcıda GitHub Issues sayfasını açar. */
private fun openFeedbackPage(context: android.content.Context) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        data = FEEDBACK_URL.toUri()
    }
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, "Geri bildirim sayfası açılamadı.", Toast.LENGTH_LONG)
            .show()
    }
}

private const val FEEDBACK_URL = "https://github.com/batuantpc/screenshot-search/issues"
