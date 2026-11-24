package com.reianix.reianix_local.player.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Toast
import com.reianix.reianix_local.player.ExternalVideoChooser
import com.reianix.reianix_local.R
import java.io.Closeable

/** One TV picker. The protocol is internal; only device names reach the UI. */
internal class ReiAnixTvPicker(
    private val activity: Activity,
    val sourceId: String,
    private val sourceUri: Uri,
    private val title: String,
    positionMs: Long,
    private val onRemotePlaybackStarted: () -> Unit,
) : Closeable {
    private var entries: List<ReiAnixTvDevice> = emptyList()
    private var adapter: ArrayAdapter<String>? = null
    private var dialog: AlertDialog? = null
    private var remotePaused = false
    private val ui = Handler(Looper.getMainLooper())
    private var emptySearchNotice: Runnable? = null
    private var closed = false
    private val sender = ReiAnixTvSender(
        activity, sourceUri, title, positionMs,
        onDevicesChanged = { devices ->
            entries = devices
            refresh()
        },
        onConnectionChanged = ::onConnection,
    )

    fun show() {
        if (closed || activity.isFinishing || activity.isDestroyed) return
        sender.activeDevice?.let { connected ->
            showControls(connected)
            return
        }
        dialog?.dismiss()
        val list = ArrayAdapter(
            activity, android.R.layout.simple_list_item_1,
            arrayListOf("Procurando TVs na mesma rede…"),
        )
        adapter = list
        val picker = AlertDialog.Builder(activity)
            .setTitle("Transmitir para TV")
            .setAdapter(list) { _, index ->
                val device = entries.getOrNull(index)
                if (device?.kind == ReiAnixTvKind.ROKU) {
                    // Roku discovery does not imply a push-capable DLNA renderer.
                    // Hand the actual media to Web Video Caster instead of
                    // exposing a manual Roku Media Player flow.
                    openInWebVideoCaster()
                } else if (device != null) {
                    list.clear()
                    list.add("Conectando a " + device.name + "…")
                    list.notifyDataSetChanged()
                    sender.connect(device)
                }
            }
            .setNegativeButton("Fechar", null)
            .setNeutralButton("Atualizar", null)
            .create()
        dialog = picker
        present(picker)
        picker.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            entries = emptyList()
            refresh()
            sender.discover()
            scheduleEmptySearchNotice()
        }
        picker.setOnDismissListener {
            if (dialog === picker) {
                dialog = null
                adapter = null
            }
        }
        sender.discover()
        scheduleEmptySearchNotice()
    }

    private fun scheduleEmptySearchNotice() {
        emptySearchNotice?.let(ui::removeCallbacks)
        val notice = Runnable {
            if (!closed && dialog?.isShowing == true && entries.isEmpty()) {
                adapter?.apply {
                    clear()
                    add("Nenhuma TV encontrada. Verifique a rede Wi-Fi.")
                    notifyDataSetChanged()
                }
            }
        }
        emptySearchNotice = notice
        ui.postDelayed(notice, 10_000L)
    }

    private fun refresh() {
        val list = adapter ?: return
        list.clear()
        list.addAll(
            if (entries.isEmpty()) listOf("Procurando TVs na mesma rede…")
            else {
                val counts = entries.groupingBy { it.name }.eachCount()
                val numbers = mutableMapOf<String, Int>()
                entries.map { device ->
                    if ((counts[device.name] ?: 0) <= 1) device.name else {
                        val number = (numbers[device.name] ?: 0) + 1
                        numbers[device.name] = number
                        device.name + " (" + number + ")"
                    }
                }
            }
        )
        list.notifyDataSetChanged()
    }

    private fun onConnection(state: ReiAnixTvConnection) {
        if (closed || activity.isFinishing || activity.isDestroyed) return
        dialog?.dismiss()
        when (state) {
            is ReiAnixTvConnection.Connected -> {
                remotePaused = false
                onRemotePlaybackStarted()
                showControls(state.device)
            }
            is ReiAnixTvConnection.Failure -> present(
                AlertDialog.Builder(activity)
                    .setTitle("Não foi possível transmitir")
                    .setMessage(state.message)
                    .setPositiveButton("Fechar", null)
                    .setNeutralButton("Tentar novamente") { _, _ -> show() }
                    .create()
            )
        }
    }

    /**
     * Web Video Caster documents Android ACTION_VIEW restricted to its package.
     * The local URI is granted only to that app; success does not imply that
     * playback on the Roku has already started.
     */
    private fun openInWebVideoCaster() {
        val intent = try {
            ExternalVideoChooser.createWebVideoCasterIntent(activity, sourceUri, title)
        } catch (error: IllegalArgumentException) {
            present(
                AlertDialog.Builder(activity)
                    .setTitle("Vídeo indisponível")
                    .setMessage(error.message ?: "Não foi possível compartilhar este vídeo local.")
                    .setPositiveButton("Entendi", null)
                    .create()
            )
            return
        } catch (_: Exception) {
            showWebVideoCasterError()
            return
        }
        try {
            activity.startActivity(intent)
            // Handoff succeeded, not necessarily casting or playback on TV.
            onRemotePlaybackStarted()
            Toast.makeText(
                activity,
                "Vídeo aberto no Web Video Caster. Selecione a Roku nele para transmitir.",
                Toast.LENGTH_LONG,
            ).show()
        } catch (_: ActivityNotFoundException) {
            showInstallWebVideoCaster()
        } catch (_: SecurityException) {
            showWebVideoCasterError()
        } catch (_: RuntimeException) {
            showWebVideoCasterError()
        }
    }

    private fun showInstallWebVideoCaster() {
        present(
            AlertDialog.Builder(activity)
                .setTitle("Web Video Caster indisponível")
                .setMessage(
                    "O Web Video Caster não está instalado ou não aceitou abrir este vídeo. " +
                        "Confira o aplicativo no Google Play. " +
                        "Depois, escolha a Roku dentro dele para transmitir."
                )
                .setPositiveButton("Abrir Google Play") { _, _ -> openWebVideoCasterStore() }
                .setNegativeButton("Cancelar", null)
                .create()
        )
    }

    private fun openWebVideoCasterStore() {
        val storeId = "com.instantbits.cast.webvideo"
        val storeIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=" + storeId),
        )
        try {
            activity.startActivity(storeIntent)
        } catch (_: ActivityNotFoundException) {
            try {
                activity.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + storeId),
                    )
                )
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(
                    activity, "Google Play indisponível neste dispositivo.", Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showWebVideoCasterError() {
        present(
            AlertDialog.Builder(activity)
                .setTitle("Não foi possível abrir Web Video Caster")
                .setMessage("Verifique se o aplicativo está atualizado e se o vídeo pode ser compartilhado.")
                .setPositiveButton("Entendi", null)
                .create()
        )
    }

    private fun showControls(device: ReiAnixTvDevice) {
        val controls = AlertDialog.Builder(activity)
            .setTitle(device.name)
            .setMessage("Transmitindo para a TV.")
            .setPositiveButton("Fechar", null)
            .setNeutralButton(if (remotePaused) "Reproduzir" else "Pausar", null)
            .setNegativeButton("Desconectar") { _, _ ->
                sender.disconnect()
                remotePaused = false
            }
            .create()
        present(controls)
        controls.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            val resume = remotePaused
            sender.pauseOrResume(resume) { error ->
                if (error == null) {
                    remotePaused = !resume
                    controls.getButton(AlertDialog.BUTTON_NEUTRAL)?.text =
                        if (remotePaused) "Reproduzir" else "Pausar"
                } else controls.setMessage(error)
            }
        }
    }

    private fun present(dialog: AlertDialog) {
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.reianix_player_menu_dialog_background)
    }

    override fun close() {
        if (closed) return
        closed = true
        emptySearchNotice?.let(ui::removeCallbacks)
        emptySearchNotice = null
        dialog?.dismiss()
        dialog = null
        adapter = null
        sender.close()
    }
}
