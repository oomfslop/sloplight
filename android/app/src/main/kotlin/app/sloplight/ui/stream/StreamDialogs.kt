package app.sloplight.ui.stream

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.limelight.R
import com.limelight.utils.HelpLauncher

/**
 * The stream activity's own dialogs: the "connecting" one (a loading
 * indicator and the stage) and the error / ended ones. They are dialog
 * windows, as the ones they replace (utils.SpinnerDialog, utils.Dialog):
 * while one shows, the stream activity doesn't have the window focus.
 * The host view is in the activity only while one of them shows.
 *
 * Every method may be called from any thread.
 */
class StreamDialogs(private val activity: Activity) {
    private val main = Handler(Looper.getMainLooper())
    private val spinners = mutableStateListOf<Spinner>()
    private val errors = mutableStateListOf<ErrorDialog>()
    private var host: ComposeView? = null

    inner class Spinner internal constructor(val title: String, message: String, val finishOnCancel: Boolean) {
        internal var message by mutableStateOf(message)
        private var dismissed = false

        fun setMessage(message: String) {
            onMain { this.message = message }
        }

        fun dismiss() {
            onMain {
                dismissed = true
                spinners.remove(this)
                update()
            }
        }

        internal fun show() {
            if (dismissed || activity.isFinishing) return
            spinners.add(this)
            update()
        }

        internal fun cancel() {
            // Only cancellable when the activity ends with it
            dismissed = true
            spinners.remove(this)
            update()
            activity.finish()
        }
    }

    private class ErrorDialog(val title: String, val message: String, val endAfterDismiss: Boolean)

    /** finishOnCancel: Back cancels it and ends the activity; else it can't be cancelled */
    fun showSpinner(title: String, message: String, finishOnCancel: Boolean): Spinner {
        val spinner = Spinner(title, message, finishOnCancel)
        onMain { spinner.show() }
        return spinner
    }

    /** An error with OK and Help; either ends the activity if endAfterDismiss */
    fun showError(title: String, message: String, endAfterDismiss: Boolean) {
        onMain {
            if (activity.isFinishing) return@onMain
            errors.add(ErrorDialog(title, message, endAfterDismiss))
            update()
        }
    }

    /** All of them go, without their actions (the activity is stopping) */
    fun closeAll() {
        onMain {
            spinners.clear()
            errors.clear()
            update()
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun update() {
        val needed = spinners.isNotEmpty() || errors.isNotEmpty()
        if (needed && host == null) {
            val v = overlayView(activity) { Dialogs() }
            activity.contentRoot().addView(v, dialogHostParams())
            host = v
        } else if (!needed && host != null) {
            // Taking the host away dismisses its dialog windows
            activity.contentRoot().removeView(host)
            host = null
        }
    }

    private fun closeError(dialog: ErrorDialog, help: Boolean) {
        // After the click is done with (the dialog's window goes with the host)
        main.post {
            if (!errors.remove(dialog)) return@post
            update()
            if (dialog.endAfterDismiss) activity.finish()
            if (help) HelpLauncher.launchTroubleshooting(activity)
        }
    }

    @Composable
    private fun Dialogs() {
        spinners.lastOrNull()?.let { s -> key(s) { SpinnerDialog(s) } }
        errors.forEach { e -> key(e) { ErrorAlert(e) } }
    }

    @Composable
    private fun SpinnerDialog(s: Spinner) {
        Dialog(
            onDismissRequest = { if (s.finishOnCancel) main.post { s.cancel() } },
            properties = DialogProperties(dismissOnBackPress = s.finishOnCancel, dismissOnClickOutside = false),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.widthIn(min = 280.dp, max = 560.dp),
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text(s.title, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.size(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(40.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(
                            s.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ErrorAlert(e: ErrorDialog) {
        val ok = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(e.title) },
            text = {
                Text(e.message, modifier = Modifier.verticalScroll(rememberScrollState()))
            },
            dismissButton = {
                TextButton(onClick = { closeError(e, help = true) }) {
                    Text(stringResource(R.string.help))
                }
            },
            confirmButton = {
                TextButton(onClick = { closeError(e, help = false) }, modifier = Modifier.focusRequester(ok)) {
                    Text(stringResource(android.R.string.ok))
                }
                // OK has the focus, for a keyboard or a gamepad (as before)
                LaunchedEffect(Unit) { runCatching { ok.requestFocus() } }
            },
        )
    }
}
