package com.reiflix.reiflix_local.scanner

import com.reiflix.reiflix_local.storage.NativeBatch
import com.reiflix.reiflix_local.storage.NativeIndex
import com.reiflix.reiflix_local.storage.StorageAuthorization
import com.reiflix.reiflix_local.storage.MediaAccessLevel
import android.Manifest
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

object MediaStoreScanner {
    const val SOURCE = "mediastore:external:video"
    data class LibraryScope(val authority: String, val volumeId: String, val treeDocumentId: String)
    const val DISPLAY_NAME = "Vídeos do dispositivo"
    private const val TAG = "[REIFLIX][MEDIASTORE]"
    // Match Nova's broad local video extension surface; provider MIME values
    // remain authoritative when available.
    private val videoExtensions = setOf(
        "3g2","3gp","3gp2","3gpp","asf","avi","divx","flv","f4v","qt","m4v",
        "mtv","mkv","mp4","mpeg","mpe","mpg","mov","ogm","ogv","ogx","vob","wtv",
        "webm","ts","m2ts","wmv"
    )
    private val mediaStoreChanged = AtomicBoolean(false)
    @Volatile private var changeObserver: ContentObserver? = null

    @Synchronized
    fun startChangeObserver(context: Context, onChanged: (() -> Unit)? = null) {
        if (changeObserver != null) return
        val uri = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, changedUri: Uri?) {
                mediaStoreChanged.set(true)
                onChanged?.invoke()
            }
        }
        context.contentResolver.registerContentObserver(uri, true, observer)
        changeObserver = observer
    }

    @Synchronized
    fun stopChangeObserver(context: Context) {
        changeObserver?.let { observer ->
            runCatching { context.contentResolver.unregisterContentObserver(observer) }
        }
        changeObserver = null
    }

    fun clearChangeNotification() { mediaStoreChanged.set(false) }
    fun consumeChangeNotification(): Boolean = mediaStoreChanged.getAndSet(false)

    fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        Build.VERSION.SDK_INT >= 23 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> emptyArray()
    }
    private fun has(context: Context,p:String)=context.checkSelfPermission(p)==android.content.pm.PackageManager.PERMISSION_GRANTED

    fun accessLevelValue(context: Context): MediaAccessLevel =
        StorageAuthorization.mediaAccess(
            Build.VERSION.SDK_INT,
            readExternalStorage = Build.VERSION.SDK_INT in 23..32 &&
                has(context, Manifest.permission.READ_EXTERNAL_STORAGE),
            readMediaVideo = Build.VERSION.SDK_INT >= 33 &&
                has(context, Manifest.permission.READ_MEDIA_VIDEO),
            readSelectedVisualMedia = Build.VERSION.SDK_INT >= 34 &&
                has(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
        )

    fun hasReadPermission(context: Context): Boolean =
        StorageAuthorization.canScanMediaStore(accessLevelValue(context))

    fun accessLevel(context: Context): String = when (accessLevelValue(context)) {
        MediaAccessLevel.FULL -> "full"
        MediaAccessLevel.PARTIAL -> "partial"
        MediaAccessLevel.DENIED -> "denied"
    }
    fun isAuthorizedDocument(context: Context,uri:Uri):Boolean {
        if(uri.scheme!="content"||uri.authority!=MediaStore.AUTHORITY||!hasReadPermission(context))return false
        return runCatching{context.contentResolver.query(uri,arrayOf(MediaStore.Video.Media._ID),null,null,null)?.use{it.moveToFirst()}==true}.getOrDefault(false)
    }
    private fun volumeUuid(context: Context, volumeName: String): String {
        if(Build.VERSION.SDK_INT<24)return ""
        val manager=context.getSystemService(StorageManager::class.java) ?: return ""
        return manager.storageVolumes.firstOrNull {
            (Build.VERSION.SDK_INT>=30&&it.mediaStoreVolumeName==volumeName) ||
                (Build.VERSION.SDK_INT<30&&it.isPrimary&&volumeName==MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }?.uuid.orEmpty()
    }
    private fun normalizeScopePath(documentId:String):String {
        val decoded = Uri.decode(documentId)
        return decoded.substringAfter(':', decoded).trim().trim('/').trimEnd('/')
    }
    private fun volumeMatches(scope:LibraryScope, volumeName:String, volumeUuid:String):Boolean {
        val requested = scope.volumeId.trim()
        if(requested.equals("primary", ignoreCase=true) || requested.equals(MediaStore.VOLUME_EXTERNAL_PRIMARY, ignoreCase=true)){
            return volumeName.equals(MediaStore.VOLUME_EXTERNAL_PRIMARY, ignoreCase=true)
        }
        return requested.equals(volumeName, ignoreCase=true) || (volumeUuid.isNotBlank() && requested.equals(volumeUuid, ignoreCase=true))
    }
    private fun isWithinLibraryScope(scope:LibraryScope, volumeName:String, volumeUuid:String, relativePath:String):Boolean {
        if(scope.authority != "com.android.externalstorage.documents") return false
        if(!volumeMatches(scope, volumeName, volumeUuid)) return false
        val root = normalizeScopePath(scope.treeDocumentId)
        if(root.isBlank()) return false
        val candidate = relativePath.trim().trim('/').replace("\\", "/")
        return candidate == root || candidate.startsWith(root + "/")
    }
    fun scan(context: Context,onProgress:((JSONObject)->Unit)?=null,shouldCancel:()->Boolean={false},scanId:String?=null,onBatch:((JSONObject)->Unit)?=null,libraryScopes:Collection<LibraryScope> = emptyList()):JSONObject {
        check(hasReadPermission(context)){"Permissão de vídeos não concedida."}
        val authorizedScopes = libraryScopes.toList()
        if(authorizedScopes.isEmpty()){
            Log.w(TAG,"SCAN_SOURCE_REJECTED reason=NO_CONFIGURED_LIBRARY_SOURCE")
            onProgress?.invoke(JSONObject().put("phase","blocked").put("source",SOURCE).put("reason","NO_CONFIGURED_LIBRARY_SOURCE"))
            return JSONObject().put("source",SOURCE).put("name",DISPLAY_NAME).put("volumeScopes",JSONArray())
                .put("stats",JSONObject().put("files",0).put("videos",0).put("errors",JSONArray()).put("access",accessLevel(context)).put("status","BLOCKED").put("rejection","NO_CONFIGURED_LIBRARY_SOURCE"))
                .put("partial",false).put("cancelled",false)
        }
        val resolver=context.contentResolver
        val volumeNames=if(Build.VERSION.SDK_INT>=29)MediaStore.getExternalVolumeNames(context).ifEmpty{setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY)}else setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection=mutableListOf(MediaStore.Video.Media._ID,MediaStore.Video.Media.DISPLAY_NAME,MediaStore.Video.Media.MIME_TYPE,MediaStore.Video.Media.SIZE,MediaStore.Video.Media.DATE_MODIFIED)
        if(Build.VERSION.SDK_INT>=29){projection+=MediaStore.Video.Media.RELATIVE_PATH;projection+=MediaStore.MediaColumns.VOLUME_NAME}
        if(Build.VERSION.SDK_INT>=30){projection+=MediaStore.MediaColumns.GENERATION_ADDED;projection+=MediaStore.MediaColumns.GENERATION_MODIFIED}
        val volumeScopes=JSONArray();val errors=JSONArray()
        val access=accessLevel(context)
        val accessState = accessLevelValue(context)
        check(StorageAuthorization.canScanMediaStore(accessState)) { "Permissão de vídeos não concedida." }
        var files=0;var videos=0;var cancelled=false
        var waitingForMediaStore=false
        val volumeUuidCache = HashMap<String, String>()
        clearChangeNotification()
        onProgress?.invoke(JSONObject().put("phase","started").put("source",SOURCE).put("files",0).put("videos",0))
        for(volumeName in volumeNames){
            if(shouldCancel()){cancelled=true;break}
            var activeGeneration=0L
            var activeScopeKey="mediastore:"+volumeName
            try{
                val version=if(Build.VERSION.SDK_INT>=29)runCatching{MediaStore.getVersion(context,volumeName)}.getOrDefault("") else ""
                val generation=if(Build.VERSION.SDK_INT>=30)runCatching{MediaStore.getGeneration(context,volumeName)}.getOrDefault(0L) else 0L
                var volumeWaitingForMediaStore=false
                val scopeKey=activeScopeKey
                val baseVolumeUuid = volumeUuidCache.getOrPut(volumeName) { volumeUuid(context, volumeName) }
                if(NativeIndex.canReuseMediaStoreVolume(context,volumeName,access,version,generation)){
                    val cachedGeneration=NativeIndex.cachedGeneration(context,scopeKey)
                    val cachedCount=NativeIndex.forEachCachedBatch(context,scopeKey,NativeBatch.DEFAULT_SIZE) { batch,batchNumber ->
                        onBatch?.invoke(JSONObject()
                            .put("volumeId",volumeName)
                            .put("batchId","reused:" + NativeIndex.generationId(SOURCE,scopeKey,cachedGeneration) + ":" + batchNumber)
                            .put("batchNumber",batchNumber)
                            .put("batchSize",batch.length())
                            .put("reused",true)
                            .put("documents",batch))
                    }
                    files+=cachedCount;videos+=cachedCount
                    volumeScopes.put(JSONObject().put("volumeId",volumeName).put("scanGeneration",cachedGeneration).put("complete",true).put("reused",true).put("status",if(cachedCount==0) NativeIndex.STATUS_EMPTY_COMPLETE else NativeIndex.STATUS_COMPLETED)
                        .put("generationId",NativeIndex.generationId(SOURCE,scopeKey,cachedGeneration))
                        .put("scanId",(scanId ?: "") + ":" + volumeName)
                        .put("scopeKind","volume").put("scopeRef",volumeName)
                        .put("batchCount",if(cachedCount==0) 0 else (cachedCount + NativeBatch.DEFAULT_SIZE - 1) / NativeBatch.DEFAULT_SIZE)
                        .put("processed",cachedCount).put("duplicates",0).put("removed",0))
                    onProgress?.invoke(JSONObject().put("phase","reused").put("source",SOURCE).put("volumeId",volumeName).put("files",files).put("videos",videos))
                    continue
                }
                val scopeMetadata=JSONObject().put("mediaStoreVersion",version).put("mediaStoreGeneration",generation).put("scanId",scanId ?: "")
                    .put("accessLevel",access).put("volumeId",volumeName)
                val generationId=NativeIndex.startGeneration(context,SOURCE,scopeKey,scopeMetadata)
                activeGeneration=generationId
                val localErrors=JSONArray()
                val batches=NativeBatch.Accumulator(NativeBatch.DEFAULT_SIZE) { batch,batchId,batchNumber ->
                    onBatch?.invoke(JSONObject()
                        .put("volumeId",volumeName)
                        .put("generation",generationId)
                        .put("batchId",batchId)
                        .put("batchNumber",batchNumber)
                        .put("batchSize",batch.length())
                        .put("reused",false)
                        .put("documents",batch))
                }
                var localCancelled=false
                var volumeVideos=0
                val collection=if(Build.VERSION.SDK_INT>=29)MediaStore.Video.Media.getContentUri(volumeName)else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                resolver.query(collection,projection.toTypedArray(),null,null,MediaStore.Video.Media.DISPLAY_NAME+" COLLATE NOCASE ASC")?.use{cursor->
                    val idCol=cursor.getColumnIndex(MediaStore.Video.Media._ID);val nameCol=cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                    val mimeCol=cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE);val sizeCol=cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                    val modCol=cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED);val relCol=if(Build.VERSION.SDK_INT>=29)cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)else -1
                    val volCol=if(Build.VERSION.SDK_INT>=29)cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)else -1
                    val gaCol=if(Build.VERSION.SDK_INT>=30)cursor.getColumnIndex(MediaStore.MediaColumns.GENERATION_ADDED)else -1
                    val gmCol=if(Build.VERSION.SDK_INT>=30)cursor.getColumnIndex(MediaStore.MediaColumns.GENERATION_MODIFIED)else -1
                    if(idCol<0||nameCol<0)localErrors.put("O MediaStore não retornou os dados necessários.")else while(cursor.moveToNext()){
                        if(shouldCancel()){cancelled=true;localCancelled=true;break}
                        files++;val id=cursor.getLong(idCol);val name=cursor.getString(nameCol)?:"video-"+id
                        val mime=if(mimeCol>=0&&!cursor.isNull(mimeCol))cursor.getString(mimeCol) else "video/*"
                        val ext=name.substringAfterLast('.',"").lowercase();if(!mime.startsWith("video/")&&ext !in videoExtensions)continue
                        val relDir=if(relCol>=0&&!cursor.isNull(relCol))cursor.getString(relCol).orEmpty().trimEnd('/')else ""
                        val rel=if(relDir.isBlank())name else relDir+"/"+name
                        val actualVol=if(volCol>=0&&!cursor.isNull(volCol))cursor.getString(volCol)else volumeName
                        val actualVolumeUuid = if(actualVol == volumeName) {
                            baseVolumeUuid
                        } else {
                            volumeUuidCache.getOrPut(actualVol) { volumeUuid(context, actualVol) }
                        }
                        if(!authorizedScopes.any { scope -> isWithinLibraryScope(scope, actualVol, actualVolumeUuid, rel) }){
                            if(Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG,"SCAN_SOURCE_FILE_REJECTED reason=OUTSIDE_SOURCE volume="+actualVol+" relativePath="+rel)
                            continue
                        }
                        val uri=if(Build.VERSION.SDK_INT>=29)MediaStore.Video.Media.getContentUri(actualVol,id)else android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,id)
                        val item=JSONObject().put("uri",uri.toString()).put("name",name).put("relativePath",rel).put("volumeName",actualVol).put("volumeId",actualVol).put("volumeUuid",actualVolumeUuid)
                            .put("mediaId",id).put("mimeType",mime).put("size",if(sizeCol>=0&&!cursor.isNull(sizeCol))cursor.getLong(sizeCol)else 0L).put("modifiedAt",if(modCol>=0&&!cursor.isNull(modCol))cursor.getLong(modCol)*1000L else 0L)
                        if(gaCol>=0&&!cursor.isNull(gaCol))item.put("generationAdded",cursor.getLong(gaCol))
                        if(gmCol>=0&&!cursor.isNull(gmCol))item.put("generationModified",cursor.getLong(gmCol))
                        batches.add(item);videos++;volumeVideos++
                        if(videos%100==0)onProgress?.invoke(JSONObject().put("phase","scanning").put("source",SOURCE).put("volumeId",volumeName).put("files",files).put("videos",videos))
                    }
                }?:localErrors.put("O MediaStore não conseguiu consultar o volume "+volumeName+".")
                if(!localCancelled && !cancelled && localErrors.length()==0 && StorageAuthorization.canReconcileMediaStore(accessState)) {
                    val postVersion=if(Build.VERSION.SDK_INT>=29)runCatching{MediaStore.getVersion(context,volumeName)}.getOrDefault(version) else version
                    val postGeneration=if(Build.VERSION.SDK_INT>=30)runCatching{MediaStore.getGeneration(context,volumeName)}.getOrDefault(generation) else generation
                    val changedDuringQuery=consumeChangeNotification()
                    if(changedDuringQuery || postVersion!=version || (Build.VERSION.SDK_INT>=30 && postGeneration!=generation)) {
                        volumeWaitingForMediaStore=true
                    } else if(volumeVideos==0) {
                        // Do not use a lifecycle sleep as a synchronization primitive. Re-check the
                        // provider's own version/generation immediately; if it is still changing,
                        // this volume is not safe for destructive reconciliation.
                        if(shouldCancel()){cancelled=true;localCancelled=true}
                        val stableVersion=if(Build.VERSION.SDK_INT>=29)runCatching{MediaStore.getVersion(context,volumeName)}.getOrDefault(postVersion) else postVersion
                        val stableGeneration=if(Build.VERSION.SDK_INT>=30)runCatching{MediaStore.getGeneration(context,volumeName)}.getOrDefault(postGeneration) else postGeneration
                        val changedAfterWait=consumeChangeNotification()
                        val probeHasMedia=if(!localCancelled && !cancelled && localErrors.length()==0){
                            runCatching{
                                resolver.query(collection,arrayOf(MediaStore.Video.Media._ID),null,null,null)?.use{probe->probe.moveToFirst()} ?: false
                            }.getOrElse{
                                localErrors.put("O MediaStore não conseguiu concluir a verificação de estabilidade do volume "+volumeName+".")
                                false
                            }
                        } else false
                        if(changedAfterWait || stableVersion!=postVersion || (Build.VERSION.SDK_INT>=30 && stableGeneration!=postGeneration) || probeHasMedia){
                            volumeWaitingForMediaStore=true
                        }
                    }
                }
                if(volumeWaitingForMediaStore) waitingForMediaStore=true
                for(i in 0 until localErrors.length())errors.put(localErrors.getString(i))
                if(shouldCancel()){cancelled=true;localCancelled=true}
                val complete=StorageAuthorization.canReconcileMediaStore(accessState) &&
                    localErrors.length()==0 && !localCancelled && !cancelled && !volumeWaitingForMediaStore
                val status=when {
                    localCancelled||cancelled->NativeIndex.STATUS_CANCELLED
                    volumeWaitingForMediaStore->NativeIndex.STATUS_WAITING_FOR_MEDIASTORE
                    !complete->NativeIndex.STATUS_PARTIAL
                    else->NativeIndex.STATUS_COMPLETED
                }
                batches.flush()
                val stagedDocuments=NativeIndex.stagedDocumentCount(context,SOURCE,scopeKey,generationId)
                val batchCount=NativeIndex.batchCount(context,SOURCE,scopeKey,generationId)
                val finished=NativeIndex.finishGeneration(
                    context,SOURCE,scopeKey,generationId,status,
                    JSONObject(scopeMetadata.toString()).put("errors",localErrors).put("status",status)
                        .put("batchCount",batchCount).put("processed",stagedDocuments)
                )
                volumeScopes.put(JSONObject().put("volumeId",volumeName).put("scanGeneration",generationId).put("generationId",NativeIndex.generationId(SOURCE, scopeKey, generationId)).put("status",status)
                    .put("complete",complete).put("reused",false).put("batchCount",batchCount).put("processed",stagedDocuments)
                    .put("duplicates",finished.optInt("duplicates",0)).put("removed",0).put("errors",localErrors))
                if(cancelled)break
            }catch(security:SecurityException){
                Log.w(TAG,"MediaStore permission/query denied for volume $volumeName",security)
                errors.put("O acesso ao volume $volumeName foi negado.")
                if(activeGeneration>0) NativeIndex.finishGeneration(context,SOURCE,activeScopeKey,activeGeneration,NativeIndex.STATUS_FAILED,JSONObject().put("error",security.message ?: "MediaStore permission/query denied"))
            }catch(exception:Exception){
                Log.w(TAG,"MediaStore query failed for volume $volumeName",exception)
                errors.put("Não foi possível consultar o volume $volumeName.")
                if(activeGeneration>0) NativeIndex.finishGeneration(context,SOURCE,activeScopeKey,activeGeneration,NativeIndex.STATUS_FAILED,JSONObject().put("error",exception.message ?: "MediaStore query failed"))
            }
        }
        onProgress?.invoke(JSONObject().put("phase","finished").put("source",SOURCE).put("files",files).put("videos",videos))
        return JSONObject().put("source",SOURCE).put("name",DISPLAY_NAME).put("volumeScopes",volumeScopes)
            .put("stats",JSONObject().put("files",files).put("videos",videos).put("errors",errors).put("access",access)
                .put("canScan", StorageAuthorization.canScanMediaStore(accessState))
                .put("canReconcile", StorageAuthorization.canReconcileMediaStore(accessState))
                .put("status", when { cancelled -> "cancelled"; waitingForMediaStore -> NativeIndex.STATUS_WAITING_FOR_MEDIASTORE; errors.length()>0 -> "partial"; access!="full" -> "partial"; else -> "completed" })
                .put("waitingForMediaStore", waitingForMediaStore))
            .put("partial",errors.length()>0||cancelled||waitingForMediaStore||access!="full").put("cancelled",cancelled)
    }
}
