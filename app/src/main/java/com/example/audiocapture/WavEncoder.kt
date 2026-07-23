package com.example.audiocapture

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

interface PcmEncoder {
    fun write(data: ByteArray, length: Int)
    fun finish(): File?
}

class WavEncoder(
    private val file: File,
    private val sampleRate: Int = AudioParams.SAMPLE_RATE,
    private val channels: Int = AudioParams.CHANNELS,
    private val bitsPerSample: Int = AudioParams.BITS_PER_SAMPLE
) : PcmEncoder {

    private val out = FileOutputStream(file)
    private var dataLen = 0
    private var closed = false

    private val byteRate = sampleRate * channels * bitsPerSample / 8
    private val blockAlign = channels * bitsPerSample / 8

    init {
        out.write(buildHeader(0))
    }

    private fun buildHeader(dataSize: Int): ByteArray {
        val header = ByteArray(44)
        val totalSize = 36 + dataSize

        fun putAscii(offset: Int, s: String) {
            for (i in s.indices) header[offset + i] = s[i].code.toByte()
        }

        fun putInt(offset: Int, v: Int) {
            header[offset] = (v and 0xFF).toByte()
            header[offset + 1] = (v shr 8 and 0xFF).toByte()
            header[offset + 2] = (v shr 16 and 0xFF).toByte()
            header[offset + 3] = (v shr 24 and 0xFF).toByte()
        }

        fun putShort(offset: Int, v: Int) {
            header[offset] = (v and 0xFF).toByte()
            header[offset + 1] = (v shr 8 and 0xFF).toByte()
        }

        putAscii(0, "RIFF")
        putInt(4, totalSize)
        putAscii(8, "WAVE")
        putAscii(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1)
        putShort(22, channels)
        putInt(24, sampleRate)
        putInt(28, byteRate)
        putShort(32, blockAlign)
        putShort(34, bitsPerSample)
        putAscii(36, "data")
        putInt(40, dataSize)
        return header
    }

    @Synchronized
    override fun write(data: ByteArray, length: Int) {
        if (closed) return
        out.write(data, 0, length)
        dataLen += length
    }

    @Synchronized
    override fun finish(): File? {
        if (closed) return file
        closed = true
        return try {
            out.flush()
            out.close()
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(4)
                raf.write(intToLittleEndianBytes(36 + dataLen))
                raf.seek(40)
                raf.write(intToLittleEndianBytes(dataLen))
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    private fun intToLittleEndianBytes(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        (v shr 8 and 0xFF).toByte(),
        (v shr 16 and 0xFF).toByte(),
        (v shr 24 and 0xFF).toByte()
    )
}
