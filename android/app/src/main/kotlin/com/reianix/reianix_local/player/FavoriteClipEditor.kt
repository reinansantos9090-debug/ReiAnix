package com.reianix.reianix_local.player

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.reianix.reianix_local.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Full-screen visual trim editor. Its player and filmstrip exist only while
 * editing; the original episode and its stored watch progress are untouched.
 */
@OptIn(UnstableApi::class)
internal class FavoriteClipEditor(
    private val host: FragmentActivity,
    private val item: MediaItem,
    private val trackSelection: TrackSelectionParameters?,
    private val mediaId: String,
    private val durationMs: Long,
    positionMs: Long,
    existing: PlayerLocalMetadataStore.Note?,
    private val format: (Long) -> String,
    private val report: (String) -> Unit,
    private val onSave: suspend (Long, Long, String) -> Unit,
    private val onSaved: () -> Unit,
    private val onBack: () -> Unit,
    private val initialFrame: Bitmap?,
) {
    private val initial = FavoriteClipRange.editorInitial(durationMs, positionMs, existing)
    private var start = initial.startMs
    private var end = initial.endMs
    private var cursor = positionMs.coerceIn(start, (end - 1L).coerceAtLeast(start))
    private val originalName = existing?.text.orEmpty()
    private var name = originalName
    private var saving = false
    private var closed = false
    private var leaving = false
    private var dragging = false
    private var lastSeekAt = -1_000L
    private var previewPlayer: ExoPlayer? = null
    private var framesJob: Job? = null
    private var saveJob: Job? = null
    private var nameDialog: AlertDialog? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var preview: PlayerView
    private lateinit var placeholder: ImageView
    private lateinit var filmstrip: FavoriteClipTimeline
    private lateinit var timeLabel: TextView
    private lateinit var play: TextView
    private lateinit var revert: TextView
    private lateinit var save: TextView
    private lateinit var rangeLabel: TextView
    private lateinit var menu: TextView
    private lateinit var back: TextView

    private fun dp(v: Int) = (v * host.resources.displayMetrics.density).roundToInt()
    private fun text(value: String, size: Float = 15f) = TextView(host).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
    }
    private fun button(value: String, description: String, click: () -> Unit) = text(value).apply {
        contentDescription = description
        minHeight = dp(44)
        minWidth = dp(44)
        setPadding(dp(10), 0, dp(10), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { if (!saving && !leaving) click() }
    }
    private fun pill() = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        setStroke(dp(1), 0xFF353535.toInt())
        cornerRadius = dp(28).toFloat()
    }
    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            val p = previewPlayer
            if (p != null && !dragging && p.playWhenReady) {
                val at = p.currentPosition
                if (at >= end || p.playbackState == Player.STATE_ENDED) {
                    p.pause()
                    cursor = end
                } else {
                    cursor = at.coerceIn(start, end)
                }
                render()
            }
            handler.postDelayed(this, 50L)
        }
    }

    fun createView(): View {
        val root = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            view.setPadding(
                cutout.left, cutout.top,
                cutout.right, maxOf(cutout.bottom, dp(12)),
            )
            insets
        }
        val top = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        back = button("‹", host.getString(R.string.aux_back_without_save)) { leave() }.apply { textSize = 26f }
        top.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        top.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
        revert = button(host.getString(R.string.aux_revert), host.getString(R.string.aux_revert_trim)) { reset() }
        save = button(host.getString(R.string.pref_save), host.getString(R.string.aux_save_favorite_scene)) { persist() }
        menu = button("⋮", host.getString(R.string.ui_more_options)) { showMenu() }.apply { textSize = 24f }
        top.addView(revert)
        top.addView(save)
        top.addView(menu)
        root.addView(top, LinearLayout.LayoutParams(-1, -2))

        // The video occupies the central viewport rather than a tiny image in
        // an AlertDialog. FIT preserves the actual aspect ratio of the source.
        preview = (LayoutInflater.from(host).inflate(R.layout.native_player_view, null) as PlayerView).apply {
            setKeepContentOnPlayerReset(true)
            setShutterBackgroundColor(Color.BLACK)
            useController = false
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
            setOnClickListener { if (!saving && !leaving) togglePlayback() }
            contentDescription = host.getString(R.string.aux_video_preview)
        }
        placeholder = ImageView(host).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            setImageBitmap(initialFrame)
            visibility = if (initialFrame != null) View.VISIBLE else View.GONE
        }
        val previewBox = FrameLayout(host).apply {
            addView(preview, FrameLayout.LayoutParams(-1, -1))
            addView(placeholder, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(previewBox, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottom = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(14))
        }
        val controls = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pill()
        }
        play = button("▶", host.getString(R.string.aux_play_clip)) { togglePlayback() }.apply { textSize = 21f }
        timeLabel = text("", 14f).apply { setPadding(0, 0, dp(16), 0) }
        controls.addView(play)
        controls.addView(timeLabel)
        val playRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(controls)
            addView(button("↺", host.getString(R.string.aux_restart_clip)) {
                previewPlayer?.pause()
                seek(start, true)
            }.apply { background = pill(); textSize = 20f },
                LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) })
        }
        bottom.addView(playRow, LinearLayout.LayoutParams(-2, -2))
        filmstrip = FavoriteClipTimeline(host, durationMs).apply {
            onDrag = { target, time, finished ->
                if (!saving && !leaving) handleDrag(target, time, finished)
            }
        }
        val stripBox = FrameLayout(host).apply {
            addView(filmstrip, FrameLayout.LayoutParams(-1, dp(60), Gravity.CENTER))
        }
        bottom.addView(stripBox, LinearLayout.LayoutParams(
            minOf(dp(480), (host.resources.displayMetrics.widthPixels * 0.72f).toInt()),
            dp(64),
        ).apply { topMargin = dp(8) })
        rangeLabel = text("", 11f).apply { setTextColor(0xFFAAAAAA.toInt()) }
        bottom.addView(rangeLabel)
        root.addView(bottom, LinearLayout.LayoutParams(-1, -2))
        render()
        return root
    }

    fun startPreview() {
        if (previewPlayer != null || closed) return
        try {
            val p = ExoPlayer.Builder(host).build()
            previewPlayer = p
            preview.player = p
            p.setAudioAttributes(AudioAttributes.DEFAULT, true)
            p.repeatMode = Player.REPEAT_MODE_OFF
            trackSelection?.let { parameters ->
                p.trackSelectionParameters = parameters.buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, false)
                    .build()
            }
            p.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    if (!closed) {
                        placeholder.visibility = View.GONE
                        placeholder.setImageDrawable(null)
                    }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) { render() }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { render() }
                override fun onPlayerError(error: PlaybackException) {
                    report(host.getString(R.string.aux_preview_open_failed))
                    render()
                }
            })
            p.setMediaItem(item)
            p.seekTo(cursor)
            p.prepare()
            p.playWhenReady = false
        } catch (_: Exception) {
            preview.player = null
            previewPlayer?.release()
            previewPlayer = null
            report(host.getString(R.string.aux_preview_still_select))
        }
        handler.post(tick)
        loadFilmstrip()
    }

    private fun render() {
        if (!::filmstrip.isInitialized || closed) return
        filmstrip.setRange(start, end, cursor)
        timeLabel.text = format((cursor - start).coerceIn(0L, end - start)) + " / " + format(end - start)
        rangeLabel.text = format(start) + " – " + format(end)
        val playing = previewPlayer?.playWhenReady == true
        play.text = if (playing) "Ⅱ" else "▶"
        play.contentDescription = if (playing) host.getString(R.string.aux_pause_preview) else host.getString(R.string.aux_play_clip)
        revert.alpha = if (start == initial.startMs && end == initial.endMs && name == originalName) 0.4f else 1f
        save.text = if (saving) host.getString(R.string.flow_saving) else host.getString(R.string.pref_save)
        listOf(back, save, revert, menu, play, filmstrip).forEach { it.isEnabled = !saving }
    }

    private fun seek(value: Long, force: Boolean) {
        cursor = value.coerceIn(start, end)
        val now = SystemClock.uptimeMillis()
        if (force || now - lastSeekAt >= 80L) {
            lastSeekAt = now
            // The handle at the very end previews the last valid frame.
            previewPlayer?.seekTo(cursor.coerceAtMost((durationMs - 1L).coerceAtLeast(0L)))
        }
        render()
    }

    private fun handleDrag(target: FavoriteClipTimeline.Target, time: Long, finished: Boolean) {
        dragging = !finished
        previewPlayer?.pause()
        when (target) {
            FavoriteClipTimeline.Target.START -> {
                start = FavoriteClipRange.editStart(time, end, durationMs)
                seek(start, finished)
            }
            FavoriteClipTimeline.Target.END -> {
                end = FavoriteClipRange.editEnd(time, start, durationMs)
                seek(end, finished)
            }
            FavoriteClipTimeline.Target.CURSOR -> seek(time, finished)
        }
    }

    private fun togglePlayback() {
        val p = previewPlayer ?: return
        if (p.playWhenReady) p.pause()
        else {
            if (cursor < start || cursor >= end) seek(start, true)
            if (p.currentPosition < start || p.currentPosition >= end) p.seekTo(start)
            p.play()
        }
        render()
    }

    private fun reset() {
        previewPlayer?.pause()
        start = initial.startMs
        end = initial.endMs
        name = originalName
        seek(start, true)
    }

    private fun showMenu() {
        previewPlayer?.pause()
        PopupMenu(host, menu).apply {
            this.menu.add(0, 1, 0, host.getString(R.string.extra_profile_scene_name))
            this.menu.add(0, 2, 1, host.getString(R.string.aux_cancel_edit))
            setOnMenuItemClickListener {
                if (it.itemId == 1) rename() else leave()
                true
            }
            show()
        }
    }

    private fun rename() {
        val input = EditText(host).apply {
            setSingleLine(true)
            setTextColor(Color.WHITE)
            hint = host.getString(R.string.aux_scene_name_optional)
            filters = arrayOf(InputFilter.LengthFilter(500))
            setText(name)
        }
        nameDialog = AlertDialog.Builder(host, R.style.ReiAnixPlayerMenuDialogTheme)
            .setTitle(host.getString(R.string.extra_profile_scene_name))
            .setView(input)
            .setNegativeButton(host.getString(R.string.locale_cancel), null)
            .setPositiveButton("OK") { _, _ -> name = input.text.toString(); render() }
            .create().also { dialog ->
                dialog.setOnDismissListener {
                    nameDialog = null
                    host.window.let { SystemUiController(it).applyImmersive(false) }
                }
                dialog.show()
            }
    }

    private fun persist() {
        if (saving || closed || leaving) return
        previewPlayer?.pause()
        saving = true
        render()
        saveJob = host.lifecycleScope.launch {
            try {
                onSave(start, end, name)
                if (!closed) {
                    // Do not cancel the currently completing save in onStop.
                    saveJob = null
                    leaving = true
                    onSaved()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!closed) {
                    saving = false
                    render()
                    report(host.getString(R.string.aux_scene_save_failed))
                }
            }
        }
    }

    private fun leave() {
        if (saving || closed || leaving) return
        leaving = true
        previewPlayer?.pause()
        onBack()
    }

    fun handleBack() { leave() }

    private fun loadFilmstrip() {
        framesJob = host.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    val source = Uri.parse(mediaId)
                    if (source.scheme == "file") retriever.setDataSource(requireNotNull(source.path))
                    else if (source.scheme == "content") retriever.setDataSource(host.applicationContext, source)
                    else return@withContext
                    for (index in 0 until FavoriteClipTimeline.FRAME_COUNT) {
                        ensureActive()
                        val ms = FavoriteClipRange.thumbnailTime(durationMs, index, FavoriteClipTimeline.FRAME_COUNT)
                        val us = ms.coerceAtMost(Long.MAX_VALUE / 1_000L) * 1_000L
                        val frame = try {
                            val scaled = if (Build.VERSION.SDK_INT >= 27) {
                                retriever.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 90)
                            } else null
                            scaled ?: retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { original ->
                                val small = Bitmap.createScaledBitmap(original, 160, 90, true)
                                if (small !== original) original.recycle()
                                small
                            }
                        } catch (_: Exception) { null }
                        withContext(Dispatchers.Main.immediate) {
                            if (!closed) filmstrip.setFrame(index, frame)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Unavailable thumbnails do not prevent trimming.
                } finally {
                    runCatching { retriever.release() }
                }
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(tick)
        framesJob?.cancel()
        saveJob?.cancel()
        nameDialog?.dismiss()
        nameDialog = null
        if (::preview.isInitialized) preview.player = null
        previewPlayer?.release()
        previewPlayer = null
    }
}
