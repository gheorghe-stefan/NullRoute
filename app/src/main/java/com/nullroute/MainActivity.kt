package com.nullroute

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.HelpOutline
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nullroute.billing.BillingManager
import com.nullroute.data.BlockedDomain
import com.nullroute.data.SharedPreferencesBlocklistRepository
import com.nullroute.ui.HelpDialog
import com.nullroute.ui.MainViewModel
import com.nullroute.ui.MainViewModelFactory
import com.nullroute.ui.ProUpgradeDialog
import com.nullroute.utils.DomainNormalizer
import com.nullroute.vpn.DnsVpnService

class MainActivity : ComponentActivity() {

    private lateinit var billingManager: BillingManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.nullroute.vpn.DnsTelemetryTracker.init(applicationContext)

        billingManager = BillingManager(applicationContext, lifecycleScope)

        // Clear bypass protection preference in release/final version to enforce strict locking
        val isDebuggable = (applicationContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isDebuggable) {
            val prefs = getSharedPreferences("nullroute_prefs", Context.MODE_PRIVATE)
            if (prefs.contains("bypass_protection")) {
                prefs.edit().remove("bypass_protection").apply()
            }
        }

        // Check if we were launched due to a blocked settings/uninstall attempt
        val isBlockedAttempt = intent.getBooleanExtra("BLOCKED_ATTEMPT", false)

        setContent {
            NullRouteTheme {
                val repository = remember { SharedPreferencesBlocklistRepository(applicationContext) }
                val factory = remember { MainViewModelFactory(repository, billingManager) }
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

    override fun onDestroy() {
        super.onDestroy()
        if (::billingManager.isInitialized) {
            billingManager.endConnection()
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
    val blockedDomains by viewModel.blockedDomains.collectAsState()
    val telemetry by viewModel.telemetrySnapshot.collectAsState()
    val isPro by viewModel.isPro.collectAsState()
    val proPrice by viewModel.proPrice.collectAsState()

    var domainInput by remember { mutableStateOf("") }
    var showBlockedToast by remember { mutableStateOf(initialBlockedAttempt) }
    var pendingDomainToAdd by remember { mutableStateOf<String?>(null) }
    var showProDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }

    // Listen for billing event messages (purchases, errors, debug toggle)
    LaunchedEffect(Unit) {
        viewModel.billingMessage.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // Sync running VPN service whenever Pro status toggles
    LaunchedEffect(isPro) {
        if (isVpnActive) {
            context.startService(Intent(context, DnsVpnService::class.java))
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

    // Auto-prompt VPN permission on app launch if not active
    LaunchedEffect(Unit) {
        if (!viewModel.isVpnActive.value) {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                vpnLauncher.launch(prepareIntent)
            } else {
                context.startService(Intent(context, DnsVpnService::class.java))
                viewModel.refreshStates(context)
            }
        }
    }

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

    // Popup for adding domain: asks if entry is supposed to be removable or not
    if (pendingDomainToAdd != null) {
        AlertDialog(
            onDismissRequest = { pendingDomainToAdd = null },
            title = { Text("Domain Removability Option") },
            text = {
                Text(
                    "Is \"${pendingDomainToAdd}\" supposed to be removable later?\n\n" +
                    "• Clicking YES will add the domain to blocked list FOREVER (non-removable).\n" +
                    "• Clicking NO will add it as REMOVABLE (a delete icon will be shown)."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val domain = pendingDomainToAdd!!
                        pendingDomainToAdd = null
                        if (!viewModel.canUsePermanentLock()) {
                            Toast.makeText(context, "Permanent (Forever) mode requires NullRoute Pro", Toast.LENGTH_SHORT).show()
                            showProDialog = true
                        } else {
                            val success = viewModel.addDomain(domain, isRemovable = false)
                            if (success) {
                                domainInput = ""
                                if (isVpnActive) {
                                    context.startService(Intent(context, DnsVpnService::class.java))
                                }
                            } else {
                                Toast.makeText(context, "Failed to add domain (duplicate or invalid)", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) {
                    Text(if (isPro) "Yes (Forever)" else "Yes (Forever) 🔒")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        val domain = pendingDomainToAdd!!
                        pendingDomainToAdd = null
                        val success = viewModel.addDomain(domain, isRemovable = true)
                        if (success) {
                            domainInput = ""
                            if (isVpnActive) {
                                context.startService(Intent(context, DnsVpnService::class.java))
                            }
                        } else {
                            Toast.makeText(context, "Failed to add domain (duplicate or invalid)", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("No (Removable)")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "NullRoute",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        if (isPro) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFFF59E0B).copy(alpha = 0.2f),
                                border = BorderStroke(1.dp, Color(0xFFF59E0B))
                            ) {
                                Text(
                                    text = "PRO",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFFF59E0B),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (!isPro) {
                        FilledTonalButton(
                            onClick = { showProDialog = true },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                contentColor = MaterialTheme.colorScheme.primary
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FlashOn,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Unlock Pro", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    IconButton(onClick = { showHelpDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.HelpOutline,
                            contentDescription = "Help & Guide",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
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

            // Live Telemetry & Diagnostics Card
            item {
                DiagnosticsCard(
                    telemetry = telemetry,
                    onCopyReport = {
                        val report = viewModel.getExportTelemetryReport()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("NullRoute Telemetry Report", report)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Telemetry report copied to clipboard!", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // 1-Click Distraction Presets
            item {
                PresetPacksCard(
                    isPro = isPro,
                    onSelectPreset = { presetName, domains ->
                        if (!isPro) {
                            Toast.makeText(context, "1-Click Presets require NullRoute Pro", Toast.LENGTH_SHORT).show()
                            showProDialog = true
                        } else {
                            val added = viewModel.addPresetDomains(domains)
                            if (added > 0) {
                                Toast.makeText(context, "Added $added domains from $presetName pack!", Toast.LENGTH_SHORT).show()
                                if (isVpnActive) {
                                    context.startService(Intent(context, DnsVpnService::class.java))
                                }
                            } else {
                                Toast.makeText(context, "Preset domains already added", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onUpgradeClick = { showProDialog = true }
                )
            }

            // Blocklist Custom Management Header
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Blocked Domains",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isPro) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (isPro) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                        ) {
                            val countText = if (isPro) {
                                "${blockedDomains.size} / ∞"
                            } else if (blockedDomains.size > MainViewModel.FREE_DOMAIN_LIMIT) {
                                "${blockedDomains.size} / ${MainViewModel.FREE_DOMAIN_LIMIT} max"
                            } else {
                                "${blockedDomains.size} / ${MainViewModel.FREE_DOMAIN_LIMIT}"
                            }
                            Text(
                                text = countText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isPro) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (!isPro) {
                        TextButton(onClick = { showProDialog = true }) {
                            Text("Upgrade", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            // Add domain UI
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    OutlinedTextField(
                        value = domainInput,
                        onValueChange = { domainInput = it },
                        label = { Text("Add custom domain") },
                        placeholder = { Text("e.g. reddit.com") },
                        supportingText = {
                            Text("Domain only (e.g. reddit.com) • URLs are auto-cleaned", fontSize = 11.sp, color = Color.Gray)
                        },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val input = domainInput.trim()
                            val normalized = DomainNormalizer.normalize(input)
                            if (normalized == null) {
                                Toast.makeText(context, "Invalid domain format", Toast.LENGTH_SHORT).show()
                            } else if (viewModel.blockedDomains.value.any { it.domain.equals(normalized, ignoreCase = true) }) {
                                Toast.makeText(context, "Domain is already blocked", Toast.LENGTH_SHORT).show()
                            } else if (!viewModel.canAddDomain()) {
                                Toast.makeText(
                                    context,
                                    "Free tier limit reached (${MainViewModel.FREE_DOMAIN_LIMIT} domains). Upgrade to Pro for unlimited domains!",
                                    Toast.LENGTH_LONG
                                ).show()
                                showProDialog = true
                            } else {
                                pendingDomainToAdd = normalized
                            }
                        },
                        modifier = Modifier.padding(top = 8.dp).height(56.dp)
                    ) {
                        Text("Add")
                    }
                }
            }

            // Display Blocked List
            if (blockedDomains.isNotEmpty()) {
                items(blockedDomains) { domainItem ->
                    DomainItem(
                        domain = domainItem,
                        onRemove = if (domainItem.isRemovable) {
                            {
                                val success = viewModel.removeDomain(domainItem.domain)
                                if (success && isVpnActive) {
                                    context.startService(Intent(context, DnsVpnService::class.java))
                                }
                            }
                        } else null
                    )
                }
            } else {
                item {
                    Text(
                        text = "No blocked domains configured.",
                        fontSize = 14.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 8.dp)
                    )
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

    if (showProDialog) {
        val activity = context as? Activity
        ProUpgradeDialog(
            price = proPrice,
            isPro = isPro,
            onDismiss = { showProDialog = false },
            onUnlockClick = {
                if (activity != null) {
                    viewModel.launchPurchaseFlow(activity)
                }
            },
            onRestoreClick = {
                viewModel.restorePurchases()
            },
            onDebugToggleClick = {
                viewModel.debugTogglePro()
            }
        )
    }

    if (showHelpDialog) {
        HelpDialog(onDismiss = { showHelpDialog = false })
    }
}

@Composable
fun DomainItem(
    domain: BlockedDomain,
    onRemove: (() -> Unit)? = null
) {
    var isRevealed by rememberSaveable { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Box {
                    Text(
                        text = domain.domain,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = if (!isRevealed) {
                            Modifier.blur(10.dp)
                        } else {
                            Modifier
                        }
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                val badgeText = if (domain.isRemovable) "Removable" else "Permanent (Forever)"
                val badgeColor = if (domain.isRemovable) MaterialTheme.colorScheme.primary else Color(0xFFF59E0B)
                Text(
                    text = badgeText,
                    fontSize = 11.sp,
                    color = badgeColor
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (domain.isRemovable && onRemove != null) {
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Remove domain",
                            tint = Color(0xFFEF4444)
                        )
                    }
                }
                IconButton(
                    onClick = { isRevealed = !isRevealed },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (isRevealed) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (isRevealed) "Hide domain" else "Reveal domain",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
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

@Composable
fun DiagnosticsCard(
    telemetry: com.nullroute.vpn.TelemetrySnapshot,
    onCopyReport: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Analytics,
                        contentDescription = "Diagnostics",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "Telemetry & Diagnostics",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "Queries: ${telemetry.totalQueries} (All-Time: ${telemetry.allTimeTotalQueries}) | QPS: ${String.format(java.util.Locale.US, "%.1f", telemetry.currentQps)}",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand"
                    )
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = MaterialTheme.colorScheme.background)
                Spacer(modifier = Modifier.height(12.dp))

                // Stats Grid
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricItem(title = "Cache Hit Rate", value = "${String.format(java.util.Locale.US, "%.1f", telemetry.cacheHitRatioPct)}%")
                    MetricItem(title = "All-Time Hit Rate", value = "${String.format(java.util.Locale.US, "%.1f", telemetry.allTimeHitRatioPct)}%")
                    MetricItem(title = "Avg Latency", value = "${String.format(java.util.Locale.US, "%.1f", telemetry.avgLatencyMs)} ms")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricItem(title = "Session Queries", value = "${telemetry.totalQueries}")
                    MetricItem(title = "All-Time Queries", value = "${telemetry.allTimeTotalQueries}")
                    MetricItem(title = "Timeouts", value = "${telemetry.timeouts}")
                }

                if (telemetry.qTypeCounts.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Record Types: " + telemetry.qTypeCounts.entries.joinToString(", ") { "${it.key}: ${it.value}" },
                        fontSize = 11.sp,
                        color = Color.LightGray
                    )
                }

                if (telemetry.topDomains.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Top Domains: " + telemetry.topDomains.take(3).joinToString(", ") { "${it.first} (${it.second})" },
                        fontSize = 11.sp,
                        color = Color.LightGray
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onCopyReport,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy Diagnostic Report", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun MetricItem(title: String, value: String) {
    Column {
        Text(text = title, fontSize = 11.sp, color = Color.Gray)
        Text(text = value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun PresetPacksCard(
    isPro: Boolean,
    onSelectPreset: (String, List<String>) -> Unit,
    onUpgradeClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FlashOn,
                        contentDescription = null,
                        tint = if (isPro) MaterialTheme.colorScheme.primary else Color.Gray,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "1-Click Focus Presets",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (!isPro) {
                    Surface(
                        onClick = onUpgradeClick,
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "PRO",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Instantly block common high-distraction ecosystems:",
                fontSize = 12.sp,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PresetChip(
                    title = "Socials",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onSelectPreset(
                            "Socials",
                            listOf("instagram.com", "tiktok.com", "reddit.com", "x.com", "facebook.com")
                        )
                    }
                )
                PresetChip(
                    title = "Video Feeds",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onSelectPreset(
                            "Video Feeds",
                            listOf("youtube.com", "twitch.tv", "netflix.com")
                        )
                    }
                )
                PresetChip(
                    title = "Doomscroll",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onSelectPreset(
                            "Doomscroll",
                            listOf("news.ycombinator.com", "cnn.com", "bbc.com")
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun PresetChip(
    title: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
    ) {
        Text(
            text = title,
            fontSize = 11.sp,
            maxLines = 1,
            fontWeight = FontWeight.Medium
        )
    }
}

