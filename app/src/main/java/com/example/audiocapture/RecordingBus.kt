package com.example.audiocapture

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.io.File

object RecordingBus {

    private val _isRecording = MutableLiveData(false)
    val isRecording: LiveData<Boolean> = _isRecording

    private val _amplitude = MutableLiveData(0f)
    val amplitude: LiveData<Float> = _amplitude

    private val _savedFile = MutableLiveData<File?>()
    val savedFile: LiveData<File?> = _savedFile

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    @Volatile
    var startTimestamp: Long = 0L
        private set

    fun onStarted() {
        startTimestamp = System.currentTimeMillis()
        _isRecording.postValue(true)
    }

    fun onAmplitude(value: Float) {
        _amplitude.postValue(value)
    }

    fun onStopped(file: File?) {
        _isRecording.postValue(false)
        _amplitude.postValue(0f)
        _savedFile.postValue(file)
    }

    fun onError(message: String) {
        _isRecording.postValue(false)
        _error.postValue(message)
    }

    fun consumeSavedFile() {
        _savedFile.postValue(null)
    }

    fun consumeError() {
        _error.postValue(null)
    }
}
