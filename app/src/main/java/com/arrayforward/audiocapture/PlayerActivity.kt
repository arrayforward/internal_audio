package com.arrayforward.audiocapture

import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.arrayforward.audiocapture.databinding.ActivityPlayerBinding
import java.io.File
import kotlin.concurrent.thread

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
    }

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var file: File
    private var player: MediaPlayer? = null
    private var durationMs = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.isPlaying) {
                val pos = p.currentPosition.toLong()
                updateProgress(pos)
                handler.postDelayed(this, 50)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra(EXTRA_FILE_PATH)
        if (path.isNullOrEmpty()) {
            finish()
            return
        }
        file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.playerTitle.text = file.name
        binding.btnClosePlayer.setOnClickListener { finish() }
        binding.btnPlayPause.setOnClickListener { togglePlay() }

        binding.playerWaveform.dragEnabled = false
        binding.playerWaveform.onSeekRequest = { fraction ->
            seekTo((fraction * durationMs).toLong())
        }

        thread {
            val duration = AudioTrimmer.durationMs(file)
            val peaks = try {
                AudioTrimmer.extractPeaks(file)
            } catch (e: Exception) {
                FloatArray(1000)
            }
            runOnUiThread {
                durationMs = duration
                binding.playerWaveform.setPeaks(peaks, duration)
                binding.totalTime.text = FileUtils.formatDuration(duration)
            }
        }
    }

    private fun togglePlay() {
        val p = player
        when {
            p == null -> startPlay(0)
            p.isPlaying -> pausePlay()
            else -> {
                p.start()
                binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
                handler.post(tick)
            }
        }
    }

    private fun startPlay(fromMs: Long) {
        try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { onPlayComplete() }
                prepare()
                if (fromMs > 0) seekTo(fromMs.toInt())
                start()
            }
            binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
            handler.post(tick)
        } catch (e: Exception) {
            Toast.makeText(this, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pausePlay() {
        handler.removeCallbacks(tick)
        try {
            player?.pause()
        } catch (_: Exception) {
        }
        binding.btnPlayPause.setImageResource(R.drawable.ic_play)
    }

    private fun seekTo(ms: Long) {
        val p = player
        if (p == null) {
            startPlay(ms)
        } else {
            p.seekTo(ms.toInt())
            updateProgress(ms)
            if (!p.isPlaying) {
                p.start()
                binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
            }
            handler.removeCallbacks(tick)
            handler.post(tick)
        }
    }

    private fun updateProgress(posMs: Long) {
        binding.currentTime.text = FileUtils.formatDuration(posMs)
        if (durationMs > 0) {
            binding.playerWaveform.setPlayFraction(posMs / durationMs.toFloat())
        }
    }

    private fun onPlayComplete() {
        handler.removeCallbacks(tick)
        binding.btnPlayPause.setImageResource(R.drawable.ic_play)
        binding.playerWaveform.setPlayFraction(-1f)
        binding.currentTime.text = FileUtils.formatDuration(0)
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        super.onDestroy()
    }
}
