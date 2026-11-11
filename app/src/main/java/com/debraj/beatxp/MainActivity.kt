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
    fun log(s: String) { logs = (logs + s).takeLast(80) }
    val callback = remember {
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
                status = if (newState == BluetoothProfile.STATE_CONNECTED) "Connected · discovering services" else "Disconnected"
                if (newState == BluetoothProfile.STATE_CONNECTED) { g.requestMtu(512); g.discoverServices() }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
                status = if (g.getService(rcspService) != null || g.getService(uartService) != null) "Connected · BLE services ready" else "Connected · expected services missing"
                listOf(rcspService to rcspNotify, uartService to uartNotify).forEach { (s,c) ->
                    g.getService(s)?.getCharacteristic(c)?.let { ch ->
                        g.setCharacteristicNotification(ch, true)
                        ch.getDescriptor(cccd)?.let { d ->
                            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            else { @Suppress("DEPRECATION") d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; @Suppress("DEPRECATION") g.writeDescriptor(d) }
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
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) status = "Bluetooth permission granted. Tap Connect." else status = "Bluetooth permission denied"
    }
    fun connect() {
        val perms = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = perms.filter { androidx.core.content.ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { permissionLauncher.launch(missing.toTypedArray()); return }
        val scanner = bt.adapter.bluetoothLeScanner
        if (scanner == null) { status = "Bluetooth LE scanner unavailable"; return }
        status = "Scanning for BEATXP VECTOR…"
        val filters = listOf(ScanFilter.Builder().setDeviceAddress("70:CF:70:F1:5B:5E").build())
        scanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                val name = result.device.name.orEmpty()
                if (result.device.address == "70:CF:70:F1:5B:5E" || name.contains("beatxp",true) || name.contains("vector",true)) {
                    scanner.stopScan(this); status = "Connecting to ${name.ifBlank { "watch" }}…"; gatt = result.device.connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE)
                }
            }
            override fun onScanFailed(errorCode: Int) { status = "Scan failed: $errorCode" }
        })
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF9A7BFF), background = Color(0xFF101018), surface = Color(0xFF20202C))) {
        Scaffold(containerColor = Color(0xFF101018), bottomBar = {
            NavigationBar {
                listOf("Home","Studio","Store","Settings").forEachIndexed { i, label ->
                    NavigationBarItem(selected = activeTab == i, onClick = { activeTab = i }, icon = {
                        Icon(when(i) { 0 -> Icons.Default.Home; 1 -> Icons.Default.Watch; 2 -> Icons.Default.Store; else -> Icons.Default.Settings }, null)
                    }, label = { Text(label) })
                }
            }
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { Text("BEATXP VECTOR", style = MaterialTheme.typography.headlineMedium, color = Color.White) }
                when(activeTab) {
                    0 -> {
                        item { ElevatedCard { Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text("WATCH CONNECTION", style=MaterialTheme.typography.titleMedium)
                            Text(status)
                            Button(onClick = { connect() }, modifier=Modifier.fillMaxWidth()) { Text("Connect to Watch") }
                            OutlinedButton(onClick = { gatt?.disconnect(); gatt?.close(); gatt=null; status="Disconnected" }, modifier=Modifier.fillMaxWidth()) { Text("Disconnect") }
                        } } }
                        item { ElevatedCard { Column(Modifier.padding(16.dp)) { Text("Battery", style=MaterialTheme.typography.titleMedium); Text(if (battery>0) "$battery%" else "Waiting for device telemetry", style=MaterialTheme.typography.headlineSmall) } } }
                        item { Text("Quick actions", style=MaterialTheme.typography.titleLarge) }
                        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { log("Time sync requires validated device payload mapping") }, modifier=Modifier.weight(1f)) { Text("Sync time") }
                            Button(onClick = { log("Find watch requires validated device payload mapping") }, modifier=Modifier.weight(1f)) { Text("Find watch") }
                        } }
                    }
                    1 -> item { ElevatedCard { Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("Wallpaper Studio", style=MaterialTheme.typography.titleLarge)
                        Text("Choose an image and preview it here. Watch transfer is disabled until the firmware-specific file header is verified.")
                        Button(onClick = { log("Wallpaper import UI planned for next build") }) { Text("Wallpaper tools") }
                    } } }
                    2 -> item { ElevatedCard { Column(Modifier.padding(16.dp)) {
                        Text("Mini-app & Dial Store", style=MaterialTheme.typography.titleLarge)
                        Text("Local file transfer support is being validated against the watch firmware. No cloud account is used.")
                    } } }
                    else -> {
                        item { ElevatedCard { Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text("Privacy & Settings", style=MaterialTheme.typography.titleLarge)
                            Text("All app data stays on this phone. No login, analytics, or cloud sync.")
                            Text("Notification forwarding is not enabled in this starter build.")
                        } } }
                        item { Text("BLE diagnostic console", style=MaterialTheme.typography.titleMedium) }
                        items(logs.reversed()) { Text(it, style=MaterialTheme.typography.bodySmall, color=Color.LightGray) }
                    }
                }
            }
        }
    }
}
