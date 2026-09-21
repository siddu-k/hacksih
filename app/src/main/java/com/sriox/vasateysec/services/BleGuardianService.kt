package com.sriox.vasateysec.services

import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.sriox.vasateysec.R
import com.sriox.vasateysec.utils.LocationManager
import com.sriox.vasateysec.utils.SmsHelper
import kotlinx.coroutines.*
import java.util.*

class BleGuardianService : Service() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    
    private var esp32Latitude: Double? = null
    private var esp32Longitude: Double? = null
    
    private var isSosModeActive = false
    private var sosLoopJob: Job? = null

    private val serviceUuid = UUID.fromString("00001234-0000-1000-8000-00805f9b34fb")
    private val charUuid = UUID.fromString("00005678-0000-1000-8000-00805f9b34fb")

    companion object {
        const val TAG = "BleGuardian"
        const val ACTION_WATCH_STATUS = "com.sriox.vasateysec.WATCH_STATUS"
        const val EXTRA_STATUS = "status"
        
        const val STATUS_DISCONNECTED = "disconnected"
        const val STATUS_CONNECTING = "connecting"
        const val STATUS_CONNECTED = "connected"
        const val STATUS_SOS_ACTIVE = "sos_active"
        const val STATUS_GPS_RECEIVED = "gps_received"
    }

    override fun onCreate() {
        super.onCreate()
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(101, createNotification("Monitoring safety hardware..."))
        
        val prefs = getSharedPreferences("vasatey_settings", MODE_PRIVATE)
        val savedAddress = prefs.getString("last_device_address", null)
        
        if (savedAddress != null && bluetoothAdapter != null) {
            connectToSavedDevice(savedAddress)
        } else {
            startScanning()
        }
        
        return START_STICKY
    }

    private fun connectToSavedDevice(address: String) {
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null

            val device = bluetoothAdapter?.getRemoteDevice(address)
            if (device != null) {
                Log.d(TAG, "Connecting to: $address")
                sendStatusBroadcast(STATUS_CONNECTING)
                
                Handler(Looper.getMainLooper()).post {
                    bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    } else {
                        device.connectGatt(this, false, gattCallback)
                    }
                }
            } else {
                startScanning()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection fail: ${e.message}")
            startScanning()
        }
    }

    private fun sendStatusBroadcast(status: String) {
        val intent = Intent(ACTION_WATCH_STATUS)
        intent.putExtra(EXTRA_STATUS, status)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        sendBroadcast(intent)
    }

    private fun startScanning() {
        if (isScanning || bleScanner == null) return
        val filter = ScanFilter.Builder().setDeviceName("ESP32_Safety_Watch").build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        
        try {
            bleScanner?.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
            sendStatusBroadcast(STATUS_DISCONNECTED)
        } catch (e: Exception) { }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (isScanning) {
                stopScanning()
                connectToSavedDevice(result.device.address)
            }
        }
    }

    private fun stopScanning() {
        try { bleScanner?.stopScan(scanCallback) } catch (e: Exception) {}
        isScanning = false
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "GATT Connected. Discovering...")
                Handler(Looper.getMainLooper()).postDelayed({
                    gatt.discoverServices()
                }, 800)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT Disconnected.")
                gatt.close()
                bluetoothGatt = null
                isSosModeActive = false
                sosLoopJob?.cancel()
                sendStatusBroadcast(STATUS_DISCONNECTED)
                attemptReconnect()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(serviceUuid)
                val characteristic = service?.getCharacteristic(charUuid)
                if (characteristic != null) {
                    Log.d(TAG, "Protocol Found. Ready.")
                    gatt.setCharacteristicNotification(characteristic, true)
                    val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                    if (descriptor != null) {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                    sendStatusBroadcast(STATUS_CONNECTED)
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.getStringValue(0)?.trim() ?: ""
            Log.d(TAG, "Watch Data: '$value'")
            
            when {
                value == "1" || value == "SOS" -> {
                    if (!isSosModeActive) {
                        Log.d(TAG, "Watch Status: SOS SIGNAL RECEIVED")
                        isSosModeActive = true
                        sendStatusBroadcast(STATUS_SOS_ACTIVE)
                        startSosLoop()
                    }
                }
                value == "0" || value == "OFF" -> {
                    Log.d(TAG, "Watch Status: SAFE")
                    isSosModeActive = false
                    sosLoopJob?.cancel()
                    sendStatusBroadcast(STATUS_CONNECTED)
                }
                value.startsWith("GPS:") -> {
                    parseEsp32Gps(value)
                    sendStatusBroadcast(STATUS_GPS_RECEIVED)
                }
            }
        }
    }

    private fun startSosLoop() {
        sosLoopJob?.cancel()
        sosLoopJob = scope.launch {
            while (isSosModeActive) {
                performEmergencyActions()
                // Polling frequently (every 10s) while SOS is active.
                // SmsHelper.sendEmergencySms handles the actual user-defined interval (e.g. 2 min)
                // using its persistent timestamp.
                delay(10000)
            }
        }
    }

    private fun attemptReconnect() {
        scope.launch {
            delay(5000)
            val prefs = getSharedPreferences("vasatey_settings", MODE_PRIVATE)
            val savedAddress = prefs.getString("last_device_address", null)
            if (savedAddress != null) connectToSavedDevice(savedAddress)
        }
    }

    private fun parseEsp32Gps(data: String) {
        try {
            val coords = data.removePrefix("GPS:").split(",")
            if (coords.size == 2) {
                val lat = coords[0].toDoubleOrNull()
                val lon = coords[1].toDoubleOrNull()
                if (lat != null && lon != null && lat != 0.0 && lon != 0.0) {
                    esp32Latitude = lat
                    esp32Longitude = lon
                }
            }
        } catch (e: Exception) { }
    }

    private suspend fun performEmergencyActions() {
        try {
            val prefs = getSharedPreferences("vasatey_settings", MODE_PRIVATE)
            val useHardwareGps = prefs.getBoolean("use_hardware_gps", false)
            val mobileLoc = LocationManager.getCurrentLocation(this@BleGuardianService)
            
            val finalLat = if (useHardwareGps && esp32Latitude != null) esp32Latitude else mobileLoc?.latitude
            val finalLon = if (useHardwareGps && esp32Longitude != null) esp32Longitude else mobileLoc?.longitude

            Log.d(TAG, "Attempting SOS Alert Loop Trigger...")
            // Capture evidence photos locally
            try {
                com.sriox.vasateysec.utils.CameraManager.captureEmergencyPhotos(this@BleGuardianService)
            } catch (ce: Exception) {
                Log.w(TAG, "Hardware photo capture failed: ${ce.message}")
            }

            // Pass isHardware = true to apply hardware interval and bypass voice toggles
            SmsHelper.sendEmergencySms(this@BleGuardianService, finalLat, finalLon, isHardware = true)
        } catch (e: Exception) {
            Log.e(TAG, "PerformEmergencyActions error: ${e.message}")
        }
    }

    private fun createNotification(text: String): Notification {
        val channelId = "BLE_SERVICE_CHANNEL"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Hardware Safety Service", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Hardware Protection")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isSosModeActive = false
        sosLoopJob?.cancel()
        sendStatusBroadcast(STATUS_DISCONNECTED)
        stopScanning()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
