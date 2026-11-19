package com.reianix.reianix_local.player

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
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
) : Dialog(host, android.R.style.Theme_Material_NoActionBar_Fullscreen) {
    private val initial = FavoriteClipRange.editorInitial(durationMs, positionMs, existing)
    private var start = initial.startMs
    private var end = initial.endMs
    private var cursor = positionMs.coerceIn(start, (end - 1L).coerceAtLeast(start))
    private val originalName = existing?.text.orEmpty()
    private var name = originalName
    private var saving = false
    private var closed = false
    private var dragging = false
    private var lastSeekAt = -1_000L
    private var previewPlayer: ExoPlayer? = null
    private var framesJob: Job? = null
    private var saveJob: Job? = null
    private var nameDialog: AlertDialog? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var preview: PlayerView
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
        setOnClickListener { if (!saving) click() }
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

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
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
        back = button("‹", "Voltar sem salvar") { leave() }.apply { textSize = 26f }
        top.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        top.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
        revert = button("Reverter", "Reverter recorte") { reset() }
        save = button("Salvar", "Salvar cena favorita") { persist() }
        menu = button("⋮", "Mais opções") { showMenu() }.apply { textSize = 24f }
        top.addView(revert)
        top.addView(save)
        top.addView(menu)
        root.addView(top, LinearLayout.LayoutParams(-1, -2))

        // The video occupies the central viewport rather than a tiny image in
        // an AlertDialog. FIT preserves the actual aspect ratio of the source.
        preview = PlayerView(host).apply {
            useController = false
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
            setOnClickListener { if (!saving) togglePlayback() }
            contentDescription = "Prévia do vídeo"
        }
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))

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
        play = button("▶", "Reproduzir trecho") { togglePlayback() }.apply { textSize = 21f }
        timeLabel = text("", 14f).apply { setPadding(0, 0, dp(16), 0) }
        controls.addView(play)
        controls.addView(timeLabel)
        val playRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(controls)
            addView(button("↺", "Voltar ao início do trecho") {
                previewPlayer?.pause()
                seek(start, true)
            }.apply { background = pill(); textSize = 20f },
                LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) })
        }
        bottom.addView(playRow, LinearLayout.LayoutParams(-2, -2))
        filmstrip = FavoriteClipTimeline(host, durationMs).apply {
            onDrag = { target, time, finished ->
                if (!saving) handleDrag(target, time, finished)
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
        setContentView(root)
        setCanceledOnTouchOutside(false)
        render()
        ViewCompat.requestApplyInsets(root)
    }

    override fun onStart() {
        super.onStart()
        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawableResource(android.R.color.black)
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            SystemUiController(this).applyImmersive(false)
        }
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
                override fun onIsPlayingChanged(isPlaying: Boolean) { render() }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { render() }
                override fun onPlayerError(error: PlaybackException) {
                    report("Não foi possível abrir a prévia deste vídeo.")
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
            report("Prévia indisponível. Você ainda pode selecionar um trecho.")
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
        play.contentDescription = if (playing) "Pausar prévia" else "Reproduzir trecho"
        revert.alpha = if (start == initial.startMs && end == initial.endMs && name == originalName) 0.4f else 1f
        save.text = if (saving) "Salvando…" else "Salvar"
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
            this.menu.add("Nome da cena")
            this.menu.add("Cancelar edição")
            setOnMenuItemClickListener {
                if (it.title == "Nome da cena") rename() else leave()
                true
            }
            show()
        }
    }

    private fun rename() {
        val input = EditText(host).apply {
            setSingleLine(true)
            setTextColor(Color.WHITE)
            hint = "Nome da cena (opcional)"
            filters = arrayOf(InputFilter.LengthFilter(500))
            setText(name)
        }
        nameDialog = AlertDialog.Builder(host, R.style.ReiAnixPlayerMenuDialogTheme)
            .setTitle("Nome da cena")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("OK") { _, _ -> name = input.text.toString(); render() }
            .create().also { dialog ->
                dialog.setOnDismissListener {
                    nameDialog = null
                    window?.let { SystemUiController(it).applyImmersive(false) }
                }
                dialog.show()
            }
    }

    private fun persist() {
        if (saving || closed) return
        previewPlayer?.pause()
        saving = true
        setCancelable(false)
        render()
        saveJob = host.lifecycleScope.launch {
            try {
                onSave(start, end, name)
                if (!closed) {
                    // Do not cancel the currently completing save in onStop.
                    saveJob = null
                    dismiss()
                    onSaved()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!closed) {
                    saving = false
                    setCancelable(true)
                    render()
                    report("Não foi possível salvar a cena. Verifique o intervalo e o limite de notas.")
                }
            }
        }
    }

    private fun leave() {
        if (saving || closed) return
        dismiss()
        onBack()
    }

    @Deprecated("Dialog back navigation")
    override fun onBackPressed() { leave() }

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

    override fun onStop() {
        closed = true
        handler.removeCallbacks(tick)
        framesJob?.cancel()
        saveJob?.cancel()
        nameDialog?.dismiss()
        nameDialog = null
        if (::preview.isInitialized) preview.player = null
        previewPlayer?.release()
        previewPlayer = null
        super.onStop()
    }
}
