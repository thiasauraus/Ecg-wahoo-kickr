package com.example

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class EcgViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application

    // --- Polar H10 Streams ---
    private val _heartRate = MutableStateFlow<Int?>(null)
    val heartRate: StateFlow<Int?> = _heartRate.asStateFlow()

    private val _ecgData = MutableStateFlow<IntArray>(IntArray(0))
    val ecgData: StateFlow<IntArray> = _ecgData.asStateFlow()

    private val _status = MutableStateFlow("Disconnected")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // --- Wahoo KICKR (FTMS) Streams ---
    private val _cadence = MutableStateFlow<Int?>(null)
    val cadence: StateFlow<Int?> = _cadence.asStateFlow()

    private val _actualPower = MutableStateFlow<Int?>(null)
    val actualPower: StateFlow<Int?> = _actualPower.asStateFlow()

    private val _targetPower = MutableStateFlow(220)
    val targetPower: StateFlow<Int> = _targetPower.asStateFlow()

    private val _isFtmsConnected = MutableStateFlow(false)
    val isFtmsConnected: StateFlow<Boolean> = _isFtmsConnected.asStateFlow()

    private val _isControlTransferred = MutableStateFlow(false)
    val isControlTransferred: StateFlow<Boolean> = _isControlTransferred.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    private var service: PolarH10Service? = null
    private var bound = false
    private var shouldScanWhenConnected = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? PolarH10Service.LocalBinder
            if (localBinder == null) {
                android.util.Log.e("EcgViewModel", "Binder is not LocalBinder")
                return
            }
            val s = localBinder.getService()
            service = s
            bound = true

            viewModelScope.launch {
                s.heartRate.collect { _heartRate.value = it }
            }
            viewModelScope.launch {
                s.ecgData.collect { _ecgData.value = it }
            }
            viewModelScope.launch {
                s.status.collect { _status.value = it }
            }
            viewModelScope.launch {
                s.isConnected.collect { _isConnected.value = it }
            }

            // Bind trainer features
            viewModelScope.launch {
                s.cadence.collect { _cadence.value = it }
            }
            viewModelScope.launch {
                s.actualPower.collect { _actualPower.value = it }
            }
            viewModelScope.launch {
                s.targetPower.collect { _targetPower.value = it }
            }
            viewModelScope.launch {
                s.isFtmsConnected.collect { _isFtmsConnected.value = it }
            }
            viewModelScope.launch {
                s.isControlTransferred.collect { _isControlTransferred.value = it }
            }
            viewModelScope.launch {
                s.elapsedSeconds.collect { _elapsedSeconds.value = it }
            }

            if (shouldScanWhenConnected) {
                shouldScanWhenConnected = false
                s.startScanning()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    fun updateStatus(newStatus: String) {
        _status.value = newStatus
    }

    fun bindService() {
        if (!bound) {
            val intent = Intent(app, PolarH10Service::class.java)
            app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun unbindService() {
        if (bound) {
            app.unbindService(connection)
            bound = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        unbindService()
    }

    fun startForegroundService() {
        val intent = Intent(app, PolarH10Service::class.java).apply {
            action = "START_FOREGROUND"
        }
        try {
            androidx.core.content.ContextCompat.startForegroundService(app, intent)
        } catch (e: Exception) {
            android.util.Log.e("EcgViewModel", "Failed to start service", e)
        }
    }

    fun startScanning() {
        val s = service
        if (s != null) {
            s.startScanning()
        } else {
            shouldScanWhenConnected = true
            bindService()
        }
    }

    fun adjustTargetPower(delta: Int) {
        service?.adjustTargetPower(delta)
    }

    fun startTimer() {
        service?.startTimer()
    }

    fun stopTimer() {
        service?.stopTimer()
    }

    fun resetTimer() {
        service?.resetTimer()
    }

    fun disconnect() {
        service?.disconnect()
    }
}