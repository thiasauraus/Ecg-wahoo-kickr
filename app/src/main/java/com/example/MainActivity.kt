package com.example

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.theme.PolarH10ECGTheme
import android.annotation.SuppressLint

@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity() {
    private val viewModel: EcgViewModel by viewModels()

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            checkBluetoothAndStart()
        } else {
            viewModel.updateStatus("Bluetooth enabling was canceled")
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val bleScanGranted = permissions[Manifest.permission.BLUETOOTH_SCAN] ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        val bleConnectGranted = permissions[Manifest.permission.BLUETOOTH_CONNECT] ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED)

        val legacyBtGranted = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val bt = permissions[Manifest.permission.BLUETOOTH] ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            val btAdmin = permissions[Manifest.permission.BLUETOOTH_ADMIN] ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            val locFine = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            val locCoarse = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            bt && btAdmin && (locFine || locCoarse)
        } else {
            true
        }

        if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && bleScanGranted && bleConnectGranted) ||
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && legacyBtGranted)) {
            checkBluetoothAndStart()
        } else {
            viewModel.updateStatus("Bluetooth permissions denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        viewModel.bindService()

        setContent {
            PolarH10ECGTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        viewModel = viewModel,
                        onConnect = { checkPermissionsAndConnect() },
                        onDisconnect = { viewModel.disconnect() }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            val locFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val locCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    (locFine || locCoarse)
        }
    }

    private fun checkPermissionsAndConnect() {
        if (hasBlePermissions()) {
            checkBluetoothAndStart()
        } else {
            requestBlePermissions()
        }
    }

    private fun checkBluetoothAndStart() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        if (adapter == null) {
            viewModel.updateStatus("Bluetooth not supported")
            return
        }
        if (!adapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            try {
                enableBluetoothLauncher.launch(enableBtIntent)
            } catch (e: Exception) {
                viewModel.updateStatus("Could not request Bluetooth enable")
            }
            return
        }
        // Ble permissions are granted, and Bluetooth is enabled! Start!
        viewModel.startForegroundService()
        viewModel.startScanning()
    }

    private fun requestBlePermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }
}

@Composable
fun MainScreen(
    viewModel: EcgViewModel,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val status by viewModel.status.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val isFtmsConnected by viewModel.isFtmsConnected.collectAsState()

    val isAnyConnected = isConnected || isFtmsConnected

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = "Ecg & Smart Trainer Dashboard",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onConnect,
                enabled = !isAnyConnected,
                modifier = Modifier
                    .weight(1.5f)
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE32B2B),
                    disabledContainerColor = Color(0xFFCCCCCC)
                )
            ) {
                Text("Scan & Connect", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }

            Button(
                onClick = onDisconnect,
                enabled = isAnyConnected,
                modifier = Modifier
                    .weight(1.0f)
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE32B2B),
                    disabledContainerColor = Color(0xFFCCCCCC)
                )
            ) {
                Text("Disconnect", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Status: $status",
            fontSize = 14.sp,
            color = Color.Gray,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Smart Trainer Controls & Real-time Metrics Card
        TrainerControlCard(viewModel = viewModel)

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Heart Rate: ",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        HeartRateText(viewModel)

        Spacer(modifier = Modifier.height(16.dp))

        EcgGraphContainer(
            viewModel = viewModel,
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
        )
    }
}

@Composable
fun TrainerControlCard(viewModel: EcgViewModel) {
    val cadence by viewModel.cadence.collectAsState()
    val actualPower by viewModel.actualPower.collectAsState()
    val targetPower by viewModel.targetPower.collectAsState()
    val isFtmsConnected by viewModel.isFtmsConnected.collectAsState()
    val isControlTransferred by viewModel.isControlTransferred.collectAsState()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Wahoo KICKR v5 (FTMS)",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(6.dp))

            // State indicator bullet
            val statusColor = if (isFtmsConnected) {
                if (isControlTransferred) Color(0xFF4CAF50) else Color(0xFFFF9800)
            } else {
                Color.Gray
            }
            val statusText = if (isFtmsConnected) {
                if (isControlTransferred) "Connected & Control Granted" else "Connected (Awaiting Control...)"
            } else {
                "Disconnected"
            }
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Canvas(modifier = Modifier.size(8.dp)) {
                    drawCircle(color = statusColor)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = statusText,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Power and Cadence Metrics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "CADENCE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Text(
                        text = if (isFtmsConnected && cadence != null) "$cadence" else "--",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "RPM",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "ACTUAL POWER",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Text(
                        text = if (isFtmsConnected && actualPower != null) "$actualPower" else "--",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "WATTS",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Target ERG Power Configuration
            Text(
                text = "ERG TARGET WATTAGE",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Text(
                text = "$targetPower W",
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Adjust Power +/- 5W buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { viewModel.adjustTargetPower(-5) },
                    enabled = isFtmsConnected && isControlTransferred,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        disabledContainerColor = Color(0xFFDDDDDD)
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                ) {
                    Text("- 5 W", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                }

                Button(
                    onClick = { viewModel.adjustTargetPower(5) },
                    enabled = isFtmsConnected && isControlTransferred,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        disabledContainerColor = Color(0xFFDDDDDD)
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                ) {
                    Text("+ 5 W", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
fun HeartRateText(viewModel: EcgViewModel) {
    val heartRate by viewModel.heartRate.collectAsState()
    Text(
        text = "${heartRate ?: "--"} BPM",
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFFE32B2B)
    )
}

@Composable
fun EcgGraphContainer(viewModel: EcgViewModel, modifier: Modifier = Modifier) {
    val ecgData by viewModel.ecgData.collectAsState()
    EcgGraph(data = ecgData, modifier = modifier)
}

@Composable
fun EcgGraph(data: IntArray, modifier: Modifier = Modifier) {
    val pathColor = MaterialTheme.colorScheme.primary

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        if (width <= 0f || height <= 0f) return@Canvas

        drawGrid(width, height)

        if (data.size < 2) return@Canvas

        var min = data.minOrNull() ?: 0
        var max = data.maxOrNull() ?: 0
        var range = max - min

        if (range < 1000) {
            val mid = (max + min) / 2
            min = mid - 500
            max = mid + 500
            range = 1000
        } else {
            min -= (range * 0.1).toInt()
            max += (range * 0.1).toInt()
            range = max - min
        }

        val step = width / 800f
        val startX = (width - data.size * step).coerceAtLeast(0f)

        val path = androidx.compose.ui.graphics.Path().apply {
            data.forEachIndexed { i, value ->
                val x = startX + i * step
                val normalizedY = (value - min).toFloat() / range
                val y = height - (normalizedY * height)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }

        drawPath(
            path = path,
            color = pathColor,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
        )
    }
}

private fun DrawScope.drawGrid(width: Float, height: Float) {
    val minorColor = Color(0xFFFFE6E6)
    val majorColor = Color(0xFFFFB3B3)

    val minorPath = androidx.compose.ui.graphics.Path()
    val majorPath = androidx.compose.ui.graphics.Path()

    for (x in 0..width.toInt() step 10) {
        val path = if (x % 50 == 0) majorPath else minorPath
        path.moveTo(x.toFloat(), 0f)
        path.lineTo(x.toFloat(), height)
    }

    for (y in 0..height.toInt() step 10) {
        val path = if (y % 50 == 0) majorPath else minorPath
        path.moveTo(0f, y.toFloat())
        path.lineTo(width, y.toFloat())
    }

    drawPath(
        path = minorPath,
        color = minorColor,
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f)
    )
    drawPath(
        path = majorPath,
        color = majorColor,
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f)
    )
}
