package com.reianix.reianix_local.player

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.reianix.reianix_local.MainActivity
import com.reianix.reianix_local.bridge.NativeInternalIntentGuard
import com.reianix.reianix_local.data.sqlite.ReiAnixSqliteSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Private picker; the original catalog handoff never leaves ReiAnix. */
internal class PlaybackTargetActivity : ComponentActivity() {
    private var selectionInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val media = intent.getStringExtra(MEDIA)?.let(Uri::parse)
        @Suppress("DEPRECATION")
        val internal = intent.getParcelableExtra<Intent>(INTERNAL)
        if (media == null || internal == null ||
            internal.component?.className != MainActivity::class.java.name) {
            finish()
            return
        }
        val externalOnly = intent.getBooleanExtra(EXTERNAL_ONLY, false)
        val players = runCatching { ExternalVideoChooser.installedPlayers(this, media) }.getOrDefault(emptyList())
        val includeInternal = !externalOnly || players.isEmpty()
        val labels = (if (includeInternal) listOf("Reprodutor interno (ReiAnix)") else emptyList()) +
            players.map { it.label }
        AlertDialog.Builder(this)
            .setTitle(if (externalOnly && players.isEmpty()) "Nenhum reprodutor externo disponível" else "Abrir com...")
            .setItems(labels.toTypedArray()) { _, index ->
                if (selectionInProgress) return@setItems
                selectionInProgress = true
                if (includeInternal && index == 0) {
                    openInternal(internal)
                } else {
                    val player = players[index - (if (includeInternal) 1 else 0)]
                    lifecycleScope.launch {
                        try {
                            val target = ExternalVideoChooser.createExplicitIntent(
                                this@PlaybackTargetActivity, media, player.component,
                            )
                            if (externalOnly) {
                                ReiAnixSqliteSettingsRepository(applicationContext)
                                    .setPreference("player.external_component", player.component.flattenToString())
                            }
                            startActivity(target)
                            finish()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            showFailure(internal)
                        }
                    }
                }
            }
            .setNegativeButton("Cancelar") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun openInternal(internal: Intent) {
        try {
            startActivity(NativeInternalIntentGuard.stamp(this, internal))
            finish()
        } catch (error: Exception) {
            AlertDialog.Builder(this)
                .setMessage("Não foi possível abrir o episódio.")
                .setPositiveButton("Fechar") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
        }
    }

    private fun showFailure(internal: Intent) {
        AlertDialog.Builder(this)
            .setMessage("Não foi possível abrir o reprodutor externo.")
            .setPositiveButton("Usar ReiAnix") { _, _ -> openInternal(internal) }
            .setNegativeButton("Cancelar") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    companion object {
        private const val MEDIA = "media"
        private const val INTERNAL = "internal"
        private const val EXTERNAL_ONLY = "external_only"

        fun createIntent(context: Context, media: Uri, internal: Intent, externalOnly: Boolean): Intent =
            Intent(context, PlaybackTargetActivity::class.java)
                .putExtra(MEDIA, media.toString())
                .putExtra(INTERNAL, internal)
                .putExtra(EXTERNAL_ONLY, externalOnly)
    }
}
