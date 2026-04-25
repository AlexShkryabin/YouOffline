package com.example.youoffline.model

data class VideoMetadata(
    val title: String,
    val durationSeconds: Int
)

data class DownloadedAudio(
    val displayName: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val pathOrUri: String,
    val sizeBytes: Long
)

enum class DownloadQuality {
    BEST,
    MEDIUM,
    LOW
}

data class DownloadUiState(
    val url: String = "",
    val searchQuery: String = "",
    val isDownloading: Boolean = false,
    val progress: Float = 0f,
    val status: String = "Готово",
    val downloadQuality: DownloadQuality = DownloadQuality.BEST,
    val downloadedFiles: List<DownloadedAudio> = emptyList(),
    val nowPlaying: String? = null,
    val currentTrackIndex: Int = -1,
    val isPlaying: Boolean = false,
    val playbackPositionMs: Int = 0,
    val playbackDurationMs: Int = 0
)

data class DownloadedVideo(
    val displayName: String,
    val title: String,
    val durationMs: Long,
    val pathOrUri: String,
    val sizeBytes: Long,
    val thumbnailPath: String? = null
)

enum class VideoQuality {
    BEST,
    Q1440P,
    Q1080P,
    Q720P,
    Q480P
}

data class VideoUiState(
    val url: String = "",
    val searchQuery: String = "",
    val isDownloading: Boolean = false,
    val progress: Float = 0f,
    val status: String = "Готово",
    val downloadQuality: VideoQuality = VideoQuality.Q1080P,
    val downloadedFiles: List<DownloadedVideo> = emptyList(),
    val playingUri: String? = null,
    val isPlayerMinimized: Boolean = false,
    val videoPlaybackPositionMs: Long = 0L
)
