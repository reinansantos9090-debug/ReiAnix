package com.reiflix.reiflix_local.scanner

import com.reiflix.reiflix_local.storage.NativeBatch
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject

object SafScanner {
    private const val TAG = "[REIFLIX][SAF]"
    const val STATUS_EMPTY_COMPLETE = "EMPTY_COMPLETE"
    const val STATUS_PARTIAL = "PARTIAL"
    const val STATUS_CANCELLED = "CANCELLED"
    const val STATUS_FAILED = "FAILED"
    const val STATUS_REVOKED = "REVOKED"
    const val STATUS_UNAVAILABLE = "UNAVAILABLE"
    const val STATUS_COMPLETED = "COMPLETED"
    // Match Nova's broad local video extension surface; provider MIME values
    // remain authoritative when available.
    private val videoExtensions = setOf(
        "3g2","3gp","3gp2","3gpp","asf","avi","divx","flv","f4v","qt","m4v",
        "mtv","mkv","mp4","mpeg","mpe","mpg","mov","ogm","ogv","ogx","vob","wtv",
        "webm","ts","m2ts","wmv"
    )

    data class TreeIdentity(val treeUri:String,val authority:String,val documentId:String,val volumeId:String,val identity:String)

    fun treeIdentity(treeUri:Uri):TreeIdentity {
        require(treeUri.scheme=="content") { "A árvore SAF precisa usar content://." }
        require(DocumentsContract.isTreeUri(treeUri)) { "A URI SAF não representa uma árvore." }
        val authority=treeUri.authority?.trim().orEmpty()
        require(authority.isNotBlank()) { "A URI SAF não possui autoridade." }
        val documentId=DocumentsContract.getTreeDocumentId(treeUri).trim()
        require(documentId.isNotBlank()) { "A árvore SAF não possui Document ID." }
        val volumeId=if(documentId.contains(":")) documentId.substringBefore(":") else ""
        return TreeIdentity(treeUri.toString(),authority,documentId,volumeId,"saf:"+authority+":"+documentId)
    }

    fun identityPayload(treeUri:Uri):JSONObject {
        val i=treeIdentity(treeUri)
        return JSONObject().put("treeUri",i.treeUri).put("authority",i.authority).put("documentId",i.documentId)
            .put("volumeId",i.volumeId).put("identity",i.identity)
    }

    fun persistPermission(context:Context,uri:Uri,flags:Int) {
        val identity = treeIdentity(uri)
        Log.i(TAG,"LIBRARY_SOURCE_LOADED identity=" + identity.identity)
        val granted=flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(granted and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0) { "A pasta não concedeu permissão persistente de leitura." }
        require(flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) { "O provedor não ofereceu permissão SAF persistente." }
        try { context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch(e:SecurityException) { Log.w(TAG,"Persistable SAF grant rejected",e); throw IllegalStateException("O provedor não permitiu persistir o acesso desta pasta.",e) }
        check(hasPersistedReadPermission(context,uri)) { "A autorização da pasta não foi persistida." }
        val inspection=inspectTree(context,uri,requirePersisted=true)
        check(inspection.optString("status") in setOf(STATUS_COMPLETED,STATUS_EMPTY_COMPLETE)) {
            "A pasta foi autorizada, mas o provedor não está acessível."
        }
    }

    fun hasPersistedReadPermission(context:Context,treeUri:Uri):Boolean {
        val expected=runCatching{treeIdentity(treeUri)}.getOrNull() ?: return false
        return context.contentResolver.persistedUriPermissions.any { p ->
            if(!p.isReadPermission) return@any false
            val got=runCatching{treeIdentity(p.uri)}.getOrNull() ?: return@any false
            got.authority==expected.authority && got.documentId==expected.documentId
        }
    }

    fun inspectTree(context:Context,treeUri:Uri,requirePersisted:Boolean):JSONObject {
        val identity=runCatching{treeIdentity(treeUri)}.getOrElse{
            Log.w(TAG,"LIBRARY_SOURCE_INVALID reason=invalid_tree_uri")
            return JSONObject().put("status",STATUS_FAILED).put("error",it.message?:"invalid_saf_tree")
        }
        val base=identityPayload(treeUri)
        if(requirePersisted && !hasPersistedReadPermission(context,treeUri)) return base.put("status",STATUS_REVOKED).put("error","persisted_permission_missing")
        return try {
            val rootUri=DocumentsContract.buildDocumentUriUsingTree(treeUri,identity.documentId)
            val cursor=context.contentResolver.query(rootUri,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE),null,null,null)
            if(cursor==null) return base.put("status",STATUS_UNAVAILABLE).put("error","provider_query_null")
            val valid=cursor.use { c ->
                if(!c.moveToFirst()) return@use false
                val idCol=c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mimeCol=c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val nameCol=c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if(idCol<0 || mimeCol<0) return@use false
                base.put("name",if(nameCol>=0)c.getString(nameCol).orEmpty() else "").put("mimeType",c.getString(mimeCol).orEmpty())
                c.getString(idCol).orEmpty()==identity.documentId &&
                    c.getString(mimeCol).orEmpty().equals(DocumentsContract.Document.MIME_TYPE_DIR, ignoreCase=true)
            }
            if(valid) base.put("status",STATUS_COMPLETED) else base.put("status",STATUS_UNAVAILABLE).put("error","provider_root_not_readable")
        } catch(e:SecurityException) {
            base.put("status",if(hasPersistedReadPermission(context,treeUri))STATUS_UNAVAILABLE else STATUS_REVOKED).put("error",e.message?:"provider_denied")
        } catch(e:Exception) {
            Log.w(TAG,"SAF tree inspection failed",e); base.put("status",STATUS_UNAVAILABLE).put("error",e.message?:"provider_unavailable")
        }
    }

    private fun normalizedDocumentId(value:String):String = Uri.decode(value).trim().trim('/')
    private fun isDocumentIdWithinTree(treeDocumentId:String, documentId:String):Boolean {
        val root = normalizedDocumentId(treeDocumentId)
        val child = normalizedDocumentId(documentId)
        return root.isNotBlank() && child.isNotBlank() && (child == root || child.startsWith(root + "/"))
    }
    fun isAuthorizedDocumentForTree(context:Context,treeUri:Uri,documentUri:Uri):Boolean {
        val tree = runCatching { treeIdentity(treeUri) }.getOrNull() ?: return false
        if(documentUri.scheme != "content" || !DocumentsContract.isDocumentUri(context,documentUri)) return false
        if(documentUri.authority != tree.authority) return false
        val documentId = runCatching { DocumentsContract.getDocumentId(documentUri) }.getOrNull() ?: return false
        return isDocumentIdWithinTree(tree.documentId, documentId)
    }

    fun isAuthorizedDocument(context:Context,documentUri:Uri):Boolean {
        if(documentUri.scheme!="content" || !DocumentsContract.isDocumentUri(context,documentUri)) return false
        val documentId=runCatching{DocumentsContract.getDocumentId(documentUri)}.getOrNull() ?: return false
        return context.contentResolver.persistedUriPermissions.any { p ->
            if(!p.isReadPermission) return@any false
            val tree=runCatching{treeIdentity(p.uri)}.getOrNull() ?: return@any false
            if(tree.authority!=documentUri.authority) return@any false
            if(!isDocumentIdWithinTree(tree.documentId, documentId)) return@any false
            runCatching{DocumentsContract.buildDocumentUriUsingTree(p.uri,documentId)}.getOrNull()?.let { u ->
                context.contentResolver.query(u,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),null,null,null)?.use{it.moveToFirst()}==true
            } ?: false
        }
    }

    fun displayName(context:Context,treeUri:Uri):String = runCatching{DocumentFile.fromTreeUri(context,treeUri)?.name}.getOrNull()?.trim()?.takeIf{it.isNotBlank()} ?: "Pasta da biblioteca"

    fun scan(context:Context,treeUri:Uri,onProgress:((JSONObject)->Unit)?=null,shouldCancel:()->Boolean={false},scanId:String?=null,onBatch:((JSONObject)->Unit)?=null):JSONObject {
        val identity=treeIdentity(treeUri)
        if(!hasPersistedReadPermission(context,treeUri)){
            Log.w(TAG,"LIBRARY_SOURCE_PERMISSION_LOST identity=" + identity.identity)
            throw IllegalStateException("A permissão desta pasta foi removida.")
        }
        val root=DocumentFile.fromTreeUri(context,treeUri) ?: throw IllegalArgumentException("Árvore SAF indisponível")
        val resolver=context.contentResolver
        val errors=JSONArray()
        val batches=NativeBatch.Accumulator(NativeBatch.DEFAULT_SIZE) { batch,batchId,batchNumber ->
            onBatch?.invoke(
                JSONObject()
                    .put("treeUri", treeUri.toString())
                    .put("batchId", batchId)
                    .put("batchNumber", batchNumber)
                    .put("batchSize", batch.length())
                    .put("documents", batch)
            )
        }
        val stats=JSONObject().put("scanId",scanId ?: "").put("files",0).put("videos",0).put("directories",0).put("excludedNoMedia",0)
            .put("nomediaDirectories",0).put("nomediaFiles",0).put("metadataMissingSize",0).put("metadataMissingModified",0)
            .put("mimeFallbacks",0).put("successfulQueries",0).put("failedQueries",0).put("emptyDirectories",0).put("rejectedOutsideSource",0).put("errors",errors)
        val pending=ArrayDeque<Pair<String,String>>(); val visited=HashSet<String>(); pending.addLast(identity.documentId to "")
        var cancelled=false; var revokedDuringScan=false; var lastFiles=0; var lastDirs=0
        Log.i(TAG,"SCAN_SOURCE_STARTED identity=" + identity.identity)
        onProgress?.invoke(identityPayload(treeUri).put("phase","started").put("source","saf"))
        while(pending.isNotEmpty()){
            if(shouldCancel()){cancelled=true;break}
            val pair=pending.removeLast(); val parentId=pair.first; val currentPath=pair.second
            if(!visited.add(parentId))continue
            stats.put("directories",stats.getInt("directories")+1)
            val childrenUri=runCatching{DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,parentId)}.getOrElse{
                stats.put("failedQueries",stats.getInt("failedQueries")+1); errors.put("Não foi possível abrir a subpasta: "+currentPath); continue
            }
            try{
                val cursor=resolver.query(childrenUri,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED),null,null,null)
                if(cursor==null){stats.put("failedQueries",stats.getInt("failedQueries")+1);errors.put("O provedor SAF não conseguiu listar: "+currentPath);continue}
                cursor.use{c->
                    val idCol=c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID); val nameCol=c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME); val mimeCol=c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeCol=c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE); val modCol=c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    if(idCol<0||nameCol<0||mimeCol<0){stats.put("failedQueries",stats.getInt("failedQueries")+1);errors.put("O provedor SAF não retornou os dados mínimos em: "+currentPath);return@use}
                    stats.put("successfulQueries",stats.getInt("successfulQueries")+1)
                    if(c.count==0){stats.put("emptyDirectories",stats.getInt("emptyDirectories")+1);return@use}
                    data class Child(val id:String,val name:String,val mime:String,val size:Long?,val modified:Long?)
                    val directoriesToVisit=mutableListOf<Pair<String,String>>()
                    val videoChildren=mutableListOf<Child>()
                    var containsNoMediaMarker=false
                    while(c.moveToNext()){
                        val id=c.getString(idCol)?.trim().orEmpty()
                        if(id.isBlank()){stats.put("failedQueries",stats.getInt("failedQueries")+1);errors.put("O provedor SAF retornou um documento sem ID em: "+currentPath);continue}
                        val name=c.getString(nameCol)?.trim().takeUnless{it.isNullOrBlank()} ?: id
                        // SAF roots are explicitly selected by the user for the ReiAnix
                        // library. .nomedia is a MediaStore/gallery hint, not a veto on
                        // files the user deliberately included in this tree. Count it for
                        // diagnostics, but still index sibling videos and recurse into folders.
                        if(name.equals(".nomedia",ignoreCase=true)){
                            containsNoMediaMarker=true
                            stats.put("nomediaFiles",stats.getInt("nomediaFiles")+1)
                            continue
                        }
                        val mime=c.getString(mimeCol)?.trim().takeUnless{it.isNullOrBlank()} ?: "application/octet-stream"
                        val size=if(sizeCol>=0&&!c.isNull(sizeCol))c.getLong(sizeCol)else null
                        val modified=if(modCol>=0&&!c.isNull(modCol))c.getLong(modCol)else null
                        if(mime==DocumentsContract.Document.MIME_TYPE_DIR){
                            directoriesToVisit.add(id to name)
                            continue
                        }
                        stats.put("files",stats.getInt("files")+1)
                        val extension=name.substringAfterLast(".", "").lowercase()
                        val mimeVideo=mime.startsWith("video/")
                        val extensionVideo=extension in videoExtensions
                        if(!mimeVideo&&!extensionVideo)continue
                        videoChildren.add(Child(id,name,mime,size,modified))
                    }
                    if(containsNoMediaMarker){
                        stats.put("nomediaDirectories",stats.getInt("nomediaDirectories")+1)
                    }
                    for((childId,childName) in directoriesToVisit){
                        val relative=if(currentPath.isEmpty())childName else currentPath+"/"+childName
                        if(!isDocumentIdWithinTree(identity.documentId, childId)){
                            stats.put("rejectedOutsideSource",stats.getInt("rejectedOutsideSource")+1)
                            if(Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG,"SCAN_SOURCE_FILE_REJECTED reason=OUTSIDE_SOURCE documentId="+childId)
                            continue
                        }
                        pending.addLast(childId to relative)
                    }
                    for(child in videoChildren){
                        if(shouldCancel()){cancelled=true;break}
                        val relative=if(currentPath.isEmpty())child.name else currentPath+"/"+child.name
                        if(!isDocumentIdWithinTree(identity.documentId, child.id)){
                            stats.put("rejectedOutsideSource",stats.getInt("rejectedOutsideSource")+1)
                            if(Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG,"SCAN_SOURCE_FILE_REJECTED reason=OUTSIDE_SOURCE documentId="+child.id)
                            continue
                        }
                        val childUri=runCatching{DocumentsContract.buildDocumentUriUsingTree(treeUri,child.id)}.getOrElse{stats.put("failedQueries",stats.getInt("failedQueries")+1);errors.put("Não foi possível acessar: "+relative);continue}
                        if(!child.mime.startsWith("video/"))stats.put("mimeFallbacks",stats.getInt("mimeFallbacks")+1)
                        if(child.size==null)stats.put("metadataMissingSize",stats.getInt("metadataMissingSize")+1)
                        if(child.modified==null)stats.put("metadataMissingModified",stats.getInt("metadataMissingModified")+1)
                        batches.add(JSONObject().put("uri",childUri.toString()).put("treeUri",treeUri.toString()).put("documentId",child.id)
                            .put("name",child.name).put("relativePath",relative).put("volumeId",identity.volumeId).put("volumeUuid",identity.volumeId)
                            .put("mimeType",child.mime).put("size",child.size?:JSONObject.NULL).put("modifiedAt",child.modified?:JSONObject.NULL)
                            .put("source","saf").put("scope",identity.identity))
                        stats.put("videos",stats.getInt("videos")+1)
                        if(stats.getInt("files")-lastFiles>=100){lastFiles=stats.getInt("files");onProgress?.invoke(identityPayload(treeUri).put("phase","scanning").put("source","saf").put("directories",stats.getInt("directories")).put("files",lastFiles).put("videos",stats.getInt("videos")).put("currentPath",currentPath))}
                    }
                }
            }catch(e:SecurityException){
                stats.put("failedQueries",stats.getInt("failedQueries")+1)
                errors.put("A autorização do provedor SAF foi negada ao ler: "+currentPath)
                revokedDuringScan=!hasPersistedReadPermission(context,treeUri)
                Log.w(TAG,"SAF SecurityException",e)
            }catch(e:Exception){stats.put("failedQueries",stats.getInt("failedQueries")+1);errors.put("Não foi possível ler: "+currentPath);Log.w(TAG,"SAF provider query failed",e)}
            if(stats.getInt("directories")-lastDirs>=25){lastDirs=stats.getInt("directories");onProgress?.invoke(identityPayload(treeUri).put("phase","scanning").put("source","saf").put("directories",lastDirs).put("files",stats.getInt("files")).put("videos",stats.getInt("videos")).put("currentPath",currentPath))}
        }
        if(shouldCancel())cancelled=true
        batches.flush()
        val failed=stats.getInt("failedQueries"); val successful=stats.getInt("successfulQueries"); val fileCount=stats.getInt("files")
        val status=when{revokedDuringScan->STATUS_REVOKED;cancelled->STATUS_CANCELLED;successful==0&&failed>0->STATUS_UNAVAILABLE;failed>0->STATUS_PARTIAL;fileCount==0->STATUS_EMPTY_COMPLETE;else->STATUS_COMPLETED}
        val partial=status in setOf(STATUS_PARTIAL,STATUS_UNAVAILABLE,STATUS_REVOKED)
        stats.put("status",status).put("emptyComplete",status==STATUS_EMPTY_COMPLETE)
        onProgress?.invoke(identityPayload(treeUri).put("phase","finished").put("source","saf").put("status",status).put("directories",stats.getInt("directories")).put("files",fileCount).put("videos",stats.getInt("videos")).put("cancelled",cancelled))
        Log.i(TAG,"SCAN_SOURCE_COMPLETED identity=" + identity.identity + " status=" + status +
            " files=" + stats.getInt("files") + " accepted=" + stats.getInt("videos") + " ignored=" +
            (stats.getInt("files") - stats.getInt("videos")))
        return identityPayload(treeUri).put("name",root.name?:treeUri.toString()).put("scope",identity.identity)
            .put("stats",stats).put("status",status).put("partial",partial).put("cancelled",cancelled)
    }
}