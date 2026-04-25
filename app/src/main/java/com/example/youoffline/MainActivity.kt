package com.example.youoffline

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
// VideoView and MediaController no longer used — ExoPlayer used instead
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.compose.material.icons.Icons
import android.content.pm.ActivityInfo
import android.hardware.SensorManager
import android.view.OrientationEventListener
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.MusicNote
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.animateContentSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.youoffline.ads.rememberYandexInterstitialAd
import com.example.youoffline.model.DownloadedAudio
import com.example.youoffline.model.DownloadQuality
import com.example.youoffline.model.VideoQuality
import com.example.youoffline.ui.DownloadViewModel
import com.example.youoffline.ui.VideoViewModel
import com.example.youoffline.ui.theme.YouOfflineTheme
import com.yandex.mobile.ads.banner.BannerAdSize
import com.yandex.mobile.ads.banner.BannerAdEventListener
import com.yandex.mobile.ads.banner.BannerAdView
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.common.ImpressionData
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlin.math.absoluteValue

class MainActivity : ComponentActivity() {
    private val viewModel: DownloadViewModel by viewModels()
    private val videoViewModel: VideoViewModel by viewModels()
    private val urlRegex = Regex("https?://\\S+")
    var sharedUrl: String? = null
    var selectedTab: Int = 0  // 0 = audio, 1 = video

    private val mediaReadPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val audioGranted =
                grants[Manifest.permission.READ_MEDIA_AUDIO] == true ||
                    grants[Manifest.permission.READ_EXTERNAL_STORAGE] == true
            val videoGranted =
                grants[Manifest.permission.READ_MEDIA_VIDEO] == true ||
                    grants[Manifest.permission.READ_EXTERNAL_STORAGE] == true

            if (audioGranted) {
                viewModel.refreshDownloads()
            }
            if (videoGranted) {
                videoViewModel.refreshLibrary()
            }
        }

    private val deleteMediaLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            viewModel.confirmDelete(result.resultCode == RESULT_OK)
        }

    companion object {
        private const val SHARE_SHORTCUT_ID = "share_to_youoffline"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        publishShareShortcut()
        ensureMediaLibraryAccessAndRefresh()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lifecycleScope.launch {
                viewModel.deleteRequestUri.collect { uri ->
                    val pi = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
                    deleteMediaLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                }
            }
        }

        if (savedInstanceState == null) {
            processShareIntent(intent)
        }

        setContent {
            YouOfflineTheme {
                AppRoot(audioViewModel = viewModel, videoViewModel = videoViewModel, mainActivity = this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processShareIntent(intent)
    }

    private fun processShareIntent(incomingIntent: Intent?) {
        if (incomingIntent?.action != Intent.ACTION_SEND) return
        if (incomingIntent.type != "text/plain") return

        val sharedText = incomingIntent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (sharedText.isBlank()) return

        val url = extractFirstUrl(sharedText) ?: return
        sharedUrl = url
        ShortcutManagerCompat.reportShortcutUsed(this, SHARE_SHORTCUT_ID)
    }

    fun handleShareUrlSelection(format: String) {
        val url = sharedUrl ?: return
        
        when (format) {
            "audio" -> {
                selectedTab = 0
                viewModel.onUrlChange(url)
                viewModel.startDownload()
            }
            "video" -> {
                selectedTab = 1
                videoViewModel.onUrlChange(url)
                videoViewModel.startDownload()
            }
        }
        sharedUrl = null
    }


    private fun ensureMediaLibraryAccessAndRefresh() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val audioGranted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            val videoGranted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) ==
                    PackageManager.PERMISSION_GRANTED

            if (audioGranted) {
                viewModel.refreshDownloads()
            } else {
                mediaReadPermissionLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_AUDIO))
            }

            if (videoGranted) {
                videoViewModel.refreshLibrary()
            }
            return
        }

        val legacyGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED

        if (legacyGranted) {
            viewModel.refreshDownloads()
            videoViewModel.refreshLibrary()
            return
        }

        mediaReadPermissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
    }

    private fun publishShareShortcut() {
        val shortcutIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
        }

        val shortcut = ShortcutInfoCompat.Builder(this, SHARE_SHORTCUT_ID)
            .setShortLabel("YouOffline")
            .setLongLabel("Скачать в YouOffline")
            .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(shortcutIntent)
            .setCategories(setOf("com.example.youoffline.SHARE_TARGET"))
            .build()

        ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)
    }

    private fun extractFirstUrl(text: String): String? {
        val raw = urlRegex.find(text)?.value ?: return null
        return raw.trimEnd('.', ',', ';', ')', ']', '}', '"', '\'')
    }

}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AppRoot(
    audioViewModel: DownloadViewModel,
    videoViewModel: VideoViewModel,
    mainActivity: MainActivity
) {
    var selectedTab by remember { mutableIntStateOf(mainActivity.selectedTab) }
    val videoState by videoViewModel.uiState.collectAsStateWithLifecycle()
    var showShareDialog by remember { mutableStateOf(false) }

    LaunchedEffect(mainActivity.sharedUrl) {
        showShareDialog = mainActivity.sharedUrl != null
    }

    LaunchedEffect(mainActivity.selectedTab) {
        selectedTab = mainActivity.selectedTab
    }

    if (showShareDialog && mainActivity.sharedUrl != null) {
        AlertDialog(
            onDismissRequest = { 
                showShareDialog = false
                mainActivity.sharedUrl = null
            },
            title = { Text("Выберите формат") },
            text = { Text("Скачать видео или аудио?") },
            confirmButton = {
                Button(
                    onClick = {
                        mainActivity.handleShareUrlSelection("audio")
                        showShareDialog = false
                    }
                ) {
                    Text("🎵 Аудио")
                }
            },
            dismissButton = {
                Button(
                    onClick = {
                        mainActivity.handleShareUrlSelection("video")
                        showShareDialog = false
                    }
                ) {
                    Text("🎬 Видео")
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                Column {
                    // Mini video player above tab bar
                    val miniUri = videoState.playingUri
                    if (miniUri != null && videoState.isPlayerMinimized) {
                        val title = videoState.downloadedFiles
                            .firstOrNull { it.pathOrUri == miniUri }?.title ?: "Видео"
                        VideoMiniPlayer(
                            title = title,
                            onExpand = videoViewModel::expandPlayer,
                            onClose = videoViewModel::closePlayer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                    // Tab bar (text only)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .navigationBarsPadding()
                    ) {
                        listOf("Аудио" to 0, "Видео" to 1).forEachIndexed { _, (label, index) ->
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { selectedTab = index }
                                    .padding(vertical = 14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selectedTab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (selectedTab == index) {
                                    Box(
                                        modifier = Modifier
                                            .size(width = 32.dp, height = 2.dp)
                                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp))
                                    )
                                }
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
            ) {
                if (selectedTab == 0) {
                    MainScreen(viewModel = audioViewModel)
                } else {
                    VideoScreen(viewModel = videoViewModel)
                }
            }
        }

        // Fullscreen video player — outside Scaffold, covers NavigationBar
        val playingUri = videoState.playingUri
        if (playingUri != null && !videoState.isPlayerMinimized) {
            VideoPlayerScreen(
                uriString = playingUri,
                startPositionMs = videoState.videoPlaybackPositionMs,
                onMinimize = videoViewModel::minimizePlayer,
                onClose = videoViewModel::closePlayer
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun VideoScreen(viewModel: VideoViewModel) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val filtered = state.downloadedFiles.filter {
        val q = state.searchQuery.trim()
        q.isBlank() || it.title.contains(q, ignoreCase = true)
    }
    val readinessPercent = (state.progress * 100f).toInt().coerceIn(0, 100)
    var isDownloadBlockExpanded by rememberSaveable { mutableStateOf(true) }
    var qualityExpanded by rememberSaveable { mutableStateOf(false) }
    var showClipboardPrompt by rememberSaveable { mutableStateOf(false) }
    var clipboardUrlCandidate by rememberSaveable { mutableStateOf("") }
    var dismissedClipboardUrl by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val interstitialAdManager = rememberYandexInterstitialAd(BuildConfig.AD_UNIT_INTERSTITIAL)
    val prefs = remember { context.getSharedPreferences("youoffline_prefs", Context.MODE_PRIVATE) }
    var wasDownloading by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(interstitialAdManager) {
        viewModel.setOnPlaylistComplete {
            if (BuildConfig.ENABLE_YANDEX_ADS) {
                interstitialAdManager.showAd(onAdDismissed = {})
            }
        }
        onDispose { 
            interstitialAdManager.destroy()
            viewModel.setOnPlaylistComplete(null)
        }
    }

    LaunchedEffect(state.downloadedFiles.firstOrNull()?.pathOrUri) {
        if (state.downloadedFiles.isNotEmpty()) listState.animateScrollToItem(0)
    }

    LaunchedEffect(state.isDownloading, state.status) {
        if (state.isDownloading) { wasDownloading = true; return@LaunchedEffect }
        if (wasDownloading) {
            wasDownloading = false
            if (state.status == "Готово") {
                val n = prefs.getInt("successful_video_download_count", 0) + 1
                prefs.edit().putInt("successful_video_download_count", n).apply()
                if (n % 3 == 0) interstitialAdManager.showAd(onAdDismissed = {})
            }
        }
    }

    fun maybeSuggestClipboardUrl() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = clipboard.primaryClip
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (text.isBlank()) return
        val url = extractYoutubeUrl(text) ?: return
        if (url == state.url) return
        if (url == dismissedClipboardUrl) return
        clipboardUrlCandidate = url
        showClipboardPrompt = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "YouOffline Videos",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            if (BuildConfig.ENABLE_YANDEX_ADS) {
                YandexTopBanner(
                    modifier = Modifier.fillMaxWidth(),
                    adsReady = YouOfflineApp.isYandexAdsReady,
                    adUnitId = BuildConfig.AD_UNIT_VIDEO_BANNER
                )
            }

            ElevatedCard(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Загрузка",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        IconButton(
                            onClick = { isDownloadBlockExpanded = !isDownloadBlockExpanded },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Text(
                                text = if (isDownloadBlockExpanded) "▲" else "▼",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (isDownloadBlockExpanded) {
                        OutlinedTextField(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused) maybeSuggestClipboardUrl()
                                },
                            value = state.url,
                            onValueChange = viewModel::onUrlChange,
                            label = { Text("Ссылка на страницу") },
                            trailingIcon = {
                                if (state.url.isNotBlank()) {
                                    IconButton(onClick = { viewModel.onUrlChange("") }) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = "Очистить ссылку"
                                        )
                                    }
                                }
                            },
                            singleLine = true
                        )

                        val qualityLabel = when (state.downloadQuality) {
                            VideoQuality.BEST   -> "лучшее"
                            VideoQuality.Q1440P -> "1440p"
                            VideoQuality.Q1080P -> "1080p"
                            VideoQuality.Q720P  -> "720p"
                            VideoQuality.Q480P  -> "480p"
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    if (state.isDownloading) viewModel.cancelDownload() else viewModel.startDownload()
                                },
                                enabled = state.isDownloading || state.url.isNotBlank(),
                                modifier = Modifier.weight(1.2f).height(50.dp)
                            ) {
                                Text(if (state.isDownloading) "Отмена" else "Скачать")
                            }

                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                TextButton(
                                    onClick = { qualityExpanded = !qualityExpanded },
                                    enabled = !state.isDownloading,
                                    modifier = Modifier.fillMaxWidth().height(50.dp)
                                ) {
                                    Text(
                                        text = "Качество: $qualityLabel",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                DropdownMenu(
                                    expanded = qualityExpanded,
                                    onDismissRequest = { qualityExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Лучшее") },
                                        onClick = { viewModel.onQualitySelected(VideoQuality.BEST); qualityExpanded = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("1440p") },
                                        onClick = { viewModel.onQualitySelected(VideoQuality.Q1440P); qualityExpanded = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("1080p") },
                                        onClick = { viewModel.onQualitySelected(VideoQuality.Q1080P); qualityExpanded = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("720p") },
                                        onClick = { viewModel.onQualitySelected(VideoQuality.Q720P); qualityExpanded = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("480p") },
                                        onClick = { viewModel.onQualitySelected(VideoQuality.Q480P); qualityExpanded = false }
                                    )
                                }
                            }
                        }

                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                        val visibleStatus = if (!state.isDownloading && state.status.contains("Подготовка", ignoreCase = true)) {
                            "Готово"
                        } else state.status
                        Text(text = visibleStatus, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        if (state.isDownloading) {
                            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                            Text(
                                text = "${state.status} ($readinessPercent%)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Text(
                text = "Библиотека",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(filtered, key = { _, item -> item.pathOrUri }) { _, video ->
                    VideoRow(
                        title = video.title,
                        durationMs = video.durationMs,
                        thumbnailPath = video.thumbnailPath,
                        onClick = { viewModel.playVideo(video.pathOrUri) }
                    )
                }
            }
        }
    }

    // Full-screen player moved to AppRoot

    if (showClipboardPrompt) {
        AlertDialog(
            onDismissRequest = {
                dismissedClipboardUrl = clipboardUrlCandidate
                showClipboardPrompt = false
            },
            title = { Text("Найдена ссылка в буфере") },
            text = { Text("Вставить ссылку из буфера обмена и сразу начать загрузку?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.onUrlChange(clipboardUrlCandidate)
                        viewModel.startDownload()
                        showClipboardPrompt = false
                    }
                ) {
                    Text("Вставить и скачать")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        dismissedClipboardUrl = clipboardUrlCandidate
                        showClipboardPrompt = false
                    }
                ) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun VideoRow(
    title: String,
    durationMs: Long,
    thumbnailPath: String?,
    onClick: () -> Unit
) {
    val thumbnail = remember(thumbnailPath) {
        thumbnailPath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
    }
    ElevatedCard(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF365A80)),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Movie,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium
                )
                if (durationMs > 0) {
                    Text(
                        text = formatMs(durationMs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Воспроизвести",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun VideoMiniPlayer(
    title: String,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        onClick = onExpand,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Color(0xFF365A80), Color(0xFF4D7C95))
                    )
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Movie,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(32.dp)
                ) {
                    Text("✕", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun VideoPlayerScreen(
    uriString: String,
    startPositionMs: Long = 0L,
    onMinimize: (Long) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var showOverlay by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var isOrientationLocked by remember { mutableStateOf(false) }
    val view = LocalView.current
    val activity = view.context as Activity

    // Enable auto-rotation on open; restore original on close
    DisposableEffect(Unit) {
        val original = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        onDispose {
            activity.requestedOrientation = original
        }
    }

    // When lock toggled: lock to current physical orientation or release
    val orientationListener = remember {
        var lastOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(angle: Int) {
                if (angle == ORIENTATION_UNKNOWN) return
                lastOrientation = when {
                    angle <= 45 || angle > 315 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    angle in 46..135 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                    angle in 136..225 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                    else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
            }
            fun getLastOrientation() = lastOrientation
        }
    }

    DisposableEffect(orientationListener) {
        orientationListener.enable()
        onDispose { orientationListener.disable() }
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(uriString)))
            prepare()
            if (startPositionMs > 0) seekTo(startPositionMs)
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) { isPlaying = false; showOverlay = true }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    DisposableEffect(view) {
        val window = activity.window
        val controller = WindowInsetsControllerCompat(window, view)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // Poll position every 500ms
    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(500)
            positionMs = exoPlayer.currentPosition
            val dur = exoPlayer.duration
            if (dur > 0) durationMs = dur
        }
    }

    // Auto-hide after 3s while playing
    LaunchedEffect(showOverlay, isPlaying) {
        if (showOverlay && isPlaying) {
            delay(3000)
            showOverlay = false
        }
    }

    BackHandler { onMinimize(exoPlayer.currentPosition) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                androidx.media3.ui.PlayerView(it).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            }
        )

        // Gesture layer: single tap = toggle overlay, double tap = seek ±10s
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { showOverlay = !showOverlay },
                        onDoubleTap = { offset ->
                            val seekDelta = 10_000L
                            if (offset.x < size.width / 2) {
                                val target = (exoPlayer.currentPosition - seekDelta).coerceAtLeast(0L)
                                exoPlayer.seekTo(target); positionMs = target
                            } else {
                                val dur = exoPlayer.duration.coerceAtLeast(0L)
                                val target = (exoPlayer.currentPosition + seekDelta).coerceAtMost(dur)
                                exoPlayer.seekTo(target); positionMs = target
                            }
                        }
                    )
                }
        )

        AnimatedVisibility(visible = showOverlay, enter = fadeIn(), exit = fadeOut()) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top gradient + close button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    // Orientation lock button
                    IconButton(
                        onClick = {
                            isOrientationLocked = !isOrientationLocked
                            activity.requestedOrientation = if (isOrientationLocked) {
                                orientationListener.getLastOrientation()
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR
                            }
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = if (isOrientationLocked)
                                Icons.Filled.ScreenLockRotation
                            else
                                Icons.Filled.ScreenRotation,
                            contentDescription = null,
                            tint = if (isOrientationLocked) Color(0xFFFFD54F) else Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    // Close button
                    IconButton(
                        onClick = { onMinimize(exoPlayer.currentPosition) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Text("✕", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                }

                // Center play/pause
                IconButton(
                    onClick = {
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        showOverlay = true
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color(0x88000000))
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }

                // Bottom gradient + slider + times
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp)
                ) {
                    val sliderVal = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
                    Slider(
                        value = sliderVal,
                        onValueChange = { fraction ->
                            val target = (fraction * durationMs).toLong().coerceAtLeast(0L)
                            exoPlayer.seekTo(target)
                            positionMs = target
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color(0x66FFFFFF)
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatMs(positionMs), color = Color.White, style = MaterialTheme.typography.bodySmall)
                        Text(formatMs(durationMs), color = Color.White, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MainScreen(
    viewModel: DownloadViewModel
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val interstitialAdManager = rememberYandexInterstitialAd(BuildConfig.AD_UNIT_INTERSTITIAL)

    val prefs = remember { context.getSharedPreferences("youoffline_prefs", Context.MODE_PRIVATE) }
    var wasDownloading by rememberSaveable { mutableStateOf(false) }
    var showOnboarding by rememberSaveable {
        mutableStateOf(!prefs.getBoolean("onboarding_shown", false))
    }

    var isDownloadBlockExpanded by rememberSaveable { mutableStateOf(true) }
    var showClipboardPrompt by rememberSaveable { mutableStateOf(false) }
    var clipboardUrlCandidate by rememberSaveable { mutableStateOf("") }
    var dismissedClipboardUrl by rememberSaveable { mutableStateOf("") }
    var qualityExpanded by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val filtered = state.downloadedFiles.filter {
        val q = state.searchQuery.trim()
        q.isBlank() || it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true)
    }
    val currentTrack = viewModel.getCurrentTrack()
    val sliderValue = if (state.playbackDurationMs > 0) {
        state.playbackPositionMs.toFloat() / state.playbackDurationMs.toFloat()
    } else {
        0f
    }
    val readinessPercent = (state.progress * 100f).toInt().coerceIn(0, 100)

    LaunchedEffect(state.downloadedFiles.firstOrNull()?.pathOrUri) {
        if (state.downloadedFiles.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    DisposableEffect(interstitialAdManager) {
        onDispose { interstitialAdManager.destroy() }
    }

    LaunchedEffect(state.isDownloading, state.status) {
        if (state.isDownloading) {
            wasDownloading = true
            return@LaunchedEffect
        }

        if (wasDownloading) {
            wasDownloading = false
            if (state.status == "Готово") {
                val successCount = prefs.getInt("successful_download_count", 0) + 1
                prefs.edit().putInt("successful_download_count", successCount).apply()
                if (successCount % 4 == 0) {
                    interstitialAdManager.showAd(onAdDismissed = {})
                }
            }
        }
    }

    fun maybeSuggestClipboardUrl() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = clipboard.primaryClip
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (text.isBlank()) return

        val youtubeUrl = extractYoutubeUrl(text) ?: return
        if (youtubeUrl == state.url) return
        if (youtubeUrl == dismissedClipboardUrl) return

        clipboardUrlCandidate = youtubeUrl
        showClipboardPrompt = true
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (currentTrack != null) {
                MiniPlayer(
                    track = currentTrack,
                    isPlaying = state.isPlaying,
                    sliderValue = sliderValue,
                    elapsed = formatMs(state.playbackPositionMs.toLong()),
                    total = formatMs(state.playbackDurationMs.toLong()),
                    onSeek = viewModel::seekToFraction,
                    onPrev = viewModel::playPrevious,
                    onToggle = viewModel::togglePlayPause,
                    onNext = viewModel::playNext,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "YouOffline Music",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                if (BuildConfig.ENABLE_YANDEX_ADS) {
                    YandexTopBanner(
                        modifier = Modifier.fillMaxWidth(),
                        adsReady = YouOfflineApp.isYandexAdsReady,
                        adUnitId = BuildConfig.AD_UNIT_AUDIO_BANNER
                    )
                }

                ElevatedCard(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .animateContentSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Загрузка",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            IconButton(
                                onClick = { isDownloadBlockExpanded = !isDownloadBlockExpanded },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Text(
                                    text = if (isDownloadBlockExpanded) "▲" else "▼",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (isDownloadBlockExpanded) {
                            OutlinedTextField(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { focusState ->
                                        if (focusState.isFocused) {
                                            maybeSuggestClipboardUrl()
                                        }
                                    },
                                value = state.url,
                                onValueChange = viewModel::onUrlChange,
                                label = { Text("Ссылка на страницу") },
                                trailingIcon = {
                                    if (state.url.isNotBlank()) {
                                        IconButton(onClick = { viewModel.onUrlChange("") }) {
                                            Icon(
                                                imageVector = Icons.Filled.Close,
                                                contentDescription = "Очистить ссылку"
                                            )
                                        }
                                    }
                                },
                                singleLine = true
                            )
                            val qualityShortLabel = when (state.downloadQuality) {
                                DownloadQuality.BEST -> "лучшее"
                                DownloadQuality.MEDIUM -> "среднее"
                                DownloadQuality.LOW -> "низкое"
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        if (state.isDownloading) viewModel.cancelDownload() else viewModel.startDownload()
                                    },
                                    enabled = state.isDownloading || state.url.isNotBlank(),
                                    modifier = Modifier
                                        .weight(1.2f)
                                        .height(50.dp)
                                ) {
                                    Text(if (state.isDownloading) "Отмена" else "Скачать")
                                }

                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    TextButton(
                                        onClick = { qualityExpanded = !qualityExpanded },
                                        enabled = !state.isDownloading,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(50.dp)
                                    ) {
                                        Text(
                                            text = "Качество: $qualityShortLabel",
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = qualityExpanded,
                                        onDismissRequest = { qualityExpanded = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Лучшее") },
                                            onClick = {
                                                viewModel.onDownloadQualitySelected(DownloadQuality.BEST)
                                                qualityExpanded = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Среднее") },
                                            onClick = {
                                                viewModel.onDownloadQualitySelected(DownloadQuality.MEDIUM)
                                                qualityExpanded = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Низкое") },
                                            onClick = {
                                                viewModel.onDownloadQualitySelected(DownloadQuality.LOW)
                                                qualityExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                            val visibleStatus = if (!state.isDownloading && state.status.contains("Подготовка", ignoreCase = true)) {
                                "Готово"
                            } else {
                                state.status
                            }
                            Text(text = visibleStatus, style = MaterialTheme.typography.bodyMedium)
                        } else {
                            val collapsedStatus = when {
                                state.isDownloading -> "${state.status} ($readinessPercent%)"
                                state.status.startsWith("Ошибка", ignoreCase = true) -> state.status
                                else -> null
                            }
                            if (state.isDownloading) {
                                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                            }
                            if (collapsedStatus != null) {
                                Text(
                                    text = collapsedStatus,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (showOnboarding) {
                    AlertDialog(
                        onDismissRequest = {
                            prefs.edit().putBoolean("onboarding_shown", true).apply()
                            showOnboarding = false
                        },
                        title = { Text("Добро пожаловать в YouOffline") },
                        text = {
                            Text(
                                "Скачивайте музыку по ссылке:\n\n" +
                                "Нажмите «Поделиться» в приложении-источнике → выберите YouOffline\n\n" +
                                "Или вставьте ссылку вручную и нажмите «Скачать»\n\n" +
                                "Работает с отдельными треками и плейлистами\n\n" +
                                "Треки появятся в библиотеке — можно слушать офлайн"
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                prefs.edit().putBoolean("onboarding_shown", true).apply()
                                showOnboarding = false
                            }) {
                                Text("Понятно")
                            }
                        }
                    )
                }

                if (showClipboardPrompt) {
                    AlertDialog(
                        onDismissRequest = {
                            dismissedClipboardUrl = clipboardUrlCandidate
                            showClipboardPrompt = false
                        },
                        title = { Text("Найдена ссылка в буфере") },
                        text = { Text("Вставить ссылку из буфера и сразу начать загрузку?") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    viewModel.onUrlChange(clipboardUrlCandidate)
                                    viewModel.startDownload()
                                    showClipboardPrompt = false
                                }
                            ) {
                                Text("Вставить и скачать")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    dismissedClipboardUrl = clipboardUrlCandidate
                                    showClipboardPrompt = false
                                }
                            ) {
                                Text("Отмена")
                            }
                        }
                    )
                }

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.searchQuery,
                    onValueChange = viewModel::onSearchQueryChange,
                    label = { Text("Поиск треков и артистов") },
                    singleLine = true
                )

                Text(
                    text = "Библиотека",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(filtered, key = { _, item -> item.pathOrUri }) { _, track ->
                        TrackRow(
                            track = track,
                            isActive = currentTrack?.pathOrUri == track.pathOrUri,
                            onPlay = { viewModel.playFile(track.pathOrUri) },
                            onDelete = { viewModel.deleteTrack(track.pathOrUri) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun YandexTopBanner(
    modifier: Modifier = Modifier,
    adsReady: Boolean,
    adUnitId: String
) {
    if (!adsReady) {
        ElevatedCard(
            modifier = modifier,
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Text(
                text = "Рекламный блок недоступен",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
        return
    }

    val context = LocalContext.current
    val bannerAdView = remember {
        BannerAdView(context).apply {
            id = View.generateViewId()
            setAdUnitId(adUnitId)
            val widthDp = (context.resources.displayMetrics.widthPixels / context.resources.displayMetrics.density).toInt()
            setAdSize(BannerAdSize.stickySize(context, widthDp))
            setBannerAdEventListener(object : BannerAdEventListener {
                override fun onAdLoaded() {
                    Log.d("YouOfflineAds", "Banner loaded")
                }

                override fun onAdFailedToLoad(error: AdRequestError) {
                    Log.e("YouOfflineAds", "Banner failed: ${error.description}, code=${error.code}")
                }

                override fun onAdClicked() {
                    Log.d("YouOfflineAds", "Banner clicked")
                }

                override fun onLeftApplication() {
                    Log.d("YouOfflineAds", "Left app from banner")
                }

                override fun onReturnedToApplication() {
                    Log.d("YouOfflineAds", "Returned to app from banner")
                }

                override fun onImpression(impressionData: ImpressionData?) {
                    Log.d("YouOfflineAds", "Banner impression")
                }
            })
            loadAd(AdRequest.Builder().build())
        }
    }

    DisposableEffect(bannerAdView) {
        onDispose {
            bannerAdView.destroy()
        }
    }

    AndroidView(
        modifier = modifier
            .fillMaxWidth(),
        factory = { bannerAdView }
    )
}

@Composable
private fun TrackRow(
    track: DownloadedAudio,
    isActive: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    val artistGradient = gradientForArtist(track.artist)
    var menuExpanded by remember { mutableStateOf(false) }

    ElevatedCard(
        onClick = onPlay,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            artistGradient
                        )
                    )
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                Text("⋮")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Удалить") },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(
    track: DownloadedAudio,
    isPlaying: Boolean,
    sliderValue: Float,
    elapsed: String,
    total: String,
    onSeek: (Float) -> Unit,
    onPrev: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF365A80),
                            Color(0xFF4D7C95),
                            Color(0xFF5C8FA4)
                        )
                    )
                )
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = track.title,
                    color = Color(0xFFF8FBFF),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold
                )
                Slider(
                    value = sliderValue,
                    onValueChange = onSeek,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFEAF4FF),
                        activeTrackColor = Color(0xFFEAF4FF),
                        inactiveTrackColor = Color(0x66EAF4FF)
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(elapsed, color = Color(0xFFF8FBFF))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        IconButton(
                            onClick = onPrev,
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(Color(0x33FFFFFF))
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipPrevious,
                                contentDescription = "Previous",
                                tint = Color(0xFFF8FBFF)
                            )
                        }
                        IconButton(
                            onClick = onToggle,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(Color(0x44FFFFFF))
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color(0xFFF8FBFF)
                            )
                        }
                        IconButton(
                            onClick = onNext,
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(Color(0x33FFFFFF))
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipNext,
                                contentDescription = "Next",
                                tint = Color(0xFFF8FBFF)
                            )
                        }
                    }
                    Text(total, color = Color(0xFFF8FBFF))
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format("%02d:%02d", min, sec)
}

private val artistGradientPalettes: List<List<Color>> = listOf(
    listOf(Color(0xFFFA709A), Color(0xFFFEE140)),
    listOf(Color(0xFF43E97B), Color(0xFF38F9D7)),
    listOf(Color(0xFF4FACFE), Color(0xFF00F2FE)),
    listOf(Color(0xFFFF9A9E), Color(0xFFFAD0C4)),
    listOf(Color(0xFFA18CD1), Color(0xFFFBC2EB)),
    listOf(Color(0xFFFFC371), Color(0xFFFF5F6D)),
    listOf(Color(0xFF30CFD0), Color(0xFF330867)),
    listOf(Color(0xFF84FAB0), Color(0xFF8FD3F4)),
    listOf(Color(0xFFFFD194), Color(0xFFD1913C)),
    listOf(Color(0xFF56CCF2), Color(0xFF2F80ED)),
    listOf(Color(0xFFF6D365), Color(0xFFFDA085)),
    listOf(Color(0xFF00C9FF), Color(0xFF92FE9D)),
    listOf(Color(0xFFF093FB), Color(0xFFF5576C)),
    listOf(Color(0xFF5EFCE8), Color(0xFF736EFE)),
    listOf(Color(0xFFFFA8A8), Color(0xFFFFD3B6)),
    listOf(Color(0xFF89F7FE), Color(0xFF66A6FF))
)

private fun gradientForArtist(artist: String): List<Color> {
    val normalized = artist.trim().lowercase().ifBlank { "unknown" }
    val seed = normalized.hashCode().absoluteValue
    // 7 is co-prime with 16 palettes: better spread than plain modulo for nearby hashes.
    // Use Long + floorMod to avoid Int overflow and negative indices.
    val mixed = seed.toLong() * 7L + (seed / 11).toLong()
    val index = Math.floorMod(mixed, artistGradientPalettes.size.toLong()).toInt()
    return artistGradientPalettes[index]
}

private fun extractYoutubeUrl(text: String): String? {
    val urlRegex = Regex("https?://\\S+")
    val candidate = urlRegex.find(text)?.value ?: return null
    val cleaned = candidate.trimEnd('.', ',', ';', ')', ']', '}', '"', '\'')
    return cleaned.takeIf {
        it.contains("youtube.com", ignoreCase = true) ||
            it.contains("youtu.be", ignoreCase = true)
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    YouOfflineTheme {
        Text("Preview unavailable without ViewModel")
    }
}