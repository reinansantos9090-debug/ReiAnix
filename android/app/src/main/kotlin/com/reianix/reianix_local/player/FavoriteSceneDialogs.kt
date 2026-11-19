package com.reianix.reianix_local.player

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.text.InputFilter
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.reianix.reianix_local.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Local, virtual clips: the original media remains the only video file. */
internal object FavoriteClipRange {
    private const val DEFAULT_LENGTH_MS = 60_000L

    fun initial(positionMs: Long, durationMs: Long): PlayerLocalMetadataStore.Segment {
        require(durationMs > 0L)
        val minLength = minOf(1_000L, durationMs)
        val start = positionMs.coerceIn(0L, durationMs - minLength)
        return PlayerLocalMetadataStore.Segment(start, start + minOf(DEFAULT_LENGTH_MS, durationMs - start))
    }

    fun editStart(request: Long, end: Long, duration: Long): Long =
        request.coerceIn(0L, end - minOf(1_000L, end, duration))

    fun editEnd(request: Long, start: Long, duration: Long): Long =
        request.coerceIn(start + minOf(1_000L, duration - start), duration)
}

/** Native dialogs share the player's existing theme and are dismissed with its lifecycle. */
internal class FavoriteSceneDialogs(
    private val activity: FragmentActivity,
    private val show: (AlertDialog) -> Unit,
    private val format: (Long) -> String,
    private val report: (String) -> Unit,
) {
    private val thumbnailMutex = Mutex()
    private val thumbnails = LruCache<String, Bitmap>(24)
    private var currentDialog: AlertDialog? = null

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
        currentDialog?.dismiss()
        currentDialog = null
    }

    private fun present(dialog: AlertDialog, onClose: () -> Unit = {}) {
        dismiss()
        if (activity.isFinishing || activity.isDestroyed) return
        currentDialog = dialog
        dialog.setOnDismissListener {
            onClose()
            if (currentDialog === dialog) currentDialog = null
        }
        show(dialog)
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
    ) {
        val jobs = mutableMapOf<View, Job>()
        val content = column().apply { setPadding(dp(16), dp(4), dp(16), dp(8)) }
        if (canCreate) content.addView(action("+ Salvar novo trecho") { dismiss(); onCreate() })
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
                        }
                        jobs.remove(row)?.cancel()
                        val scene = getItem(position)
                        val note = scene.note
                        val image = row.getChildAt(0) as ImageView
                        val textColumn = row.getChildAt(1) as LinearLayout
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
                    dismiss()
                    val note = scene.note
                    val options = if (note.clipEndMs != null) arrayOf("Reproduzir trecho", "Editar trecho", "Excluir")
                        else arrayOf("Editar e transformar em clipe", "Excluir")
                    val dialog = AlertDialog.Builder(activity, R.style.ReiAnixPlayerMenuDialogTheme)
                        .setTitle(note.text)
                        .setItems(options) { _, which ->
                            if (note.clipEndMs != null && which == 0) onPlay(scene)
                            else if (which == options.lastIndex) onDelete(scene)
                            else onEdit(scene)
                        }
                        .setNegativeButton("Voltar") { _, _ -> gallery(scenes, canCreate, onCreate, onPlay, onEdit, onDelete) }
                        .create()
                    present(dialog)
                }
            }
            val maxHeight = minOf(dp(384), (activity.resources.displayMetrics.heightPixels * 0.46f).roundToInt())
            content.addView(list, LinearLayout.LayoutParams(-1, maxHeight.coerceAtLeast(dp(80))))
        }
        val dialog = AlertDialog.Builder(activity, R.style.ReiAnixPlayerMenuDialogTheme)
            .setTitle("Cenas favoritas").setView(content).setNegativeButton("Fechar", null).create()
        present(dialog) { jobs.values.forEach { it.cancel() }; jobs.clear() }
    }

    fun editor(
        mediaId: String,
        durationMs: Long,
        positionMs: Long,
        existing: PlayerLocalMetadataStore.Note?,
        onSave: suspend (Long, Long, String) -> Unit,
        onSaved: () -> Unit,
        onBack: () -> Unit,
    ) {
        if (durationMs <= 0L) { report("Duração indisponível para criar um clipe"); return }
        val initial = FavoriteClipRange.initial(existing?.timestampMs ?: positionMs, durationMs)
        var start = initial.startMs
        var end = existing?.clipEndMs?.takeIf { it > start && it <= durationMs } ?: initial.endMs
        // Preserve the original exact range when editing a valid saved clip.
        if (existing != null && existing.timestampMs in 0 until end) start = existing.timestampMs
        val content = column().apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val preview = thumbnail()
        content.addView(preview, LinearLayout.LayoutParams(-1, dp(132)))
        val durationLabel = label("", 15f)
        val startLabel = label("")
        val endLabel = label("")
        val sliderMax = minOf(durationMs, Int.MAX_VALUE.toLong()).toInt()
        val startSlider = SeekBar(activity).apply { max = sliderMax; contentDescription = "Início do clipe" }
        val endSlider = SeekBar(activity).apply { max = sliderMax; contentDescription = "Fim do clipe" }
        fun asProgress(time: Long) = (time.toDouble() / durationMs * sliderMax).roundToInt().coerceIn(0, sliderMax)
        fun asTime(progress: Int) = (progress.toDouble() / sliderMax * durationMs).roundToLong().coerceIn(0L, durationMs)
        fun render() {
            startLabel.text = "Início: " + format(start)
            endLabel.text = "Fim: " + format(end)
            durationLabel.text = "Duração do clipe: " + format(end - start)
            startSlider.progress = asProgress(start)
            endSlider.progress = asProgress(end)
        }
        var imageJob: Job? = null
        fun updatePreview(time: Long) {
            imageJob?.cancel()
            imageJob = activity.lifecycleScope.launch {
                preview.setImageBitmap(loadFrame(mediaId, time.coerceIn(0L, durationMs - 1L)))
            }
        }
        fun addControl(title: TextView, slider: SeekBar, isStart: Boolean) {
            content.addView(title)
            content.addView(slider)
            val fine = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            fun change(value: Long) {
                if (isStart) start = FavoriteClipRange.editStart(value, end, durationMs)
                else end = FavoriteClipRange.editEnd(value, start, durationMs)
                render()
            }
            for (delta in listOf(-1_000L, 1_000L)) {
                fine.addView(action(if (delta < 0) "−1 s" else "+1 s") {
                    change((if (isStart) start else end) + delta)
                    updatePreview(if (isStart) start else end)
                }, LinearLayout.LayoutParams(0, dp(44), 1f))
            }
            content.addView(fine)
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) change(asTime(progress))
                }
                override fun onStopTrackingTouch(bar: SeekBar) { updatePreview(if (isStart) start else end) }
            })
        }
        addControl(startLabel, startSlider, true)
        addControl(endLabel, endSlider, false)
        content.addView(durationLabel)
        val name = EditText(activity).apply {
            hint = "Nome da cena (opcional)"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            filters = arrayOf(InputFilter.LengthFilter(500))
            setText(existing?.text.orEmpty())
        }
        content.addView(name)
        val scroll = ScrollView(activity).apply { addView(content) }
        val dialog = AlertDialog.Builder(activity, R.style.ReiAnixPlayerMenuDialogTheme)
            .setTitle(if (existing == null) "Salvar cena favorita" else "Editar cena favorita")
            .setView(scroll).setNegativeButton("Voltar") { _, _ -> onBack() }
            .setPositiveButton("Salvar", null).create()
        var saving = false
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (saving) return@setOnClickListener
                saving = true
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                dialog.setCancelable(false)
                dialog.setCanceledOnTouchOutside(false)
                activity.lifecycleScope.launch {
                    try {
                        onSave(start, end, name.text.toString())
                        dialog.dismiss()
                        onSaved()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        saving = false
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                        dialog.setCancelable(true)
                        report("Não foi possível salvar a cena. Verifique o intervalo e o limite de notas.")
                    }
                }
            }
        }
        render()
        present(dialog) { imageJob?.cancel() }
        if (dialog.isShowing) updatePreview(start)
    }
}
