package com.nullroute

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nullroute.data.SharedPreferencesBlocklistRepository
import com.nullroute.ui.MainViewModel
import com.nullroute.ui.MainViewModelFactory
import com.nullroute.vpn.DnsVpnService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if we were launched due to a blocked settings/uninstall attempt
        val isBlockedAttempt = intent.getBooleanExtra("BLOCKED_ATTEMPT", false)

        setContent {
            NullRouteTheme {
                val repository = remember { SharedPreferencesBlocklistRepository(applicationContext) }
                val factory = remember { MainViewModelFactory(repository) }
                val viewModel: MainViewModel = viewModel(factory = factory)

                MainScreen(viewModel, isBlockedAttempt)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val isBlockedAttempt = intent.getBooleanExtra("BLOCKED_ATTEMPT", false)
        if (isBlockedAttempt) {
            Toast.makeText(this, "Self-sabotage blocked! Focus mode is active.", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
fun NullRouteTheme(content: @Composable () -> Unit) {
    // Custom Slate/Indigo Theme
    val darkColorScheme = darkColorScheme(
        primary = Color(0xFF6366F1), // Indigo
        secondary = Color(0xFF4F46E5),
        background = Color(0xFF0F172A), // Slate 900
        surface = Color(0xFF1E293B),    // Slate 800
        onBackground = Color(0xFFF8FAFC),
        onSurface = Color(0xFFE2E8F0)
    )

    MaterialTheme(
        colorScheme = darkColorScheme,
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel, initialBlockedAttempt: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // Collect States from ViewModel
    val isVpnActive by viewModel.isVpnActive.collectAsState()
    val isAccessibilityActive by viewModel.isAccessibilityActive.collectAsState()
    val isPermanentLockActive by viewModel.isPermanentLockActive.collectAsState()
    val isBypassActive by viewModel.isBypassProtectionActive.collectAsState()
    val initialDomains by viewModel.initialDomains.collectAsState()
    val customDomains by viewModel.customDomains.collectAsState()

    var domainInput by remember { mutableStateOf("") }
    var showLockConfirmDialog by remember { mutableStateOf(false) }
    var showBlockedToast by remember { mutableStateOf(initialBlockedAttempt) }

    // Refresh states on app resume
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refreshStates(context)
                viewModel.loadData()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // VPN Permission Launcher
    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            context.startService(Intent(context, DnsVpnService::class.java))
            viewModel.refreshStates(context)
        } else {
            Toast.makeText(context, "VPN Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    if (showBlockedToast) {
        AlertDialog(
            onDismissRequest = { showBlockedToast = false },
            title = { Text("⚠️ System Lock Active") },
            text = { Text("NullRoute blocked an attempt to disable protection or access application details in Settings. Focus on your goals!") },
            confirmButton = {
                TextButton(onClick = { showBlockedToast = false }) {
                    Text("Understood")
                }
            }
        )
    }

    if (showLockConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showLockConfirmDialog = false },
            title = { Text("🔒 Freeze Settings Permanently?") },
            text = { Text("Warning: Once activated, you CANNOT add any more websites to your blocklist. The list will be locked forever. Are you absolutely sure?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.freezeLock(context)
                        showLockConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Lock Permanently")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLockConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "NullRoute",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Info Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Focus Mode",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isVpnActive && isAccessibilityActive) "Full Protection Enabled 🛡️" else "Setup Required",
                            fontSize = 14.sp,
                            color = if (isVpnActive && isAccessibilityActive) Color(0xFF10B981) else Color(0xFFF59E0B)
                        )
                    }
                }
            }

            // Controls Header
            item {
                Text(
                    text = "System Setup",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // VPN Service card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("1. System Blocker (VPN)", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = if (isVpnActive) "Running" else "Not Running",
                                fontSize = 12.sp,
                                color = if (isVpnActive) Color(0xFF10B981) else Color(0xFFEF4444)
                            )
                        }
                        Button(
                            onClick = {
                                val prepareIntent = VpnService.prepare(context)
                                if (prepareIntent != null) {
                                    vpnLauncher.launch(prepareIntent)
                                } else {
                                    context.startService(Intent(context, DnsVpnService::class.java))
                                    viewModel.refreshStates(context)
                                }
                            },
                            enabled = !isVpnActive
                        ) {
                            Text(if (isVpnActive) "Active" else "Enable")
                        }
                    }
                }
            }

            // Accessibility Blocker card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("2. Uninstall Protection", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = if (isAccessibilityActive) "Protected" else "Unprotected",
                                fontSize = 12.sp,
                                color = if (isAccessibilityActive) Color(0xFF10B981) else Color(0xFFEF4444)
                            )
                        }
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                context.startActivity(intent)
                            },
                            enabled = !isAccessibilityActive
                        ) {
                            Text(if (isAccessibilityActive) "Active" else "Enable")
                        }
                    }
                }
            }

            // Developer Testing Bypass Control Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Testing Bypass (Developer Mode)", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = if (isBypassActive) "Bypass Active (Easy Uninstall)" else "Bypass Inactive (Strict Lock)",
                                fontSize = 12.sp,
                                color = if (isBypassActive) Color(0xFF10B981) else Color(0xFFEF4444)
                            )
                        }
                        Switch(
                            checked = isBypassActive,
                            onCheckedChange = { checked ->
                                viewModel.setBypassProtection(context, checked)
                            }
                        )
                    }
                }
            }

            // Blocklist Custom Management Header
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Blocklist Domains",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    if (!isPermanentLockActive) {
                        TextButton(
                            onClick = { showLockConfirmDialog = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF4444))
                        ) {
                            Text("🔒 Freeze List")
                        }
                    } else {
                        Text(
                            text = "Locked 🔒",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFEF4444)
                        )
                    }
                }
            }

            // Add custom domain UI (hidden if permanently locked)
            if (!isPermanentLockActive) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = domainInput,
                            onValueChange = { domainInput = it },
                            label = { Text("Add custom domain") },
                            placeholder = { Text("e.g. reddit.com") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val input = domainInput.trim()
                                if (input.isNotEmpty()) {
                                    val success = viewModel.addDomain(input)
                                    if (success) {
                                        domainInput = ""
                                        // Restart service to pick up changes immediately
                                        if (isVpnActive) {
                                            context.startService(Intent(context, DnsVpnService::class.java))
                                        }
                                    } else {
                                        Toast.makeText(context, "Invalid or duplicate domain", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier.height(56.dp)
                        ) {
                            Text("Add")
                        }
                    }
                }
            }

            // Display Blocked List - Initial
            if (initialDomains.isNotEmpty()) {
                item {
                    Text(
                        text = "Carved in Stone (Initial Asset List):",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                items(initialDomains.toList()) { domain ->
                    DomainItem(domain = domain, isHardcoded = true)
                }
            }

            // Display Blocked List - Custom Added
            if (customDomains.isNotEmpty()) {
                item {
                    Text(
                        text = "User Added Blocker List (No removals allowed):",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                items(customDomains.toList()) { domain ->
                    DomainItem(domain = domain, isHardcoded = false)
                }
            }

            // Footer Version info
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "NullRoute v${getAppVersion(context)}",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            }
        }
    }
}

@Composable
fun DomainItem(domain: String, isHardcoded: Boolean) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp)),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(domain, fontWeight = FontWeight.Medium)
                Text(
                    text = if (isHardcoded) "Initial File List" else "User Custom List",
                    fontSize = 10.sp,
                    color = if (isHardcoded) Color(0xFFEF4444) else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

fun getAppVersion(context: Context): String {
    return try {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        packageInfo.versionName ?: "1.0.0-test"
    } catch (e: Exception) {
        "1.0.0-test"
    }
}
