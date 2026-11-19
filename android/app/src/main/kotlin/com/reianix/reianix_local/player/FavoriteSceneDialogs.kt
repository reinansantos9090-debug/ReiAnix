package com.reianix.reianix_local.player

import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Local, virtual clips: the original media remains the only video file. */
internal object FavoriteClipRange {
    private const val DEFAULT_LENGTH_MS = 60_000L

    fun initial(positionMs: Long, durationMs: Long): PlayerLocalMetadataStore.Segment {
        require(durationMs > 0L)
        val minLength = minOf(1_000L, durationMs)
        val start = positionMs.coerceIn(0L, durationMs - minLength)
        return PlayerLocalMetadataStore.Segment(start, start + minOf(DEFAULT_LENGTH_MS, durationMs - start))
    }

    /** New edits match the gallery reference: show the complete original filmstrip first. */
    fun editorInitial(durationMs: Long, positionMs: Long, existing: PlayerLocalMetadataStore.Note?): PlayerLocalMetadataStore.Segment {
        require(durationMs > 0L)
        if (existing == null) return PlayerLocalMetadataStore.Segment(0L, durationMs)
        val savedEnd = existing.clipEndMs
        if (savedEnd != null && existing.timestampMs >= 0L &&
            savedEnd > existing.timestampMs && savedEnd <= durationMs) {
            return PlayerLocalMetadataStore.Segment(existing.timestampMs, savedEnd)
        }
        return initial(existing.timestampMs.takeIf { it >= 0L } ?: positionMs, durationMs)
    }

    fun thumbnailTime(durationMs: Long, index: Int, count: Int): Long {
        require(durationMs > 0L && count > 0 && index in 0 until count)
        return (durationMs.toDouble() * (index + 0.5) / count).toLong().coerceIn(0L, durationMs - 1L)
    }

    fun editStart(request: Long, end: Long, duration: Long): Long =
        request.coerceIn(0L, end - minOf(1_000L, end, duration))

    fun editEnd(request: Long, start: Long, duration: Long): Long =
        request.coerceIn(start + minOf(1_000L, duration - start), duration)
}

/** Native dialogs share the player's existing theme and are dismissed with its lifecycle. */
internal class FavoriteSceneDialogs(
    private val activity: FragmentActivity,
    private val format: (Long) -> String,
    private val report: (String) -> Unit,
) {
    private val thumbnailMutex = Mutex()
    private val thumbnails = LruCache<String, Bitmap>(24)
    private var currentDialog: Dialog? = null
    private var currentEditor: FavoriteClipEditor? = null

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()
    private fun label(value: String, size: Float = 14f, color: Int = Color.WHITE) =
        TextView(activity).apply { text = value; textSize = size; setTextColor(color) }
    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun action(value: String, click: () -> Unit) = TextView(activity).apply {
        text = value
        textSize = 15f
        setTextColor(0xFF78A9FF.toInt())
        gravity = Gravity.CENTER
        minHeight = dp(48)
        isClickable = true
        isFocusable = true
        setOnClickListener { click() }
    }
    private fun thumbnail() = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(0xFF303034.toInt())
        contentDescription = "Miniatura da cena"
    }

    fun dismiss() {
        val editor = currentEditor
        currentEditor = null
        editor?.dismiss()
        currentDialog?.dismiss()
        currentDialog = null
    }

    private fun fullScreen(title: String, onBack: () -> Unit, body: LinearLayout.() -> Unit, onClose: () -> Unit = {}) {
        if (activity.isFinishing || activity.isDestroyed) return
        val previous = currentDialog
        val previousEditor = currentEditor
        val dialog = Dialog(activity, android.R.style.Theme_Material_NoActionBar_Fullscreen)
        dialog.window?.apply {
            setWindowAnimations(0)
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
        }
        val root = column().apply {
            setBackgroundColor(Color.BLACK)
            val header = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(action("‹") {
                    dialog.dismiss()
                    onBack()
                }.apply {
                    contentDescription = "Voltar"
                    textSize = 28f
                    setTextColor(Color.WHITE)
                }, LinearLayout.LayoutParams(dp(52), dp(52)))
                addView(label(title, 20f), LinearLayout.LayoutParams(0, -2, 1f))
            }
            addView(header, LinearLayout.LayoutParams(-1, -2))
            body()
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            view.setPadding(cutout.left, cutout.top, cutout.right, maxOf(cutout.bottom, dp(12)))
            insets
        }
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { onBack() }
        dialog.setOnDismissListener {
            onClose()
            if (currentDialog === dialog) currentDialog = null
        }
        currentDialog = dialog
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            SystemUiController(this).applyImmersive(false)
        }
        ViewCompat.requestApplyInsets(root)
        // Replace the window only after the new black surface is visible.
        previous?.dismiss()
        previousEditor?.dismiss()
    }

    private suspend fun loadFrame(mediaId: String, positionMs: Long): Bitmap? {
        val key = "$mediaId@$positionMs"
        thumbnails.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            thumbnailMutex.withLock {
                thumbnails.get(key)?.let { return@withLock it }
                val image = runCatching {
                    val media = Uri.parse(mediaId)
                    if (media.scheme != "file" && media.scheme != "content") return@runCatching null
                    val retriever = MediaMetadataRetriever()
                    try {
                        if (media.scheme == "file") retriever.setDataSource(requireNotNull(media.path))
                        else retriever.setDataSource(activity.applicationContext, media)
                        val timeUs = positionMs.coerceIn(0L, Long.MAX_VALUE / 1_000L) * 1_000L
                        if (Build.VERSION.SDK_INT >= 27) {
                            retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, 240, 135)
                        } else {
                            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)?.let { source ->
                                val scaled = Bitmap.createScaledBitmap(source, 240, 135, true)
                                if (scaled !== source) source.recycle()
                                scaled
                            }
                        }
                    } finally { retriever.release() }
                }.getOrNull()
                image?.also { thumbnails.put(key, it) }
            }
        }
    }

    suspend fun durationOf(mediaId: String): Long = withContext(Dispatchers.IO) {
        runCatching {
            val media = Uri.parse(mediaId)
            require(media.scheme == "content" || media.scheme == "file")
            val retriever = MediaMetadataRetriever()
            try {
                if (media.scheme == "file") retriever.setDataSource(requireNotNull(media.path))
                else retriever.setDataSource(activity.applicationContext, media)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally { retriever.release() }
        }.getOrDefault(0L)
    }

    fun gallery(
        scenes: List<PlayerLocalMetadataStore.FavoriteScene>,
        canCreate: Boolean,
        onCreate: () -> Unit,
        onPlay: (PlayerLocalMetadataStore.FavoriteScene) -> Unit,
        onEdit: (PlayerLocalMetadataStore.FavoriteScene) -> Unit,
        onDelete: (PlayerLocalMetadataStore.FavoriteScene) -> Unit,
        onBack: () -> Unit = {},
    ) {
        val jobs = mutableMapOf<View, Job>()
        val content = column().apply { setPadding(dp(16), dp(4), dp(16), dp(8)) }
        if (canCreate) content.addView(action("+ Salvar cena") { onCreate() })
        if (scenes.isEmpty()) content.addView(label("Nenhuma cena favorita salva.").apply {
            setPadding(dp(4), dp(16), dp(4), dp(16))
        }) else {
            val list = ListView(activity).apply {
                dividerHeight = dp(8)
                adapter = object : BaseAdapter() {
                    override fun getCount() = scenes.size
                    override fun getItem(position: Int) = scenes[position]
                    override fun getItemId(position: Int) = position.toLong()
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val row = (convertView as? LinearLayout) ?: LinearLayout(activity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(4), dp(8), dp(4), dp(8))
                            addView(thumbnail(), LinearLayout.LayoutParams(dp(116), dp(66)))
                            addView(column().apply {
                                setPadding(dp(12), 0, 0, 0)
                                addView(label("", 15f))
                                addView(label("", 12f, 0xFFCCCCCC.toInt()))
                                addView(label("", 12f, 0xFFCCCCCC.toInt()))
                            }, LinearLayout.LayoutParams(0, -2, 1f))
                            addView(label("⋮", 26f).apply {
                                gravity = Gravity.CENTER
                                isClickable = true
                                isFocusable = false
                            }, LinearLayout.LayoutParams(dp(48), dp(48)))
                        }
                        jobs.remove(row)?.cancel()
                        val scene = getItem(position)
                        val note = scene.note
                        val image = row.getChildAt(0) as ImageView
                        val textColumn = row.getChildAt(1) as LinearLayout
                        val more = row.getChildAt(2) as TextView
                        more.contentDescription = "Opções de ${note.text}"
                        more.setOnClickListener { anchor ->
                            val popup = PopupMenu(ContextThemeWrapper(activity,
                                android.R.style.Theme_Material_NoActionBar_Fullscreen), anchor)
                            popup.menu.add(0, 1, 0, "Editar")
                            popup.menu.add(0, 2, 1, "Excluir")
                            popup.setOnMenuItemClickListener { item ->
                                when (item.itemId) {
                                    1 -> { onEdit(scene); true }
                                    2 -> { onDelete(scene); true }
                                    else -> false
                                }
                            }
                            popup.show()
                        }
                        (textColumn.getChildAt(0) as TextView).text = note.text
                        (textColumn.getChildAt(1) as TextView).text =
                            listOf(note.animeTitle, note.episodeLabel).filter(String::isNotBlank)
                                .joinToString(" • ").ifBlank { "Episódio local" }
                        (textColumn.getChildAt(2) as TextView).text = note.clipEndMs?.let {
                            format(note.timestampMs) + " – " + format(it) + " • " + format(it - note.timestampMs)
                        } ?: ("Marcador antigo • " + format(note.timestampMs))
                        image.setImageDrawable(null)
                        jobs[row] = activity.lifecycleScope.launch {
                            val bitmap = loadFrame(scene.mediaId, note.timestampMs)
                            image.setImageBitmap(bitmap)
                        }
                        return row
                    }
                }
                setOnItemClickListener { _, _, index, _ ->
                    val scene = scenes[index]
                    // A saved clip opens directly. Old point-only bookmarks
                    // need a range before they can be played as clips.
                    if (scene.note.clipEndMs != null) onPlay(scene)
                    else report("Use Editar para definir o trecho desta cena.")
                }
            }
            list.setBackgroundColor(Color.BLACK)
            list.divider = ColorDrawable(0xFF181818.toInt())
            list.dividerHeight = dp(1)
            content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        fullScreen("Cenas favoritas", onBack = onBack, body = {
            addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        }, onClose = { jobs.values.forEach { it.cancel() }; jobs.clear() })
    }

    fun confirmDelete(name: String, onCancel: () -> Unit, onDelete: () -> Unit) {
        fullScreen("Excluir cena favorita?", onBack = onCancel, body = {
            addView(label(name + "\nO arquivo original não será alterado.").apply {
                setPadding(dp(20), dp(20), dp(20), dp(20))
            })
            addView(action("Excluir") { onDelete() })
            addView(action("Cancelar") { onCancel() })
        })
    }

    fun editor(
        mediaId: String,
        previewItem: MediaItem,
        trackSelection: TrackSelectionParameters?,
        durationMs: Long,
        positionMs: Long,
        existing: PlayerLocalMetadataStore.Note?,
        onSave: suspend (Long, Long, String) -> Unit,
        onSaved: () -> Unit,
        onBack: () -> Unit,
    ) {
        if (durationMs <= 0L) { report("Duração indisponível para criar um clipe"); return }
        if (activity.isFinishing || activity.isDestroyed) return
        val previous = currentDialog
        val previousEditor = currentEditor
        val editor = FavoriteClipEditor(
            host = activity,
            item = previewItem,
            trackSelection = trackSelection,
            mediaId = mediaId,
            durationMs = durationMs,
            positionMs = positionMs,
            existing = existing,
            format = format,
            report = report,
            onSave = onSave,
            onSaved = onSaved,
            onBack = onBack,
        )
        currentEditor = editor
        editor.setOnDismissListener {
            if (currentEditor === editor) currentEditor = null
        }
        editor.window?.setWindowAnimations(0)
        editor.show()
        previous?.dismiss()
        previousEditor?.dismiss()
    }
}
