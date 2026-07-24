package com.arrayforward.audiocapture

import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * PESQ 风格语音质量评估（非侵入式估算）。
 *
 * 标准 PESQ（ITU-T P.862）是有参考算法，需要原始干净语音作对比；
 * 本实现针对录音场景无参考信号的特点，通过分析有效电平、估算信噪比、
 * 削波比例、静音比例等指标，映射到 1.0 ~ 4.5 的 MOS-LQO 风格分值。
 */
object PesqAnalyzer {

    private const val TARGET_RATE = 16000
    private const val FRAME_SIZE = 512 // 32ms @ 16kHz

    data class Result(
        val score: Double,
        val rating: String,
        val activeLevelDb: Double,
        val snrDb: Double,
        val clippingPercent: Double,
        val silencePercent: Double
    )

    fun analyze(file: File): Result {
        val (pcm, sampleRate) = AudioTrimmer.decodeToMonoPcm(file)
        require(pcm.size >= sampleRate / 2) { "音频太短，无法分析" }

        val samples = resampleTo16k(pcm, sampleRate)

        var clipping = 0
        for (s in samples) {
            if (s >= 32113 || s <= -32113) clipping++
        }
        val clippingRatio = clipping.toDouble() / samples.size

        val frameCount = samples.size / FRAME_SIZE
        require(frameCount > 4) { "音频太短，无法分析" }
        val frameRms = DoubleArray(frameCount)
        for (f in 0 until frameCount) {
            var sum = 0.0
            val base = f * FRAME_SIZE
            for (i in 0 until FRAME_SIZE) {
                val v = samples[base + i].toDouble()
                sum += v * v
            }
            frameRms[f] = sqrt(sum / FRAME_SIZE) / Short.MAX_VALUE
        }

        val sorted = frameRms.sorted()
        val noiseFloor = sorted[(frameCount * 0.1).toInt().coerceAtLeast(0)]
        val activeLevel = sorted[(frameCount * 0.9).toInt().coerceAtMost(frameCount - 1)]

        val activeDb = if (activeLevel > 0) 20 * log10(activeLevel) else -100.0
        val noiseDb = if (noiseFloor > 0) 20 * log10(noiseFloor) else -100.0
        val snr = (activeDb - noiseDb).coerceIn(0.0, 60.0)

        val silenceThreshold = Math.pow(10.0, -50.0 / 20.0)
        val silenceFrames = frameRms.count { it < silenceThreshold }
        val silenceRatio = silenceFrames.toDouble() / frameCount

        var score = 4.5
        score -= (clippingRatio * 100 * 1.5).coerceAtMost(2.0)
        if (snr < 30) score -= (30 - snr) * 0.05
        if (activeDb < -40) score -= (-40 - activeDb) * 0.04
        if (activeDb > -3) score -= 0.3
        score -= silenceRatio * 0.5
        score = score.coerceIn(1.0, 4.5)

        val rating = when {
            score >= 4.0 -> "优秀"
            score >= 3.0 -> "良好"
            score >= 2.0 -> "一般"
            else -> "较差"
        }

        return Result(
            score = Math.round(score * 100) / 100.0,
            rating = rating,
            activeLevelDb = Math.round(activeDb * 10) / 10.0,
            snrDb = Math.round(snr * 10) / 10.0,
            clippingPercent = Math.round(clippingRatio * 1000) / 10.0,
            silencePercent = Math.round(silenceRatio * 1000) / 10.0
        )
    }

    private fun resampleTo16k(pcm: ShortArray, sampleRate: Int): ShortArray {
        if (sampleRate <= TARGET_RATE) return pcm
        val factor = sampleRate / TARGET_RATE
        if (factor <= 1) return pcm
        val outSize = pcm.size / factor
        val out = ShortArray(outSize)
        for (i in 0 until outSize) {
            var sum = 0
            val base = i * factor
            for (j in 0 until factor) sum += pcm[base + j].toInt()
            out[i] = (sum / factor).toShort()
        }
        return out
    }
}
