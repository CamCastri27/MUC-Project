package edu.gatech.phr25.hapticglove

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

/*
 * MAIN SCREEN — everything the user sees, top to bottom:
 *   1. ConnectionBar  : Bluetooth status, Connect/Disconnect, Demo switch
 *   2. Hint text      : "Tap a finger..."
 *   3. HandView       : the tappable hand (drawing lives in HandView.kt)
 *   4. MotorCard      : on/off switch + intensity slider for the selected finger
 *   5. All On / All off buttons
 *   6. DemoLog        : list of packets, only shown in Demo mode
 *
 * This file only lays things out. What the buttons actually do lives in GloveViewModel.kt.
 */

// EDIT: page background color.
private val Background = Color(0xFFF6F1EC)

// EDIT: intensity (0-100%) the "All On" button sets every motor to.
private const val ALL_ON_INTENSITY = 40

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // EDIT: primary = accent color for buttons, switches and the slider.
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF2F6FED))) {
                Surface(Modifier.fillMaxSize(), color = Background) {
                    GloveScreen()
                }
            }
        }
    }
}

/** Bluetooth permissions the app asks for when you tap Connect (they differ by Android version). */
private val blePermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/** The whole screen. Reads state from [GloveViewModel] and forwards button presses to it. */
@Composable
fun GloveScreen(vm: GloveViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val conn by vm.ble.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Shows Android's permission popup; connects once everything is granted.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted -> if (granted.values.all { it }) vm.ble.connect() }

    // Connect button: ask for permissions first if we don't have them yet.
    fun connect() {
        val missing = blePermissions.any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) permissionLauncher.launch(blePermissions) else vm.ble.connect()
    }

    // EDIT: to reorder sections of the screen, reorder the blocks inside this Column.
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()                    // keep clear of the status bar / gesture bar
            .verticalScroll(rememberScrollState())   // page scrolls if it doesn't fit
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ConnectionBar(
            conn = conn,
            demoMode = ui.demoMode,
            onConnect = ::connect,
            onDisconnect = vm.ble::disconnect,
            onDemoMode = vm::setDemoMode,
        )

        Spacer(Modifier.height(8.dp))
        Text(
            if (ui.selected == null) "Tap a finger to control its motor" else "Tap the hand to deselect",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF5F6368),
        )

        HandView(
            motors = ui.motors,
            selected = ui.selected,
            onFingerTap = vm::select,
            modifier = Modifier
                .widthIn(max = 420.dp) // EDIT: maximum hand size on big screens / tablets
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        )

        // Controls for the selected finger (hidden when no finger is selected).
        val finger = ui.selected
        if (finger != null) {
            MotorCard(
                finger = finger,
                state = ui.motors[finger.ordinal],
                onToggle = { vm.setOn(finger, it) },
                onIntensity = { vm.setIntensity(finger, it) },
            )
        }

        // All On / All off buttons, side by side.
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            // "All On" greys out when every motor is already on at ALL_ON_INTENSITY.
            val allAt40 = ui.motors.all { it.on && it.intensity == ALL_ON_INTENSITY }
            Button(onClick = { vm.allOn(ALL_ON_INTENSITY) }, enabled = !allAt40) {
                Text("All On")
            }
            OutlinedButton(onClick = vm::allOff, enabled = ui.motors.any { it.on }) { Text("All off") }
        }

        if (ui.demoMode) DemoLog(ui.log)
    }
}

/** Top card: connection status dot + text, Connect/Disconnect button, and the Demo switch. */
@Composable
private fun ConnectionBar(
    conn: ConnectionState,
    demoMode: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDemoMode: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // EDIT: status dot color and message for each connection state.
                val (dot, text) = when {
                    demoMode -> Color(0xFFF2A600) to "Demo mode — no glove needed"
                    conn is ConnectionState.Connected -> Color(0xFF1E8E3E) to "Connected to ${conn.name}"
                    conn is ConnectionState.Connecting -> Color(0xFFF2A600) to "Connecting to ${conn.name}…"
                    conn is ConnectionState.Scanning -> Color(0xFFF2A600) to "Searching for glove…"
                    conn is ConnectionState.Error -> Color(0xFFD93025) to conn.message
                    else -> Color(0xFF9AA0A6) to "Not connected"
                }
                Box(Modifier.size(10.dp).background(dot, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!demoMode) {
                    val busy = conn is ConnectionState.Scanning || conn is ConnectionState.Connecting
                    if (conn is ConnectionState.Connected || busy) {
                        OutlinedButton(onClick = onDisconnect) { Text(if (busy) "Cancel" else "Disconnect") }
                    } else {
                        Button(onClick = onConnect) { Text("Connect") }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("Demo", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                Switch(checked = demoMode, onCheckedChange = onDemoMode)
            }
        }
    }
}

/** Card under the hand for the selected finger: title, on/off switch, intensity slider. */
@Composable
private fun MotorCard(
    finger: Finger,
    state: MotorState,
    onToggle: (Boolean) -> Unit,
    onIntensity: (Int) -> Unit,
) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${finger.label} motor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("ESP32 pin ${finger.pin}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF5F6368))
                }
                Text(if (state.on) "On" else "Off", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                Switch(checked = state.on, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Intensity", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text("${state.intensity}%", style = MaterialTheme.typography.titleMedium)
            }
            // EDIT: add `steps = 9` to make the slider snap to 10% increments.
            Slider(
                value = state.intensity.toFloat(),
                onValueChange = { onIntensity(it.roundToInt()) },
                valueRange = 0f..100f,
            )
            if (!state.on) {
                Text(
                    "Motor is off — the intensity is saved and applied when you turn it on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF5F6368),
                )
            }
        }
    }
}

/** Dark card shown in Demo mode listing the exact bytes that would be sent to the glove (newest first). */
@Composable
private fun DemoLog(lines: List<String>) {
    Card(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF202124)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Packets that would be sent to the glove", color = Color(0xFFBDC1C6), fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            if (lines.isEmpty()) {
                Text("(none yet — tap a finger and change something)", color = Color(0xFF9AA0A6), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            // EDIT: how many log lines to show.
            lines.take(12).forEach {
                Text(it, color = Color(0xFFE8EAED), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
