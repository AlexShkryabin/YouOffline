# Required binaries

Place executable native binaries before building/running on device:

- yt-dlp binary:
  - `app/src/main/assets/yt-dlp/arm64-v8a/yt-dlp`
  - `app/src/main/assets/yt-dlp/armeabi-v7a/yt-dlp`
  - `app/src/main/assets/yt-dlp/x86_64/yt-dlp`

Each binary must be Android-compatible ELF and executable by app process.
