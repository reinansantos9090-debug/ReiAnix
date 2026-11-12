package com.reianix.reianix_local.downloads

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Best-effort codec conversion after the lossless MediaExtractor/MediaMuxer path.
 * Media3 can convert *supported* inputs to H.264/AAC MP4; it is not an AVI/WMV
 * or DRM decoder, and the source is never mislabeled if export fails.
 *
 * A fallback export requires a temporary MP4 because the stable Media3 version
 * accepts a file path, not the selected SAF document's file descriptor.
 */
@OptIn(UnstableApi::class)
internal object DownloadMediaTranscoder {
    private const val TAG="ReiAnixTranscode"
    private const val RESERVE=32L*1024*1024
    private val conversionLock=Mutex()

    suspend fun tryTranscode(context: Context, source: android.net.Uri, expectedBytes: Long): File? =
        conversionLock.withLock {
            val folder=context.externalCacheDir ?: context.cacheDir
            if(expectedBytes<=0L || expectedBytes>Long.MAX_VALUE-RESERVE ||
                StatFs(folder.absolutePath).availableBytes<expectedBytes+RESERVE) return@withLock null
            val output=File(folder,"reianix-transcode-"+UUID.randomUUID()+".mp4")
            var success=false
            try {
                val exported=withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine<Boolean> { continuation ->
                        val listener=object:Transformer.Listener {
                            override fun onCompleted(composition: Composition, result: ExportResult) {
                                if(continuation.isActive) continuation.resume(true)
                            }
                            override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                                Log.i(TAG,"MP4 transcoding unsupported: "+exception.javaClass.simpleName)
                                if(continuation.isActive) continuation.resume(false)
                            }
                        }
                        val transformer=Transformer.Builder(context)
                            .setVideoMimeType(MimeTypes.VIDEO_H264)
                            .setAudioMimeType(MimeTypes.AUDIO_AAC)
                            .addListener(listener)
                            .build()
                        continuation.invokeOnCancellation {
                            Handler(Looper.getMainLooper()).post { transformer.cancel() }
                        }
                        try {
                            transformer.start(MediaItem.fromUri(source),output.absolutePath)
                        } catch(error: Exception) {
                            Log.i(TAG,"MP4 export cannot start: "+error.javaClass.simpleName)
                            if(continuation.isActive) continuation.resume(false)
                        }
                    }
                }
                if(!exported || !output.isFile || output.length()<=0L) return@withLock null
                val verifier=MediaExtractor()
                try {
                    verifier.setDataSource(output.absolutePath)
                    if(verifier.trackCount !in 1..2 ||
                        (0 until verifier.trackCount).none {
                            verifier.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
                        }) return@withLock null
                } finally {verifier.release()}
                success=true
                output
            } catch(cancelled: CancellationException) {
                throw cancelled
            } catch(error: Exception) {
                Log.i(TAG,"Keeping original container: "+error.javaClass.simpleName)
                null
            } finally {
                if(!success) output.delete()
            }
        }
}
