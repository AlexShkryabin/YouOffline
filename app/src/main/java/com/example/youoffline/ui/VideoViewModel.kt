package com.example.youoffline.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.youoffline.downloader.VideoDownloader
import com.example.youoffline.model.VideoQuality
import com.example.youoffline.model.VideoUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoViewModel(application: Application) : AndroidViewModel(application) {

    private val downloader = VideoDownloader(application)

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

    fun startDownload() {
        val current = _uiState.value
        if (current.url.isBlank()) {
            _uiState.update { it.copy(status = "Введите ссылку") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, progress = 0f, status = "Подготовка скачивания") }
            var visualProgress = 0f
            runCatching {
                withContext(Dispatchers.IO) {
                    downloader.downloadVideo(
                        url = current.url,
                        quality = current.downloadQuality,
                        onProgress = { p, s ->
                            val stableProgress = maxOf(visualProgress, p).coerceIn(0f, 1f)
                            visualProgress = stableProgress
                            _uiState.update { it.copy(progress = stableProgress, status = s) }
                        }
                    )
                }
            }.onSuccess {
                _uiState.update { it.copy(isDownloading = false, progress = 1f, status = "Готово", url = "") }
                refreshLibrary()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(isDownloading = false, status = error.message ?: "Ошибка скачивания")
                }
            }
        }
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
