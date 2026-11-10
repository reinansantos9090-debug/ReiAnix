package com.reiflix.reiflix_local.downloads

import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.media3.common.C
import androidx.media3.datasource.*
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/** Only the app can resolve partial references; URLs/authentication never enter media intents. */
class DownloadProvider: ContentProvider() {
    override fun onCreate()=true
    private fun item(uri: Uri): DownloadItem? = uri.pathSegments.firstOrNull()?.let { DownloadStore(requireNotNull(context)).get(it) }
    override fun getType(uri: Uri): String? = item(uri)?.mime
    override fun query(uri: Uri,projection: Array<out String>?,selection: String?,args: Array<out String>?,order: String?): Cursor {
        val item=item(uri) ?: throw FileNotFoundException()
        val names=projection ?: arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
        return MatrixCursor(names).apply { addRow(names.map { when(it) { OpenableColumns.DISPLAY_NAME->item.name; OpenableColumns.SIZE->if(item.total>0)item.total else item.bytes; else->null } }) }
    }
    override fun openFile(uri: Uri,mode: String): ParcelFileDescriptor {
        if(mode!="r") throw FileNotFoundException("Somente leitura")
        val item=item(uri) ?: throw FileNotFoundException()
        if(item.state in setOf("CANCELLED","CORRUPT")) throw FileNotFoundException("Download indisponível")
        if(item.state=="COMPLETE" && item.output.isNotBlank()) return context!!.contentResolver.openFileDescriptor(Uri.parse(item.output),"r") ?: throw FileNotFoundException()
        return ParcelFileDescriptor.open(DownloadStore(context!!).partial(item.id),ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun insert(uri: Uri,values: ContentValues?): Uri?=null
    override fun delete(uri: Uri,selection: String?,args: Array<out String>?)=0
    override fun update(uri: Uri,values: ContentValues?,selection: String?,args: Array<out String>?)=0
}

/** Delegates every ordinary local source unchanged; only our partial URI uses a growing reader. */
class DownloadDataSourceFactory(private val context: Context): DataSource.Factory {
    override fun createDataSource(): DataSource = RoutingSource(context)
    private class RoutingSource(private val context: Context): DataSource {
        private var source: DataSource?=null
        private val listeners=mutableListOf<TransferListener>()
        override fun addTransferListener(listener: TransferListener) { listeners+=listener; source?.addTransferListener(listener) }
        override fun open(dataSpec: DataSpec): Long {
            val owned=dataSpec.uri.authority==context.packageName+".downloads"
            val item=if(owned) dataSpec.uri.pathSegments.firstOrNull()?.let { DownloadStore(context).get(it) } else null
            if(owned && item==null) throw IOException("Download não encontrado")
            source=if(owned && item?.state!="COMPLETE") GrowingSource(context) else DefaultDataSource.Factory(context).createDataSource()
            listeners.forEach { source!!.addTransferListener(it) }
            return source!!.open(if(item?.state=="COMPLETE") dataSpec.withUri(Uri.parse(item.output)) else dataSpec)
        }
        override fun read(buffer: ByteArray,offset: Int,length: Int)=source?.read(buffer,offset,length) ?: C.RESULT_END_OF_INPUT
        override fun getUri(): Uri?=source?.uri
        override fun getResponseHeaders(): Map<String,List<String>> = source?.responseHeaders ?: emptyMap()
        override fun close() { source?.close(); source=null }
    }
    private class GrowingSource(context: Context): BaseDataSource(false) {
        private val store=DownloadStore(context)
        private var file: RandomAccessFile?=null
        private var item: DownloadItem?=null
        private var spec: DataSpec?=null
        private var position=0L
        private var remaining=C.LENGTH_UNSET.toLong()
        @Volatile private var closed=false
        override fun open(dataSpec: DataSpec): Long {
            transferInitializing(dataSpec)
            val id=dataSpec.uri.pathSegments.firstOrNull() ?: throw IOException("Download inválido")
            val current=store.get(id) ?: throw IOException("Download não encontrado")
            if(current.state in setOf("CANCELLED","CORRUPT","ERROR")) throw IOException("Aguarde ou retome o download")
            item=current; spec=dataSpec; closed=false; position=dataSpec.position
            file=RandomAccessFile(store.partial(id),"r")
            file!!.seek(position)
            remaining=if(dataSpec.length!=C.LENGTH_UNSET.toLong()) dataSpec.length else if(current.total>=0) (current.total-position).coerceAtLeast(0) else C.LENGTH_UNSET.toLong()
            transferStarted(dataSpec)
            return remaining
        }
        override fun read(buffer: ByteArray,offset: Int,length: Int): Int {
            if(length==0) return 0
            if(remaining==0L) return C.RESULT_END_OF_INPUT
            val initial=item ?: throw IOException("Fonte fechada")
            val started=android.os.SystemClock.elapsedRealtime()
            while(!closed) {
                if(Thread.currentThread().isInterrupted) throw InterruptedIOException()
                val current=store.get(initial.id) ?: initial
                if(current.generation!=initial.generation) throw IOException("O arquivo foi reiniciado pelo servidor; abra o episódio novamente")
                if(current.state in setOf("CANCELLED","CORRUPT","ERROR")) throw IOException("Download interrompido; confira a aba Downloads")
                val available=(minOf(file!!.length(),current.bytes)-position).coerceAtLeast(0)
                if(available>0) {
                    val n=file!!.read(buffer,offset,minOf(length.toLong(),available,if(remaining>=0)remaining else Long.MAX_VALUE).toInt())
                    if(n>0) { position+=n; if(remaining>=0) remaining-=n; bytesTransferred(n); return n }
                }
                if(current.state=="COMPLETE" || (current.total>=0 && position>=current.total && current.bytes>=current.total)) return C.RESULT_END_OF_INPUT
                if(android.os.SystemClock.elapsedRealtime()-started>60_000) throw IOException("Aguardando mais dados do download. Tente reproduzir novamente quando avançar.")
                try { Thread.sleep(200) } catch(_: InterruptedException) { Thread.currentThread().interrupt(); throw InterruptedIOException() }
            }
            throw InterruptedIOException()
        }
        override fun getUri(): Uri?=spec?.uri
        override fun close() { closed=true; file?.close(); if(spec!=null) transferEnded(); spec=null; file=null }
    }
}
