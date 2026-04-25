package com.example.youoffline.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.youoffline.downloader.DownloadCancelledException
import com.example.youoffline.downloader.VideoDownloader
import com.example.youoffline.model.VideoQuality
import com.example.youoffline.model.VideoUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoViewModel(application: Application) : AndroidViewModel(application) {

    private val downloader = VideoDownloader(application)
    private var downloadJob: Job? = null
    private var downloadedTrackCount = 0
    private var onPlaylistComplete: (() -> Unit)? = null

    private val _uiState = MutableStateFlow(VideoUiState())
    val uiState: StateFlow<VideoUiState> = _uiState.asStateFlow()

    init {
        refreshLibrary()
    }

    fun onUrlChange(url: String) {
        _uiState.update { it.copy(url = url) }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun onQualitySelected(quality: VideoQuality) {
        _uiState.update { it.copy(downloadQuality = quality) }
    }

    fun setOnPlaylistComplete(callback: (() -> Unit)?) {
        onPlaylistComplete = callback
    }

    fun startDownload() {
        val current = _uiState.value
        if (current.isDownloading) return
        if (current.url.isBlank()) {
            _uiState.update { it.copy(status = "Введите ссылку") }
            return
        }

        downloadedTrackCount = 0
        downloadJob = viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, progress = 0f, status = "Подготовка скачивания") }
            try {
                withContext(Dispatchers.IO) {
                    var prepProgress = 0f
                    var downloadPhaseStarted = false
                    var visualProgress = 0f
                    var currentTrackNum = 0

                    fun trackPrefix() = if (currentTrackNum > 0) "Трек $currentTrackNum · " else ""

                    downloader.downloadVideo(
                        url = current.url,
                        quality = current.downloadQuality,
                        onProgress = { p, s ->
                            val raw = s.trim()
                            val isDownloadPhase = raw.contains("[download]", ignoreCase = true) ||
                                raw.startsWith("Скачивание", ignoreCase = true) ||
                                p > 0f
                            val titleFromDestination = extractTitleFromDestination(raw)

                            val mappedStatus: String
                            val mappedProgress: Float

                            if (isDownloadPhase) {
                                downloadPhaseStarted = true
                                val normalizedDownload = p.coerceIn(0f, 1f)
                                mappedProgress = normalizedDownload
                                mappedStatus = when {
                                    titleFromDestination != null -> "${trackPrefix()}«$titleFromDestination»"
                                    else -> "${trackPrefix()}Скачивание ${(normalizedDownload * 100).toInt()}%"
                                }
                            } else {
                                val isHeartbeatPrep = raw.startsWith("Подготовка", ignoreCase = true) ||
                                    raw.contains("] Подготовка", ignoreCase = true)
                                prepProgress = if (!downloadPhaseStarted) {
                                    (prepProgress + 0.03f).coerceAtMost(0.15f)
                                } else {
                                    prepProgress
                                }
                                mappedProgress = if (!downloadPhaseStarted) prepProgress else visualProgress
                                mappedStatus = when {
                                    raw.contains("Сохранение", ignoreCase = true) ->
                                        "${trackPrefix()}Сохранение..."
                                    raw.startsWith("Трек ", ignoreCase = true) ->
                                        raw
                                    raw.contains("Повтор", ignoreCase = true) ->
                                        "${trackPrefix()}Повторная попытка..."
                                    raw.contains("Таймаут", ignoreCase = true) ->
                                        "${trackPrefix()}Таймаут, повтор..."
                                    raw.contains("Ошибка", ignoreCase = true) ->
                                        raw
                                    isHeartbeatPrep ->
                                        "${trackPrefix()}Подготовка ${(prepProgress * 100 / 0.15f).toInt()}%"
                                    titleFromDestination != null ->
                                        "${trackPrefix()}«$titleFromDestination»"
                                    else ->
                                        "${trackPrefix()}Подготовка..."
                                }
                            }

                            val stableProgress = maxOf(visualProgress, mappedProgress).coerceIn(0f, 1f)
                            visualProgress = stableProgress
                            _uiState.update { it.copy(progress = stableProgress, status = mappedStatus) }
                        },
                        onTrackSaved = {
                            downloadedTrackCount++
                            viewModelScope.launch {
                                _uiState.update { state ->
                                    state.copy(
                                        status = if (currentTrackNum > 0)
                                            "Трек $currentTrackNum сохранён"
                                        else
                                            "Сохранено"
                                    )
                                }
                                refreshLibrary()
                            }
                        },
                        onTrackStart = { trackNum ->
                            currentTrackNum = trackNum
                            prepProgress = 0f
                            downloadPhaseStarted = false
                            visualProgress = 0f
                            _uiState.update { old -> old.copy(progress = 0f, status = "Трек $trackNum · Подготовка...") }
                        }
                    )
                }
                _uiState.update { it.copy(isDownloading = false, progress = 1f, status = "Готово", url = "") }
                refreshLibrary()
                if (downloadedTrackCount > 2) {
                    onPlaylistComplete?.invoke()
                }
            } catch (_: CancellationException) {
                _uiState.update {
                    it.copy(isDownloading = false, progress = 0f, status = "")
                }
            } catch (_: DownloadCancelledException) {
                _uiState.update {
                    it.copy(isDownloading = false, progress = 0f, status = "")
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isDownloading = false, status = error.message ?: "Ошибка скачивания")
                }
            } finally {
                downloadJob = null
            }
        }
    }

    private fun extractTitleFromDestination(raw: String): String? {
        val marker = "Destination:"
        val idx = raw.indexOf(marker, ignoreCase = true)
        if (idx < 0) return null
        val path = raw.substring(idx + marker.length).trim()
        if (path.isBlank()) return null
        return path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').takeIf { it.isNotBlank() }
    }

    fun cancelDownload() {
        if (!_uiState.value.isDownloading) return
        _uiState.update { it.copy(isDownloading = false, progress = 0f, status = "") }
        downloader.cancelActiveDownload()
        downloadJob?.cancel()
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) { downloader.listDownloadedVideos() }
            _uiState.update { it.copy(downloadedFiles = files) }
        }
    }

    fun playVideo(pathOrUri: String) {
        _uiState.update {
            it.copy(
                playingUri = pathOrUri,
                isPlayerMinimized = false,
                videoPlaybackPositionMs = 0L
            )
        }
    }

    fun minimizePlayer(positionMs: Long) {
        _uiState.update { it.copy(isPlayerMinimized = true, videoPlaybackPositionMs = positionMs) }
    }

    fun expandPlayer() {
        _uiState.update { it.copy(isPlayerMinimized = false) }
    }

    fun closePlayer() {
        _uiState.update { it.copy(playingUri = null, isPlayerMinimized = false, videoPlaybackPositionMs = 0L) }
    }
}
