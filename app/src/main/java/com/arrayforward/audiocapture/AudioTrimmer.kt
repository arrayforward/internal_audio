package com.arrayforward.audiocapture

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder
import kotlin.math.abs

object AudioTrimmer {

    const val MIN_TRIM_MS = 1000L
    private const val MAX_BARS = 1000

    data class WavInfo(
        val dataOffset: Int,
        val dataSize: Int,
        val byteRate: Int,
        val blockAlign: Int,
        val sampleRate: Int = AudioParams.SAMPLE_RATE,
        val channels: Int = AudioParams.CHANNELS
    )

    fun durationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }

    fun extractPeaks(file: File, maxBars: Int = MAX_BARS): FloatArray {
        return when (file.extension.lowercase()) {
            "wav" -> extractPeaksWav(file, maxBars)
            else -> extractPeaksEncoded(file, maxBars)
        }
    }

    fun trim(input: File, output: File, startMs: Long, endMs: Long) {
        require(endMs - startMs >= MIN_TRIM_MS) { "保留时长不能少于 1 秒" }
        when (input.extension.lowercase()) {
            "wav" -> trimWav(input, output, startMs, endMs)
            "m4a", "aac", "mp4" -> trimM4a(input, output, startMs, endMs)
            "mp3" -> throw UnsupportedOperationException("MP3 剪辑需集成 LAME 编码库")
            else -> throw UnsupportedOperationException("不支持的格式: ${input.extension}")
        }
    }

    private fun parseWav(file: File): WavInfo {
        FileInputStream(file).use { fis ->
            val header = ByteArray(256)
            val read = fis.read(header)
            if (read < 44) throw IllegalArgumentException("文件太小，不是有效的 WAV")
            fun ascii(offset: Int) = String(header, offset, 4, Charsets.US_ASCII)
            fun intAt(offset: Int): Int =
                (header[offset].toInt() and 0xFF) or
                    (header[offset + 1].toInt() and 0xFF shl 8) or
                    (header[offset + 2].toInt() and 0xFF shl 16) or
                    (header[offset + 3].toInt() and 0xFF shl 24)

            if (ascii(0) != "RIFF" || ascii(8) != "WAVE") {
                throw IllegalArgumentException("不是有效的 WAV 文件")
            }
            var byteRate = AudioParams.BYTE_RATE
            var blockAlign = AudioParams.BLOCK_ALIGN
            var sampleRate = AudioParams.SAMPLE_RATE
            var channels = AudioParams.CHANNELS
            var offset = 12
            while (offset + 8 <= read) {
                val id = ascii(offset)
                val size = intAt(offset + 4)
                when (id) {
                    "fmt " -> {
                        channels =
                            (header[offset + 8 + 2].toInt() and 0xFF) or
                                (header[offset + 8 + 3].toInt() and 0xFF shl 8)
                        sampleRate = intAt(offset + 8 + 4)
                        byteRate = intAt(offset + 8 + 8)
                        blockAlign =
                            (header[offset + 8 + 12].toInt() and 0xFF) or
                                (header[offset + 8 + 13].toInt() and 0xFF shl 8)
                    }
                    "data" -> return WavInfo(offset, size, byteRate, blockAlign, sampleRate, channels)
                }
                offset += 8 + size + (size and 1)
                if (offset + 8 > header.size) break
            }
            throw IllegalArgumentException("未找到 WAV data 块")
        }
    }

    private fun putIntLE(bytes: ByteArray, offset: Int, v: Int) {
        bytes[offset] = (v and 0xFF).toByte()
        bytes[offset + 1] = (v shr 8 and 0xFF).toByte()
        bytes[offset + 2] = (v shr 16 and 0xFF).toByte()
        bytes[offset + 3] = (v shr 24 and 0xFF).toByte()
    }

    private fun trimWav(input: File, output: File, startMs: Long, endMs: Long) {
        val info = parseWav(input)
        val dataStart = info.dataOffset + 8L
        val dataEnd = dataStart + info.dataSize

        var startByte = dataStart + (startMs * info.byteRate / 1000 / info.blockAlign) * info.blockAlign
        var endByte = dataStart + (endMs * info.byteRate / 1000 / info.blockAlign) * info.blockAlign
        startByte = startByte.coerceIn(dataStart, dataEnd)
        endByte = endByte.coerceIn(startByte + info.blockAlign, dataEnd)
        val newDataLen = (endByte - startByte).toInt()

        RandomAccessFile(input, "r").use { raf ->
            val header = ByteArray(dataStart.toInt())
            raf.seek(0)
            raf.readFully(header)
            putIntLE(header, 4, (dataStart + newDataLen - 8).toInt())
            putIntLE(header, info.dataOffset + 4, newDataLen)

            FileOutputStream(output).use { fos ->
                fos.write(header)
                raf.seek(startByte)
                val buffer = ByteArray(64 * 1024)
                var remaining = newDataLen
                while (remaining > 0) {
                    val toRead = minOf(buffer.size, remaining)
                    val read = raf.read(buffer, 0, toRead)
                    if (read <= 0) break
                    fos.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }

    private fun trimM4a(input: File, output: File, startMs: Long, endMs: Long) {
        val extractor = MediaExtractor()
        extractor.setDataSource(input.absolutePath)

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            throw IllegalArgumentException("未找到音轨")
        }

        extractor.selectTrack(trackIndex)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val dstTrack = muxer.addTrack(format)
        muxer.start()

        extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val buffer = java.nio.ByteBuffer.allocate(256 * 1024)
        val info = MediaCodec.BufferInfo()
        val startUs = startMs * 1000
        val endUs = endMs * 1000
        var sampleCount = 0

        try {
            while (true) {
                info.offset = 0
                info.size = extractor.readSampleData(buffer, 0)
                if (info.size < 0) break
                val pts = extractor.sampleTime
                if (pts < 0 || pts > endUs) break
                info.presentationTimeUs = (pts - startUs).coerceAtLeast(0)
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(dstTrack, buffer, info)
                sampleCount++
                extractor.advance()
            }
        } finally {
            try {
                muxer.stop()
            } catch (_: Exception) {
            }
            muxer.release()
            extractor.release()
        }

        if (sampleCount == 0) {
            output.delete()
            throw IllegalStateException("选区内没有有效音频数据")
        }
    }

    fun decodeToMonoPcm(file: File, maxSamples: Int = 16000 * 120): Pair<ShortArray, Int> {
        return when (file.extension.lowercase()) {
            "wav" -> decodeWavToMono(file, maxSamples)
            else -> decodeEncodedToMono(file, maxSamples)
        }
    }

    private fun decodeWavToMono(file: File, maxSamples: Int): Pair<ShortArray, Int> {
        val info = parseWav(file)
        val out = ShortArray(maxSamples)
        var outCount = 0
        RandomAccessFile(file, "r").use { raf ->
            raf.seek((info.dataOffset + 8).toLong())
            val bytesPerFrame = info.blockAlign
            val frameBytes = ByteArray(bytesPerFrame)
            var remaining = info.dataSize
            while (remaining >= bytesPerFrame && outCount < maxSamples) {
                val read = raf.read(frameBytes, 0, bytesPerFrame)
                if (read < bytesPerFrame) break
                var sum = 0
                for (c in 0 until info.channels) {
                    val i = c * 2
                    sum += (frameBytes[i].toInt() and 0xFF) or (frameBytes[i + 1].toInt() shl 8)
                }
                out[outCount++] = (sum / info.channels).toShort()
                remaining -= bytesPerFrame
            }
        }
        return out.copyOf(outCount) to info.sampleRate
    }

    private fun decodeEncodedToMono(file: File, maxSamples: Int): Pair<ShortArray, Int> {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            throw IllegalArgumentException("未找到音轨")
        }

        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else AudioParams.SAMPLE_RATE
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else AudioParams.CHANNELS

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val out = ShortArray(maxSamples)
        var outCount = 0

        try {
            while (!outputDone && outCount < maxSamples) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(5_000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = decoder.dequeueOutputBuffer(info, 5_000)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val buf = decoder.getOutputBuffer(outIdx)!!.order(ByteOrder.LITTLE_ENDIAN)
                        val shorts = buf.asShortBuffer()
                        val frameCount = info.size / 2 / channels
                        var f = 0
                        while (f < frameCount && outCount < maxSamples) {
                            var sum = 0
                            repeat(channels) {
                                if (shorts.hasRemaining()) sum += shorts.get().toInt()
                            }
                            out[outCount++] = (sum / channels).toShort()
                            f++
                        }
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }
        } finally {
            try {
                decoder.stop()
            } catch (_: Exception) {
            }
            decoder.release()
            extractor.release()
        }
        return out.copyOf(outCount) to sampleRate
    }

    private fun extractPeaksWav(file: File, maxBars: Int): FloatArray {
        val info = parseWav(file)
        val peaks = FloatArray(maxBars)
        val bytesPerFrame = info.blockAlign
        val totalFrames = info.dataSize / bytesPerFrame
        val framesPerBucket = (totalFrames / maxBars).coerceAtLeast(1)

        RandomAccessFile(file, "r").use { raf ->
            raf.seek((info.dataOffset + 8).toLong())
            val buffer = ByteArray(64 * 1024)
            var frameIndex = 0L
            var read: Int
            while (true) {
                read = raf.read(buffer)
                if (read <= 0) break
                var i = 0
                while (i + 1 < read) {
                    val sample = abs(
                        (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                    )
                    val bucket = (frameIndex / framesPerBucket).toInt()
                    if (bucket >= maxBars) return peaks
                    val v = sample / 32768f
                    if (v > peaks[bucket]) peaks[bucket] = v
                    frameIndex++
                    i += bytesPerFrame
                }
            }
        }
        return peaks
    }

    private fun extractPeaksEncoded(file: File, maxBars: Int): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            return FloatArray(maxBars)
        }

        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
            format.getLong(MediaFormat.KEY_DURATION) else 0L
        val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else AudioParams.SAMPLE_RATE
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else AudioParams.CHANNELS

        val totalFrames = (durationUs / 1_000_000.0 * sampleRate).toLong().coerceAtLeast(1)
        val framesPerBucket = (totalFrames / maxBars).coerceAtLeast(1)
        val peaks = FloatArray(maxBars)

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var frameIndex = 0L

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(5_000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = decoder.dequeueOutputBuffer(info, 5_000)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val buf = decoder.getOutputBuffer(outIdx)!!.order(ByteOrder.LITTLE_ENDIAN)
                        val shorts = buf.asShortBuffer()
                        val frameCount = info.size / 2 / channels
                        repeat(frameCount) { f ->
                            var maxAbs = 0
                            repeat(channels) {
                                if (shorts.hasRemaining()) {
                                    val s = abs(shorts.get().toInt())
                                    if (s > maxAbs) maxAbs = s
                                }
                            }
                            val bucket = ((frameIndex + f) / framesPerBucket).toInt()
                            if (bucket < maxBars) {
                                val v = maxAbs / 32768f
                                if (v > peaks[bucket]) peaks[bucket] = v
                            }
                        }
                        frameIndex += frameCount
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }
        } finally {
            try {
                decoder.stop()
            } catch (_: Exception) {
            }
            decoder.release()
            extractor.release()
        }
        return peaks
    }
}
