package com.syncliko.android.ui

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon as AndroidIcon
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.ViewModule
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncliko.android.R
import com.syncliko.android.capture.CaptureEngine
import com.syncliko.android.capture.ClipboardTileService
import com.syncliko.android.core.LocalNetwork
import com.syncliko.android.core.OemGuidance
import com.syncliko.android.sync.ConnectionProblem
import com.syncliko.android.sync.Controller
import com.syncliko.android.sync.HistoryItem
import com.syncliko.android.sync.SendResult
import com.syncliko.protocol.Endpoint
import com.syncliko.transport.SyncClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(controller: Controller, onOpenSettings: () -> Unit) {
    val history by controller.history.collectAsStateWithLifecycle()
    val width = rememberWidthClass()
    val feedback = LocalFeedback.current
    val message = LocalMessage.current
    val copiedText = stringResource(R.string.history_copied)
    val onCopyItem: (HistoryItem) -> Unit = { item ->
        controller.restore(item) { result ->
            if (result == SendResult.SENT || result == SendResult.QUEUED) message(copiedText) else feedback.show(result)
        }
    }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val topBar = @Composable {
        TopAppBar(
            title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge) },
            actions = { SettingsButton(onOpenSettings) },
            scrollBehavior = scroll,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
        )
    }

    if (width == WidthClass.COMPACT) {
        // Phone / cover screen: status first, then history, then the rarely-needed cards.
        Scaffold(modifier = Modifier.nestedScroll(scroll.nestedScrollConnection), containerColor = MaterialTheme.colorScheme.surface, topBar = topBar) { padding ->
            LazyColumn(
                contentPadding = PaddingValues(start = width.gutter, end = width.gutter, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 88.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "problem") { ProblemCard(controller) }
                item(key = "status") { StatusCard(controller) }
                item(key = "auto") { AutoSendCard(controller, onOpenSettings) }
                item(key = "setup") { SetupCard(controller) }
                historyItems(history, controller, onCopyItem)
                item(key = "manual") { ManualSendCard() }
            }
        }
    } else {
        // Unfolded / tablet: status and controls on the left, history on the right.
        Scaffold(containerColor = MaterialTheme.colorScheme.surface, topBar = topBar) { padding ->
            Row(
                Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).padding(horizontal = width.gutter),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f).widthIn(max = 520.dp).windowInsetsPadding(WindowInsets.navigationBars),
                ) {
                    item(key = "problem") { ProblemCard(controller) }
                    item(key = "status") { StatusCard(controller) }
                    item(key = "auto") { AutoSendCard(controller, onOpenSettings) }
                    item(key = "setup") { SetupCard(controller) }
                    item(key = "manual") { ManualSendCard() }
                }
                LazyColumn(
                    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(if (width == WidthClass.EXPANDED) 1.15f else 1f).fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars),
                ) {
                    historyItems(history, controller, onCopyItem)
                }
            }
        }
    }
}

@Composable
private fun SettingsButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.Rounded.Settings, stringResource(R.string.action_settings)) }
}

private fun LazyListScope.historyItems(history: List<HistoryItem>, controller: Controller, onCopy: (HistoryItem) -> Unit) {
    item(key = "historyHeader") {
        SectionHeader(stringResource(R.string.history_title)) {
            if (history.isNotEmpty()) {
                TextButton(onClick = controller::clearHistory) { Text(stringResource(R.string.history_clear)) }
            }
        }
    }
    if (history.isEmpty()) {
        item(key = "historyEmpty") { EmptyHistory() }
    } else {
        items(history, key = { it.key }) { item ->
            HistoryRow(item, onCopy = { onCopy(item) }, onDelete = { controller.deleteHistoryItem(item) }, modifier = Modifier.animateItem())
        }
    }
}

@Composable
private fun ProblemCard(controller: Controller) {
    val problem by controller.problem.collectAsStateWithLifecycle()
    val mac by controller.mac.collectAsStateWithLifecycle()
    AnimatedVisibility(problem != ConnectionProblem.NONE) {
        val name = mac?.name ?: "Mac"
        SectionCard(color = MaterialTheme.colorScheme.errorContainer) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.LinkOff, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val forgot = problem == ConnectionProblem.MAC_FORGOT_PHONE
                    Text(
                        stringResource(if (forgot) R.string.problem_forgot_title else R.string.problem_identity_title, name),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        stringResource(if (forgot) R.string.problem_forgot_body else R.string.problem_identity_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            Button(
                onClick = controller::unpair,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                modifier = Modifier.align(Alignment.End),
            ) { Text(stringResource(R.string.action_pair_again)) }
        }
    }
}

@Composable
private fun StatusCard(controller: Controller) {
    val mac by controller.mac.collectAsStateWithLifecycle()
    val state by controller.connection.collectAsStateWithLifecycle()
    val paused by controller.paused.collectAsStateWithLifecycle()
    val feedback = LocalFeedback.current
    val extras = Syncliko.colors
    val macName = mac?.name ?: "Mac"
    val connected = state is SyncClient.State.Connected && !paused

    val title = when {
        paused -> stringResource(R.string.status_paused)
        state is SyncClient.State.Connected -> stringResource(R.string.status_connected)
        state is SyncClient.State.Waiting -> stringResource(R.string.status_offline, macName)
        else -> stringResource(R.string.status_connecting)
    }
    val subtitle = when {
        paused -> stringResource(R.string.status_paused_body)
        state is SyncClient.State.Connected -> {
            val c = state as SyncClient.State.Connected
            stringResource(if (c.endpoint.kind == Endpoint.Kind.TAILSCALE) R.string.status_via_tailscale else R.string.status_via_wifi, c.peer.deviceName)
        }
        state is SyncClient.State.Waiting -> stringResource(R.string.status_offline_hint)
        else -> stringResource(R.string.status_looking_for, macName)
    }

    Surface(shape = MaterialTheme.shapes.extraLarge, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .then(if (connected) Modifier.background(extras.heroGradient) else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh))
                .padding(24.dp)
                .animateContentSize(),
        ) {
            val onColor = if (connected) extras.onHero else MaterialTheme.colorScheme.onSurface
            val onColorVariant = if (connected) extras.onHero.copy(alpha = 0.82f) else MaterialTheme.colorScheme.onSurfaceVariant
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(
                        color = when {
                            connected -> extras.onHero
                            paused -> MaterialTheme.colorScheme.outline
                            else -> extras.warning
                        },
                        pulsing = !connected && !paused,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(title, color = onColor, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(6.dp))
                Text(subtitle, color = onColorVariant, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(20.dp))
                when {
                    paused -> FilledTonalButton(onClick = { controller.setPaused(false) }) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_resume))
                    }
                    state is SyncClient.State.Waiting -> FilledTonalButton(onClick = controller::reconnectNow) {
                        Icon(Icons.Rounded.RestartAlt, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.conn_reconnect))
                    }
                    else -> FilledTonalButton(
                        onClick = { controller.sendCurrentClipboard(force = true) { feedback.show(it) } },
                        colors = if (connected) {
                            ButtonDefaults.filledTonalButtonColors(containerColor = extras.onHero.copy(alpha = 0.18f), contentColor = extras.onHero)
                        } else {
                            ButtonDefaults.filledTonalButtonColors()
                        },
                    ) {
                        Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_send_clipboard))
                    }
                }
            }
        }
    }
}

fun sendResultText(context: Context, result: SendResult, macName: String): String = when (result) {
    SendResult.SENT -> context.getString(R.string.send_sent, macName)
    SendResult.QUEUED -> context.getString(R.string.send_queued, macName)
    SendResult.NOTHING -> context.getString(R.string.send_nothing)
    SendResult.DUPLICATE -> context.getString(R.string.send_duplicate, macName)
    SendResult.PAUSED -> context.getString(R.string.send_paused)
    SendResult.TOO_LARGE -> context.getString(R.string.send_too_large)
}

/** For the invisible activities (share, selection menu, tile), which have no UI to host a snackbar. */
fun toast(context: Context, result: SendResult, macName: String) {
    Toast.makeText(context, sendResultText(context, result, macName), Toast.LENGTH_SHORT).show()
}

@Composable
private fun AutoSendCard(controller: Controller, onOpenSettings: () -> Unit) {
    val enabled by controller.autoCapture.collectAsStateWithLifecycle()
    val status by controller.capture.status.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { controller.capture.refresh() }
    val proUsable = status.method == CaptureEngine.Method.PRO && !status.proPending
    val active = enabled && (status.accessibilityOn || proUsable)

    SectionCard {
        IconRow(
            icon = Icons.Rounded.AutoAwesome,
            title = stringResource(R.string.auto_title),
            subtitle = stringResource(
                when {
                    !enabled -> R.string.auto_state_off
                    proUsable -> R.string.auto_state_pro
                    status.accessibilityOn -> R.string.auto_state_standard
                    status.proPending -> R.string.auto_state_pro_pending
                    else -> R.string.auto_state_needs_setup
                },
            ),
            iconBackground = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
            iconTint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            onClick = onOpenSettings,
        ) {
            when {
                !enabled -> Switch(checked = false, onCheckedChange = { controller.setAutoCapture(true) })
                active -> StatusDot(Syncliko.colors.online, pulsing = false)
            }
        }
        when {
            !enabled -> Text(stringResource(R.string.auto_body_off), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            !active -> {
                Text(stringResource(R.string.auto_body_setup), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onOpenSettings) { Text(stringResource(R.string.action_finish_setup)) }
            }
        }
    }
}

/** Things that keep the background connection alive. Hidden once everything is set. */
@Composable
private fun SetupCard(controller: Controller) {
    val context = LocalContext.current
    val state by controller.connection.collectAsStateWithLifecycle()
    var notificationsGranted by remember { mutableStateOf(true) }
    var batteryUnrestricted by remember { mutableStateOf(true) }
    var backgroundRestricted by remember { mutableStateOf(false) }
    var localNetwork by remember { mutableStateOf(true) }
    var autostartDone by remember { mutableStateOf(controller.store.autostartAcknowledged) }
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    LaunchedEffect(refresh) {
        notificationsGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        batteryUnrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        backgroundRestricted = OemGuidance.isBackgroundRestricted(context)
        localNetwork = LocalNetwork.isGranted(context)
    }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsGranted = it }
    val requestLocalNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        localNetwork = granted
        if (granted) controller.reconnectNow()
    }
    // Connected over the LAN already works; don't nag.
    val needsLocalNetwork = !localNetwork && state !is SyncClient.State.Connected
    val needsAutostart = OemGuidance.hasAutostartScreen && !autostartDone

    AnimatedVisibility(!notificationsGranted || !batteryUnrestricted || backgroundRestricted || needsLocalNetwork || needsAutostart) {
        SectionCard(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleMedium)
            val iconBg = MaterialTheme.colorScheme.surfaceContainerLowest
            val iconTint = Syncliko.colors.warning
            if (needsLocalNetwork) {
                IconRow(Icons.Rounded.Wifi, stringResource(R.string.setup_local_network), stringResource(R.string.setup_local_network_sub), iconTint, iconBg,
                    onClick = { requestLocalNetwork.launch(LocalNetwork.PERMISSION) })
            }
            if (!notificationsGranted) {
                IconRow(Icons.Rounded.Notifications, stringResource(R.string.setup_notifications), stringResource(R.string.setup_notifications_sub), iconTint, iconBg,
                    onClick = { if (Build.VERSION.SDK_INT >= 33) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) })
            }
            if (backgroundRestricted) {
                IconRow(Icons.Rounded.BatteryChargingFull, stringResource(R.string.setup_restricted), stringResource(R.string.setup_restricted_sub), iconTint, iconBg,
                    onClick = { context.startActivity(OemGuidance.appDetails(context)) })
            } else if (!batteryUnrestricted) {
                val samsung = OemGuidance.vendor == OemGuidance.Vendor.SAMSUNG
                IconRow(
                    Icons.Rounded.BatteryChargingFull,
                    stringResource(R.string.setup_battery),
                    stringResource(if (samsung) R.string.setup_battery_sub_samsung else R.string.setup_battery_sub),
                    iconTint,
                    iconBg,
                    onClick = {
                        @Suppress("BatteryLife")
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                        runCatching { context.startActivity(intent) }.onFailure { context.startActivity(OemGuidance.appDetails(context)) }
                    },
                )
            }
            if (needsAutostart) {
                IconRow(Icons.Rounded.RestartAlt, stringResource(R.string.setup_autostart), stringResource(R.string.setup_autostart_sub, Build.MANUFACTURER.replaceFirstChar { it.uppercase() }), iconTint, iconBg,
                    onClick = {
                        OemGuidance.openAutostart(context)
                        controller.store.autostartAcknowledged = true
                        autostartDone = true
                    })
            }
        }
    }
}

@Composable
private fun ManualSendCard() {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    SectionCard(contentPadding = 16.dp, modifier = Modifier.animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.how_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.ExpandMore, null, Modifier.rotate(rotation), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            IconRow(Icons.Rounded.TextFields, stringResource(R.string.how_select), stringResource(R.string.how_select_sub))
            IconRow(Icons.Rounded.Share, stringResource(R.string.how_share), stringResource(R.string.how_share_sub))
            IconRow(Icons.Rounded.ViewModule, stringResource(R.string.how_tile), stringResource(R.string.how_tile_sub)) {
                if (Build.VERSION.SDK_INT >= 33) {
                    TextButton(onClick = { requestTile(context) }) { Text(stringResource(R.string.how_add_tile)) }
                }
            }
        }
    }
}

private fun requestTile(context: Context) {
    if (Build.VERSION.SDK_INT < 33) return
    context.getSystemService(StatusBarManager::class.java).requestAddTileService(
        ComponentName(context, ClipboardTileService::class.java),
        context.getString(R.string.send_to_mac),
        AndroidIcon.createWithResource(context, R.drawable.ic_tile),
        ContextCompat.getMainExecutor(context),
    ) { }
}
