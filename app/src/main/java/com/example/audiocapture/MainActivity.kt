package com.example.audiocapture

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.audiocapture.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: RecordingAdapter
    private var selectedFormat: OutputFormat = OutputFormat.WAV
    private val timerHandler = Handler(Looper.getMainLooper())

    private val timerRunnable = object : Runnable {
        override fun run() {
            val elapsed = System.currentTimeMillis() - RecordingBus.startTimestamp
            binding.timer.text = FileUtils.formatDuration(elapsed)
            timerHandler.postDelayed(this, 500)
        }
    }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                AudioCaptureService.start(this, result.resultCode, result.data!!, selectedFormat)
            } else {
                Toast.makeText(this, "系统授权失败，无法录制内部音频", Toast.LENGTH_SHORT).show()
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.RECORD_AUDIO] == true) {
                requestProjection()
            } else {
                Toast.makeText(this, "无法录音，请前往设置开启权限", Toast.LENGTH_LONG).show()
            }
        }

    private val trimLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshFileList()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupFormatSpinner()
        setupRecordButton()
        setupFileList()
        observeRecordingBus()
        ensurePermissions(false)
    }

    private fun setupFormatSpinner() {
        val formats = OutputFormat.entries.map { it.displayName }
        binding.spinnerFormat.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, formats
        )
        binding.spinnerFormat.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    selectedFormat = OutputFormat.entries[position]
                    if (selectedFormat == OutputFormat.MP3) {
                        Toast.makeText(
                            this@MainActivity,
                            "当前版本未集成 LAME 库，MP3 将自动降级为 M4A 保存",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    private fun setupRecordButton() {
        binding.btnRecord.setOnClickListener {
            val recording = RecordingBus.isRecording.value == true
            if (recording) {
                AudioCaptureService.stop(this)
            } else {
                ensurePermissions(true)
            }
        }
    }

    private fun ensurePermissions(launchProjectionWhenGranted: Boolean) {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        if (launchProjectionWhenGranted) {
            if (needsAllFilesAccess()) {
                showAllFilesAccessDialog()
            } else {
                requestProjection()
            }
        }
    }

    private fun needsAllFilesAccess(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !android.os.Environment.isExternalStorageManager()
    }

    private fun showAllFilesAccessDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要文件访问权限")
            .setMessage("保存录音到 Music/audio 目录需要“所有文件访问”权限，请在系统设置中开启后返回。")
            .setNegativeButton("取消", null)
            .setPositiveButton("去开启") { _, _ ->
                try {
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    )
                }
            }
            .show()
    }

    private fun requestProjection() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun observeRecordingBus() {
        RecordingBus.isRecording.observe(this) { recording ->
            updateRecordingUi(recording)
        }
        RecordingBus.amplitude.observe(this) { amp ->
            if (RecordingBus.isRecording.value == true) {
                binding.waveform.addAmplitude(amp)
            }
        }
        RecordingBus.savedFile.observe(this) { file ->
            if (file != null) {
                Toast.makeText(
                    this, "已保存: ${file.absolutePath}", Toast.LENGTH_LONG
                ).show()
                RecordingBus.consumeSavedFile()
            }
            refreshFileList()
        }
        RecordingBus.error.observe(this) { msg ->
            if (msg != null) {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                RecordingBus.consumeError()
            }
        }
    }

    private fun updateRecordingUi(recording: Boolean) {
        if (recording) {
            binding.btnRecord.text = "⏹ 停止"
            binding.btnRecord.setIconResource(R.drawable.ic_stop)
            binding.spinnerFormat.isEnabled = false
            binding.waveform.clear()
            timerHandler.post(timerRunnable)
        } else {
            binding.btnRecord.text = "● 录音"
            binding.btnRecord.setIconResource(R.drawable.ic_mic)
            binding.spinnerFormat.isEnabled = true
            timerHandler.removeCallbacks(timerRunnable)
        }
    }

    private fun setupFileList() {
        adapter = RecordingAdapter(
            onPlay = { playFile(it) },
            onRename = { showRenameDialog(it) },
            onTrim = { openTrim(it) },
            onDelete = { confirmDelete(it) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        refreshFileList()
    }

    private fun refreshFileList() {
        val files = FileUtils.listRecordings()
        adapter.submitList(files)
        binding.emptyView.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun playFile(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "audio/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "播放录音"))
        } catch (e: Exception) {
            Toast.makeText(this, "无法播放: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRenameDialog(file: File) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_rename, null)
        val input = dialogView.findViewById<EditText>(R.id.renameInput)
        input.setText(file.nameWithoutExtension)
        input.setSelection(input.text.length)
        dialogView.findViewById<android.widget.TextView>(R.id.renameNote).text =
            "扩展名 .${file.extension} 不可修改"

        AlertDialog.Builder(this)
            .setTitle("重命名录音文件")
            .setView(dialogView)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                val newName = input.text.toString().trim()
                when {
                    newName.isEmpty() ->
                        Toast.makeText(this, "文件名不能为空", Toast.LENGTH_SHORT).show()
                    newName.contains(Regex("[\\\\/:*?\"<>|]")) ->
                        Toast.makeText(this, "文件名包含非法字符", Toast.LENGTH_SHORT).show()
                    else -> {
                        val target = File(file.parentFile, "$newName.${file.extension}")
                        if (target.exists()) {
                            Toast.makeText(this, "文件名已存在", Toast.LENGTH_SHORT).show()
                        } else if (file.renameTo(target)) {
                            refreshFileList()
                        } else {
                            Toast.makeText(this, "重命名失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun openTrim(file: File) {
        trimLauncher.launch(
            Intent(this, TrimActivity::class.java).apply {
                putExtra(TrimActivity.EXTRA_FILE_PATH, file.absolutePath)
            }
        )
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle("删除文件")
            .setMessage("确定删除 ${file.name} 吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                if (file.delete()) {
                    refreshFileList()
                } else {
                    Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshFileList()
    }

    override fun onDestroy() {
        timerHandler.removeCallbacks(timerRunnable)
        super.onDestroy()
    }
}
