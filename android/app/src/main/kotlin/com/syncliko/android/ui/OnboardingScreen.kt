package com.syncliko.android.ui

import android.content.ClipboardManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.syncliko.android.core.LocalNetwork
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.syncliko.android.R
import com.syncliko.android.sync.Controller
import com.syncliko.android.sync.PairingState

@Composable
fun OnboardingScreen(controller: Controller) {
    val pairing by controller.pairing.collectAsStateWithLifecycle()
    val context = LocalContext.current

    fun scan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
        runCatching {
            GmsBarcodeScanning.getClient(context, options).startScan()
                .addOnSuccessListener { code -> code.rawValue?.let(controller::pair) }
                .addOnFailureListener { e ->
                    android.util.Log.w("Syncliko", "QR scanner failed", e)
                    controller.scannerFailed(e.message ?: e.javaClass.simpleName)
                }
        }.onFailure { e ->
            android.util.Log.w("Syncliko", "QR scanner unavailable", e)
            controller.scannerFailed(e.message ?: e.javaClass.simpleName)
        }
    }

    // Android 17: ask for local network access before pairing; continue either way (Tailscale works without it).
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val localNetworkRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        afterPermission?.invoke()
        afterPermission = null
    }
    fun withLocalNetwork(action: () -> Unit) {
        if (LocalNetwork.isGranted(context)) {
            action()
        } else {
            afterPermission = action
            localNetworkRequest.launch(LocalNetwork.PERMISSION)
        }
    }

    fun pasteLink() {
        val text = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        controller.pair(text.orEmpty())
    }

    val configuration = LocalConfiguration.current
    // Side-by-side when there is room horizontally (unfolded) or little room vertically (landscape).
    val twoColumns = configuration.screenWidthDp >= 720 || (configuration.screenWidthDp >= 600 && configuration.screenHeightDp < 520)

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = pairing,
            contentKey = { it::class },
            transitionSpec = { (fadeIn() + slideInVertically { it / 16 }) togetherWith fadeOut() },
            label = "pairing",
        ) { state ->
            Box(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                when (state) {
                    PairingState.Idle, is PairingState.Done -> Welcome(twoColumns, onScan = { withLocalNetwork(::scan) }, onPaste = { withLocalNetwork(::pasteLink) })
                    PairingState.Connecting -> Progress(stringResource(R.string.pair_connecting), null, onCancel = controller::cancelPairing)
                    is PairingState.AwaitingConfirmation -> Progress(
                        stringResource(R.string.pair_confirm_title),
                        stringResource(R.string.pair_confirm_body, state.macName),
                        pulse = true,
                        code = state.code,
                        onCancel = controller::cancelPairing,
                    )
                    is PairingState.Failed -> Failure(state.reason, onRetry = controller::resetPairing)
                }
            }
        }
    }
}

@Composable
private fun Hero(icon: ImageVector, pulse: Boolean = false, size: Int = 112) {
    // Only animate while waiting; a static hero must not keep the screen redrawing.
    val scale = if (pulse) {
        rememberInfiniteTransition(label = "hero").animateFloat(1f, 1.08f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "scale").value
    } else {
        1f
    }
    Box(
        Modifier.size(size.dp).scale(scale).background(Syncliko.colors.heroGradient, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Syncliko.colors.onHero, modifier = Modifier.size((size * 0.46f).dp))
    }
}

@Composable
private fun Welcome(twoColumns: Boolean, onScan: () -> Unit, onPaste: () -> Unit) {
    if (twoColumns) {
        Row(
            Modifier.widthIn(max = 960.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Intro() }
            Column(Modifier.weight(1f)) { Actions(onScan, onPaste) }
        }
    } else {
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Intro()
            Spacer(Modifier.height(36.dp))
            Actions(onScan, onPaste)
        }
    }
}

@Composable
private fun Intro() {
    Hero(Icons.Rounded.ContentPaste)
    Spacer(Modifier.height(28.dp))
    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(10.dp))
    Text(
        stringResource(R.string.onb_tagline),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Actions(onScan: () -> Unit, onPaste: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Step(1, stringResource(R.string.onb_step1))
                Step(2, stringResource(R.string.onb_step2))
                Step(3, stringResource(R.string.onb_step3))
            }
        }
        Spacer(Modifier.height(28.dp))
        Button(onClick = onScan, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Rounded.QrCodeScanner, null)
            Spacer(Modifier.size(10.dp))
            Text(stringResource(R.string.onb_scan), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onPaste) { Text(stringResource(R.string.onb_paste)) }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Progress(title: String, subtitle: String?, pulse: Boolean = false, code: String? = null, onCancel: () -> Unit) {
    Column(Modifier.widthIn(max = 440.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Hero(Icons.Rounded.LaptopMac, pulse = pulse)
        Spacer(Modifier.height(32.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        subtitle?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        }
        if (code != null) {
            Spacer(Modifier.height(20.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
                Text(
                    code,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 4.sp,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp),
                )
            }
        } else {
            Spacer(Modifier.height(28.dp))
            CircularProgressIndicator()
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
    }
}

@Composable
private fun Failure(reason: String, onRetry: () -> Unit) {
    Column(Modifier.widthIn(max = 440.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.errorContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.pair_failed_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(reason, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(28.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.pair_try_again)) }
    }
}
