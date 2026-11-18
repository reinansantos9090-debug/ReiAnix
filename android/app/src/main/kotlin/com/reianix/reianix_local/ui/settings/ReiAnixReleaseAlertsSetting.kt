package com.reianix.reianix_local.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.reianix.reianix_local.data.releases.ReiAnixReleaseMonitor

/** Explicit opt-in; no runtime permission prompt on app startup or library scan. */
@Composable
fun ReiAnixReleaseAlertsSetting() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(ReiAnixReleaseMonitor.enabled(context)) }
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            ReiAnixReleaseMonitor.setEnabled(context, true)
            enabled = true
            permissionDenied = false
        } else {
            enabled = false
            permissionDenied = true
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Avisar sobre novos episódios", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Somente animes da sua biblioteca. Consulta leve com internet, " +
                    "aproximadamente a cada 6 horas; horários não são garantidos.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (permissionDenied) {
                Text(
                    "Autorize notificações nas configurações do Android para receber avisos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Switch(
            checked = enabled,
            onCheckedChange = { newValue ->
                when {
                    !newValue -> {
                        ReiAnixReleaseMonitor.setEnabled(context, false)
                        enabled = false
                    }
                    Build.VERSION.SDK_INT >= 33 &&
                        !ReiAnixReleaseMonitor.notificationsAllowed(context) -> {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    else -> {
                        ReiAnixReleaseMonitor.setEnabled(context, true)
                        enabled = true
                    }
                }
            },
        )
    }
}
