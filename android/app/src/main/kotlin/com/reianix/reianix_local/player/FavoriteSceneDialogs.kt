package com.reianix.reianix_local.player

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.graphics.Typeface
import android.widget.ImageButton
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
import android.widget.FrameLayout
import android.widget.ProgressBar
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

/** Full-screen pages in the existing player window; no extra fullscreen surfaces. */
internal class FavoriteSceneDialogs(
    private val activity: FragmentActivity,
    private val container: () -> FrameLayout,
    private val format: (Long) -> String,
    private val report: (String) -> Unit,
) {
    private val thumbnailMutex = Mutex()
    private val thumbnails = LruCache<String, Bitmap>(24)
    private var page: View? = null
    private var closePage: (() -> Unit)? = null
    private var backPage: (() -> Unit)? = null
    private var busyLayer: View? = null
    val isShowing: Boolean get() = page != null

    fun handleBack(): Boolean {
        val back = backPage ?: return false
        if (busyLayer == null) back()
        return true
    }

    fun setBusy(busy: Boolean) {
        busyLayer?.let { (it.parent as? ViewGroup)?.removeView(it) }
        busyLayer = null
        if (!busy || page == null) return
        val layer = FrameLayout(activity).apply {
            isClickable = true
            isFocusable = true
            addView(ProgressBar(activity).apply { contentDescription = "Carregando cena" },
                FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
        }
        busyLayer = layer
        container().addView(layer, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showPage(view: View, back: () -> Unit, close: () -> Unit = {}) {
        setBusy(false)
        val previous = page
        val previousClose = closePage
        view.isClickable = true
        view.isFocusable = true
        view.setBackgroundColor(Color.BLACK)
        view.tag = "reianix_favorite_scene_page"
        container().addView(view, FrameLayout.LayoutParams(-1, -1))
        view.bringToFront()
        page = view
        closePage = close
        backPage = back
        previous?.let { (it.parent as? ViewGroup)?.removeView(it) }
        previousClose?.invoke()
        ViewCompat.requestApplyInsets(view)
    }

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
        setBusy(false)
        val previous = page
        val previousClose = closePage
        page = null
        closePage = null
        backPage = null
        previous?.let { (it.parent as? ViewGroup)?.removeView(it) }
        previousClose?.invoke()
    }

    private fun fullScreen(
        title: String,
        onBack: () -> Unit,
        body: LinearLayout.() -> Unit,
        onClose: () -> Unit = {},
        headerActions: LinearLayout.() -> Unit = {},
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val root = column().apply {
            val header = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(action("‹") { if (busyLayer == null) onBack() }.apply {
                    contentDescription = "Voltar"
                    textSize = 28f
                    setTextColor(Color.WHITE)
                }, LinearLayout.LayoutParams(dp(52), dp(52)))
                addView(label(title, 20f), LinearLayout.LayoutParams(0, -2, 1f))
                headerActions()
            }
            addView(header, LinearLayout.LayoutParams(-1, -2))
            body()
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            view.setPadding(cutout.left, cutout.top, cutout.right, maxOf(cutout.bottom, dp(12)))
            insets
        }
        showPage(root, onBack, onClose)
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

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun galleryRipple(fill: Int = Color.TRANSPARENT, radius: Int = 12) =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(fill, radius), rounded(Color.WHITE, radius))

    private enum class GalleryIcon { ADD, SORT, MORE, MOVIE }

    /** Small monochrome vector-style icons, independent of the selected app font. */
    private fun galleryIcon(kind: GalleryIcon, description: String, size: Int = 24): ImageButton =
        ImageButton(activity).apply {
            contentDescription = description
            setBackground(galleryRipple())
            scaleType = ImageView.ScaleType.CENTER
            setImageDrawable(object : android.graphics.drawable.Drawable() {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    strokeWidth = 1.8f
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    style = Paint.Style.STROKE
                }
                override fun getIntrinsicWidth() = dp(size)
                override fun getIntrinsicHeight() = dp(size)
                override fun draw(canvas: Canvas) {
                    canvas.save()
                    canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
                    canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
                    when (kind) {
                        GalleryIcon.ADD -> {
                            canvas.drawLine(5f, 12f, 19f, 12f, paint)
                            canvas.drawLine(12f, 5f, 12f, 19f, paint)
                        }
                        GalleryIcon.SORT -> {
                            canvas.drawLine(7f, 4f, 7f, 20f, paint)
                            canvas.drawLine(3f, 8f, 7f, 4f, paint)
                            canvas.drawLine(7f, 4f, 11f, 8f, paint)
                            canvas.drawLine(17f, 4f, 17f, 20f, paint)
                            canvas.drawLine(13f, 16f, 17f, 20f, paint)
                            canvas.drawLine(17f, 20f, 21f, 16f, paint)
                        }
                        GalleryIcon.MORE -> {
                            paint.style = Paint.Style.FILL
                            for (y in listOf(5f, 12f, 19f)) canvas.drawCircle(12f, y, 1.6f, paint)
                            paint.style = Paint.Style.STROKE
                        }
                        GalleryIcon.MOVIE -> {
                            canvas.drawRoundRect(3f, 7f, 21f, 21f, 2f, 2f, paint)
                            canvas.drawLine(3f, 11f, 21f, 11f, paint)
                            canvas.drawLine(3f, 7f, 19f, 3f, paint)
                            canvas.drawLine(8f, 6f, 11f, 10f, paint)
                            canvas.drawLine(15f, 4f, 18f, 8f, paint)
                            canvas.drawPath(Path().apply {
                                moveTo(10f, 13f); lineTo(15f, 16f); lineTo(10f, 19f); close()
                            }, paint)
                        }
                    }
                    canvas.restore()
                }
                override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
                override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
                    paint.colorFilter = colorFilter
                    invalidateSelf()
                }
                @Suppress("DEPRECATION")
                override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
            })
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
        val content = column().apply { setPadding(dp(16), dp(8), dp(12), dp(8)) }
        val orderedScenes = scenes.sortedByDescending { it.note.createdAtMs }.toMutableList()
        var sortIndex = 0
        var galleryAdapter: BaseAdapter? = null
        val sortLabels = listOf("Mais recentes", "Mais antigas", "Nome (A–Z)", "Maior duração")

        fun play(scene: PlayerLocalMetadataStore.FavoriteScene) {
            if (scene.note.clipEndMs != null) onPlay(scene)
            else report("Use Editar para definir o trecho desta cena.")
        }

        if (scenes.isEmpty()) {
            val empty = column().apply {
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(24), dp(24), dp(24))
                addView(galleryIcon(GalleryIcon.MOVIE, "", 44).apply {
                    isClickable = false
                    isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    background = null
                    alpha = 0.55f
                }, LinearLayout.LayoutParams(dp(56), dp(56)))
                addView(label("Nenhuma cena favorita", 20f).apply {
                    gravity = Gravity.CENTER
                    setTypeface(typeface, Typeface.BOLD)
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
                addView(label(if (canCreate) "Salve seus momentos favoritos para assistir novamente quando quiser."
                    else "Salve trechos no player do ReiAnix para encontrá-los aqui.", 14f, 0xFFAAAAAA.toInt()).apply {
                    gravity = Gravity.CENTER
                    setLineSpacing(dp(3).toFloat(), 1f)
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                if (canCreate) addView(action("+  Salvar cena", onCreate).apply {
                    setTextColor(Color.WHITE)
                    background = galleryRipple(0xFF202020.toInt())
                    setPadding(dp(22), 0, dp(22), 0)
                }, LinearLayout.LayoutParams(-2, dp(48)).apply { topMargin = dp(20) })
            }
            content.addView(empty, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            content.addView(label(if (scenes.size == 1) "1 cena salva" else "${scenes.size} cenas salvas",
                13f, 0xFFAAAAAA.toInt()).apply {
                setPadding(dp(4), dp(4), dp(4), dp(8))
            })
            val list = ListView(activity).apply {
                divider = null
                dividerHeight = 0
                setBackgroundColor(Color.BLACK)
                setItemsCanFocus(true)
                adapter = object : BaseAdapter() {
                    override fun getCount() = orderedScenes.size
                    override fun getItem(position: Int) = orderedScenes[position]
                    override fun getItemId(position: Int) = position.toLong()
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val row = (convertView as? LinearLayout) ?: LinearLayout(activity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(4), dp(10), 0, dp(10))
                            background = galleryRipple()
                            isClickable = true
                            isFocusable = true
                            val preview = column().apply {
                                addView(thumbnail().apply {
                                    background = rounded(0xFF202020.toInt(), 10)
                                    clipToOutline = true
                                }, LinearLayout.LayoutParams(-1, dp(79)))
                                addView(label("", 12f, 0xFFAAAAAA.toInt()).apply {
                                    maxLines = 1
                                    ellipsize = TextUtils.TruncateAt.END
                                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
                            }
                            addView(preview, LinearLayout.LayoutParams(dp(140), -2))
                            addView(column().apply {
                                setPadding(dp(12), 0, 0, 0)
                                addView(label("", 16f).apply {
                                    setTypeface(typeface, Typeface.BOLD)
                                    maxLines = 2
                                    ellipsize = TextUtils.TruncateAt.END
                                })
                                addView(label("", 13f, 0xFFBBBBBB.toInt()).apply {
                                    maxLines = 2
                                    ellipsize = TextUtils.TruncateAt.END
                                    setPadding(0, dp(4), 0, 0)
                                })
                                addView(label("", 12f, 0xFFAAAAAA.toInt()).apply {
                                    setPadding(0, dp(4), 0, 0)
                                })
                            }, LinearLayout.LayoutParams(0, -2, 1f))
                            addView(galleryIcon(GalleryIcon.MORE, "Opções da cena"),
                                LinearLayout.LayoutParams(dp(48), dp(48)))
                        }
                        jobs.remove(row)?.cancel()
                        val scene = getItem(position)
                        val note = scene.note
                        val preview = row.getChildAt(0) as LinearLayout
                        val image = preview.getChildAt(0) as ImageView
                        val duration = preview.getChildAt(1) as TextView
                        val textColumn = row.getChildAt(1) as LinearLayout
                        val more = row.getChildAt(2) as ImageButton
                        row.setOnClickListener { play(scene) }
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
                                .distinct().joinToString(" • ").ifBlank { "Episódio local" }
                        (textColumn.getChildAt(2) as TextView).text = note.clipEndMs?.let {
                            format(note.timestampMs) + " – " + format(it)
                        } ?: ("Marcador antigo • " + format(note.timestampMs))
                        duration.text = note.clipEndMs?.let {
                            "Duração: " + format(it - note.timestampMs)
                        } ?: "Sem trecho"
                        val cached = thumbnails.get("${scene.mediaId}@${note.timestampMs}")
                        image.setImageBitmap(cached)
                        if (cached == null) jobs[row] = activity.lifecycleScope.launch {
                            val bitmap = loadFrame(scene.mediaId, note.timestampMs)
                            image.setImageBitmap(bitmap)
                        }
                        return row
                    }
                }.also { galleryAdapter = it }
            }
            content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        fullScreen("Cenas favoritas", onBack = onBack, body = {
            addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        }, onClose = { jobs.values.forEach { it.cancel() }; jobs.clear() }, headerActions = {
            if (scenes.isNotEmpty()) {
                addView(galleryIcon(GalleryIcon.SORT, "Ordenar cenas").apply {
                    setOnClickListener { anchor ->
                        val popup = PopupMenu(ContextThemeWrapper(activity,
                            android.R.style.Theme_Material_NoActionBar_Fullscreen), anchor)
                        sortLabels.forEachIndexed { index, title ->
                            popup.menu.add(1, index, index, title).isChecked = index == sortIndex
                        }
                        popup.menu.setGroupCheckable(1, true, true)
                        popup.setOnMenuItemClickListener { item ->
                            sortIndex = item.itemId
                            val sorted = when (sortIndex) {
                                1 -> scenes.sortedBy { it.note.createdAtMs }
                                2 -> scenes.sortedWith(compareBy<PlayerLocalMetadataStore.FavoriteScene, String>(String.CASE_INSENSITIVE_ORDER) { it.note.text })
                                3 -> scenes.sortedByDescending { scene ->
                                    scene.note.clipEndMs?.minus(scene.note.timestampMs) ?: 0L
                                }
                                else -> scenes.sortedByDescending { it.note.createdAtMs }
                            }
                            orderedScenes.clear()
                            orderedScenes.addAll(sorted)
                            galleryAdapter?.notifyDataSetChanged()
                            true
                        }
                        popup.show()
                    }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
            }
            if (canCreate) {
                addView(galleryIcon(GalleryIcon.ADD, "Salvar cena").apply {
                    setOnClickListener { onCreate() }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
            }
        })
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
        initialFrame: Bitmap? = null,
    ) {
        if (durationMs <= 0L) { report("Duração indisponível para criar um clipe"); return }
        if (activity.isFinishing || activity.isDestroyed) return
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
            initialFrame = initialFrame ?: thumbnails.get("$mediaId@$positionMs"),
        )
        showPage(editor.createView(), editor::handleBack, editor::close)
        editor.startPreview()
    }
}
