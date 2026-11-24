package com.reianix.reianix_local.ui.privacy

import android.view.Gravity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // This is an in-app consent, not an Android runtime permission.
        // Keep the window modal and use its native dimming and back/outside dismissal.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        }

        val colors = MaterialTheme.colorScheme
        Surface(
            modifier = Modifier
                .widthIn(max = 344.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
            shape = RoundedCornerShape(28.dp),
            color = colors.surfaceVariant,
            contentColor = colors.onSurface,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Filled.Image,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(30.dp),
                )
                Text(
                    text = "Permitir que o app ReiAnix envie miniaturas ao Trace.moe?",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "O ReiAnix poderá enviar imagens JPEG extraídas dos seus episódios ao serviço externo Trace.moe para identificar animes, inclusive na identificação automática. As imagens revelam cenas do vídeo; o vídeo completo não é enviado. O serviço recebe também seu IP. Você pode recusar e continuar usando a biblioteca ou revogar a autorização em Configurações > Metadados e Capas > Identificação por imagem. Envios anteriores não podem ser desfeitos.",
                    modifier = Modifier.padding(top = 10.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (savingFailed) {
                    Text(
                        text = "Não foi possível salvar a autorização. Tente novamente.",
                        modifier = Modifier.padding(bottom = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                        textAlign = TextAlign.Center,
                    )
                }
                TextButton(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface),
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
                ) {
                    Text("Permitir", style = MaterialTheme.typography.labelLarge)
                }
                TextButton(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface),
                    onClick = onDismiss,
                ) {
                    Text("Não permitir", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
