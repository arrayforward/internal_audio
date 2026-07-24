package com.arrayforward.audiocapture

import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.arrayforward.audiocapture.databinding.ActivityTrimBinding
import java.io.File
import kotlin.concurrent.thread

class TrimActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
    }

    private lateinit var binding: ActivityTrimBinding
    private lateinit var sourceFile: File
    private var durationMs = 0L
    private var startMs = 0L
    private var endMs = 0L
    private var updatingSlider = false

    private var player: MediaPlayer? = null
    private val playHandler = Handler(Looper.getMainLooper())
    private val playTick = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.isPlaying) {
                val pos = p.currentPosition
                binding.playPosition.text =
                    "当前播放位置: ${FileUtils.formatDuration(pos.toLong())}"
                if (durationMs > 0) {
                    binding.trimWaveform.setPlayFraction(pos / durationMs.toFloat())
                }
                if (pos >= endMs) {
                    pausePreview()
                    binding.trimWaveform.setPlayFraction(-1f)
                    return
                }
                playHandler.postDelayed(this, 50)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrimBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra(EXTRA_FILE_PATH)
        if (path.isNullOrEmpty()) {
            finish()
            return
        }
        sourceFile = File(path)
        if (!sourceFile.exists()) {
            Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.toolbarTitle.text = "✂️ 裁剪音频"
        binding.btnClose.setOnClickListener { finish() }
        binding.btnPlayPause.setOnClickListener { togglePreview() }
        binding.btnReset.setOnClickListener { resetSelection() }
        binding.btnAudition.setOnClickListener { auditionSelection() }
        binding.btnPesq.setOnClickListener { runPesqAnalysis() }
        binding.btnSaveCopy.setOnClickListener { saveAsCopy() }
        binding.btnOverwrite.setOnClickListener { confirmOverwrite() }

        binding.trimWaveform.onSelectionChanged = { startFrac, endFrac ->
            startMs = (startFrac * durationMs).toLong()
            endMs = (endFrac * durationMs).toLong()
            updateInfoTexts()
            if (!updatingSlider) {
                updatingSlider = true
                binding.rangeSlider.setValues(
                    startMs.toFloat().coerceIn(0f, durationMs.toFloat()),
                    endMs.toFloat().coerceIn(0f, durationMs.toFloat())
                )
                updatingSlider = false
            }
        }

        binding.rangeSlider.addOnChangeListener { slider, _, _ ->
            if (updatingSlider) return@addOnChangeListener
            val values = slider.values
            if (values.size >= 2) {
                startMs = values[0].toLong()
                endMs = values[1].toLong()
                if (durationMs > 0) {
                    updatingSlider = true
                    binding.trimWaveform.setSelection(
                        startMs / durationMs.toFloat(),
                        endMs / durationMs.toFloat()
                    )
                    updatingSlider = false
                }
                updateInfoTexts()
            }
        }

        binding.trimInfo.text = "正在解析音频…"
        thread {
            try {
                val duration = AudioTrimmer.durationMs(sourceFile)
                val peaks = AudioTrimmer.extractPeaks(sourceFile)
                runOnUiThread { onAudioReady(duration, peaks) }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this, "解析失败: ${e.message}", Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        }
    }

    private fun onAudioReady(duration: Long, peaks: FloatArray) {
        if (duration <= 0) {
            Toast.makeText(this, "无法读取音频时长", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        durationMs = duration
        startMs = 0
        endMs = duration
        binding.trimWaveform.setPeaks(peaks, duration)
        binding.rangeSlider.valueFrom = 0f
        binding.rangeSlider.valueTo = duration.toFloat()
        updatingSlider = true
        binding.rangeSlider.setValues(0f, duration.toFloat())
        updatingSlider = false
        updateInfoTexts()
    }

    private fun updateInfoTexts() {
        val format = sourceFile.extension.uppercase()
        binding.trimInfo.text =
            "原始时长: ${FileUtils.formatDuration(durationMs)}  |  " +
                "选中时长: ${FileUtils.formatDuration(endMs - startMs)}  |  格式: $format"
        binding.startLabel.text = "起始时间: ${FileUtils.formatDuration(startMs)}"
        binding.endLabel.text = "结束时间: ${FileUtils.formatDuration(endMs)}"
    }

    private fun togglePreview() {
        val p = player
        if (p != null && p.isPlaying) {
            pausePreview()
        } else {
            startPreview(startMs)
        }
    }

    private fun auditionSelection() {
        pausePreview()
        startPreview(startMs)
    }

    private fun startPreview(fromMs: Long) {
        pausePreview()
        try {
            player = MediaPlayer().apply {
                setDataSource(sourceFile.absolutePath)
                prepare()
                seekTo(fromMs.toInt())
                start()
            }
            binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
            playHandler.post(playTick)
        } catch (e: Exception) {
            Toast.makeText(this, "试听失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pausePreview() {
        playHandler.removeCallbacks(playTick)
        try {
            player?.let {
                if (it.isPlaying) it.pause()
                it.release()
            }
        } catch (_: Exception) {
        }
        player = null
        binding.btnPlayPause.setImageResource(R.drawable.ic_play)
    }

    private fun resetSelection() {
        startMs = 0
        endMs = durationMs
        binding.trimWaveform.setSelection(0f, 1f)
        updatingSlider = true
        binding.rangeSlider.setValues(0f, durationMs.toFloat())
        updatingSlider = false
        updateInfoTexts()
    }

    private fun validateSelection(): Boolean {
        if (endMs - startMs < AudioTrimmer.MIN_TRIM_MS) {
            Toast.makeText(this, "保留时长过短（至少 1 秒）", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun saveAsCopy() {
        if (!validateSelection()) return
        pausePreview()
        val output = FileUtils.newTrimmedFile(sourceFile)
        runTrim(output) {
            Toast.makeText(this, "已保存副本: ${output.name}", Toast.LENGTH_LONG).show()
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun confirmOverwrite() {
        if (!validateSelection()) return
        AlertDialog.Builder(this)
            .setTitle("覆盖保存")
            .setMessage("将用裁剪后的内容替换原文件 ${sourceFile.name}，该操作不可撤销，确定继续吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("覆盖") { _, _ -> overwriteOriginal() }
            .show()
    }

    private fun overwriteOriginal() {
        pausePreview()
        val temp = File(sourceFile.parentFile, "trim_temp_${System.currentTimeMillis()}.${sourceFile.extension}")
        runTrim(temp) {
            if (sourceFile.delete() && temp.renameTo(sourceFile)) {
                Toast.makeText(this, "已覆盖保存", Toast.LENGTH_LONG).show()
                setResult(RESULT_OK)
                finish()
            } else {
                temp.delete()
                Toast.makeText(this, "覆盖保存失败，原文件已保留", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun runTrim(output: File, onSuccess: () -> Unit) {
        setButtonsEnabled(false)
        thread {
            try {
                AudioTrimmer.trim(sourceFile, output, startMs, endMs)
                runOnUiThread {
                    setButtonsEnabled(true)
                    onSuccess()
                }
            } catch (e: Exception) {
                output.delete()
                runOnUiThread {
                    setButtonsEnabled(true)
                    Toast.makeText(
                        this, "裁剪失败: ${e.message}", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.btnSaveCopy.isEnabled = enabled
        binding.btnOverwrite.isEnabled = enabled
        binding.btnReset.isEnabled = enabled
        binding.btnAudition.isEnabled = enabled
        binding.btnPesq.isEnabled = enabled
    }

    private fun runPesqAnalysis() {
        pausePreview()
        setButtonsEnabled(false)
        binding.btnPesq.text = "分析中…"
        thread {
            try {
                val result = PesqAnalyzer.analyze(sourceFile)
                runOnUiThread {
                    setButtonsEnabled(true)
                    binding.btnPesq.text = getString(R.string.btn_pesq)
                    showPesqResult(result)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setButtonsEnabled(true)
                    binding.btnPesq.text = getString(R.string.btn_pesq)
                    Toast.makeText(
                        this, "分析失败: ${e.message}", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showPesqResult(result: PesqAnalyzer.Result) {
        val message = buildString {
            append("PESQ 评分：${result.score} / 4.5（${result.rating}）\n\n")
            append("有效电平：${result.activeLevelDb} dBFS\n")
            append("估算信噪比：${result.snrDb} dB\n")
            append("削波比例：${result.clippingPercent}%\n")
            append("静音比例：${result.silencePercent}%\n\n")
            append("说明：标准 PESQ（ITU-T P.862）需要原始参考语音做对比，")
            append("本结果为非侵入式估算，基于电平、信噪比、削波、静音等指标映射到 MOS 分值，仅供参考。")
        }
        AlertDialog.Builder(this)
            .setTitle("📊 PESQ 语音质量分析")
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }

    override fun onDestroy() {
        pausePreview()
        binding.trimWaveform.setPlayFraction(-1f)
        super.onDestroy()
    }
}
