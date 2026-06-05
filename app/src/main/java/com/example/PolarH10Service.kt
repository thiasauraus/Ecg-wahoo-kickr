package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import android.annotation.SuppressLint
import android.content.pm.ServiceInfo

@SuppressLint("MissingPermission")
class PolarH10Service : Service() {
    private val binder = LocalBinder()

    // --- State Streams ---
    private val _heartRate = MutableStateFlow<Int?>(null)
    val heartRate: StateFlow<Int?> = _heartRate.asStateFlow()

    private val _ecgData = MutableStateFlow<IntArray>(IntArray(0))
    val ecgData: StateFlow<IntArray> = _ecgData.asStateFlow()

    private val _status = MutableStateFlow("Disconnected")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // Fitness Machine State Streams
    private val _cadence = MutableStateFlow<Int?>(null)
    val cadence: StateFlow<Int?> = _cadence.asStateFlow()

    private val _actualPower = MutableStateFlow<Int?>(null)
    val actualPower: StateFlow<Int?> = _actualPower.asStateFlow()

    private val _targetPower = MutableStateFlow(220)
    val targetPower: StateFlow<Int> = _targetPower.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    private val _isFtmsConnected = MutableStateFlow(false)
    val isFtmsConnected: StateFlow<Boolean> = _isFtmsConnected.asStateFlow()

    private val _isControlTransferred = MutableStateFlow(false)
    val isControlTransferred: StateFlow<Boolean> = _isControlTransferred.asStateFlow()

    // --- Legacy and Polar ECG logic ---
    private val ecgBuffer = kotlin.collections.ArrayDeque<Int>()
    private var lastEcgEmit = 0L

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var hrGatt: BluetoothGatt? = null
    private var ftmsGatt: BluetoothGatt? = null
    private var scanning = false

    private var lastHrDevice: BluetoothDevice? = null
    private var lastFtmsDevice: BluetoothDevice? = null
    private var userWantsConnection = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var timerRunning = false
    private var timerBaseElapsed = 0L
    private var timerStartRealtime = 0L
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (timerRunning) {
                val now = android.os.SystemClock.elapsedRealtime()
                _elapsedSeconds.value = timerBaseElapsed + ((now - timerStartRealtime) / 1000)
                updateNotification()
                handler.postDelayed(this, 1000)
            }
        }
    }

    fun startTimer() {
        if (!timerRunning) {
            timerRunning = true
            timerStartRealtime = android.os.SystemClock.elapsedRealtime()
            handler.post(timerRunnable)
            _elapsedSeconds.value = timerBaseElapsed
            updateNotification()
            _status.value = "Timer started"
        }
    }

    fun stopTimer() {
        if (timerRunning) {
            timerRunning = false
            timerBaseElapsed = _elapsedSeconds.value
            handler.removeCallbacks(timerRunnable)
            _status.value = "Timer paused"
            updateNotification()
        }
    }

    fun resetTimer() {
        timerRunning = false
        handler.removeCallbacks(timerRunnable)
        timerBaseElapsed = 0L
        _elapsedSeconds.value = 0L
        updateNotification()
        _status.value = "Timer reset"
    }

    private val gattOperationQueue = ArrayDeque<() -> Unit>()
    private var pendingOperation = false

    @Synchronized
    private fun enqueueOperation(operation: () -> Unit) {
        gattOperationQueue.addLast(operation)
        if (!pendingOperation) {
            executeNextOperation()
        }
    }

    @Synchronized
    private fun executeNextOperation() {
        if (pendingOperation) return
        val op = gattOperationQueue.removeFirstOrNull() ?: return
        pendingOperation = true
        try {
            op()
        } catch (e: Exception) {
            Log.e(TAG, "GATT operation failed", e)
            signalOperationComplete()
        }
    }

    @Synchronized
    private fun signalOperationComplete() {
        pendingOperation = false
        executeNextOperation()
    }

    @Synchronized
    private fun clearOperationQueue() {
        gattOperationQueue.clear()
        pendingOperation = false
    }


    // --- Service & Characteristic UUIDs ---
    private val HR_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val HR_CHAR_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val PMD_SERVICE_UUID = UUID.fromString("fb005c80-02e7-f387-1cad-8acd2d8df0c8")
    private val PMD_CONTROL_UUID = UUID.fromString("fb005c81-02e7-f387-1cad-8acd2d8df0c8")
    private val PMD_DATA_UUID = UUID.fromString("fb005c82-02e7-f387-1cad-8acd2d8df0c8")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // FTMS (Fitness Machine Service) standard UUIDs
    private val FTMS_SERVICE_UUID = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")
    private val INDOOR_BIKE_DATA_UUID = UUID.fromString("00002ad2-0000-1000-8000-00805f9b34fb")
    private val FTMS_CONTROL_POINT_UUID = UUID.fromString("00002ad9-0000-1000-8000-00805f9b34fb")

    // Notification State Tracking
    private var lastBpm = "--"
    private var lastCadenceText = "--"
    private var lastPowerText = "--"
    private var lastTimerText = "00:00"
    private var lastNotif = 0L

    companion object {
        const val CHANNEL_ID = "polar_h10_channel"
        const val NOTIFICATION_ID = 1
        const val TAG = "PolarH10Service"
    }

    inner class LocalBinder : Binder() {
        fun getService(): PolarH10Service = this@PolarH10Service
    }

    override fun onCreate() {
        super.onCreate()
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        createNotificationChannel()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            disconnect()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        } else if (intent?.action == "START_FOREGROUND") {
            try {
                val notification = buildNotification("--", false, "--", "--", "00:00")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val hasBodySensors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            this,
                            android.Manifest.permission.BODY_SENSORS
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    } else {
                        true
                    }
                    val type = if (Build.VERSION.SDK_INT >= 34) {
                        var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                        if (hasBodySensors) {
                            t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                        }
                        t
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    }
                    startForeground(NOTIFICATION_ID, notification, type)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception starting foreground service", e)
                _status.value = "Foreground permission error"
            }
        }
        return START_STICKY
    }

    fun startScanning() {
        val adapter = bluetoothAdapter ?: return
        if (scanning) return

        _status.value = "Scanning..."
        scanning = true

        val scanner = adapter.bluetoothLeScanner
        // Use multiple filters to detect both Heart Rate devices and Smart Trainers
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE_UUID)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(FTMS_SERVICE_UUID)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner?.startScan(filters, settings, scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Scan permission missing or scan failed", e)
            _status.value = "Scan error: ${e.message}"
            scanning = false
        }

        // Auto-stop scanning after 15 seconds to save power
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (scanning) {
                stopScan()
                _status.value = when {
                    hrGatt != null && ftmsGatt != null -> "Connected to H10 & KICKR"
                    hrGatt != null -> "Connected to Polar H10"
                    ftmsGatt != null -> "Connected to Wahoo KICKR"
                    else -> "Scanning completed"
                }
            }
        }, 15000)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                val name = try { device.name } catch (e: Exception) { null } ?: ""
                val serviceUuids = result.scanRecord?.serviceUuids ?: emptyList()

                val isFtms = serviceUuids.contains(ParcelUuid(FTMS_SERVICE_UUID)) ||
                        name.contains("KICKR", ignoreCase = true) ||
                        name.contains("Wahoo", ignoreCase = true) ||
                        name.contains("Trainer", ignoreCase = true)

                val isHr = serviceUuids.contains(ParcelUuid(HR_SERVICE_UUID)) ||
                        name.contains("Polar", ignoreCase = true) ||
                        name.contains("H10", ignoreCase = true) ||
                        name.contains("Heart", ignoreCase = true)

                if (isFtms) {
                    if (ftmsGatt == null) {
                        Log.d(TAG, "Found trainer: $name (${device.address})")
                        connectFtmsDevice(device)
                    }
                } else if (isHr) {
                    if (hrGatt == null) {
                        Log.d(TAG, "Found HR device: $name (${device.address})")
                        connectHrDevice(device)
                    }
                }

                if (hrGatt != null && ftmsGatt != null) {
                    stopScan()
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _status.value = "Scan failed: $errorCode"
            scanning = false
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Stop scan error", e)
        }
    }

    private fun connectHrDevice(device: BluetoothDevice) {
        lastHrDevice = device
        userWantsConnection = true
        val deviceName = try {
            device.name ?: device.address
        } catch (e: Exception) {
            device.address
        }
        _status.value = "Connecting to H10: $deviceName..."
        try {
            hrGatt = device.connectGatt(this, false, hrGattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Log.e(TAG, "Connect H10 error", e)
            _status.value = "H10 connect error: ${e.message}"
        }
    }

    private fun connectFtmsDevice(device: BluetoothDevice) {
        lastFtmsDevice = device
        userWantsConnection = true
        val deviceName = try {
            device.name ?: device.address
        } catch (e: Exception) {
            device.address
        }
        _status.value = "Connecting to KICKR: $deviceName..."
        try {
            ftmsGatt = device.connectGatt(this, false, ftmsGattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Log.e(TAG, "Connect FTMS error", e)
            _status.value = "Trainer connect error: ${e.message}"
        }
    }

    // --- GATT Callback for Polar H10 HRM ---
    private val hrGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _isConnected.value = true
                    _status.value = "H10 connected, configuring MTU..."
                    try {
                        val requested = gatt?.requestMtu(232) == true
                        if (!requested) {
                            gatt?.discoverServices()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "H10 MTU request error", e)
                        try { gatt?.discoverServices() } catch (ex: Exception) {}
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _isConnected.value = false
                    _status.value = "H10 Disconnected"
                    _heartRate.value = null
                    lastBpm = "--"
                    updateNotification()
                    try { hrGatt?.close() } catch (e: Exception) {}
                    hrGatt = null
                    clearOperationQueue()

                    if (userWantsConnection && lastHrDevice != null) {
                        _status.value = "H10 connection lost. Reconnecting..."
                        handler.postDelayed({
                            if (userWantsConnection && hrGatt == null) {
                                lastHrDevice?.let { connectHrDevice(it) }
                            }
                        }, 3000)
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            Log.d(TAG, "H10 MTU changed to $mtu")
            try {
                gatt?.discoverServices()
            } catch (e: Exception) {
                Log.e(TAG, "H10 discover services error", e)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _status.value = "H10 service discovery failed"
                return
            }
            gatt?.let { enableHRNotifications(it) }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            when (characteristic.uuid) {
                HR_CHAR_UUID -> parseHR(value)
                PMD_DATA_UUID -> parseECG(value)
            }
        }

        @Deprecated("Deprecated in Java", ReplaceWith("onCharacteristicChanged(gatt, characteristic, characteristic.value)"))
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            when (characteristic.uuid) {
                HR_CHAR_UUID -> parseHR(characteristic.value)
                PMD_DATA_UUID -> parseECG(characteristic.value)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            if (characteristic?.uuid == PMD_CONTROL_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "ECG control write success")
                    _status.value = "Streaming ECG & HR"
                } else {
                    Log.e(TAG, "ECG control write failed: $status")
                }
            }
            signalOperationComplete()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "H10 descriptor write failed: ${descriptor?.uuid} status: $status")
                signalOperationComplete()
                return
            }
            when (descriptor?.characteristic?.uuid) {
                HR_CHAR_UUID -> {
                    Log.d(TAG, "H10 HR notifications enabled")
                    gatt?.let { setupECG(it) }
                }
                PMD_DATA_UUID -> {
                    Log.d(TAG, "H10 PMD notifications enabled")
                    gatt?.let { writeStartECGCommand(it) }
                }
            }
            signalOperationComplete()
        }
    }

    // --- GATT Callback for Wahoo KICKR v5 Smart Trainer ---
    private val ftmsGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _isFtmsConnected.value = true
                    _status.value = "KICKR connected, configuring MTU..."
                    try {
                        val requested = gatt?.requestMtu(232) == true
                        if (!requested) {
                            gatt?.discoverServices()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "KICKR MTU request error", e)
                        try { gatt?.discoverServices() } catch (ex: Exception) {}
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _isFtmsConnected.value = false
                    _isControlTransferred.value = false
                    _status.value = "KICKR Disconnected"
                    _cadence.value = null
                    _actualPower.value = null
                    lastCadenceText = "--"
                    lastPowerText = "--"
                    updateNotification()
                    try { ftmsGatt?.close() } catch (e: Exception) {}
                    ftmsGatt = null
                    clearOperationQueue()

                    if (userWantsConnection && lastFtmsDevice != null) {
                        _status.value = "KICKR connection lost. Reconnecting..."
                        handler.postDelayed({
                            if (userWantsConnection && ftmsGatt == null) {
                                lastFtmsDevice?.let { connectFtmsDevice(it) }
                            }
                        }, 3000)
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            Log.d(TAG, "KICKR MTU changed to $mtu")
            try {
                gatt?.discoverServices()
            } catch (e: Exception) {
                Log.e(TAG, "KICKR discover services error", e)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _status.value = "KICKR service discovery failed"
                return
            }
            gatt?.let { setupFtms(it) }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            when (characteristic.uuid) {
                INDOOR_BIKE_DATA_UUID -> parseIndoorBike(value)
                FTMS_CONTROL_POINT_UUID -> parseControlPointIndication(value)
            }
        }

        @Deprecated("Deprecated in Java", ReplaceWith("onCharacteristicChanged(gatt, characteristic, characteristic.value)"))
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            when (characteristic.uuid) {
                INDOOR_BIKE_DATA_UUID -> parseIndoorBike(characteristic.value)
                FTMS_CONTROL_POINT_UUID -> parseControlPointIndication(characteristic.value)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "KICKR descriptor write failed: ${descriptor?.uuid} status: $status")
                signalOperationComplete()
                return
            }
            if (descriptor?.characteristic?.uuid == INDOOR_BIKE_DATA_UUID) {
                Log.d(TAG, "KICKR Indoor Bike Data notifications enabled")
                gatt?.let { enableControlPointIndications(it) }
            } else if (descriptor?.characteristic?.uuid == FTMS_CONTROL_POINT_UUID) {
                Log.d(TAG, "KICKR Control Point indications enabled, requesting control")
                gatt?.let { requestFtmsControl(it) }
            }
            signalOperationComplete()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            if (characteristic?.uuid == FTMS_CONTROL_POINT_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "KICKR command write success")
                } else {
                    Log.e(TAG, "KICKR command write failed: $status")
                }
            }
            signalOperationComplete()
        }
    }

    // --- Subscriptions and Control Writes ---
    private fun enableHRNotifications(gatt: BluetoothGatt) {
        try {
            val service = gatt.getService(HR_SERVICE_UUID) ?: return
            val char = service.getCharacteristic(HR_CHAR_UUID) ?: return
            gatt.setCharacteristicNotification(char, true)
            val descriptor = char.getDescriptor(CCCD_UUID) ?: return
            enqueueOperation {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "H10 setup error", e)
        }
    }

    private fun setupECG(gatt: BluetoothGatt) {
        try {
            val pmdService = gatt.getService(PMD_SERVICE_UUID) ?: run {
                _status.value = "H10 ECG service not found"
                return
            }
            val dataChar = pmdService.getCharacteristic(PMD_DATA_UUID) ?: return
            gatt.setCharacteristicNotification(dataChar, true)
            val descriptor = dataChar.getDescriptor(CCCD_UUID) ?: return
            enqueueOperation {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ECG setup error", e)
        }
    }

    private fun writeStartECGCommand(gatt: BluetoothGatt) {
        try {
            val pmdService = gatt.getService(PMD_SERVICE_UUID) ?: return
            val controlChar = pmdService.getCharacteristic(PMD_CONTROL_UUID) ?: return
            val startCmd = byteArrayOf(0x02, 0x00, 0x00, 0x01, 0x82.toByte(), 0x00, 0x01, 0x01, 0x0E, 0x00)
            enqueueOperation {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(controlChar, startCmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                } else {
                    controlChar.value = startCmd
                    controlChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    gatt.writeCharacteristic(controlChar)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ECG start command error", e)
        }
    }

    private fun setupFtms(gatt: BluetoothGatt) {
        try {
            val service = gatt.getService(FTMS_SERVICE_UUID) ?: run {
                Log.e(TAG, "FTMS Service not found")
                return
            }
            val char = service.getCharacteristic(INDOOR_BIKE_DATA_UUID) ?: run {
                Log.e(TAG, "Indoor Bike Data characteristic not found")
                return
            }
            gatt.setCharacteristicNotification(char, true)
            val descriptor = char.getDescriptor(CCCD_UUID) ?: return
            enqueueOperation {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "KICKR subscribe setup error", e)
        }
    }

    private fun enableControlPointIndications(gatt: BluetoothGatt) {
        try {
            val service = gatt.getService(FTMS_SERVICE_UUID) ?: return
            val char = service.getCharacteristic(FTMS_CONTROL_POINT_UUID) ?: run {
                Log.e(TAG, "KICKR Control Point characteristic not found")
                return
            }
            gatt.setCharacteristicNotification(char, true)
            val descriptor = char.getDescriptor(CCCD_UUID) ?: return
            enqueueOperation {
                 if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                     gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                 } else {
                     descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                     gatt.writeDescriptor(descriptor)
                 }
            }
        } catch (e: Exception) {
            Log.e(TAG, "KICKR Control Point enable error", e)
        }
    }

    private fun requestFtmsControl(gatt: BluetoothGatt) {
        try {
            val service = gatt.getService(FTMS_SERVICE_UUID) ?: return
            val controlChar = service.getCharacteristic(FTMS_CONTROL_POINT_UUID) ?: return
            val cmd = byteArrayOf(0x00) // Opcode 0x00: Request Control
            Log.d(TAG, "KICKR write Request Control")
            enqueueOperation {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(controlChar, cmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                } else {
                    controlChar.value = cmd
                    controlChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    gatt.writeCharacteristic(controlChar)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing control request: ${e.message}")
        }
    }

    private fun writeTargetPower(watts: Int) {
        val gatt = ftmsGatt ?: return
        if (!_isControlTransferred.value) {
            Log.w(TAG, "Wahoo KICKR control not transferred yet. Postponing message.")
            return
        }
        try {
            val service = gatt.getService(FTMS_SERVICE_UUID) ?: return
            val controlChar = service.getCharacteristic(FTMS_CONTROL_POINT_UUID) ?: return
            // Opcode 0x05: Set Target Power (UInt16 in Watts)
            val cmd = byteArrayOf(
                0x05,
                (watts and 0xFF).toByte(),
                ((watts shr 8) and 0xFF).toByte()
            )
            Log.d(TAG, "Writing ERG Target Power: $watts W")
            enqueueOperation {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(controlChar, cmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                } else {
                    controlChar.value = cmd
                    controlChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    gatt.writeCharacteristic(controlChar)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing target power: ${e.message}")
        }
    }

    private fun parseControlPointIndication(data: ByteArray) {
        if (data.size < 3) return
        val responseCode = data[0]
        val requestOpcode = data[1]
        val resultCode = data[2]

        Log.d(TAG, "FTMS Control Point Indication: Response Code=0x${String.format("%02X", responseCode)}, Request Opcode=0x${String.format("%02X", requestOpcode)}, Result Code=0x${String.format("%02X", resultCode)}")

        if (responseCode == 0x80.toByte()) {
            if (requestOpcode == 0x00.toByte()) { // Response to Request Control
                if (resultCode == 0x01.toByte()) { // Success
                    Log.i(TAG, "Wahoo KICKR successfully transferred control!")
                    _isControlTransferred.value = true
                    _status.value = "KICKR Control Transferred"
                    // Send default target power now that control is transferred
                    writeTargetPower(_targetPower.value)
                } else {
                    Log.e(TAG, "Wahoo KICKR control transfer failed: $resultCode")
                    _status.value = "KICKR control denied: $resultCode"
                }
            } else if (requestOpcode == 0x05.toByte()) { // Response to Set Target Power
                if (resultCode == 0x01.toByte()) {
                    Log.i(TAG, "Wahoo KICKR target power write successful!")
                } else {
                    Log.e(TAG, "Wahoo KICKR target power write failed: $resultCode")
                }
            }
        }
    }

    fun adjustTargetPower(delta: Int) {
        val newPower = (_targetPower.value + delta).coerceIn(50, 1000)
        _targetPower.value = newPower
        writeTargetPower(newPower)
    }

    // --- Data Parsers ---
    private fun parseHR(data: ByteArray) {
        if (data.isEmpty()) return
        val flags = data[0].toInt()
        val hr = if (flags and 0x01 != 0) {
            if (data.size >= 3) (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8) else null
        } else {
            if (data.size >= 2) data[1].toInt() and 0xFF else null
        }
        hr?.let {
            _heartRate.value = it
            lastBpm = it.toString()
            updateNotification()
        }
    }

    private fun parseECG(data: ByteArray) {
        if (data.size < 10 || data[0] != 0x00.toByte()) return

        var offset = 10
        while (offset + 2 < data.size) {
            val b0 = data[offset].toInt() and 0xFF
            val b1 = data[offset + 1].toInt() and 0xFF
            val b2 = data[offset + 2].toInt() and 0xFF

            var microVolts = (b2 shl 16) or (b1 shl 8) or b0
            if (microVolts >= 0x800000) {
                microVolts -= 0x1000000
            }

            synchronized(ecgBuffer) {
                ecgBuffer.addLast(microVolts)
                if (ecgBuffer.size > 400) {
                    ecgBuffer.removeFirst()
                }
            }
            offset += 3
        }

        val now = System.currentTimeMillis()
        if (now - lastEcgEmit > 100) {
            val snapshot = synchronized(ecgBuffer) { ecgBuffer.toIntArray() }
            _ecgData.value = snapshot
            lastEcgEmit = now
        }
    }

    private fun parseIndoorBike(data: ByteArray) {
        if (data.size < 2) return // need at least flags
        var index = 0
        val flags = (data[index].toInt() and 0xFF) or ((data[index + 1].toInt() and 0xFF) shl 8)
        index += 2

        // FTMS Specifications: Instantaneous Speed is present if More Data (bit 0) is 0
        val hasInstantSpeed = (flags and 0x0001) == 0
        val hasAvgSpeed = (flags and 0x0002) != 0
        val hasInstantCadence = (flags and 0x0004) != 0
        val hasAvgCadence = (flags and 0x0008) != 0
        val hasTotalDistance = (flags and 0x0010) != 0
        val hasResistance = (flags and 0x0020) != 0
        val hasInstantPower = (flags and 0x0040) != 0
        val hasAvgPower = (flags and 0x0080) != 0

        if (hasInstantSpeed) {
            if (index + 1 < data.size) {
                val instantaneousSpeedRaw = (data[index].toInt() and 0xFF) or ((data[index + 1].toInt() and 0xFF) shl 8)
                // val speedKmh = instantaneousSpeedRaw * 0.01
                index += 2
            } else {
                return // truncated
            }
        }

        if (hasAvgSpeed) {
            if (index + 1 < data.size) {
                index += 2
            } else {
                return // truncated
            }
        }

        if (hasInstantCadence) {
            if (index + 1 < data.size) {
                val rawCadence = (data[index].toInt() and 0xFF) or ((data[index + 1].toInt() and 0xFF) shl 8)
                val cadenceVal = (rawCadence * 0.5).toInt() // 0.5 rpm resolution per spec
                _cadence.value = cadenceVal
                lastCadenceText = cadenceVal.toString()
                index += 2
            } else {
                return // truncated
            }
        }

        if (hasAvgCadence) {
            if (index + 1 < data.size) {
                index += 2
            } else {
                return // truncated
            }
        }

        if (hasTotalDistance) {
            if (index + 2 < data.size) {
                index += 3 // 24-bit total distance per spec
            } else {
                return // truncated
            }
        }

        if (hasResistance) {
            if (index < data.size) {
                index += 1 // Resistance Level is sint8 (1 byte per BLE FTMS spec)
            } else {
                return // truncated
            }
        }

        if (hasInstantPower) {
            if (index + 1 < data.size) {
                val rawPower = (data[index].toInt() and 0xFF) or ((data[index + 1].toInt() and 0xFF) shl 8)
                val powerVal = rawPower.toShort().toInt() // sint16 per BLE FTMS spec
                _actualPower.value = powerVal
                lastPowerText = powerVal.toString()
                index += 2
            } else {
                return // truncated
            }
        }

        if (hasAvgPower) {
            if (index + 1 < data.size) {
                index += 2
            } else {
                return // truncated
            }
        }

        updateNotification()
    }
    // --- Foreground Persistence & Notification Builders ---
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Polar H10 & Wahoo Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent trainer and heart stats indicator"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(bpmText: String, isConnected: Boolean, cadenceText: String, powerText: String, timerText: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, PolarH10Service::class.java).apply {
            action = "STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val statusChipText = "BPM: $bpmText  •  ⏱️ $timerText"

        val info = buildString {
            var first = true
            if (cadenceText != "--") {
                append("Cadence: $cadenceText RPM")
                first = false
            }
            if (powerText != "--") {
                if (!first) append("   |   ")
                append("Power: $powerText W")
                first = false
            }
            if (first) {
                append("Connected & Monitoring Statistics")
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Smart Trainer & ECG Monitor")
            .setContentText(info)
            .setSubText(statusChipText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()
    }

        private fun updateNotification() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastNotif < 500) return
        lastNotif = now
        try {
            val secs = _elapsedSeconds.value
            lastTimerText = String.format("%02d:%02d", secs / 60, secs % 60)
            val notification = buildNotification(lastBpm, _isConnected.value || _isFtmsConnected.value, lastCadenceText, lastPowerText, lastTimerText)
            val manager = getSystemService(NotificationManager::class.java)
            // Use startForeground to keep the service alive on Android 14+
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                manager.notify(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Notification refresh error", e)
        }
    }

    fun disconnect() {
        userWantsConnection = false
        lastHrDevice = null
        lastFtmsDevice = null
        _isControlTransferred.value = false
        stopScan()
        clearOperationQueue()
        try {
            hrGatt?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "H10 disconnect error", e)
        }
        try {
            ftmsGatt?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "FTMS disconnect error", e)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
        disconnect()
        stopForeground(STOP_FOREGROUND_REMOVE)
        try { hrGatt?.close() } catch (e: Exception) {}
        try { ftmsGatt?.close() } catch (e: Exception) {}
        hrGatt = null
        ftmsGatt = null
    }
}