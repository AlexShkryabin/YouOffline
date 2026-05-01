package com.example.youoffline.ui

import android.app.Application
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.youoffline.downloader.DownloadCancelledException
import com.example.youoffline.downloader.YouTubeDownloader
import com.example.youoffline.model.DownloadedAudio
import com.example.youoffline.model.DownloadQuality
import com.example.youoffline.model.DownloadUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val downloader = YouTubeDownloader(application)
    private val player = MediaPlayer()
    private var progressJob: Job? = null
    private var downloadJob: Job? = null

    private val _uiState = MutableStateFlow(DownloadUiState())
    val uiState: StateFlow<DownloadUiState> = _uiState.asStateFlow()

    // Emits a URI that needs system delete-permission dialog (Android 11+)
    private val _deleteRequestUri = MutableSharedFlow<Uri>(extraBufferCapacity = 1)
    val deleteRequestUri: SharedFlow<Uri> = _deleteRequestUri.asSharedFlow()

    // Pending pathOrUri waiting for user to confirm system delete dialog
    private var pendingDeletePath: String? = null

    init {
        player.setOnCompletionListener {
            playNext()
        }
        refreshDownloads()
    }

    fun onUrlChange(url: String) {
        _uiState.update { it.copy(url = url) }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun onDownloadQualitySelected(quality: DownloadQuality) {
        _uiState.update { it.copy(downloadQuality = quality) }
    }

    fun startDownload() {
        val current = _uiState.value
        if (current.isDownloading) return
        if (current.url.isBlank()) {
            _uiState.update { it.copy(status = "Введите URL") }
            return
        }

        downloadJob = viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, progress = 0f, status = "Подготовка скачивания") }
            try {
                withContext(Dispatchers.IO) {
                    var prepProgress = 0f
                    var downloadPhaseStarted = false
                    var visualProgress = 0f
                    // currentTrackNum: номер текущего трека в плейлисте (0 = одиночный трек)
                    var currentTrackNum = 0

                    fun trackPrefix() = if (currentTrackNum > 0) "Трек $currentTrackNum · " else ""

                    val progressMapper: (Float, String) -> Unit = { p, s ->
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
                            // Полоска 0→100% для каждого трека отдельно
                            mappedProgress = normalizedDownload
                            mappedStatus = when {
                                titleFromDestination != null ->
                                    "${trackPrefix()}«$titleFromDestination»"
                                else ->
                                    "${trackPrefix()}Скачивание ${(normalizedDownload * 100).toInt()}%"
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
                                    // Уже отформатированный статус из загрузчика (начало нового трека)
                                    raw
                                raw.contains("Повтор", ignoreCase = true) ->
                                    "${trackPrefix()}Повторная попытка..."
                                raw.contains("Таймаут", ignoreCase = true) ->
                                    "${trackPrefix()}Таймаут, повтор..."
                                raw.contains("Ошибка", ignoreCase = true) -> raw
                                isHeartbeatPrep ->
                                    "${trackPrefix()}Подготовка ${(prepProgress * 100 / 0.15f).toInt()}%"
                                titleFromDestination != null ->
                                    "${trackPrefix()}«$titleFromDestination»"
                                else ->
                                    "${trackPrefix()}Подготовка..."
                            }
                        }

                        // Монотонный прогресс только внутри одного трека — при смене трека сбрасывается
                        val stableProgress = maxOf(visualProgress, mappedProgress).coerceIn(0f, 1f)
                        visualProgress = stableProgress
                        _uiState.update { old -> old.copy(progress = stableProgress, status = mappedStatus) }
                    }

                    downloader.downloadAudio(
                        url = current.url,
                        useProxy = false,
                        quality = current.downloadQuality,
                        onProgress = progressMapper,
                        onTrackSaved = {
                            viewModelScope.launch {
                                _uiState.update { state ->
                                    state.copy(status = if (currentTrackNum > 0)
                                        "Трек $currentTrackNum сохранён: ${it.title}"
                                    else
                                        "Сохранено: ${it.title}"
                                    )
                                }
                                refreshDownloads()
                            }
                        },
                        onTrackStart = { trackNum ->
                            currentTrackNum = trackNum
                            // Сбросить прогресс-бар для нового трека
                            prepProgress = 0f
                            downloadPhaseStarted = false
                            visualProgress = 0f
                            _uiState.update { old -> old.copy(progress = 0f, status = "Трек $trackNum · Подготовка...") }
                        }
                    )
                }
                _uiState.update { it.copy(isDownloading = false, progress = 1f, status = "Готово", url = "") }
                refreshDownloads()
            } catch (_: CancellationException) {
                _uiState.update {
                    it.copy(
                        isDownloading = false,
                        progress = 0f,
                        status = ""
                    )
                }
                refreshDownloads()
            } catch (_: DownloadCancelledException) {
                _uiState.update {
                    it.copy(
                        isDownloading = false,
                        progress = 0f,
                        status = ""
                    )
                }
                refreshDownloads()
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isDownloading = false,
                        status = error.message ?: "Ошибка скачивания"
                    )
                }
                refreshDownloads()
            } finally {
                downloadJob = null
            }
        }
    }

    fun cancelDownload() {
        if (!_uiState.value.isDownloading) return
        _uiState.update { it.copy(isDownloading = false, progress = 0f, status = "") }
        downloader.cancelActiveDownload()
        downloadJob?.cancel()
    }

    private fun extractTitleFromDestination(raw: String): String? {
        val marker = "Destination:"
        val idx = raw.indexOf(marker, ignoreCase = true)
        if (idx < 0) return null
        val path = raw.substring(idx + marker.length).trim()
        if (path.isBlank()) return null
        return path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').takeIf { it.isNotBlank() }
    }


    fun refreshDownloads() {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { downloader.listDownloadedFiles() }
            }.onSuccess { files ->
                _uiState.update { state ->
                    val currentPath = state.nowPlaying
                    val idx = files.indexOfFirst { it.pathOrUri == currentPath }
                    state.copy(
                        downloadedFiles = files,
                        currentTrackIndex = if (idx >= 0) idx else state.currentTrackIndex
                    )
                }
            }.onFailure { error ->
                if (error is SecurityException) {
                    _uiState.update { it.copy(status = "Нет доступа к медиатеке") }
                }
            }
        }
    }

    fun playFile(pathOrUri: String) {
        val idx = _uiState.value.downloadedFiles.indexOfFirst { it.pathOrUri == pathOrUri }
        if (idx >= 0) {
            playTrackAt(idx)
        }
    }

    fun deleteTrack(pathOrUri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val deletingCurrent = _uiState.value.nowPlaying == pathOrUri
            if (deletingCurrent) {
                runCatching { player.stop() }
                runCatching { player.reset() }
            }

            // On Android 11+ we cannot directly delete files added by other apps —
            // request system confirmation dialog via createDeleteRequest.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && pathOrUri.startsWith("content://")) {
                pendingDeletePath = pathOrUri
                if (deletingCurrent) {
                    _uiState.update {
                        it.copy(
                            nowPlaying = null,
                            isPlaying = false,
                            playbackPositionMs = 0,
                            playbackDurationMs = 0
                        )
                    }
                }
                _deleteRequestUri.emit(Uri.parse(pathOrUri))
                return@launch
            }

            runCatching {
                downloader.deleteDownloadedFile(pathOrUri)
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        nowPlaying = if (deletingCurrent) null else it.nowPlaying,
                        isPlaying = if (deletingCurrent) false else it.isPlaying,
                        playbackPositionMs = if (deletingCurrent) 0 else it.playbackPositionMs,
                        playbackDurationMs = if (deletingCurrent) 0 else it.playbackDurationMs,
                        status = "Трек удалён"
                    )
                }
                refreshDownloads()
            }.onFailure { error ->
                _uiState.update { it.copy(status = error.message ?: "Не удалось удалить трек") }
            }
        }
    }

    /** Called by MainActivity after the user confirms the system delete dialog. */
    fun confirmDelete(granted: Boolean) {
        val path = pendingDeletePath ?: return
        pendingDeletePath = null
        if (!granted) {
            _uiState.update { it.copy(status = "Удаление отменено") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            downloader.cleanArtistMeta(path)
            refreshDownloads()
            _uiState.update { it.copy(status = "Трек удалён") }
        }
    }

    fun playTrackAt(index: Int) {
        val list = _uiState.value.downloadedFiles
        if (index !in list.indices) return
        val track = list[index]

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                player.reset()
                if (track.pathOrUri.startsWith("content://")) {
                    player.setDataSource(getApplication(), Uri.parse(track.pathOrUri))
                } else {
                    player.setDataSource(track.pathOrUri)
                }
                player.prepare()
                player.start()
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        nowPlaying = track.pathOrUri,
                        currentTrackIndex = index,
                        isPlaying = true,
                        playbackPositionMs = 0,
                        playbackDurationMs = player.duration.coerceAtLeast(0),
                        status = "Воспроизведение"
                    )
                }
                startProgressUpdates()
            }.onFailure { error ->
                _uiState.update { it.copy(status = error.message ?: "Ошибка воспроизведения") }
            }
        }
    }

    fun togglePlayPause() {
        if (_uiState.value.currentTrackIndex < 0) return
        runCatching {
            if (player.isPlaying) {
                player.pause()
                _uiState.update { it.copy(isPlaying = false, status = "Пауза") }
            } else {
                player.start()
                _uiState.update { it.copy(isPlaying = true, status = "Воспроизведение") }
                startProgressUpdates()
            }
        }.onFailure { error ->
            _uiState.update { it.copy(status = error.message ?: "Ошибка плеера") }
        }
    }

    fun playNext() {
        val state = _uiState.value
        val list = state.downloadedFiles
        if (list.isEmpty()) return
        val next = if (state.currentTrackIndex in list.indices) {
            (state.currentTrackIndex + 1) % list.size
        } else {
            0
        }
        playTrackAt(next)
    }

    fun playPrevious() {
        val state = _uiState.value
        val list = state.downloadedFiles
        if (list.isEmpty()) return
        val prev = if (state.currentTrackIndex in list.indices) {
            if (state.currentTrackIndex == 0) list.lastIndex else state.currentTrackIndex - 1
        } else {
            0
        }
        playTrackAt(prev)
    }

    fun seekToFraction(fraction: Float) {
        val duration = player.duration.coerceAtLeast(0)
        if (duration <= 0) return
        val target = (duration * fraction.coerceIn(0f, 1f)).toInt()
        runCatching {
            player.seekTo(target)
            _uiState.update { it.copy(playbackPositionMs = target) }
        }
    }

    fun getCurrentTrack(): DownloadedAudio? {
        val state = _uiState.value
        return if (state.currentTrackIndex in state.downloadedFiles.indices) {
            state.downloadedFiles[state.currentTrackIndex]
        } else null
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive) {
                val isPlayingNow = runCatching { player.isPlaying }.getOrDefault(false)
                val position = runCatching { player.currentPosition }.getOrDefault(0)
                val duration = runCatching { player.duration }.getOrDefault(0)
                _uiState.update {
                    it.copy(
                        isPlaying = isPlayingNow,
                        playbackPositionMs = position.coerceAtLeast(0),
                        playbackDurationMs = duration.coerceAtLeast(0)
                    )
                }
                delay(500)
            }
        }
    }

    override fun onCleared() {
        downloadJob?.cancel()
        progressJob?.cancel()
        player.release()
        super.onCleared()
    }
}
