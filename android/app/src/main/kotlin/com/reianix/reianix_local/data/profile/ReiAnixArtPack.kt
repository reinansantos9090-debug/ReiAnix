package com.reianix.reianix_local.data.profile

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

internal data class PersonalArt(val folder: String, val id: String, val title: String) {
    val path get() = "personalization/$folder/$id.webp"
}

internal object ReiAnixArtPack {
    val banners = listOf(
        "chuva_neon" to "Chuva neon", "cidade_dourada" to "Cidade dourada",
        "eclipse_rubro" to "Eclipse rubro", "floresta_encantada" to "Floresta encantada",
        "montanha_nevada" to "Montanha nevada", "quarto_gamer" to "Quarto gamer",
        "ruinas_submersas" to "Ruínas submersas", "sakura_lunar" to "Sakura lunar",
        "santuario_celestial" to "Santuário celestial", "trem_de_outono" to "Trem de outono",
    ).map { PersonalArt("banners", it.first, it.second) }
    val wallpapers = listOf(
        "cafe_chuvoso" to "Café chuvoso", "caminho_sakura" to "Caminho sakura",
        "cascatas_misticas" to "Cascatas místicas", "cidade_cyberpunk" to "Cidade cyberpunk",
        "costa_ao_entardecer" to "Costa ao entardecer", "crepusculo_urbano" to "Crepúsculo urbano",
        "magia_violeta" to "Magia violeta", "montanhas_estreladas" to "Montanhas estreladas",
        "vale_dos_templos" to "Vale dos templos", "viajante_lunar" to "Viajante lunar",
    ).map { PersonalArt("wallpapers", it.first, it.second) }
    private val all = banners + wallpapers
    private val allowed = all.associateBy { it.path }
    private val mutex = Mutex()
    private fun directory(context: Context) = File(context.filesDir, "reianix_art_pack_v1")

    suspend fun available(context: Context, banner: Boolean): List<PersonalArt> = withContext(Dispatchers.IO) {
        (if (banner) banners else wallpapers).filter {
            File(directory(context), it.path).isFile || runCatching {
                context.assets.open(it.path).use { true }
            }.getOrDefault(false)
        }
    }

    // Called on an IO worker by the editor/store.
    fun open(context: Context, art: PersonalArt): InputStream {
        require(allowed[art.path] == art)
        val imported = File(directory(context), art.path)
        return if (imported.isFile) imported.inputStream() else context.assets.open(art.path)
    }

    fun model(context: Context, art: PersonalArt): Any {
        val imported = File(directory(context), art.path)
        return if (imported.isFile) imported else "file:///android_asset/" + art.path
    }

    /** Imports only the 20 named images, never arbitrary archive paths or executable data. */
    suspend fun install(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val target = directory(context)
            val staging = File(context.filesDir, "art_stage_" + UUID.randomUUID())
            val backup = File(context.filesDir, "art_backup_" + UUID.randomUUID())
            check(staging.mkdirs())
            try {
                val input = context.contentResolver.openInputStream(uri) ?: error("Arquivo indisponível.")
                input.use { extract(it, staging) }
                if (target.exists()) check(target.renameTo(backup)) { "Não foi possível substituir o pacote." }
                if (!staging.renameTo(target)) {
                    if (backup.exists()) backup.renameTo(target)
                    error("Não foi possível instalar o pacote.")
                }
                backup.deleteRecursively()
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    internal fun extract(input: InputStream, staging: File) {
        val seen = HashSet<String>()
        var entries = 0
        var total = 0L
        ZipInputStream(input).use { zip ->
            val buffer = ByteArray(8192)
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                require(entries <= 64) { "Pacote inválido." }
                val name = entry.name.removePrefix("android/app/src/main/assets/")
                val art = allowed[name]
                require(entry.isDirectory || art != null || name == "README.txt") { "Arquivo não reconhecido." }
                val output = art?.let {
                    require(!entry.isDirectory && seen.add(name)) { "Imagem duplicada." }
                    File(staging, it.path).also { file -> check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true) }
                }
                var size = 0L
                val stream = output?.outputStream()
                try {
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        size += n; total += n
                        require(size <= 1_500_000 && total <= 12_000_000) { "Pacote grande demais." }
                        stream?.write(buffer, 0, n)
                    }
                } finally { stream?.close() }
                if (output != null && art != null) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(output.absolutePath, bounds)
                    require(bounds.outMimeType == "image/webp" && bounds.outWidth in 1..2000 && bounds.outHeight in 1..2000)
                    require(if (art.folder == "banners") bounds.outWidth >= bounds.outHeight * 2 else bounds.outHeight > bounds.outWidth)
                }
                zip.closeEntry()
            }
        }
        require(seen == allowed.keys) { "O pacote precisa conter todas as 20 artes." }
    }
}
