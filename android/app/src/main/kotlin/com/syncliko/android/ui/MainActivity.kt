package com.syncliko.android.ui

import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncliko.android.R
import com.syncliko.android.SynclikoApp
import com.syncliko.android.sync.Controller
import com.syncliko.android.sync.SendResult
import com.syncliko.android.sync.Watchdog
import com.syncliko.protocol.toHex
import kotlinx.coroutines.launch

/** In-app feedback: a snackbar plus haptics (system toasts are for the invisible activities only). */
fun interface Feedback {
    fun show(result: SendResult)
}

val LocalFeedback = staticCompositionLocalOf<Feedback> { Feedback { } }
val LocalMessage = staticCompositionLocalOf<(String) -> Unit> { {} }

class MainActivity : ComponentActivity() {
    private val controller get() = (application as SynclikoApp).controller

    private enum class Screen { HOME, SETTINGS }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A recreated activity (rotation, theme change) must not re-run a pairing link.
        if (savedInstanceState == null) handleDeepLink(intent)
        setContent {
            val dynamicColor by controller.dynamicColor.collectAsStateWithLifecycle()
            SynclikoTheme(dynamicColor = dynamicColor) {
                Root(controller)
            }
        }
    }

    @Composable
    private fun Root(controller: Controller) {
        val mac by controller.mac.collectAsStateWithLifecycle()
        var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
        BackHandler(enabled = mac != null && screen == Screen.SETTINGS) { screen = Screen.HOME }

        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val view = LocalView.current
        val macName = mac?.name ?: "Mac"
        val message: (String) -> Unit = { text ->
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(text)
            }
        }
        val feedback = Feedback { result ->
            if (result == SendResult.SENT) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            message(sendResultText(context, result, macName))
        }

        CompositionLocalProvider(LocalFeedback provides feedback, LocalMessage provides message) {
            Box(Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = if (mac == null) null else screen,
                    transitionSpec = {
                        when {
                            initialState == Screen.HOME && targetState == Screen.SETTINGS ->
                                (slideInHorizontally { it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 8 } + fadeOut())
                            initialState == Screen.SETTINGS && targetState == Screen.HOME ->
                                (slideInHorizontally { -it / 8 } + fadeIn()) togetherWith (slideOutHorizontally { it / 4 } + fadeOut())
                            else -> (fadeIn() + scaleIn(initialScale = 0.97f)) togetherWith fadeOut()
                        }
                    },
                    label = "root",
                ) { target ->
                    when (target) {
                        null -> OnboardingScreen(controller)
                        Screen.HOME -> HomeScreen(controller, onOpenSettings = { screen = Screen.SETTINGS })
                        Screen.SETTINGS -> SettingsScreen(controller, onBack = { screen = Screen.HOME })
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
            }
            InviteDialog(controller)
        }
    }

    /** Pairing links from outside the app are confirmed explicitly; they can't silently re-pair the phone. */
    @Composable
    private fun InviteDialog(controller: Controller) {
        val invite by controller.pendingInvite.collectAsStateWithLifecycle()
        val current by controller.mac.collectAsStateWithLifecycle()
        val pending = invite ?: return
        AlertDialog(
            onDismissRequest = controller::dismissInvite,
            title = { Text(stringResource(R.string.invite_title, pending.deviceName)) },
            text = {
                Column {
                    Text(stringResource(R.string.invite_body))
                    Text(
                        stringResource(R.string.invite_fingerprint, pending.fingerprint.toHex().take(16).chunked(4).joinToString(" ")),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    current?.let {
                        Text(
                            stringResource(R.string.invite_replace, it.name),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = controller::confirmInvite) { Text(stringResource(R.string.action_pair)) } },
            dismissButton = { TextButton(onClick = controller::dismissInvite) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        controller.ensureRunning()
        controller.reconnectNow()
        controller.onUserPresent()
        controller.capture.refresh()
        controller.capture.onAppForeground()
        if (controller.mac.value != null) Watchdog.schedule(this)
    }

    /** Opening the app counts as "I want this on the Mac": send the clipboard if it's new. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && controller.mac.value != null && controller.store.sendOnOpen) {
            controller.sendCurrentClipboard(force = false)
        }
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "syncliko" && data.host == "pair") {
            controller.offerInvite(data.toString())
            setIntent(Intent(this, MainActivity::class.java))
        }
    }
}
