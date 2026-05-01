package com.example.youoffline.downloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.media.MediaScannerConnection
import com.example.youoffline.model.DownloadedVideo
import com.example.youoffline.model.VideoQuality
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "VideoDownloader"
private const val YT_DLP_UPDATE_ATTEMPT_KEY = "yt_dlp_last_update_attempt_ms"
private const val YT_DLP_UPDATE_SUCCESS_KEY = "yt_dlp_last_update_success_ms"
private const val YT_DLP_UPDATE_INTERVAL_MS = 12 * 60 * 60 * 1000L
private const val YT_DLP_UPDATE_TIMEOUT_SEC = 8L
private const val YT_ANDROID_USER_AGENT = "com.google.android.youtube/19.16.39 (Linux; U; Android 13)"
private const val YT_REQUEST_GAP_MS = 900L
private const val YT_RETRY_GAP_MS = 450L
private const val YT_BOT_RETRY_LIMIT = 2
private const val PREF_VIDEO_EXTRACTOR_ARGS = "yt_video_preferred_extractor_args"

class VideoDownloader(private val context: Context) {

    private val initialized = AtomicBoolean(false)
    private val thumbDir = File(context.filesDir, "video_thumbs").also { it.mkdirs() }
    private val metaPrefs = context.getSharedPreferences("youoffline_meta", Context.MODE_PRIVATE)
    private val cancelRequested = AtomicBoolean(false)
    private val activeRequestFuture = AtomicReference<Future<*>?>(null)

    fun cancelActiveDownload() {
        cancelRequested.set(true)
        activeRequestFuture.get()?.cancel(true)
    }

    private fun ensureNotCancelled() {
        if (cancelRequested.get()) {
            throw DownloadCancelledException()
        }
    }

    private fun ensureInitialized() {
        if (initialized.get()) return
        synchronized(this) {
            if (initialized.get()) return
            try {
                YoutubeDL.getInstance().init(context)
                FFmpeg.getInstance().init(context)
                initialized.set(true)
                // Auto-update disabled: new yt-dlp versions may require EJS (external JS runtime)
                // unavailable on Android, breaking n-challenge solver and DASH quality.
            } catch (e: YoutubeDLException) {
                throw IOException("Не удалось инициализировать yt-dlp: ${e.message}", e)
            }
        }
    }

    private fun maybeUpdateYtDlpIfStale(force: Boolean) {
        val now = System.currentTimeMillis()
        val lastAttempt = metaPrefs.getLong(YT_DLP_UPDATE_ATTEMPT_KEY, 0L)
        if (!force && now - lastAttempt < YT_DLP_UPDATE_INTERVAL_MS) return

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
        } catch (_: TimeoutException) {
            Log.w(TAG, "yt-dlp update timeout after ${YT_DLP_UPDATE_TIMEOUT_SEC}s")
        } catch (e: Exception) {
            Log.w(TAG, "yt-dlp update failed", e)
        } finally {
            executor.shutdownNow()
        }
    }

    fun downloadVideo(
        url: String,
        quality: VideoQuality,
        onProgress: (Float, String) -> Unit,
        onTrackSaved: (() -> Unit)? = null,
        onTrackStart: ((trackNumber: Int) -> Unit)? = null
    ) {
        cancelRequested.set(false)
        ensureNotCancelled()
        ensureInitialized()
        val tempDir = File(context.cacheDir, "video_downloads").apply { mkdirs() }
        cleanDir(tempDir)
        val isPlaylist = isPlaylistUrl(url)

        // Cascade through lower resolutions before giving up on DASH entirely.
        // YouTube progressive ("best") streams max at ~360p — avoid them until last resort.
        val format = when (quality) {
            VideoQuality.BEST   -> "bestvideo+bestaudio/bestvideo[height<=1080]+bestaudio/bestvideo[height<=720]+bestaudio/best"
            VideoQuality.Q1440P -> "bestvideo[height<=1440]+bestaudio/bestvideo[height<=1080]+bestaudio/bestvideo[height<=720]+bestaudio/best"
            VideoQuality.Q1080P -> "bestvideo[height<=1080]+bestaudio/bestvideo[height<=720]+bestaudio/bestvideo[height<=480]+bestaudio/best"
            VideoQuality.Q720P  -> "bestvideo[height<=720]+bestaudio/bestvideo[height<=480]+bestaudio/bestvideo[height<=360]+bestaudio/best"
            VideoQuality.Q480P  -> "bestvideo[height<=480]+bestaudio/bestvideo[height<=360]+bestaudio/bestvideo[height<=240]+bestaudio/best"
            VideoQuality.Q360P  -> "bestvideo[height<=360]+bestaudio/bestvideo[height<=240]+bestaudio/best"
            VideoQuality.Q240P  -> "bestvideo[height<=240]+bestaudio/bestvideo[height<=144]+bestaudio/best"
            VideoQuality.Q144P  -> "bestvideo[height<=144]+bestaudio/bestvideo[height<=240]+bestaudio/best"
        }
        // Last-resort progressive: YouTube only has combined streams at 360p max.
        val progressiveFormat = when (quality) {
            VideoQuality.BEST   -> "best[ext=mp4]/best"
            VideoQuality.Q1440P -> "best[ext=mp4]/best"
            VideoQuality.Q1080P -> "best[ext=mp4]/best"
            VideoQuality.Q720P  -> "best[ext=mp4]/best"
            VideoQuality.Q480P  -> "best[height<=480][ext=mp4]/best[height<=480]/best"
            VideoQuality.Q360P  -> "best[height<=360][ext=mp4]/best[height<=360]/best"
            VideoQuality.Q240P  -> "best[height<=240][ext=mp4]/best[height<=240]/best"
            VideoQuality.Q144P  -> "best[height<=144][ext=mp4]/best[height<=144]/best[height<=240]/best"
        }
        Log.d(TAG, "DOWNLOAD_FORMAT: quality=$quality format=$format")

        var chunkIndex = 0
        var savedCount = 0
        var lastResponse = DownloadResponse(1, "Не начато")

        while (true) {
            ensureNotCancelled()

            if (isPlaylist && chunkIndex > 0) {
                onProgress(0f, "Пауза перед следующим треком...")
                SystemClock.sleep(YT_REQUEST_GAP_MS)
            }

            val trackNum = if (isPlaylist) chunkIndex + 1 else 0
            val chunkRange = if (isPlaylist) "$trackNum-$trackNum" else null

            val beforeFiles = tempDir.listFiles()?.map { it.absolutePath }?.toSet() ?: emptySet()
            lastResponse = downloadSingleRequestWithFallback(
                url = url,
                format = format,
                progressiveFormat = progressiveFormat,
                tempDir = tempDir,
                onProgress = onProgress,
                playlistItems = chunkRange
            )

            if (cancelRequested.get() || lastResponse.exitCode == 130) {
                throw DownloadCancelledException()
            }

            val videoExts = setOf("mp4", "mkv", "webm", "avi", "mov")
            val newFiles = tempDir.listFiles()
                ?.filter { it.isFile && it.absolutePath !in beforeFiles }
                ?.filter { it.extension.lowercase(Locale.US) in videoExts }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()

            if (newFiles.isEmpty()) {
                if (!isPlaylist) {
                    val details = (lastResponse.out + "\n" + lastResponse.err)
                        .trim()
                        .ifBlank { "Видеофайл не найден после скачивания" }
                    throw IOException(details)
                }
                break
            }

            // Track start notification comes after confirming file was downloaded
            if (isPlaylist && trackNum > 0) {
                onTrackStart?.invoke(trackNum)
            }

            val thumbExts = setOf("jpg", "jpeg", "webp")
            val thumbFile = tempDir.listFiles()
                ?.filter { it.isFile && it.absolutePath !in beforeFiles }
                ?.filter { it.extension.lowercase(Locale.US) in thumbExts }
                ?.maxByOrNull { it.lastModified() }

            newFiles.forEach {
                ensureNotCancelled()
                val trackNum = if (isPlaylist) chunkIndex + 1 else 0
                onProgress(1f, if (trackNum > 0) "Трек $trackNum · Сохранение..." else "Сохранение...")
                saveToMovies(it, thumbFile)
                savedCount++
                onTrackSaved?.invoke()
            }

            if (!isPlaylist) break
            if (lastResponse.exitCode != 0) break
            chunkIndex++
        }

        if (cancelRequested.get() || lastResponse.exitCode == 130) {
            throw DownloadCancelledException()
        }

        if (savedCount == 0 && lastResponse.exitCode != 0) {
            val details = (lastResponse.out + "\n" + lastResponse.err)
                .trim()
                .ifBlank { "yt-dlp завершился с ошибкой, код=${lastResponse.exitCode}" }
            Log.e(TAG, "Final video download failure, exitCode=${lastResponse.exitCode}, details=$details")
            val userMessage = if (isBotChallengeError(details)) {
                "YouTube отклонил запрос с этой сети. Попробуйте мобильный интернет, другой Wi-Fi или повторите позже."
            } else {
                details
            }
            throw IOException(userMessage)
        }

        if (savedCount == 0) {
            throw IOException("Видеофайл не найден после скачивания")
        }
    }

    private fun downloadSingleRequestWithFallback(
        url: String,
        format: String,
        progressiveFormat: String,
        tempDir: File,
        onProgress: (Float, String) -> Unit,
        playlistItems: String?
    ): DownloadResponse {
        val initialExtractorArgs = preferredVideoExtractorArgs() ?: "youtube:player_client=mweb"
        var response = executeRequest(
            buildDownloadRequest(
                url = url,
                format = format,
                tempDir = tempDir,
                extractorArgs = initialExtractorArgs,
                allowPlaylist = !playlistItems.isNullOrBlank(),
                playlistItems = playlistItems
            ),
                    onProgress
        )
        if (response.exitCode == 0) {
            rememberVideoExtractorArgs(initialExtractorArgs)
        }

        if (response.exitCode != 0) {
            val baseRetryPlans = listOf(
                Triple("youtube:player_client=tv_embedded", format, "Повтор 1/5: tv_embedded клиент..."),
                Triple("youtube:player_client=web", format, "Повтор 2/5: web клиент..."),
                Triple("youtube:player_client=android_vr,tv_embedded", format, "Повтор 3/5: android_vr+tv_embedded..."),
                Triple(null, format, "Повтор 4/5: клиент по умолчанию..."),
                Triple(null, progressiveFormat, "Повтор 5/5: совместимый поток (низкое качество)...")
            )
            val preferred = preferredVideoExtractorArgs()
            val retryPlans = if (!preferred.isNullOrBlank() && preferred != initialExtractorArgs &&
                baseRetryPlans.none { it.first == preferred }) {
                listOf(Triple(preferred, format, "Повтор 0/5: сохраненный режим сети...")) + baseRetryPlans
            } else {
                baseRetryPlans
            }

            for ((retryIndex, plan) in retryPlans.withIndex()) {
                val (extractorArgs, retryFormat, statusText) = plan
                ensureNotCancelled()
                if (response.exitCode == 0) break

                val details = (response.out + "\n" + response.err).trim()
                if (isBotChallengeError(details) && retryIndex >= YT_BOT_RETRY_LIMIT) {
                    Log.w(TAG, "Bot challenge persists, skipping remaining generic retries")
                    break
                }

                onProgress(0f, statusText)
                SystemClock.sleep(YT_RETRY_GAP_MS)
                Log.w(
                    TAG,
                    "Retry after failure: extractorArgs=${extractorArgs ?: "default"}, format=$retryFormat"
                )

                response = executeRequest(
                    buildDownloadRequest(
                        url = url,
                        format = retryFormat,
                        tempDir = tempDir,
                        extractorArgs = extractorArgs,
                        allowPlaylist = !playlistItems.isNullOrBlank(),
                        playlistItems = playlistItems
                    ),
                    onProgress
                )
                if (response.exitCode == 0) {
                    rememberVideoExtractorArgs(extractorArgs)
                }
            }
        }

        if (response.exitCode != 0 && isBotChallengeError(response.out + "\n" + response.err)) {
            ensureNotCancelled()
            onProgress(0f, "YouTube требует проверку сети, последняя попытка...")
            SystemClock.sleep(YT_RETRY_GAP_MS)
            response = executeRequest(
                buildDownloadRequest(
                    url = url,
                    format = format,
                    tempDir = tempDir,
                    extractorArgs = "youtube:player_client=ios,tv_embedded",
                    allowPlaylist = !playlistItems.isNullOrBlank(),
                    playlistItems = playlistItems
                ),
                onProgress
            )
            if (response.exitCode == 0) {
                rememberVideoExtractorArgs("youtube:player_client=ios,tv_embedded")
            }
        }

        if (response.exitCode != 0 && !isBotChallengeError(response.out + "\n" + response.err)) {
            ensureNotCancelled()
            val details = (response.out + "\n" + response.err).trim()
            if (isSabrOrForbidden(details)) {
                val emergencyFormat = "best[ext=mp4]/best"
                onProgress(0f, "Финальная попытка загрузки...")
                Log.w(TAG, "EMERGENCY_FALLBACK: trying safe progressive format=$emergencyFormat")
                response = executeRequest(
                    buildEmergencyRequest(
                        url = url,
                        format = emergencyFormat,
                        tempDir = tempDir,
                        allowPlaylist = !playlistItems.isNullOrBlank(),
                        playlistItems = playlistItems
                    ),
                    onProgress
                )
            }
        }

        return response
    }

    private fun chooseBestDownloadedFile(files: List<File>): File? {
        val existingFiles = files.filter { it.exists() && it.length() > 0L }
        return existingFiles.maxWithOrNull(
            compareBy<File> { it.length() }
                .thenBy { it.lastModified() }
        )
    }

    private fun buildDownloadRequest(
        url: String,
        format: String,
        tempDir: File,
        extractorArgs: String?,
        allowPlaylist: Boolean = false,
        playlistItems: String? = null
    ): YoutubeDLRequest {
        val uniquePrefix = UUID.randomUUID().toString().substring(0, 8)
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
            addOption("--sleep-requests", "1")
            addOption("--user-agent", YT_ANDROID_USER_AGENT)
            addOption("--add-header", "Accept-Language: en-US,en;q=0.9")
            addOption("--write-thumbnail")
            addOption("--convert-thumbnails", "jpg")
            if (!extractorArgs.isNullOrBlank()) {
                addOption("--extractor-args", extractorArgs)
            }
            addOption("-f", format)
            addOption("--merge-output-format", "mp4")
            addOption("-o", "${tempDir.absolutePath}/${uniquePrefix}_%(title)s.%(ext)s")
        }
    }

    private fun buildEmergencyRequest(
        url: String,
        format: String,
        tempDir: File,
        allowPlaylist: Boolean = false,
        playlistItems: String? = null
    ): YoutubeDLRequest {
        val uniquePrefix = UUID.randomUUID().toString().substring(0, 8)
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
            addOption("--no-update")
            addOption("--no-part")
            addOption("--no-continue")
            addOption("--force-overwrites")
            addOption("--sleep-requests", "1")
            addOption("--user-agent", YT_ANDROID_USER_AGENT)
            addOption("--add-header", "Accept-Language: en-US,en;q=0.9")
            addOption("-f", format)
            addOption("-o", "${tempDir.absolutePath}/${uniquePrefix}_%(title)s.%(ext)s")
        }
    }

    private fun isPlaylistUrl(url: String): Boolean {
        return url.contains("list=", ignoreCase = true)
    }

    private fun preferredVideoExtractorArgs(): String? {
        return metaPrefs.getString(PREF_VIDEO_EXTRACTOR_ARGS, null)?.takeIf { it.isNotBlank() }
    }

    private fun rememberVideoExtractorArgs(extractorArgs: String?) {
        if (extractorArgs.isNullOrBlank()) return
        if (metaPrefs.getString(PREF_VIDEO_EXTRACTOR_ARGS, null) == extractorArgs) return
        metaPrefs.edit().putString(PREF_VIDEO_EXTRACTOR_ARGS, extractorArgs).apply()
        Log.d(TAG, "Remembered preferred video extractor args: $extractorArgs")
    }

    private fun isSabrOrForbidden(message: String): Boolean {
        if (message.isBlank()) return false
        val text = message.lowercase(Locale.US)
        return text.contains("sabr") ||
            text.contains("http error 403") ||
            text.contains("forbidden") ||
            text.contains("missing a url") ||
            text.contains("po token") ||
            text.contains("gvs po token") ||
            isBotChallengeError(text)
    }

    private fun isBotChallengeError(message: String): Boolean {
        if (message.isBlank()) return false
        val text = message.lowercase(Locale.US)
        return text.contains("sign in to confirm you're not a bot") ||
            text.contains("please sign in") ||
            text.contains("use --cookies-from-browser") ||
            text.contains("use --cookies") ||
            text.contains("for the authentication") ||
            text.contains("no title found in player responses")
    }

    private fun executeRequest(
        request: YoutubeDLRequest,
        onProgress: (Float, String) -> Unit
    ): DownloadResponse {
        val requestStart = SystemClock.elapsedRealtime()
        val executor = Executors.newSingleThreadExecutor()
        var prepProgress = 0f
        var downloadStarted = false
        var visualProgress = 0f

        try {
            val future = executor.submit {
                YoutubeDL.getInstance().execute(request) { progress, etaInSeconds, line ->
                    val lineText = line?.trim() ?: ""
                    val p = (progress.coerceIn(0f, 100f)) / 100f
                    val isDownload = lineText.contains("[download]", ignoreCase = true) || p > 0f

                    val mappedProgress: Float
                    val mappedStatus: String

                    if (isDownload) {
                        downloadStarted = true
                        mappedProgress = (0.15f + p * 0.85f).coerceIn(0f, 1f)
                        mappedStatus = when {
                            lineText.contains("Destination:", ignoreCase = true) -> {
                                val name = lineText.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
                                if (name.isNotBlank()) "Загружаем \"$name\"" else "Скачивание ${(p * 100).toInt()}%"
                            }
                            else -> "Скачивание ${(p * 100).toInt()}%"
                        }
                    } else {
                        prepProgress = if (!downloadStarted) (prepProgress + 0.02f).coerceAtMost(0.15f) else prepProgress
                        mappedProgress = if (!downloadStarted) prepProgress else visualProgress
                        val prepPercent = (prepProgress * 100).toInt()
                        mappedStatus = when {
                            lineText.contains("Повтор", ignoreCase = true) -> lineText
                            lineText.contains("Ошибка", ignoreCase = true) -> lineText
                            lineText.startsWith("Подготовка", ignoreCase = true) -> "Подготовка... $prepPercent%"
                            else -> "Подготовка... $prepPercent%"
                        }
                    }

                    val stableP = maxOf(visualProgress, mappedProgress).coerceIn(0f, 1f)
                    visualProgress = stableP
                    onProgress(stableP, mappedStatus)
                }
            }
            activeRequestFuture.set(future)

            var lastHeartbeat = -1
            while (true) {
                if (cancelRequested.get()) {
                    future.cancel(true)
                    return DownloadResponse(130, "DOWNLOAD_CANCELLED")
                }
                try {
                    val response = future.get(1, TimeUnit.SECONDS)
                    val exitCode = response?.javaClass?.getDeclaredField("exitCode")?.let {
                        it.isAccessible = true; it.getInt(response)
                    } ?: 0
                    val out = response?.javaClass?.getDeclaredField("out")?.let {
                        it.isAccessible = true; it.get(response) as String
                    } ?: ""
                    val err = runCatching { response?.javaClass?.getDeclaredField("err")?.let {
                        it.isAccessible = true; it.get(response) as? String
                    } }.getOrNull() ?: ""
                    return DownloadResponse(exitCode, out, err)
                } catch (_: TimeoutException) {
                    if (cancelRequested.get()) {
                        future.cancel(true)
                        return DownloadResponse(130, "DOWNLOAD_CANCELLED")
                    }
                    val now = SystemClock.elapsedRealtime()
                    val sec = ((now - requestStart) / 1000L).toInt()
                    if (sec >= 10 && sec % 5 == 0 && sec != lastHeartbeat) {
                        lastHeartbeat = sec
                        onProgress(0f, "Подготовка...")
                    }
                } catch (e: Exception) {
                    val msg = e.cause?.message ?: e.message ?: "Ошибка выполнения yt-dlp"
                    Log.w(TAG, "executeRequest exception", e)
                    return DownloadResponse(1, "", msg)
                }
            }
        } finally {
            activeRequestFuture.set(null)
            executor.shutdownNow()
        }
    }

    private fun saveToMovies(source: File, thumbnailFile: File? = null) {
        val displayName = buildDisplayName(source)
        val mimeType = when (source.extension.lowercase(Locale.US)) {
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            else -> "video/*"
        }
        Log.d(TAG, "saveToMovies: $displayName")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.TITLE, displayName.substringBeforeLast('.'))
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/YouOffline")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Не удалось создать запись в MediaStore")

            uri.lastPathSegment?.toLongOrNull()?.let { videoId ->
                if (thumbnailFile != null && thumbnailFile.exists()) {
                    runCatching { thumbnailFile.copyTo(File(thumbDir, "$videoId.jpg"), overwrite = true) }
                }
            }

            resolver.openOutputStream(uri).use { output ->
                source.inputStream().use { it.copyTo(output!!) }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val targetDir = File(moviesDir, "YouOffline").apply { mkdirs() }
            source.copyTo(File(targetDir, displayName), overwrite = true)
        }
        source.delete()
    }

    private fun buildDisplayName(file: File): String {
        val ext = file.extension.lowercase(Locale.US)
        val originalName = file.name
            .replace(Regex("^[0-9a-fA-F]{8}_"), "")
            .ifBlank { file.name }

        return if (ext.isBlank()) {
            "${originalName}.mp4"
        } else {
            if (originalName.lowercase(Locale.US).endsWith(".$ext")) {
                originalName
            } else {
                "${originalName}.$ext"
            }
        }
    }

    private fun ensureLibraryFolderIndexed() {
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val targetDir = File(moviesDir, "YouOffline")
        if (!targetDir.exists() || !targetDir.isDirectory) return

        val videoFiles = targetDir.listFiles()
            ?.filter { it.isFile }
            ?.filter { file ->
                when (file.extension.lowercase(Locale.US)) {
                    "mp4", "mkv", "webm", "avi", "mov", "m4v" -> true
                    else -> false
                }
            }
            .orEmpty()

        if (videoFiles.isEmpty()) return

        val latch = CountDownLatch(videoFiles.size)
        MediaScannerConnection.scanFile(
            context,
            videoFiles.map { it.absolutePath }.toTypedArray(),
            null
        ) { _, _ -> latch.countDown() }
        runCatching { latch.await(2, TimeUnit.SECONDS) }
    }

    fun listDownloadedVideos(): List<DownloadedVideo> {
        val result = mutableListOf<DownloadedVideo>()
        ensureLibraryFolderIndexed()
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.RELATIVE_PATH
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%Movies/YouOffline/%", "%YouOffline/%")
        val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs, sort
        )?.use { cursor ->
            val idCol       = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol     = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val titleCol    = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
            val durCol      = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol     = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

            while (cursor.moveToNext()) {
                val id    = cursor.getLong(idCol)
                val name  = cursor.getString(nameCol) ?: continue
                val title = cursor.getString(titleCol)?.ifBlank { null } ?: name.substringBeforeLast('.')
                val dur   = cursor.getLong(durCol)
                val size  = cursor.getLong(sizeCol)
                val uri   = MediaStore.Video.Media.EXTERNAL_CONTENT_URI.buildUpon()
                    .appendPath(id.toString()).build().toString()
                val thumbnailPath = File(thumbDir, "$id.jpg").takeIf { it.exists() }?.absolutePath
                result += DownloadedVideo(
                    displayName = name,
                    title = title,
                    durationMs = dur,
                    pathOrUri = uri,
                    sizeBytes = size,
                    thumbnailPath = thumbnailPath
                )
            }
        }
        return result
    }

    fun deleteDownloadedVideo(pathOrUri: String) {
        val deleted = if (pathOrUri.startsWith("content://")) {
            context.contentResolver.delete(Uri.parse(pathOrUri), null, null) > 0
        } else {
            File(pathOrUri).takeIf { it.exists() }?.delete() == true
        }
        cleanVideoMeta(pathOrUri)
        if (!deleted) {
            throw IOException("Не удалось удалить видео")
        }
    }

    fun cleanVideoMeta(pathOrUri: String) {
        val mediaId = Uri.parse(pathOrUri).lastPathSegment
        if (!mediaId.isNullOrBlank()) {
            File(thumbDir, "$mediaId.jpg").takeIf { it.exists() }?.delete()
        }
    }

    private fun cleanDir(dir: File) {
        dir.listFiles()?.forEach { it.delete() }
    }
}
