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
            arrayListOf(activity.getString(R.string.aux_tv_search)),
        )
        adapter = list
        val picker = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.ui_cast_tv))
            .setAdapter(list) { _, index ->
                val device = entries.getOrNull(index)
                if (device?.kind == ReiAnixTvKind.ROKU) {
                    // Roku discovery does not imply a push-capable DLNA renderer.
                    // Hand the actual media to Web Video Caster instead of
                    // exposing a manual Roku Media Player flow.
                    openInWebVideoCaster()
                } else if (device != null) {
                    list.clear()
                    list.add(activity.getString(R.string.aux_tv_connecting, device.name))
                    list.notifyDataSetChanged()
                    sender.connect(device)
                }
            }
            .setNegativeButton(activity.getString(R.string.ui_close), null)
            .setNeutralButton(activity.getString(R.string.more_refresh), null)
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
                    add(activity.getString(R.string.aux_tv_none))
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
            if (entries.isEmpty()) listOf(activity.getString(R.string.aux_tv_search))
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
                    .setTitle(activity.getString(R.string.aux_cast_failed))
                    .setMessage(state.message)
                    .setPositiveButton(activity.getString(R.string.ui_close), null)
                    .setNeutralButton(activity.getString(R.string.ui_retry)) { _, _ -> show() }
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
                    .setTitle(activity.getString(R.string.aux_video_unavailable))
                    .setMessage(error.message ?: activity.getString(R.string.aux_share_failed))
                    .setPositiveButton(activity.getString(R.string.aux_understood), null)
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
                activity.getString(R.string.aux_wvc_opened),
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
                .setTitle(activity.getString(R.string.aux_wvc_unavailable))
                .setMessage(
                    activity.getString(R.string.aux_wvc_install)
                )
                .setPositiveButton(activity.getString(R.string.aux_open_play_store)) { _, _ -> openWebVideoCasterStore() }
                .setNegativeButton(activity.getString(R.string.locale_cancel), null)
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
                    activity, activity.getString(R.string.aux_play_store_unavailable), Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showWebVideoCasterError() {
        present(
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.aux_wvc_open_failed))
                .setMessage(activity.getString(R.string.aux_wvc_check))
                .setPositiveButton(activity.getString(R.string.aux_understood), null)
                .create()
        )
    }

    private fun showControls(device: ReiAnixTvDevice) {
        val controls = AlertDialog.Builder(activity)
            .setTitle(device.name)
            .setMessage(activity.getString(R.string.aux_casting))
            .setPositiveButton(activity.getString(R.string.ui_close), null)
            .setNeutralButton(if (remotePaused) activity.getString(R.string.aux_play) else activity.getString(R.string.aux_pause), null)
            .setNegativeButton(activity.getString(R.string.aux_disconnect)) { _, _ ->
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
                        if (remotePaused) activity.getString(R.string.aux_play) else activity.getString(R.string.aux_pause)
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
