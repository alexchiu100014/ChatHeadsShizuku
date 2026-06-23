package com.chatheads.launcher.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chatheads.launcher.data.AppPreferences
import com.chatheads.launcher.service.ChatHeadService
import com.chatheads.launcher.shizuku.FreeformLauncher
import com.chatheads.launcher.ui.theme.ChatHeadsTheme
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private var serviceRunning = mutableStateOf(false)
    private var shizukuStatus = mutableStateOf(ShizukuState.NOT_RUNNING)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshShizukuStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shizukuStatus.value = ShizukuState.NOT_RUNNING
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            shizukuStatus.value = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                ShizukuState.GRANTED
            } else {
                ShizukuState.DENIED
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            ChatHeadsTheme {
                MainScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshShizukuStatus()
    }

    private fun refreshShizukuStatus() {
        shizukuStatus.value = try {
            when {
                !Shizuku.pingBinder() -> ShizukuState.NOT_RUNNING
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.GRANTED
                Shizuku.shouldShowRequestPermissionRationale() -> ShizukuState.DENIED
                else -> ShizukuState.NEEDS_REQUEST
            }
        } catch (_: Exception) {
            ShizukuState.NOT_RUNNING
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreen() {
        val prefs = remember { AppPreferences(this@MainActivity) }
        val selectedApps by prefs.selectedApps.collectAsState(initial = emptySet())
        val isRunning by serviceRunning
        val status by shizukuStatus

        DisposableEffect(Unit) {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            onDispose {
                Shizuku.removeBinderReceivedListener(binderReceivedListener)
                Shizuku.removeBinderDeadListener(binderDeadListener)
                Shizuku.removeRequestPermissionResultListener(permissionResultListener)
            }
        }

        Scaffold(
            topBar = { TopAppBar(title = { Text("ChatHeads") }) },
            floatingActionButton = {
                FloatingActionButton(onClick = {
                    startActivity(Intent(this@MainActivity, AppPickerActivity::class.java))
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Add apps")
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PermissionCard(
                    title = "Overlay Permission",
                    granted = Settings.canDrawOverlays(this@MainActivity),
                    onGrant = {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    }
                )

                ShizukuCard(
                    state = status,
                    onRequestPermission = {
                        if (Shizuku.pingBinder()) Shizuku.requestPermission(0)
                    }
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Selected Apps: ${selectedApps.size}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        selectedApps.forEach { pkg ->
                            val label = try {
                                packageManager.getApplicationLabel(
                                    packageManager.getApplicationInfo(pkg, 0)
                                ).toString()
                            } catch (_: Exception) { pkg }
                            Text(text = label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            FreeformLauncher.bindService()
                            ChatHeadService.start(this@MainActivity, selectedApps)
                            serviceRunning.value = true
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isRunning && selectedApps.isNotEmpty() &&
                            Settings.canDrawOverlays(this@MainActivity)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Start")
                    }

                    Button(
                        onClick = {
                            FreeformLauncher.unbindService()
                            ChatHeadService.stop(this@MainActivity)
                            serviceRunning.value = false
                        },
                        modifier = Modifier.weight(1f),
                        enabled = isRunning
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Stop")
                    }
                }
            }
        }
    }
}

enum class ShizukuState { NOT_RUNNING, GRANTED, DENIED, NEEDS_REQUEST }

@Composable
fun PermissionCard(title: String, granted: Boolean, onGrant: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (granted) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.Error,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (!granted) {
                Button(onClick = onGrant) { Text("Grant") }
            }
        }
    }
}

@Composable
fun ShizukuCard(state: ShizukuState, onRequestPermission: () -> Unit) {
    val (icon, text, isOk) = when (state) {
        ShizukuState.GRANTED -> Triple(Icons.Filled.CheckCircle, "Shizuku: Ready", true)
        ShizukuState.DENIED -> Triple(Icons.Filled.Error, "Shizuku: Permission Denied", false)
        ShizukuState.NOT_RUNNING -> Triple(Icons.Filled.Warning, "Shizuku: Not Running", false)
        ShizukuState.NEEDS_REQUEST -> Triple(Icons.Filled.Warning, "Shizuku: Permission Required", false)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isOk) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (state == ShizukuState.NEEDS_REQUEST || state == ShizukuState.DENIED) {
                Button(onClick = onRequestPermission) { Text("Grant") }
            }
        }
    }
}
