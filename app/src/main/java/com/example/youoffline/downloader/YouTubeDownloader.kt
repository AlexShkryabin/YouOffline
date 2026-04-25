package com.example.youoffline.downloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.net.Uri
import com.yausername.ffmpeg.FFmpeg
import com.example.youoffline.model.DownloadedAudio
import com.example.youoffline.model.DownloadQuality
import com.example.youoffline.model.VideoMetadata
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val TAG = "YouOffline"
private const val YT_DLP_UPDATE_ATTEMPT_KEY = "yt_dlp_last_update_attempt_ms"
private const val YT_DLP_UPDATE_SUCCESS_KEY = "yt_dlp_last_update_success_ms"
private const val YT_DLP_UPDATE_INTERVAL_MS = 12 * 60 * 60 * 1000L
private const val YT_DLP_UPDATE_TIMEOUT_SEC = 8L

data class DownloadResponse(val exitCode: Int, val out: String, val err: String = "")

class YouTubeDownloader(private val context: Context) {

    private val initialized = AtomicBoolean(false)
    private val metaPrefs = context.getSharedPreferences("youoffline_meta", Context.MODE_PRIVATE)

    private fun ensureYtDlpInitialized() {
        if (initialized.get()) {
            Log.d(TAG, "YtDlp already initialized")
            return
        }
        synchronized(this) {
            if (initialized.get()) return
            try {
                Log.d(TAG, "Initializing YotubeDL...")
                YoutubeDL.getInstance().init(context)
                Log.d(TAG, "YoutubeDL initialized")
                
                Log.d(TAG, "Initializing FFmpeg...")
                FFmpeg.getInstance().init(context)
                Log.d(TAG, "FFmpeg initialized")
                
                initialized.set(true)
                Log.d(TAG, "Initialization complete")

                // Auto-update disabled: new yt-dlp STABLE versions require EJS (external JS
                // runtime) unavailable on Android, breaking n-challenge solver and DASH quality.
            } catch (e: YoutubeDLException) {
                Log.e(TAG, "Initialization failed", e)
                throw IOException("Не удалось инициализировать yt-dlp: ${e.message}", e)
            }
        }
    }

    private fun maybeUpdateYtDlpIfStale(force: Boolean) {
        val now = System.currentTimeMillis()
        val lastAttempt = metaPrefs.getLong(YT_DLP_UPDATE_ATTEMPT_KEY, 0L)
        if (!force && now - lastAttempt < YT_DLP_UPDATE_INTERVAL_MS) {
            Log.d(TAG, "Skipping yt-dlp update check: checked recently")
            return
        }

        metaPrefs.edit().putLong(YT_DLP_UPDATE_ATTEMPT_KEY, now).apply()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<YoutubeDL.UpdateStatus> {
                YoutubeDL.getInstance().updateYoutubeDL(
                    context,
                    YoutubeDL.UpdateChannel.STABLE
                )
            }
            val status = future.get(YT_DLP_UPDATE_TIMEOUT_SEC, TimeUnit.SECONDS)
            Log.d(TAG, "yt-dlp update status: $status")
            if (status == YoutubeDL.UpdateStatus.DONE || status == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) {
                metaPrefs.edit().putLong(YT_DLP_UPDATE_SUCCESS_KEY, now).apply()
            }
        } catch (e: TimeoutException) {
            Log.w(TAG, "yt-dlp update check timeout after ${YT_DLP_UPDATE_TIMEOUT_SEC}s")
        } catch (e: Exception) {
            Log.w(TAG, "yt-dlp update check failed", e)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun getYtDlpVersionLabel(): String {
        return runCatching {
            val code = YoutubeDL.getInstance().version(context).orEmpty()
            val name = YoutubeDL.getInstance().versionName(context).orEmpty()
            when {
                code.isNotBlank() && name.isNotBlank() -> "$name ($code)"
                name.isNotBlank() -> name
                code.isNotBlank() -> code
                else -> "unknown"
            }
        }.getOrDefault("unknown")
    }

    private fun elapsedMs(startMs: Long): Long = SystemClock.elapsedRealtime() - startMs

    private fun cleanupTempDir(tempDir: File) {
        val cleanupStart = SystemClock.elapsedRealtime()
        // Delete ALL files — tempDir is a single-use staging area per download job.
        // Leftovers from previous runs would corrupt the "new files" detection logic.
        val allFiles = tempDir.listFiles()?.filter { it.isFile }.orEmpty()
        var removed = 0
        for (f in allFiles) {
            if (f.delete()) removed++
        }
        Log.d(TAG, "TIMING cleanupTempDir=${elapsedMs(cleanupStart)}ms, removed=$removed")
    }

    fun downloadAudio(
        url: String,
        useProxy: Boolean,
        quality: DownloadQuality,
        onProgress: (Float, String) -> Unit,
        onTrackSaved: ((DownloadedAudio) -> Unit)? = null,
        onTrackStart: ((trackNumber: Int) -> Unit)? = null
    ): DownloadedAudio {
        val totalStart = SystemClock.elapsedRealtime()
        Log.d(TAG, "====== START DOWNLOAD: $url, proxy=$useProxy ======")

        val initStart = SystemClock.elapsedRealtime()
        ensureYtDlpInitialized()
        onProgress(0f, "Проверка версии yt-dlp")
        val versionBefore = getYtDlpVersionLabel()
        Log.d(TAG, "yt-dlp runtime version: $versionBefore")
        // Auto-update disabled intentionally — see ensureYtDlpInitialized comment.
        val versionAfter = versionBefore
        Log.d(TAG, "TIMING ensureYtDlpInitialized=${elapsedMs(initStart)}ms")

        val tempDir = File(context.cacheDir, "downloads").apply { mkdirs() }
        cleanupTempDir(tempDir)
        Log.d(TAG, "Temp dir: ${tempDir.absolutePath}")
        val beforeMediaFiles = listMediaFiles(tempDir).map { it.absolutePath }.toSet()
        val isPlaylist = isPlaylistUrl(url)

        // For playlists prefer android first (usually less JS challenge overhead),
        // while keeping full fallback chain for reliability.
        val clients = if (isPlaylist) {
            listOf<String?>("android", "mweb", "web", "web_creator", null)
        } else {
            listOf<String?>("mweb", "android", "web", "web_creator", null)
        }
        val baseAttempts = clients.size
        Log.d(TAG, "Starting client fallback chain with ${clients.size} clients")

        val ytdlpStart = SystemClock.elapsedRealtime()
        var lastResponse = DownloadResponse(1, "Не начато")
        val playlistChunkSize = 1
        var playlistProducedFiles = false
        var chunkIndex = 0

        val saved = mutableListOf<DownloadedAudio>()
        val saveStart = SystemClock.elapsedRealtime()

        while (true) {
            val chunkRange = if (isPlaylist) {
                val start = chunkIndex * playlistChunkSize + 1
                "$start-${start + playlistChunkSize - 1}"
            } else {
                null
            }

            if (isPlaylist) {
                val trackNumber = chunkIndex + 1
                Log.d(TAG, "Playlist chunk start: $chunkRange")
                onTrackStart?.invoke(trackNumber)
                onProgress(0f, "Трек $trackNumber · Подготовка...")
            }

            val chunkBeforeMediaFiles = listMediaFiles(tempDir).map { it.absolutePath }.toSet()

            // For playlists: disable fast extractor hints (player_skip=webpage,configs) because
            // skipping those pages prevents yt-dlp from enumerating all playlist items.
            lastResponse = executeRequest(
                buildDownloadRequest(
                    url = url,
                    tempDir = tempDir,
                    useProxy = useProxy,
                    playerClient = clients[0],
                    fastFormat = true,
                    fastExtractorHints = !isPlaylist,
                    quality = quality,
                    allowPlaylist = isPlaylist,
                    playlistItems = chunkRange
                ),
                onProgress,
                timeoutSeconds = if (isPlaylist) 120L else 60L
            )
            Log.d(TAG, "First attempt (${clients[0]} client, fast format): exitCode=${lastResponse.exitCode}")

            for ((idx, client) in clients.drop(1).withIndex()) {
                if (lastResponse.exitCode == 0) {
                    Log.d(TAG, "Download succeeded on attempt ${idx}")
                    break
                }
                val out = lastResponse.out
                Log.d(TAG, "Attempt ${idx} failed. Output: ${out.take(200)}")

                val isRetryable = out.contains("403", ignoreCase = true) ||
                    out.contains("SABR", ignoreCase = true) ||
                    out.contains("missing a url", ignoreCase = true) ||
                    out.contains("forcing SABR streaming", ignoreCase = true) ||
                    out.contains("PO Token", ignoreCase = true) ||
                    out.contains("Please sign in", ignoreCase = true) ||
                    out.contains("--cookies-from-browser", ignoreCase = true) ||
                    out.contains("for the authentication", ignoreCase = true) ||
                    out.contains("HTTP Error 416", ignoreCase = true) ||
                    out.contains("Requested range not satisfiable", ignoreCase = true) ||
                    out.contains("unsupported client", ignoreCase = true) ||
                    out.contains("Skipping unsupported client", ignoreCase = true) ||
                    out.contains("The page needs to be reloaded", ignoreCase = true) ||
                    out.contains("needs to be reloaded", ignoreCase = true) ||
                    out.contains("Only images are available", ignoreCase = true) ||
                    out.contains("drm protected", ignoreCase = true) ||
                    out.contains("not available", ignoreCase = true) ||
                    out.contains("Requested format is not available", ignoreCase = true)
                if (!isRetryable) {
                    Log.d(TAG, "Error not retryable, breaking")
                    break
                }
                Log.d(TAG, "Retrying with client: $client")
                val attemptNo = idx + 2 // first request is 1, retries start from 2
                onProgress(0f, "Повторная попытка $attemptNo/$baseAttempts: режим ${client ?: "neutral"}")
                val retryStart = SystemClock.elapsedRealtime()
                lastResponse = executeRequest(
                    buildDownloadRequest(
                        url = url,
                        tempDir = tempDir,
                        useProxy = useProxy,
                        playerClient = client,
                        fastFormat = false,
                        fastExtractorHints = false,
                        quality = quality,
                        allowPlaylist = isPlaylist,
                        playlistItems = chunkRange
                    ),
                    onProgress,
                    timeoutSeconds = if (isPlaylist) 120L else 60L
                )
                Log.d(TAG, "Retry attempt ${idx} with $client: exitCode=${lastResponse.exitCode}")
                Log.d(TAG, "TIMING retry[$client]=${elapsedMs(retryStart)}ms")
            }

            // Run neutral fallback only as last resort for explicit reload-required errors.
            if (lastResponse.exitCode != 0) {
                val out = lastResponse.out
                val needsReload = out.contains("needs to be reloaded", ignoreCase = true) ||
                    out.contains("page needs to be reloaded", ignoreCase = true)
                if (needsReload) {
                    Log.w(TAG, "Detected reload-required error after client chain, running short neutral retry")
                    onProgress(0f, "Доп. попытка: перезагрузка источника, нейтральный режим")
                    val neutralStart = SystemClock.elapsedRealtime()
                    lastResponse = executeRequest(
                        buildDownloadRequest(
                            url = url,
                            tempDir = tempDir,
                            useProxy = useProxy,
                            playerClient = null,
                            fastFormat = false,
                            fastExtractorHints = false,
                            quality = quality,
                            allowPlaylist = isPlaylist,
                            playlistItems = chunkRange
                        ),
                        onProgress,
                        timeoutSeconds = if (isPlaylist) 120L else 60L
                    )
                    Log.d(TAG, "Neutral retry exitCode=${lastResponse.exitCode}")
                    Log.d(TAG, "TIMING neutralRetry=${elapsedMs(neutralStart)}ms")
                }
            }

            // Dedicated fallback for HTTP 416: retry via ffmpeg downloader with seek disabled.
            if (lastResponse.exitCode != 0) {
                val out = lastResponse.out
                val isHttp416 = out.contains("HTTP Error 416", ignoreCase = true) ||
                    out.contains("Requested range not satisfiable", ignoreCase = true)
                if (isHttp416) {
                    Log.w(TAG, "Detected HTTP 416, running no-range fallback retry")
                    onProgress(0f, "Доп. попытка: ошибка 416, совместимый режим")
                    val noRangeStart = SystemClock.elapsedRealtime()
                    lastResponse = executeRequest(
                        buildDownloadRequest(
                            url = url,
                            tempDir = tempDir,
                            useProxy = useProxy,
                            playerClient = "web",
                            fastFormat = false,
                            fastExtractorHints = false,
                            forceNoRange = true,
                            quality = quality,
                            allowPlaylist = isPlaylist,
                            playlistItems = chunkRange
                        ),
                        onProgress,
                        timeoutSeconds = if (isPlaylist) 120L else 60L
                    )
                    Log.d(TAG, "No-range retry exitCode=${lastResponse.exitCode}")
                    Log.d(TAG, "TIMING noRangeRetry=${elapsedMs(noRangeStart)}ms")
                }
            }

            val chunkNewMediaFiles = listMediaFiles(tempDir)
                .filter { it.absolutePath !in chunkBeforeMediaFiles }
                .sortedByDescending { it.lastModified() }

            if (chunkNewMediaFiles.isNotEmpty()) {
                playlistProducedFiles = true
                chunkNewMediaFiles.forEachIndexed { index, file ->
                    val totalInChunk = chunkNewMediaFiles.size
                    val chunkLabel = if (isPlaylist) " (пакет $chunkRange)" else ""
                    onProgress(1f, "Сохранение ${index + 1}/$totalInChunk$chunkLabel")
                    Log.d(TAG, "Starting save to Music: ${file.name}")
                    val channelStart = SystemClock.elapsedRealtime()
                    val channelName = readChannelFromJson(tempDir, file)
                    Log.d(TAG, "Channel name: $channelName")
                    Log.d(TAG, "TIMING readChannelFromJson=${elapsedMs(channelStart)}ms")
                    runCatching {
                        val savedTrack = saveToMusic(file, channelName)
                        saved += savedTrack
                        onTrackSaved?.invoke(savedTrack)
                    }
                        .onFailure { e -> Log.e(TAG, "saveToMusic failed for ${file.name}: ${e.message}") }
                }
            }

            if (!isPlaylist) {
                break
            }

            // End of playlist reached: requested range produced no files.
            if (chunkNewMediaFiles.isEmpty()) {
                Log.d(TAG, "Playlist chunk produced no files, stopping at range=$chunkRange")
                break
            }

            // If this chunk failed hard, stop but keep already saved items.
            if (lastResponse.exitCode != 0) {
                Log.w(TAG, "Playlist chunk failed after partial progress, stopping on range=$chunkRange")
                break
            }

            chunkIndex++

        }
        Log.d(TAG, "TIMING ytDlpStageTotal=${elapsedMs(ytdlpStart)}ms")

        if (lastResponse.exitCode != 0 && saved.isEmpty()) {
            val details = lastResponse.out.ifBlank { "yt-dlp завершился с ошибкой, код=${lastResponse.exitCode}" }
            Log.e(TAG, "Download failed: $details")
            throw IOException("$details (yt-dlp: $versionAfter)")
        }

        if (saved.isEmpty() && !playlistProducedFiles) {
            val detectStart = SystemClock.elapsedRealtime()
            val newMediaFiles = listMediaFiles(tempDir)
                .filter { it.absolutePath !in beforeMediaFiles }
                .sortedByDescending { it.lastModified() }

            val filesToSave = if (newMediaFiles.isNotEmpty()) {
                newMediaFiles
            } else {
                tempDir.listFiles()
                    ?.filter { it.isFile }
                    ?.maxByOrNull { it.lastModified() }
                    ?.let { listOf(it) }
                    ?: emptyList()
            }

            if (filesToSave.isEmpty()) {
                Log.e(TAG, "No file found in ${tempDir.absolutePath}")
                val files = tempDir.listFiles() ?: emptyArray()
                Log.e(TAG, "Files in temp dir: ${files.map { it.name }}")
                throw IOException("Файл не найден после скачивания")
            }
            Log.d(TAG, "TIMING detectDownloadedFile=${elapsedMs(detectStart)}ms")

            filesToSave.forEachIndexed { index, file ->
                onProgress(1f, "Сохранение ${index + 1}/${filesToSave.size}")
                Log.d(TAG, "Starting save to Music: ${file.name}")
                val channelStart = SystemClock.elapsedRealtime()
                val channelName = readChannelFromJson(tempDir, file)
                Log.d(TAG, "Channel name: $channelName")
                Log.d(TAG, "TIMING readChannelFromJson=${elapsedMs(channelStart)}ms")
                runCatching {
                    val savedTrack = saveToMusic(file, channelName)
                    saved += savedTrack
                    onTrackSaved?.invoke(savedTrack)
                }
                    .onFailure { e -> Log.e(TAG, "saveToMusic failed for ${file.name}: ${e.message}") }
            }
        }

        if (saved.isEmpty()) {
            throw IOException("Не удалось сохранить ни одного файла")
        }

        return saved.first().also {
            Log.d(TAG, "Saved files count=${saved.size}")
            Log.d(TAG, "TIMING saveToMusic=${elapsedMs(saveStart)}ms")
            Log.d(TAG, "TIMING totalDownloadPipeline=${elapsedMs(totalStart)}ms")
            Log.d(TAG, "====== DOWNLOAD COMPLETE ======")
        }
    }

    private fun resolveChannelName(url: String): String {
        Log.d(TAG, "resolveChannelName: Getting info for $url")
        return runCatching {
            val info = YoutubeDL.getInstance().getInfo(url)
            val result = info.uploader?.takeIf { it.isNotBlank() } ?: "Неизвестный канал"
            Log.d(TAG, "resolveChannelName: Got $result")
            result
        }.getOrDefault("Неизвестный канал").also {
            Log.d(TAG, "resolveChannelName: Final result=$it")
        }
    }

    private fun readChannelFromJson(tempDir: File, mediaFile: File): String {
        Log.d(TAG, "readChannelFromJson: Looking for .info.json files in ${tempDir.absolutePath}")
        return runCatching {
            val jsonFiles = tempDir.listFiles { f -> f.name.endsWith(".info.json") } ?: emptyArray()
            Log.d(TAG, "Found ${jsonFiles.size} JSON files")
            
            if (jsonFiles.isEmpty()) {
                Log.d(TAG, "No JSON files found")
                return@runCatching "Unknown Channel"
            }
            
            val expectedName = "${mediaFile.nameWithoutExtension}.info.json"
            val jsonFile = jsonFiles.firstOrNull { it.name == expectedName }
                ?: jsonFiles.maxByOrNull { it.lastModified() }
                ?: return@runCatching "Unknown Channel"
            Log.d(TAG, "Reading JSON from: ${jsonFile.name}")
            
            val jsonContent = jsonFile.readText()
            val json = JSONObject(jsonContent)
            val uploader = sequenceOf("uploader", "channel", "creator")
                .mapNotNull { key -> json.optString(key).takeIf { it.isNotBlank() && it != "null" } }
                .firstOrNull()
            
            Log.d(TAG, "Parsed uploader: $uploader")
            uploader ?: "Unknown Channel"
        }.getOrDefault("Unknown Channel").also {
            Log.d(TAG, "readChannelFromJson result: $it")
        }
    }

    /**
     * Updates the bundled yt-dlp binary to the latest stable version.
     * Returns a description of the result.
     */
    fun updateYtDlp(): String {
        ensureYtDlpInitialized()
        val before = getYtDlpVersionLabel()
        maybeUpdateYtDlpIfStale(force = true)
        val after = getYtDlpVersionLabel()
        return if (before != after && after != "unknown") {
            "yt-dlp обновлён: $before -> $after"
        } else {
            "yt-dlp версия: $after"
        }
    }

    private fun buildDownloadRequest(
        url: String,
        tempDir: File,
        useProxy: Boolean,
        playerClient: String?,
        fastFormat: Boolean,
        fastExtractorHints: Boolean = false,
        forceNoRange: Boolean = false,
        quality: DownloadQuality,
        allowPlaylist: Boolean,
        playlistItems: String? = null
    ): YoutubeDLRequest {
        Log.d(TAG, "buildDownloadRequest: client=$playerClient, proxy=$useProxy, fastFormat=$fastFormat, fastExtractorHints=$fastExtractorHints, forceNoRange=$forceNoRange")
        return YoutubeDLRequest(url).apply {
            if (allowPlaylist) {
                addOption("--yes-playlist")
                addOption("--ignore-errors")
                if (!playlistItems.isNullOrBlank()) {
                    addOption("--playlist-items", playlistItems)
                }
            } else {
                addOption("--no-playlist")
            }
            addOption("--newline")
            addOption("--no-update")
            addOption("--no-part")
            addOption("--no-continue")
            addOption("--force-overwrites")
            addOption("--extractor-retries", "3")
            addOption("--fragment-retries", "3")
            if (forceNoRange) {
                addOption("--downloader", "ffmpeg")
                addOption("--downloader-args", "ffmpeg_i:-http_seekable 0")
            }
            // Quality profile selection.
            // NOTE: do NOT include container names like "m4a" or "mp4a" here — yt-dlp interprets
            // them as remux targets and converts the downloaded file, producing double extensions
            // like "title.mp4.m4a". Use only format IDs (140) or stream selectors (ba/bestaudio).
            val format = when (quality) {
                DownloadQuality.BEST -> if (fastFormat) "140/ba/bestaudio/best" else "ba/bestaudio/best"
                DownloadQuality.MEDIUM -> "bestaudio[abr<=128]/bestaudio[asr<=44100]/ba/bestaudio/best"
                // Prefer known low-bitrate audio IDs first, then fall back gracefully.
                DownloadQuality.LOW -> "139/249/250/worstaudio/ba/bestaudio/best"
            }
            addOption("-f", format)
            addOption("-o", "${tempDir.absolutePath}/%(title)s.%(ext)s")
            addOption("--write-info-json")
            addOption("--embed-metadata")
            when (quality) {
                DownloadQuality.BEST -> {
                    // Always extract audio to strip any video stream that may have been
                    // downloaded via the "best" combined-format fallback.
                    addOption("-x")
                    addOption("--audio-format", "m4a")
                }
                DownloadQuality.MEDIUM -> {
                    addOption("-x")
                    addOption("--audio-format", "m4a")
                    addOption("--audio-quality", "128K")
                }
                DownloadQuality.LOW -> {
                    addOption("-x")
                    addOption("--audio-format", "m4a")
                    addOption("--audio-quality", "64K")
                }
            }
            if (playerClient != null) {
                val extractorArgs = if (fastExtractorHints) {
                    "youtube:player_client=$playerClient;player_skip=webpage,configs"
                } else {
                    "youtube:player_client=$playerClient"
                }
                addOption("--extractor-args", extractorArgs)
                Log.d(TAG, "Added player_client=$playerClient")
            }
            if (useProxy) {
                addOption("--proxy", "socks5h://127.0.0.1:1080")
                Log.d(TAG, "Added proxy: socks5h://127.0.0.1:1080")
            }
        }
    }

    private fun executeRequest(
        request: YoutubeDLRequest,
        onProgress: (Float, String) -> Unit,
        timeoutSeconds: Long = 60
    ): DownloadResponse {
        Log.d(TAG, "executeRequest: Starting with inactivity timeout=${timeoutSeconds}s")
        val requestStart = SystemClock.elapsedRealtime()
        val timeoutMs = timeoutSeconds * 1000L
        val lastActivityAt = AtomicLong(SystemClock.elapsedRealtime())
        val currentItem = AtomicReference<String>("подготовка")
        val itemNum = AtomicInteger(0)
        val pendingItemNum = AtomicInteger(0)
        val totalItems = AtomicInteger(0)
        val executor = Executors.newSingleThreadExecutor()

        fun parseCurrentItem(line: String?): String? {
            if (line.isNullOrBlank()) return null
            val trimmed = line.trim()
            val destination = Regex("""(?i)destination:\s*(.+)$""")
                .find(trimmed)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?.substringBeforeLast('.')
                ?.takeIf { it.isNotBlank() }
            if (destination != null) return destination

            val itemProgress = Regex("""(?i)item\s+(\d+)\s+of\s+(\d+)""")
                .find(trimmed)
                ?.let { "элемент ${it.groupValues[1]}/${it.groupValues[2]}" }
            return itemProgress
        }

        try {
            val future = executor.submit {
                Log.d(TAG, "executeRequest: Inside executor thread, calling YoutubeDL.execute()")
                var lineCount = 0
                val itemRegex = Regex("""(?i)^\[download]\s+Downloading\s+item\s+(\d+)\s+of\s+(\d+)""")
                val destinationRegex = Regex("""(?i)^\[download]\s+Destination:\s+""")
                YoutubeDL.getInstance().execute(request) { progress, etaInSeconds, line ->
                    lineCount++
                    lastActivityAt.set(SystemClock.elapsedRealtime())
                    parseCurrentItem(line)?.let { currentItem.set(it) }

                    if (!line.isNullOrBlank()) {
                        val trimmedLine = line.trim()
                        itemRegex.find(trimmedLine)?.let {
                            pendingItemNum.set(it.groupValues[1].toIntOrNull() ?: pendingItemNum.get())
                            totalItems.set(it.groupValues[2].toIntOrNull() ?: totalItems.get())
                        }
                        if (destinationRegex.containsMatchIn(trimmedLine)) {
                            val pending = pendingItemNum.get()
                            if (pending > 0) itemNum.set(pending)
                        }
                    }

                    val p = (progress.coerceIn(0f, 100f)) / 100f
                    val n = itemNum.get().takeIf { it > 0 } ?: pendingItemNum.get()
                    val t = totalItems.get()
                    val lineText = line.trim()
                    val hasLine = lineText.isNotEmpty()
                    val msg = when {
                        itemRegex.containsMatchIn(lineText) ->
                            "Подготовка трека $n/$t"
                        t > 1 && !hasLine ->
                            "[$n/$t] Скачивание ${(p * 100).toInt()}%"
                        t > 1 ->
                            "[$n/$t] $lineText"
                        !hasLine ->
                            "Скачивание ${(p * 100).toInt()}% (ETA ${etaInSeconds}s)"
                        else -> lineText
                    }
                    Log.d(TAG, "[Line $lineCount] progress=$p eta=${etaInSeconds}s: ${line?.take(100) ?: "(null)"}")
                    onProgress(p, msg)
                }
            }

            var lastHeartbeatSecond = -1
            while (true) {
                try {
                    val response = future.get(1, TimeUnit.SECONDS)
                    Log.d(TAG, "executeRequest: Got response, parsing...")

                    val exitCode = response?.javaClass?.getDeclaredField("exitCode")?.let {
                        it.isAccessible = true
                        it.getInt(response)
                    } ?: 0
                    val out = response?.javaClass?.getDeclaredField("out")?.let {
                        it.isAccessible = true
                        it.get(response) as String
                    } ?: ""

                    Log.d(TAG, "executeRequest: exitCode=$exitCode, output length=${out.length}")
                    Log.d(TAG, "TIMING executeRequest=${elapsedMs(requestStart)}ms")
                    Log.d(TAG, "Output (first 500 chars): ${out.take(500)}")
                    return DownloadResponse(exitCode, out)
                } catch (_: TimeoutException) {
                    val now = SystemClock.elapsedRealtime()
                    val idleMs = now - lastActivityAt.get()
                    if (idleMs >= timeoutMs) {
                        val itemLabel = currentItem.get().ifBlank { "текущий этап" }
                        Log.e(TAG, "INACTIVITY TIMEOUT after ${timeoutSeconds}s on $itemLabel")
                        Log.d(TAG, "TIMING executeRequestInactivityTimeout=${elapsedMs(requestStart)}ms")
                        future.cancel(true)
                        val timeoutMsg = "Таймаут бездействия ${timeoutSeconds}с: $itemLabel"
                        onProgress(0f, timeoutMsg)
                        return DownloadResponse(124, timeoutMsg)
                    }

                    val waitSec = ((now - requestStart) / 1000L).toInt()
                    if (waitSec >= 10 && waitSec % 5 == 0 && waitSec != lastHeartbeatSecond) {
                        lastHeartbeatSecond = waitSec
                        val n = itemNum.get().takeIf { it > 0 } ?: pendingItemNum.get()
                        val t = totalItems.get()
                        val status = if (t > 1 && n > 0) {
                            "Подготовка (элемент $n/$t)"
                        } else {
                            "Подготовка"
                        }
                        onProgress(0f, status)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeRequest: Exception occurred", e)
            return DownloadResponse(1, e.message ?: "Ошибка при выполнении запроса")
        } finally {
            executor.shutdownNow()
            Log.d(TAG, "executeRequest: Executor shutdown")
        }
    }

    private fun parseProgress(line: String): Float? {
        val match = Regex("\\[download]\\s+([0-9]{1,3}(?:\\.[0-9]+)?)%")
            .find(line)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null

        return (match.toFloatOrNull() ?: return null)
            .div(100f)
            .coerceIn(0f, 1f)
    }

    private fun listMediaFiles(tempDir: File): List<File> {
        val mediaExtensions = setOf("m4a", "mp3", "opus", "webm", "aac", "ogg", "wav", "flac", "mp4")
        return tempDir.listFiles()
            ?.filter { it.isFile }
            ?.filter { it.extension.lowercase(Locale.US) in mediaExtensions }
            ?: emptyList()
    }

    private fun isPlaylistUrl(url: String): Boolean {
        return url.contains("list=", ignoreCase = true)
    }

    private fun saveToMusic(source: File, channelName: String): DownloadedAudio {
        Log.d(TAG, "saveToMusic: source=${source.name}, channelName=$channelName")
        // Map file extension → (displayName, mimeType).
        // If yt-dlp downloads a combined mp4 (e.g. format 18), Android MediaStore will append
        // ".m4a" when MIME is "audio/mp4" but the display name already ends in ".mp4".
        // Prevent double-extension by normalising mp4 → m4a for audio entries.
        val ext = source.extension.lowercase(Locale.US)
        val (displayName, mimeType) = when (ext) {
            "mp4"  -> "${source.nameWithoutExtension}.m4a" to "audio/mp4"
            "m4a"  -> source.name to "audio/mp4"
            "mp3"  -> source.name to "audio/mpeg"
            "opus" -> source.name to "audio/ogg"
            "webm" -> source.name to "audio/webm"
            "aac"  -> source.name to "audio/aac"
            "ogg"  -> source.name to "audio/ogg"
            else   -> source.name to "audio/mp4"
        }
        Log.d(TAG, "saveToMusic: displayName=$displayName, mimeType=$mimeType")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.d(TAG, "Using MediaStore (Q+)")
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.TITLE, displayName.substringBeforeLast('.'))
                put(MediaStore.Audio.Media.ARTIST, channelName)
                put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/YouOffline")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }

            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: run {
                    Log.e(TAG, "Failed to insert MediaStore record")
                    throw IOException("Не удалось создать запись в MediaStore")
                }
            Log.d(TAG, "Created MediaStore entry: $uri")

            resolver.openOutputStream(uri).use { output ->
                source.inputStream().use { input ->
                    if (output == null) {
                        Log.e(TAG, "Failed to open output stream")
                        throw IOException("Не удалось открыть выходной поток")
                    }
                    Log.d(TAG, "Copying file to MediaStore...")
                    input.copyTo(output)
                    Log.d(TAG, "File copy complete")
                }
            }

            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Log.d(TAG, "Updated pending flag")
            
            source.delete()
            Log.d(TAG, "Deleted temp file")

            DownloadedAudio(
                displayName = displayName,
                title = displayName.substringBeforeLast('.'),
                artist = channelName,
                durationMs = 0L,
                pathOrUri = uri.toString(),
                sizeBytes = resolver.openFileDescriptor(uri, "r")?.statSize ?: source.length()
            ).also {
                persistArtistMeta(it.pathOrUri, channelName)
            }
        } else {
            Log.d(TAG, "Using legacy file storage")
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val targetDir = File(musicDir, "YouOffline").apply { mkdirs() }
            val target = File(targetDir, displayName)
            Log.d(TAG, "Copying to: ${target.absolutePath}")
            source.copyTo(target, overwrite = true)
            source.delete()
            DownloadedAudio(
                displayName = target.name,
                title = target.name.substringBeforeLast('.'),
                artist = channelName,
                durationMs = 0L,
                pathOrUri = target.absolutePath,
                sizeBytes = target.length()
            ).also {
                persistArtistMeta(it.pathOrUri, channelName)
            }
        }
    }

    private fun persistArtistMeta(pathOrUri: String, artist: String) {
        if (artist.isBlank()) return
        metaPrefs.edit().putString("artist:$pathOrUri", artist).apply()
    }

    private fun resolveArtist(pathOrUri: String, mediaStoreArtist: String?): String {
        val normalized = mediaStoreArtist
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.takeUnless { it.equals("<unknown>", ignoreCase = true) }
            ?.takeUnless { it.equals("unknown", ignoreCase = true) }
            ?.takeUnless { it.equals("unknown artist", ignoreCase = true) }
            ?.takeUnless { it.equals("неизвестный исполнитель", ignoreCase = true) }

        if (normalized != null) return normalized

        return metaPrefs.getString("artist:$pathOrUri", null)
            ?.takeIf { it.isNotBlank() }
            ?: "Неизвестный исполнитель"
    }

    private fun ensureLibraryFolderIndexed() {
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val targetDir = File(musicDir, "YouOffline")
        if (!targetDir.exists() || !targetDir.isDirectory) return

        val audioFiles = targetDir.listFiles()
            ?.filter { it.isFile }
            ?.filter { file ->
                when (file.extension.lowercase(Locale.US)) {
                    "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "webm", "mp4" -> true
                    else -> false
                }
            }
            .orEmpty()

        if (audioFiles.isEmpty()) return

        val latch = CountDownLatch(audioFiles.size)
        MediaScannerConnection.scanFile(
            context,
            audioFiles.map { it.absolutePath }.toTypedArray(),
            null
        ) { _, _ -> latch.countDown() }
        runCatching { latch.await(2, TimeUnit.SECONDS) }
    }

    fun listDownloadedFiles(): List<DownloadedAudio> {
        val result = mutableListOf<DownloadedAudio>()
        ensureLibraryFolderIndexed()
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DATA
        )
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%Music/YouOffline/%", "%YouOffline/%")
        val sort = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sort
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol)
                val title = cursor.getString(titleCol)?.ifBlank { null } ?: name.substringBeforeLast('.')
                val duration = cursor.getLong(durationCol)
                val size = cursor.getLong(sizeCol)
                val filePath = if (dataCol >= 0) cursor.getString(dataCol) else null
                val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.buildUpon()
                    .appendPath(id.toString())
                    .build()
                val pathOrUri = uri.toString()
                val msArtist = cursor.getString(artistCol)
                val resolvedArtist = resolveArtist(pathOrUri, msArtist)
                    .let { artist ->
                        if (artist == "Неизвестный исполнитель" && filePath != null) {
                            readArtistFromFile(filePath) ?: artist
                        } else artist
                    }
                result += DownloadedAudio(
                    displayName = name,
                    title = title,
                    artist = resolvedArtist,
                    durationMs = duration,
                    pathOrUri = pathOrUri,
                    sizeBytes = size
                )
            }
        }

        return result
    }

    private fun readArtistFromFile(path: String): String? {
        return runCatching {
            MediaMetadataRetriever().use { mmr ->
                mmr.setDataSource(path)
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.takeUnless { it.equals("<unknown>", ignoreCase = true) }
            }
        }.getOrNull()
    }

    fun deleteDownloadedFile(pathOrUri: String) {
        val deleted = if (pathOrUri.startsWith("content://")) {
            context.contentResolver.delete(Uri.parse(pathOrUri), null, null) > 0
        } else {
            File(pathOrUri).takeIf { it.exists() }?.delete() == true
        }
        metaPrefs.edit().remove("artist:$pathOrUri").apply()
        if (!deleted) {
            throw IOException("Не удалось удалить трек")
        }
    }

    /** Removes only the stored artist meta without touching the file (used after system delete). */
    fun cleanArtistMeta(pathOrUri: String) {
        metaPrefs.edit().remove("artist:$pathOrUri").apply()
    }

    companion object {
        fun formatDuration(seconds: Int): String {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            return if (h > 0) {
                String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            } else {
                String.format(Locale.US, "%02d:%02d", m, s)
            }
        }
    }
}
