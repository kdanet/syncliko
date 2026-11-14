package com.syncliko.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import android.os.Build
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncliko.android.BuildConfig
import com.syncliko.android.R
import com.syncliko.android.capture.CaptureEngine
import com.syncliko.android.sync.Controller
import com.syncliko.transport.SyncClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(controller: Controller, onBack: () -> Unit) {
    val width = rememberWidthClass()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { controller.capture.refresh() }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                contentPadding = PaddingValues(start = width.gutter, end = width.gutter, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
            ) {
                item { SectionHeader(stringResource(R.string.sec_auto)) }
                item { AutoSection(controller) }
                item { SectionHeader(stringResource(R.string.sec_sync)) }
                item { SyncSection(controller) }
                item { SectionHeader(stringResource(R.string.sec_appearance)) }
                item { AppearanceSection(controller) }
                item { SectionHeader(stringResource(R.string.sec_connection)) }
                item { ConnectionSection(controller) }
                item { SectionHeader(stringResource(R.string.sec_about)) }
                item { AboutSection() }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AutoSection(controller: Controller) {
    val enabled by controller.autoCapture.collectAsStateWithLifecycle()
    val status by controller.capture.status.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var explain by remember { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(status.logsGranted) }

    SectionCard {
        SwitchRow(stringResource(R.string.auto_switch), stringResource(R.string.auto_switch_sub), enabled, controller::setAutoCapture)
        Text(stringResource(R.string.toast_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Spacer(Modifier.size(12.dp))
    ModeCard(
        icon = Icons.Rounded.Accessibility,
        title = stringResource(R.string.mode_standard_title),
        body = stringResource(R.string.mode_standard_body),
        state = when {
            !status.accessibilityOn -> ModeState.OFF
            enabled && status.method == CaptureEngine.Method.STANDARD -> ModeState.ACTIVE
            else -> ModeState.READY
        },
    ) {
        if (status.accessibilityStalled) {
            Text(stringResource(R.string.a11y_stalled), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        if (!status.accessibilityOn) {
            Button(onClick = { explain = true }) { Text(stringResource(R.string.mode_standard_action)) }
        }
    }

    if (explain) {
        // Android's own warning about accessibility apps is frightening; say plainly what we do first,
        // and list the steps in the order they must happen (restricted settings first on sideloads).
        val sideloaded = Build.VERSION.SDK_INT >= 33
        AlertDialog(
            onDismissRequest = { explain = false },
            title = { Text(stringResource(R.string.a11y_explain_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.a11y_explain_body))
                    if (sideloaded) {
                        StepRow(done = false, text = "1. " + stringResource(R.string.a11y_step_restricted)) {
                            TextButton(onClick = {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                            }) { Text(stringResource(R.string.action_open)) }
                        }
                    }
                    StepRow(done = false, text = (if (sideloaded) "2. " else "") + stringResource(R.string.a11y_step_enable))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    explain = false
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text(stringResource(R.string.mode_standard_action)) }
            },
            dismissButton = { TextButton(onClick = { explain = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    Spacer(Modifier.size(12.dp))
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { advanced = !advanced }.padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.advanced_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!advanced) return

    val command = "adb shell pm grant ${context.packageName} android.permission.READ_LOGS && adb shell appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow"
    ModeCard(
        icon = Icons.Rounded.Terminal,
        title = stringResource(R.string.mode_pro_title),
        body = stringResource(R.string.mode_pro_body),
        state = when {
            !status.logsGranted -> ModeState.OFF
            status.proPending -> ModeState.PENDING
            enabled && status.method == CaptureEngine.Method.PRO -> ModeState.ACTIVE
            else -> ModeState.READY
        },
    ) {
        if (status.proPending) {
            Text(stringResource(R.string.mode_pro_pending), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = controller.capture::requestLogAccess) { Text(stringResource(R.string.mode_pro_request)) }
        }
        // Step 1: a way to show the focus window (overlay permission, or the Accessibility service).
        StepRow(
            done = status.overlayGranted || status.accessibilityOn,
            text = stringResource(
                when {
                    status.overlayGranted -> R.string.mode_pro_step_overlay_done
                    status.accessibilityOn -> R.string.mode_pro_step_overlay_a11y
                    else -> R.string.mode_pro_step_overlay
                },
            ),
        ) {
            if (!status.overlayGranted && !status.accessibilityOn) {
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                }) { Text(stringResource(R.string.action_allow)) }
            }
        }
        // Step 2: READ_LOGS over ADB.
        StepRow(done = status.logsGranted, text = stringResource(R.string.mode_pro_step_adb))
        if (!status.logsGranted) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        command.replace(" && ", "\n"),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            FilledTonalButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adb", command))
                Toast.makeText(context, R.string.mode_pro_copied, Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.mode_pro_copy))
            }
        }
        Text(stringResource(R.string.mode_pro_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private enum class ModeState { ACTIVE, PENDING, READY, OFF }

@Composable
private fun ModeCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    state: ModeState,
    content: @Composable () -> Unit,
) {
    val active = state == ModeState.ACTIVE
    SectionCard(color = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceContainerLow) {
        IconRow(
            icon = icon,
            title = title,
            iconBackground = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
            iconTint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            StateChip(state)
        }
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun StateChip(state: ModeState) {
    val (text, bg, fg) = when (state) {
        ModeState.ACTIVE -> Triple(R.string.mode_state_active, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
        ModeState.PENDING -> Triple(R.string.mode_state_pending, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        ModeState.READY -> Triple(R.string.mode_state_ready, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        ModeState.OFF -> Triple(R.string.mode_state_off, MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(color = bg, shape = MaterialTheme.shapes.extraLarge) {
        Text(stringResource(text), color = fg, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun StepRow(done: Boolean, text: String, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(
            if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            null,
            Modifier.size(20.dp),
            tint = if (done) Syncliko.colors.online else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun SyncSection(controller: Controller) {
    val paused by controller.paused.collectAsStateWithLifecycle()
    var sendOnOpen by remember { mutableStateOf(controller.store.sendOnOpen) }
    SectionCard {
        SwitchRow(stringResource(R.string.sync_enabled), stringResource(R.string.sync_enabled_sub), !paused) { controller.setPaused(!it) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        SwitchRow(stringResource(R.string.send_on_open), stringResource(R.string.send_on_open_sub), sendOnOpen) {
            sendOnOpen = it
            controller.store.sendOnOpen = it
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        NotificationRow(controller)
    }
}

/**
 * Android requires a visible notification for a foreground service, but the user may turn its channel
 * off: the service keeps running (it's then listed only under "Active apps" in Quick Settings).
 */
@Composable
private fun NotificationRow(controller: Controller) {
    val context = LocalContext.current
    val hosted by controller.hostedByAccessibility.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.notif_setting), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(if (hosted) R.string.notif_setting_hosted else R.string.notif_setting_sub),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (hosted) return@Row
        Spacer(Modifier.width(12.dp))
        TextButton(onClick = {
            val intent = android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, com.syncliko.android.sync.SyncService.CHANNEL_ID)
            runCatching { context.startActivity(intent) }
                .onFailure {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                }
        }) { Text(stringResource(R.string.notif_setting_action)) }
    }
}

@Composable
private fun AppearanceSection(controller: Controller) {
    val dynamic by controller.dynamicColor.collectAsStateWithLifecycle()
    SectionCard {
        SwitchRow(stringResource(R.string.dynamic_color), stringResource(R.string.dynamic_color_sub), dynamic, controller::setDynamicColor)
    }
}

@Composable
private fun ConnectionSection(controller: Controller) {
    val mac by controller.mac.collectAsStateWithLifecycle()
    val state by controller.connection.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    val name = mac?.name ?: "Mac"

    SectionCard {
        InfoRow(stringResource(R.string.conn_mac), name)
        InfoRow(
            stringResource(R.string.conn_status),
            (state as? SyncClient.State.Connected)?.let { "${stringResource(R.string.status_connected)} · ${it.endpoint.host}" }
                ?: stringResource(R.string.status_offline, name),
        )
        InfoRow(stringResource(R.string.conn_addresses), mac?.endpoints?.joinToString("\n") { "${it.host}:${it.port}" } ?: "—", monospace = true)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        InfoRow(stringResource(R.string.conn_phone), controller.store.deviceName)
        InfoRow(stringResource(R.string.conn_fingerprint), controller.phoneFingerprint, monospace = true)
        OutlinedButton(onClick = controller::reconnectNow) { Text(stringResource(R.string.conn_reconnect)) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        TextButton(
            onClick = { confirm = true },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text(stringResource(R.string.unpair, name), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.unpair_title, name)) },
            text = { Text(stringResource(R.string.unpair_body)) },
            confirmButton = {
                TextButton(onClick = { confirm = false; controller.unpair() }) {
                    Text(stringResource(R.string.action_unpair), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String, monospace: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.6f),
        )
    }
}

@Composable
private fun AboutSection() {
    SectionCard {
        Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.about_privacy), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
