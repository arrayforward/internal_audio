package com.arrayforward.audiocapture

import android.os.Environment
import java.io.File
import kotlin.random.Random

object FileUtils {

    val audioDir: File
        get() = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "audio"
        )

    fun ensureAudioDir(): File {
        val dir = audioDir
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw java.io.IOException("无法创建目录 ${dir.absolutePath}")
        }
        return dir
    }

    fun audioDirOrNull(): File? {
        val dir = audioDir
        return if (dir.exists() || dir.mkdirs()) dir else null
    }

    fun randomString(length: Int = 6): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    fun newRecordingFile(format: OutputFormat): File {
        val dir = ensureAudioDir()
        var file: File
        do {
            file = File(dir, "recording_${randomString()}.${format.extension}")
        } while (file.exists())
        return file
    }

    fun newTrimmedFile(source: File): File {
        val dir = ensureAudioDir()
        val base = source.nameWithoutExtension
        val ext = source.extension
        var file: File
        do {
            file = File(dir, "trimmed_${base}_${randomString()}.$ext")
        } while (file.exists())
        return file
    }

    fun listRecordings(): List<File> {
        val dir = audioDirOrNull() ?: return emptyList()
        return dir.listFiles { f ->
            f.isFile && f.extension.lowercase() in listOf("wav", "m4a", "mp3")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
