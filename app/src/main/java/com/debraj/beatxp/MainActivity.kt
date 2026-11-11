package com.debraj.beatxp

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.UUID

private val rcspService = UUID.fromString("0000ae00-0000-1000-8000-00805f9b34fb")
private val rcspWrite = UUID.fromString("0000ae01-0000-1000-8000-00805f9b34fb")
private val rcspNotify = UUID.fromString("0000ae02-0000-1000-8000-00805f9b34fb")
private val uartService = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
private val uartWrite = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
private val uartNotify = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CompanionApp() }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun CompanionApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bt = remember { context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    var status by remember { mutableStateOf("Not connected") }
    var battery by remember { mutableIntStateOf(0) }
    var activeTab by remember { mutableIntStateOf(0) }
    var logs by remember { mutableStateOf(listOf("Ready. Local-only companion.")) }
    var gatt by remember { mutableStateOf<BluetoothGatt?>(null) }
    var foundDevices by remember { mutableStateOf(listOf<BluetoothDevice>()) }
    var scanCallback by remember { mutableStateOf<ScanCallback?>(null) }
    fun log(s: String) { logs = (logs + s).takeLast(80) }

    val callback = remember {
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
                status = if (newState == BluetoothProfile.STATE_CONNECTED) "Connected · discovering services" else "Disconnected (GATT status $statusCode)"
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.requestMtu(512)
                    g.discoverServices()
                }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
                status = if (statusCode == BluetoothGatt.GATT_SUCCESS &&
                    (g.getService(rcspService) != null || g.getService(uartService) != null)
                ) "Connected · BLE services ready"
                else "Connected · expected services not found (status $statusCode)"
                listOf(rcspService to rcspNotify, uartService to uartNotify).forEach { (s, c) ->
                    g.getService(s)?.getCharacteristic(c)?.let { ch ->
                        g.setCharacteristicNotification(ch, true)
                        ch.getDescriptor(cccd)?.let { d ->
                            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            else {
                                @Suppress("DEPRECATION")
                                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                g.writeDescriptor(d)
                            }
                        }
                    }
                }
            }
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                val bytes = c.value ?: return
                log("RX ${c.uuid}: " + bytes.joinToString(" ") { "%02X".format(it) })
            }
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                log("RX ${c.uuid}: " + value.joinToString(" ") { "%02X".format(it) })
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        status = if (result.values.all { it }) "Bluetooth permission granted. Tap Scan nearby devices."
        else "Bluetooth permission denied. Allow Nearby devices permission in Android Settings."
    }

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermissions(): Boolean = requiredPermissions().all {
        androidx.core.content.ContextCompat.checkSelfPermission(context, it) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun connectTo(device: BluetoothDevice) {
        if (!hasPermissions()) {
            permissionLauncher.launch(requiredPermissions())
            return
        }
        try {
            bt.adapter.bluetoothLeScanner?.stopScan(scanCallback)
            scanCallback = null
            status = "Connecting to ${device.name ?: "BLE device"}…"
            gatt?.close()
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            status = "Connection error: ${e.message ?: "unknown"}"
        }
    }

    fun scanDevices() {
        if (!hasPermissions()) {
            permissionLauncher.launch(requiredPermissions())
            return
        }
        if (!bt.adapter.isEnabled) {
            status = "Turn Bluetooth ON, then scan again."
            return
        }
        val scanner = bt.adapter.bluetoothLeScanner
        if (scanner == null) {
            status = "Bluetooth LE scanner unavailable."
            return
        }
        foundDevices = emptyList()
        status = "Scanning nearby BLE devices…"
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                foundDevices = (foundDevices.filterNot { it.address == device.address } + device)
                    .sortedBy { it.name ?: it.address }
                status = "Found ${foundDevices.size} BLE device(s). Select your watch."
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { result ->
                    val device = result.device
                    foundDevices = (foundDevices.filterNot { it.address == device.address } + device)
                }
                status = "Found ${foundDevices.size} BLE device(s). Select your watch."
            }
            override fun onScanFailed(errorCode: Int) {
                status = "Scan failed: $errorCode"
            }
        }
        scanCallback = cb
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        } catch (e: Exception) {
            status = "Scan error: ${e.message ?: "unknown"}"
        }
    }

    fun stopScan() {
        try {
            scanCallback?.let { bt.adapter.bluetoothLeScanner?.stopScan(it) }
        } catch (_: Exception) {}
        scanCallback = null
        status = "Scan stopped. Found ${foundDevices.size} BLE device(s)."
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF9A7BFF), background = Color(0xFF101018), surface = Color(0xFF20202C))) {
        Scaffold(containerColor = Color(0xFF101018), bottomBar = {
            NavigationBar {
                listOf("Home", "Studio", "Store", "Settings").forEachIndexed { i, label ->
                    NavigationBarItem(selected = activeTab == i, onClick = { activeTab = i }, icon = {
                        Icon(when (i) { 0 -> Icons.Default.Home; 1 -> Icons.Default.Watch; 2 -> Icons.Default.Store; else -> Icons.Default.Settings }, null)
                    }, label = { Text(label) })
                }
            }
        }) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item { Text("BEATXP VECTOR", style = MaterialTheme.typography.headlineMedium, color = Color.White) }
                when (activeTab) {
                    0 -> {
                        item {
                            ElevatedCard {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("WATCH CONNECTION", style = MaterialTheme.typography.titleMedium)
                                    Text(status)
                                    Button(onClick = { scanDevices() }, modifier = Modifier.fillMaxWidth()) { Text("Scan nearby devices") }
                                    OutlinedButton(onClick = { stopScan() }, modifier = Modifier.fillMaxWidth()) { Text("Stop scan") }
                                    OutlinedButton(onClick = {
                                        gatt?.disconnect()
                                        gatt?.close()
                                        gatt = null
                                        status = "Disconnected"
                                    }, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
                                }
                            }
                        }
                        item { Text("Nearby BLE devices (${foundDevices.size})", style = MaterialTheme.typography.titleLarge, color = Color.White) }
                        if (foundDevices.isEmpty()) {
                            item { Text("No devices listed yet. Keep the watch awake and scan again.", color = Color.LightGray) }
                        }
                        items(foundDevices, key = { it.address }) { device ->
                            ElevatedCard {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(device.name ?: "Unknown BLE device", style = MaterialTheme.typography.titleMedium)
                                    Text(device.address, style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                                    Button(onClick = { connectTo(device) }, modifier = Modifier.fillMaxWidth()) { Text("Connect") }
                                }
                            }
                        }
                        item {
                            ElevatedCard {
                                Column(Modifier.padding(16.dp)) {
                                    Text("Battery", style = MaterialTheme.typography.titleMedium)
                                    Text(if (battery > 0) "$battery%" else "Waiting for device telemetry", style = MaterialTheme.typography.headlineSmall)
                                }
                            }
                        }
                        item { Text("Quick actions", style = MaterialTheme.typography.titleLarge, color = Color.White) }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { log("Time sync requires validated device payload mapping") }, modifier = Modifier.weight(1f)) { Text("Sync time") }
                                Button(onClick = { log("Find watch requires validated device payload mapping") }, modifier = Modifier.weight(1f)) { Text("Find watch") }
                            }
                        }
                    }
                    1 -> item {
                        ElevatedCard {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Wallpaper Studio", style = MaterialTheme.typography.titleLarge)
                                Text("Choose an image and preview it here. Watch transfer is disabled until the firmware-specific file header is verified.")
                                Button(onClick = { log("Wallpaper import UI planned for next build") }) { Text("Wallpaper tools") }
                            }
                        }
                    }
                    2 -> item {
                        ElevatedCard {
                            Column(Modifier.padding(16.dp)) {
                                Text("Mini-app & Dial Store", style = MaterialTheme.typography.titleLarge)
                                Text("Local file transfer support is being validated against the watch firmware. No cloud account is used.")
                            }
                        }
                    }
                    else -> {
                        item {
                            ElevatedCard {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Privacy & Settings", style = MaterialTheme.typography.titleLarge)
                                    Text("All app data stays on this phone. No login, analytics, or cloud sync.")
                                    Text("Notification forwarding is not enabled in this starter build.")
                                }
                            }
                        }
                        item { Text("BLE diagnostic console", style = MaterialTheme.typography.titleMedium) }
                        items(logs.reversed()) { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.LightGray) }
                    }
                }
            }
        }
    }
}
