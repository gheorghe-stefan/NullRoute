package com.nullroute.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(24.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.HelpOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Guide & Tips",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "How NullRoute protects your focus",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = MaterialTheme.colorScheme.background)
                Spacer(modifier = Modifier.height(16.dp))

                // Scrollable Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    HelpSection(
                        icon = Icons.Default.Info,
                        title = "1. How to Add Websites",
                        content = "• What to enter: Type the root domain, e.g. reddit.com, instagram.com, or tiktok.com.\n\n" +
                                "• URLs auto-cleaned: If you paste a full link (like https://www.tiktok.com/@user), NullRoute automatically extracts the root domain.\n\n" +
                                "• Subdomains covered: Blocking youtube.com automatically blocks m.youtube.com and subdomains.\n\n" +
                                "• System-wide: NullRoute blocks the entire domain across all web browsers and apps."
                    )

                    HelpSection(
                        icon = Icons.Default.Lock,
                        title = "2. Removable vs. Permanent (Forever)",
                        content = "• Removable: Displays a red trash icon. You can remove it anytime.\n\n" +
                                "• Permanent (Forever — Pro): Cannot be deleted from within the app. Designed for strict self-control to beat impulsive moments of weak willpower."
                    )

                    HelpSection(
                        icon = Icons.Default.Security,
                        title = "3. How Protection Works",
                        content = "• System Blocker (VPN): Operates an on-device local DNS loopback resolver. Blocked domains resolve locally to 0.0.0.0. No internet traffic ever leaves your device through external proxy servers (100% private).\n\n" +
                                "• Uninstall Protection: Intercepts attempts to open App Settings to stop self-sabotage during active sessions."
                    )

                    HelpSection(
                        icon = Icons.Default.Warning,
                        title = "4. Emergency Removal (Safe Mode)",
                        content = "If you ever need to uninstall the app during a permanent lock or emergency:\n" +
                                "1. Restart your phone into Safe Mode (Hold Power → long-press Restart or Power off → tap Safe mode).\n" +
                                "2. In Safe Mode, third-party accessibility locks are paused by Android, letting you uninstall normally in Settings."
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Got it", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun HelpSection(
    icon: ImageVector,
    title: String,
    content: String
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = content,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                lineHeight = 18.sp
            )
        }
    }
}
