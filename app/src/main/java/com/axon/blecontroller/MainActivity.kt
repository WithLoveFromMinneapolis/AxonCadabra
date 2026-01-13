package com.axon.blecontroller

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AxonCadabra"
        private const val TARGET_OUI = "00:25:DF"
        // Service UUID 0xFE6C
        private val SERVICE_UUID = UUID.fromString("0000FE6C-0000-1000-8000-00805F9B34FB")
        // Base service data from screenshot: 01583837303032465034010200000000CE1B330000020000
        private val BASE_SERVICE_DATA = byteArrayOf(
            0x01, 0x58, 0x38, 0x37, 0x30, 0x30, 0x32, 0x46,
            0x50, 0x34, 0x01, 0x02, 0x00, 0x00, 0x00, 0x00,
            0xCE.toByte(), 0x1B, 0x33, 0x00, 0x00, 0x02, 0x00, 0x00
        )
        private const val FUZZ_INTERVAL_MS = 500L
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null

    private var isScanning = false
    private var isAdvertising = false
    private var isFuzzing = false
    private var fuzzValue: Int = 0
    private var currentServiceData = BASE_SERVICE_DATA.copyOf()

    private val fuzzHandler = Handler(Looper.getMainLooper())
    private var fuzzRunnable: Runnable? = null

    private lateinit var scanButton: Button
    private lateinit var advertiseButton: Button
    private lateinit var fuzzButton: Button
    private lateinit var statusText: TextView
    private lateinit var fuzzStatus: TextView
    private lateinit var deviceRecyclerView: RecyclerView

    private val foundDevices = mutableListOf<BleDevice>()
    private lateinit var deviceAdapter: DeviceAdapter

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, ">> PERMISSIONS GRANTED", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, ">> ERROR: BLE PERMS REQUIRED", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initBluetooth()
        checkPermissions()
    }

    private fun initViews() {
        scanButton = findViewById(R.id.scanButton)
        advertiseButton = findViewById(R.id.advertiseButton)
        fuzzButton = findViewById(R.id.fuzzButton)
        statusText = findViewById(R.id.statusText)
        fuzzStatus = findViewById(R.id.fuzzStatus)
        deviceRecyclerView = findViewById(R.id.deviceRecyclerView)

        deviceAdapter = DeviceAdapter(foundDevices)
        deviceRecyclerView.layoutManager = LinearLayoutManager(this)
        deviceRecyclerView.adapter = deviceAdapter

        scanButton.setOnClickListener {
            if (hasRequiredPermissions()) {
                toggleScanning()
            } else {
                checkPermissions()
            }
        }

        advertiseButton.setOnClickListener {
            if (hasRequiredPermissions()) {
                toggleAdvertising()
            } else {
                checkPermissions()
            }
        }

        fuzzButton.setOnClickListener {
            toggleFuzzing()
        }
    }

    private fun initBluetooth() {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, ">> ERROR: NO BT ADAPTER", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser

        if (bluetoothLeAdvertiser == null) {
            Toast.makeText(this, ">> ERROR: BLE ADV NOT SUPPORTED", Toast.LENGTH_LONG).show()
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkPermissions() {
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest)
        }
    }

    private fun toggleScanning() {
        if (isScanning) {
            stopScanning()
        } else {
            startScanning()
        }
    }

    private fun startScanning() {
        if (bluetoothLeScanner == null) {
            Toast.makeText(this, ">> ERROR: NO SCANNER", Toast.LENGTH_SHORT).show()
            return
        }

        foundDevices.clear()
        deviceAdapter.notifyDataSetChanged()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            bluetoothLeScanner?.startScan(null, scanSettings, scanCallback)
            isScanning = true
            scanButton.text = "[ SCAN ■ ]"
            updateStatus()
            Log.d(TAG, ">> SCAN INITIATED FOR OUI: $TARGET_OUI")
        } catch (e: SecurityException) {
            Toast.makeText(this, ">> ERROR: SCAN DENIED", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopScanning() {
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
            scanButton.text = "[ SCAN ]"
            updateStatus()
            Log.d(TAG, ">> SCAN TERMINATED")
        } catch (e: SecurityException) {
            Toast.makeText(this, ">> ERROR: PERMISSION", Toast.LENGTH_SHORT).show()
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = device.address ?: return

            if (address.uppercase().startsWith(TARGET_OUI)) {
                val existingIndex = foundDevices.indexOfFirst { it.address == address }
                val deviceName = try {
                    device.name ?: "UNKNOWN"
                } catch (e: SecurityException) {
                    "UNKNOWN"
                }

                val bleDevice = BleDevice(
                    name = deviceName,
                    address = address,
                    rssi = result.rssi
                )

                runOnUiThread {
                    if (existingIndex >= 0) {
                        foundDevices[existingIndex] = bleDevice
                        deviceAdapter.notifyItemChanged(existingIndex)
                    } else {
                        foundDevices.add(bleDevice)
                        deviceAdapter.notifyItemInserted(foundDevices.size - 1)
                        Log.d(TAG, ">> TARGET ACQUIRED: $address ($deviceName)")
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, ">> SCAN FAILED: ERR_$errorCode")
            runOnUiThread {
                Toast.makeText(this@MainActivity, ">> SCAN FAILED: $errorCode", Toast.LENGTH_SHORT).show()
                isScanning = false
                scanButton.text = "[ SCAN ]"
                updateStatus()
            }
        }
    }

    private fun toggleFuzzing() {
        isFuzzing = !isFuzzing
        if (isFuzzing) {
            fuzzButton.text = "[ FUZZ ■ ]"
            fuzzValue = 0
            if (isAdvertising) {
                startFuzzLoop()
            }
        } else {
            fuzzButton.text = "[ FUZZ ]"
            stopFuzzLoop()
        }
        updateStatus()
    }

    private fun startFuzzLoop() {
        fuzzRunnable = object : Runnable {
            override fun run() {
                if (isFuzzing && isAdvertising) {
                    // Stop current advertising
                    stopAdvertisingInternal()

                    // Increment fuzz value and update service data
                    fuzzValue = (fuzzValue + 1) and 0xFFFF
                    updateServiceDataWithFuzz()

                    // Restart advertising with new data
                    startAdvertisingInternal()

                    // Schedule next iteration
                    fuzzHandler.postDelayed(this, FUZZ_INTERVAL_MS)
                }
            }
        }
        fuzzHandler.post(fuzzRunnable!!)
    }

    private fun stopFuzzLoop() {
        fuzzRunnable?.let { fuzzHandler.removeCallbacks(it) }
        fuzzRunnable = null
    }

    private fun updateServiceDataWithFuzz() {
        // Create a copy of base data
        currentServiceData = BASE_SERVICE_DATA.copyOf()

        // Fuzz strategy: Modify bytes at different positions based on fuzz value
        // Position 10-11 seem to be command bytes (0x01 0x02 in original)
        // Position 20-21 also has values (0x00 0x02 in original)

        // Increment byte at position 10 (command byte 1)
        currentServiceData[10] = ((fuzzValue shr 8) and 0xFF).toByte()
        // Increment byte at position 11 (command byte 2)
        currentServiceData[11] = (fuzzValue and 0xFF).toByte()

        // Also vary the last few bytes
        currentServiceData[20] = ((fuzzValue shr 4) and 0xFF).toByte()
        currentServiceData[21] = ((fuzzValue shl 4) and 0xFF).toByte()

        updateFuzzStatus()
    }

    private fun updateFuzzStatus() {
        val hexBytes = currentServiceData.take(12).joinToString("") { String.format("%02X", it) }
        fuzzStatus.text = "│ FUZZ_VAL: 0x${String.format("%04X", fuzzValue)} DATA: $hexBytes..."
    }

    private fun toggleAdvertising() {
        if (isAdvertising) {
            stopAdvertising()
        } else {
            startAdvertising()
        }
    }

    private fun startAdvertising() {
        if (bluetoothLeAdvertiser == null) {
            Toast.makeText(this, ">> ERROR: NO ADVERTISER", Toast.LENGTH_SHORT).show()
            return
        }

        // Reset to base data if not fuzzing
        if (!isFuzzing) {
            currentServiceData = BASE_SERVICE_DATA.copyOf()
        }

        startAdvertisingInternal()

        // Start fuzz loop if fuzzing is enabled
        if (isFuzzing) {
            startFuzzLoop()
        }
    }

    private fun startAdvertisingInternal() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(ParcelUuid(SERVICE_UUID), currentServiceData)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(true)
            .build()

        try {
            bluetoothLeAdvertiser?.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
            Log.d(TAG, ">> TX INITIATED - DATA: ${currentServiceData.joinToString("") { String.format("%02X", it) }}")
        } catch (e: SecurityException) {
            Toast.makeText(this, ">> ERROR: TX DENIED", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopAdvertising() {
        stopFuzzLoop()
        stopAdvertisingInternal()
        isAdvertising = false
        advertiseButton.text = "•˚₊‧⋆.AxoNCadAbArA.⋆‧₊˚•"
        updateStatus()
        Log.d(TAG, ">> TX TERMINATED")
    }

    private fun stopAdvertisingInternal() {
        try {
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (e: SecurityException) {
            // Ignore
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, ">> TX ACTIVE")
            runOnUiThread {
                isAdvertising = true
                advertiseButton.text = "◉ TX ACTIVE ◉"
                updateStatus()
            }
        }

        override fun onStartFailure(errorCode: Int) {
            val errorMsg = when (errorCode) {
                ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_ACTIVE"
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_OVERFLOW"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "UNSUPPORTED"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERR"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADV"
                else -> "ERR_$errorCode"
            }
            Log.e(TAG, ">> TX FAILED: $errorMsg")
            runOnUiThread {
                isAdvertising = false
                advertiseButton.text = "•˚₊‧⋆.AxoNCadAbArA.⋆‧₊˚•"
                updateStatus()
                Toast.makeText(this@MainActivity, ">> TX FAILED: $errorMsg", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateStatus() {
        val scanStatus = if (isScanning) "ON" else "OFF"
        val txStatus = if (isAdvertising) "ON" else "OFF"
        val fuzzStat = if (isFuzzing) "ON" else "OFF"
        statusText.text = "│ SCAN: $scanStatus │ TX: $txStatus │ FUZZ: $fuzzStat"

        if (!isFuzzing) {
            fuzzStatus.text = "│ FUZZ_VAL: DISABLED"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopFuzzLoop()
        if (isScanning) stopScanning()
        if (isAdvertising) stopAdvertising()
    }

    data class BleDevice(
        val name: String,
        val address: String,
        val rssi: Int
    )

    class DeviceAdapter(private val devices: List<BleDevice>) :
        RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameText: TextView = view.findViewById(R.id.deviceName)
            val addressText: TextView = view.findViewById(R.id.deviceAddress)
            val rssiText: TextView = view.findViewById(R.id.deviceRssi)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_device, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val device = devices[position]
            holder.nameText.text = "► ${device.name}"
            holder.addressText.text = "  MAC: ${device.address}"
            holder.rssiText.text = "RSSI: ${device.rssi}"
        }

        override fun getItemCount() = devices.size
    }
}
