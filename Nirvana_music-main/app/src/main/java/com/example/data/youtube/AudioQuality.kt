package com.example.data.youtube

/**
 * Preferred audio quality for stream format selection in [YTPlayerUtils].
 * AUTO lets [YTPlayerUtils] pick based on network type (wifi vs mobile).
 */
enum class AudioQuality {
    AUTO,
    LOW,
    HIGH,
}
