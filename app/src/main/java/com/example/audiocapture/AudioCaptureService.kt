package com.example.audiocapture

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.sqrt

class AudioCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.example.audiocapture.action.START"
        const val ACTION_STOP = "com.example.audiocapture.action.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_FORMAT = "extra_format"
        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context, resultCode: Int, data: Intent, format: OutputFormat) {
            val intent = Intent(context, AudioCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_FORMAT, format.name)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AudioCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var encoder: PcmEncoder? = null
    private var outputFile: File? = null

    @Volatile
    private var isRecording = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopRecording()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val formatName = intent.getStringExtra(EXTRA_FORMAT) ?: OutputFormat.WAV.name
                val format = OutputFormat.valueOf(formatName)
                if (data != null) {
                    startCapture(resultCode, data, format)
                } else {
                    RecordingBus.onError("授权数据无效")
                    stopSelf()
                }
            }
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "录音服务", NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("正在录制内部音频")
            .setContentText("点击返回应用")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCapture(resultCode: Int, data: Intent, format: OutputFormat) {
        try {
            startForegroundNotification()

            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(projectionCallback, null)

            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val channelMask = if (AudioParams.CHANNELS == 2)
                AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AudioParams.SAMPLE_RATE)
                .setChannelMask(channelMask)
                .build()

            val minBuffer = AudioRecord.getMinBufferSize(
                AudioParams.SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT
            )

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBuffer * 2)
                .build()

            outputFile = FileUtils.newRecordingFile(format)
            encoder = when (format) {
                OutputFormat.WAV -> WavEncoder(outputFile!!)
                else -> AacEncoder(outputFile!!)
            }

            audioRecord?.startRecording()
            isRecording = true
            RecordingBus.onStarted()
            readLoop(minBuffer * 2)
        } catch (e: Exception) {
            RecordingBus.onError("启动录制失败: ${e.message}")
            cleanup()
            stopSelf()
        }
    }

    private fun readLoop(bufferSize: Int) {
        thread {
            val buffer = ByteArray(bufferSize)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    encoder?.write(buffer, read)
                    RecordingBus.onAmplitude(computeRms(buffer, read))
                }
            }
        }
    }

    private fun computeRms(data: ByteArray, length: Int): Float {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
            sum += sample.toDouble() * sample
            count++
            i += 2
        }
        if (count == 0) return 0f
        val rms = sqrt(sum / count)
        return (rms / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }

    private fun stopRecording() {
        if (!isRecording && audioRecord == null) return
        isRecording = false
        thread {
            val file = try {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
                encoder?.finish()
            } catch (e: Exception) {
                null
            }
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null

            if (file != null && file.exists() && file.length() > 44) {
                RecordingBus.onStopped(file)
            } else {
                file?.delete()
                RecordingBus.onStopped(null)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun cleanup() {
        isRecording = false
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
