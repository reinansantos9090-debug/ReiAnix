package com.reiflix.reiflix_local.downloads

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Newly queued downloads use their final public storage volume from the first byte.
 * Previously started private .part files remain readable and resume without a forced
 * copy/migration, and Android <29 retains the prior safe implementation.
 *
 * A persisted transferUri survives process death. No app-private full-episode staging
 * or second copy is made for normal downloads on Android 10+.
 */
internal class DownloadTransferFile(private val context: Context, private val store: DownloadStore, val id: String) {
    val legacyFile: File get() = store.partial(id)
    val uri: Uri? get() = store.get(id)?.json?.optString("transferUri")
        ?.takeIf(String::isNotBlank)?.let(Uri::parse)
    val isPublic: Boolean get() = uri != null

    fun prepare() {
        if(uri != null) return
        val item = store.get(id) ?: throw IOException("Download não encontrado")
        // A previously started private transfer must continue from its committed bytes.
        if ((item.bytes > 0L || legacyFile.length() > 0L) && legacyFile.exists()) return
        if(Build.VERSION.SDK_INT < 29) {
            // Android 7-9 continue using the existing private staging/export
            // path; create the old placeholder only for those devices.
            if(!legacyFile.exists()) legacyFile.createNewFile()
            return
        }
        val destination=item.json.optString("destination")
        val created = if(destination.isNotBlank()) {
            val parent = DocumentFile.fromTreeUri(context, Uri.parse(destination))
                ?: throw IOException("Pasta de download indisponível")
            parent.createFile("application/octet-stream", "ReiAnix-$id.part")?.uri
                ?: throw IOException("Não foi possível criar arquivo na pasta selecionada")
        } else {
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME,".ReiAnix-$id.part")
                put(MediaStore.Downloads.MIME_TYPE,"application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/ReiAnix/")
                put(MediaStore.Downloads.IS_PENDING,1)
            }) ?: throw IOException("Não foi possível iniciar arquivo em Downloads/ReiAnix")
        }
        try {
            // Reject non-seekable cloud/tree providers; pause, ranges and player
            // depend on true positional I/O rather than an app-private fallback.
            context.contentResolver.openFileDescriptor(created,"rw")?.use { descriptor ->
                // The ParcelFileDescriptor owns the FD for this probe.
                FileOutputStream(descriptor.fileDescriptor).channel.position(0)
            } ?: throw IOException("Destino não permite gravação direta")
            store.mutate(id) { it.put("transferUri",created.toString()) }
        } catch(error:Exception) {
            runCatching {context.contentResolver.delete(created,null,null)}
            throw error
        }
    }

    fun open(write: Boolean=true): DownloadRandomFile {
        val target=uri
        return if(target!=null) {
            val descriptor=context.contentResolver.openFileDescriptor(target,if(write) "rw" else "r")
                ?: throw IOException("O armazenamento público não está acessível")
            DownloadRandomFile.openDescriptor(descriptor,write)
        } else DownloadRandomFile.openLegacy(legacyFile,write)
    }

    fun readUri(): Uri = uri ?: Uri.fromFile(legacyFile)
    fun inputStream(): InputStream = uri?.let {
        context.contentResolver.openInputStream(it) ?: throw IOException("Falha ao ler download")
    } ?: FileInputStream(legacyFile)
    fun size(): Long = open(false).use {it.length()}
    fun sizeOf(candidate: Uri):Long {
        return context.contentResolver.openFileDescriptor(candidate,"r")?.use { descriptor ->
            val stat=descriptor.statSize
            if(stat>=0) stat else FileInputStream(descriptor.fileDescriptor).channel.use {it.size()}
        } ?: throw IOException("Não foi possível confirmar o tamanho final")
    }
    fun discardSource() {
        uri?.let {runCatching {context.contentResolver.delete(it,null,null)}} ?: legacyFile.delete()
    }

    fun sibling(suffix: String): Uri {
        val current=store.get(id) ?: throw IOException("Download não encontrado")
        val destination=current.json.optString("destination")
        val siblingName="ReiAnix-$id.$suffix"
        return if(destination.isNotBlank()) {
            val parent=DocumentFile.fromTreeUri(context,Uri.parse(destination))
                ?: throw IOException("Pasta de download indisponível")
            parent.createFile("video/mp4",siblingName)?.uri
                ?: throw IOException("Não foi possível criar MP4 temporário")
        } else {
            if(Build.VERSION.SDK_INT<29) throw IOException("Destino público indisponível")
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME,".$siblingName")
                put(MediaStore.Downloads.MIME_TYPE,"video/mp4")
                put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/ReiAnix/")
                put(MediaStore.Downloads.IS_PENDING,1)
            }) ?: throw IOException("Não foi possível criar MP4 temporário")
        }
    }

    fun publish(candidate: Uri, name: String, mime: String): Uri {
        if(candidate.authority==MediaStore.AUTHORITY && Build.VERSION.SDK_INT>=29) {
            val changed=context.contentResolver.update(candidate,ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME,name)
                put(MediaStore.Downloads.MIME_TYPE,mime)
                put(MediaStore.Downloads.IS_PENDING,0)
            },null,null)
            if(changed<=0) throw IOException("Não foi possível publicar o vídeo no armazenamento público")
            return candidate
        }
        if(candidate.scheme=="content") {
            return DocumentsContract.renameDocument(context.contentResolver,candidate,name)
                ?: throw IOException("Não foi possível renomear o arquivo na pasta selecionada")
        }
        return candidate
    }
    fun discard(uri: Uri) {
        runCatching {context.contentResolver.delete(uri,null,null)}
    }
}

/** Seekable descriptor for both MediaStore and older app-private partial files. */
internal class DownloadRandomFile private constructor(
    private val legacy: RandomAccessFile?,
    private val descriptor: ParcelFileDescriptor?,
    private val stream: Closeable?,
    private val channel: FileChannel,
): Closeable {
    companion object {
        fun openLegacy(file: File,write: Boolean): DownloadRandomFile {
            val random=RandomAccessFile(file,if(write) "rw" else "r")
            return DownloadRandomFile(random,null,null,random.channel)
        }
        fun openDescriptor(pfd: ParcelFileDescriptor,write: Boolean): DownloadRandomFile {
            val handle: Closeable = if(write) FileOutputStream(pfd.fileDescriptor) else FileInputStream(pfd.fileDescriptor)
            val channel=if(write) (handle as FileOutputStream).channel else (handle as FileInputStream).channel
            return DownloadRandomFile(null,pfd,handle,channel)
        }
    }
    fun length(): Long = channel.size()
    fun setLength(length: Long) {
        require(length>=0)
        if(length<channel.size()) channel.truncate(length)
        else if(length>channel.size()) {
            channel.position(length-1)
            channel.write(ByteBuffer.wrap(byteArrayOf(0)))
        }
    }
    fun seek(position: Long) {channel.position(position)}
    val filePointer: Long get() = channel.position()
    fun write(bytes: ByteArray,offset: Int=0,length: Int=bytes.size-offset) {
        val buffer=ByteBuffer.wrap(bytes,offset,length)
        while(buffer.hasRemaining()) {
            if(channel.write(buffer)<=0) throw IOException("Destino parou de aceitar dados")
        }
    }
    fun read(bytes: ByteArray,offset: Int,length: Int):Int =
        channel.read(ByteBuffer.wrap(bytes,offset,length))
    fun sync() {channel.force(true)}
    override fun close() {
        // The stream/channel and ParcelFileDescriptor share the native FD.
        // Closing one may implicitly close the others; clean up every owner.
        runCatching {channel.close()}
        runCatching {stream?.close()}
        runCatching {legacy?.close()}
        runCatching {descriptor?.close()}
    }
}
