package com.reianix.reianix_local.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.reianix.reianix_local.data.releases.ReiAnixReleaseMonitor
import com.reianix.reianix_local.ui.theme.ReiAnixTokens

/** The system prompt is requested from the welcome action, never at app startup. */
@Composable
fun ReiAnixReleaseAlertsSetting() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(ReiAnixReleaseMonitor.active(context)) }
    var permissionDenied by remember { mutableStateOf(false) }

    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ReiAnixReleaseMonitor.reconcilePermission(context)
                enabled = ReiAnixReleaseMonitor.active(context)
                permissionDenied = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        ReiAnixReleaseMonitor.recordPermissionDecision(context, granted)
        enabled = ReiAnixReleaseMonitor.active(context)
        permissionDenied = !enabled
    }

    fun changeEnabled(newValue: Boolean) {
        when {
            !newValue -> {
                ReiAnixReleaseMonitor.setEnabled(context, false)
                enabled = false
                permissionDenied = false
            }
            ReiAnixReleaseMonitor.notificationsAllowed(context) -> {
                ReiAnixReleaseMonitor.setEnabled(context, true)
                enabled = true
                permissionDenied = false
            }
            Build.VERSION.SDK_INT >= 33 -> {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> {
                ReiAnixReleaseMonitor.recordPermissionDecision(context, false)
                enabled = false
                permissionDenied = true
            }
        }
    }

    val description = "Somente animes da sua biblioteca. Consulta leve com internet, " +
        "aproximadamente a cada 4 horas; horários não são garantidos."
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ReiAnixTokens.Dimensions.settingsRowMinHeight)
            .settingsClickable(role = Role.Switch, onClick = { changeEnabled(!enabled) })
            .semantics(mergeDescendants = true) {
                contentDescription = "Avisar sobre novos episódios. " + description
                stateDescription = if (enabled) "Ativado" else "Desativado"
            }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsLeadingIcon("releases.notifications")
        Column(modifier = Modifier.weight(1f)) {
            Text("Avisar sobre novos episódios", style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (permissionDenied || (ReiAnixReleaseMonitor.hasSavedPreference(context) &&
                    !ReiAnixReleaseMonitor.notificationsAllowed(context))) {
                Text(
                    "Autorize as notificações nas configurações do Android para receber avisos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Switch(
            checked = enabled,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}
