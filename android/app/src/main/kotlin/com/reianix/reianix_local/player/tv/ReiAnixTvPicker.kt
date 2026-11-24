package com.reianix.reianix_local.player.tv

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.widget.ArrayAdapter
import com.reianix.reianix_local.R
import java.io.Closeable

/** One TV picker. The protocol is internal; only device names reach the UI. */
internal class ReiAnixTvPicker(
    private val activity: Activity,
    val sourceId: String,
    sourceUri: Uri,
    private val title: String,
    positionMs: Long,
    private val onRemotePlaybackStarted: () -> Unit,
) : Closeable {
    private var entries: List<ReiAnixTvDevice> = emptyList()
    private var adapter: ArrayAdapter<String>? = null
    private var dialog: AlertDialog? = null
    private var remotePaused = false
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
                if (device != null) {
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
        }
        picker.setOnDismissListener {
            if (dialog === picker) {
                dialog = null
                adapter = null
            }
        }
        sender.discover()
    }

    private fun refresh() {
        val list = adapter ?: return
        list.clear()
        list.addAll(
            if (entries.isEmpty()) listOf("Procurando TVs na mesma rede…")
            else entries.map { it.name }
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
            is ReiAnixTvConnection.RokuReady -> showRokuInstructions(state.name)
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

    private fun showRokuInstructions(name: String) {
        present(
            AlertDialog.Builder(activity)
                .setTitle(name)
                .setMessage(
                    "Na TV, abra Roku Media Player, escolha ReiAnix e selecione “" +
                        title + "”.\n\nO celular e a TV precisam estar na mesma rede. " +
                        "Mantenha o player aberto enquanto assiste."
                )
                .setPositiveButton("Entendi", null)
                .setNegativeButton("Encerrar") { _, _ -> sender.disconnect() }
                .create()
        )
    }

    private fun showControls(device: ReiAnixTvDevice) {
        if (device.kind == ReiAnixTvKind.ROKU) {
            showRokuInstructions(device.name)
            return
        }
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
        dialog?.dismiss()
        dialog = null
        adapter = null
        sender.close()
    }
}
