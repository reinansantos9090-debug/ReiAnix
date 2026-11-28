package com.reianix.reianix_local.ui.privacy

import com.reianix.reianix_local.R

import androidx.compose.ui.res.stringResource

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.reianix.reianix_local.data.tracemoe.ReiAnixTraceMoeConsent

/** One disclosure for both manual identification and the privacy setting. */
@Composable
internal fun TraceMoeConsentDialog(
    onAuthorized: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var savingFailed by remember { mutableStateOf(false) }
    var authorized by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.more_trace_consent_title)) },
        text = {
            Text(
                stringResource(R.string.more_trace_consent_body) +
                    if (savingFailed) "\n\n" + stringResource(R.string.tail_trace_save_failed) else "",
                color = if (savingFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(
                enabled = !authorized,
                onClick = {
                    if (!authorized) {
                        if (ReiAnixTraceMoeConsent.setAllowed(context, true)) {
                            authorized = true
                            onAuthorized()
                        } else {
                            savingFailed = true
                        }
                    }
                },
            ) { Text(stringResource(R.string.more_authorize)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.more_deny_authorize)) } },
    )
}
