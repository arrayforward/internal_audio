package com.arrayforward.audiocapture

object AudioParams {
    const val SAMPLE_RATE = 48000
    const val CHANNELS = 2
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
    const val BLOCK_ALIGN = CHANNELS * BYTES_PER_SAMPLE
    const val BYTE_RATE = SAMPLE_RATE * BLOCK_ALIGN
    const val AAC_BITRATE = 192_000
    const val MP3_BITRATE = 192
}

enum class OutputFormat(val extension: String, val displayName: String) {
    WAV("wav", "WAV"),
    M4A("m4a", "M4A"),
    MP3("mp3", "MP3");

    companion object {
        fun fromExtension(ext: String): OutputFormat? =
            entries.firstOrNull { it.extension.equals(ext, ignoreCase = true) }
    }
}
