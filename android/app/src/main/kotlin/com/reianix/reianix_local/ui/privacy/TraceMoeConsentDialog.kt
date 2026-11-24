package com.reianix.reianix_local.ui.privacy

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
        title = { Text("Autorizar envio de miniaturas?") },
        text = {
            Text(
                "O ReiAnix poderá enviar imagens JPEG extraídas dos seus episódios ao serviço externo Trace.moe para identificar animes, inclusive na identificação automática. As imagens revelam cenas do vídeo; o vídeo completo não é enviado. O serviço recebe também seu IP. Você pode recusar e continuar usando a biblioteca ou revogar a autorização em Configurações > Metadados e Capas > Identificação por imagem. Envios anteriores não podem ser desfeitos." +
                    if (savingFailed) "\n\nNão foi possível salvar a autorização. Tente novamente." else "",
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
            ) { Text("Autorizar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Não autorizar") } },
    )
}
