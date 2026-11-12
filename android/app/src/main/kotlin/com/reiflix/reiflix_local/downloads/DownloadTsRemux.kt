package com.reiflix.reiflix_local.downloads

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Optional container-only remux. MPEG-TS -> MP4 is supported only for formats
 * that Android's platform MediaExtractor and MediaMuxer both expose without
 * decoding. No FFmpeg/native binaries, no codec transcoding, no loss of quality.
 *
 * The original .part is never mutated; playback while downloading is unchanged.
 * On ANY unsupported track, disk pressure or bad timestamp, return null and
 * publish the fully downloaded TS intact.
 */
internal object DownloadTsRemux {
    private const val TAG="ReiAnixTsRemux"
    private const val BUFFER_BYTES=4*1024*1024
    private const val RESERVE_BYTES=24L*1024*1024

    fun compatibleTracks(mimes: List<String>, sdk: Int = Build.VERSION.SDK_INT): Boolean {
        if(mimes.isEmpty() || mimes.size>2) return false
        val videos=mimes.count { it=="video/avc" || (sdk>=24 && it=="video/hevc") }
        val audios=mimes.count { it=="audio/mp4a-latm" }
        return videos==1 && videos+audios==mimes.size
    }

    /**
     * Remux a public pending MPEG-TS item directly to another pending public item.
     * Never writes a second full-sized episode inside the app data directory.
     * The caller owns and deletes the candidate URI on failure.
     */
    suspend fun tryRemux(context: Context, input: android.net.Uri, output: android.net.Uri): Boolean {
        if(Build.VERSION.SDK_INT<26) return false
        var success=false
        try {
            context.contentResolver.openFileDescriptor(input,"r")?.use { inDescriptor ->
                val length=inDescriptor.statSize
                if(length in 0 until 256L*1024) return false
                val extractor=MediaExtractor()
                var muxer:MediaMuxer?=null
                var started=false
                try {
                    extractor.setDataSource(inDescriptor.fileDescriptor)
                    val count=extractor.trackCount
                    val formats=(0 until count).map(extractor::getTrackFormat)
                    val mimes=formats.map { it.getString(MediaFormat.KEY_MIME).orEmpty().lowercase() }
                    if(!compatibleTracks(mimes)) return false
                    context.contentResolver.openFileDescriptor(output,"rw")?.use { outDescriptor ->
                        val activeMuxer=MediaMuxer(outDescriptor.fileDescriptor,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        muxer=activeMuxer
                        val tracks=formats.map(activeMuxer::addTrack)
                        formats.indices.forEach(extractor::selectTrack)
                        activeMuxer.start()
                        started=true
                        val buffer=ByteBuffer.allocateDirect(BUFFER_BYTES)
                        val info=MediaCodec.BufferInfo()
                        val lastTime=LongArray(count) {-1L}
                        val samples=IntArray(count)
                        var copied=0L
                        while(true) {
                            currentCoroutineContext().ensureActive()
                            val track=extractor.sampleTrackIndex
                            if(track<0) break
                            if(track !in 0 until count) error("Faixa fora do limite")
                            buffer.clear()
                            val declared=extractor.sampleSize
                            require(declared in 1..BUFFER_BYTES.toLong()) {"Amostra incompatível"}
                            val size=extractor.readSampleData(buffer,0)
                            if(size<0) break
                            require(size.toLong()==declared) {"Amostra truncada"}
                            val timestamp=extractor.sampleTime
                            require(timestamp>=0 && timestamp>=lastTime[track]) {"Timestamps incompatíveis"}
                            lastTime[track]=timestamp
                            info.set(0,size,timestamp,extractor.sampleFlags)
                            activeMuxer.writeSampleData(tracks[track],buffer,info)
                            samples[track]++
                            copied+=size.toLong()
                            if(!extractor.advance())break
                        }
                        require(samples.isNotEmpty() && samples.all{it>0} && copied>0L)
                        activeMuxer.stop()
                        started=false
                        activeMuxer.release()
                        muxer=null
                    } ?: return false
                } finally {
                    if(started) runCatching{muxer?.stop()}
                    runCatching{muxer?.release()}
                    extractor.release()
                }
            } ?: return false
            context.contentResolver.openFileDescriptor(output,"r")?.use { candidate ->
                if(candidate.statSize==0L) return false
                val verification=MediaExtractor()
                try {
                    verification.setDataSource(candidate.fileDescriptor)
                    require(verification.trackCount in 1..2)
                    require((0 until verification.trackCount).any {
                        verification.getTrackFormat(it).getString(MediaFormat.KEY_MIME) in listOf("video/avc","video/hevc")
                    })
                } finally {verification.release()}
            } ?: return false
            success=true
        } catch(cancelled:kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch(error:Exception) {
            Log.i(TAG,"Mantendo MPEG-TS original: ${error.javaClass.simpleName}")
        }
        return success
    }

    suspend fun tryRemux(context: Context, input: File): File? {
        if(!input.isFile || input.length()<256*1024) return null
        val output=File(input.parentFile,input.name+".remux.mp4")
        // During saving, an additional copy of the final video must still fit
        // beside the original partial. If storage is tight, keep the .ts.
        if(input.length()>(Long.MAX_VALUE-RESERVE_BYTES)/2) return null
        val required=input.length()*2+RESERVE_BYTES
        if(StatFs(input.parentFile!!.absolutePath).availableBytes<required) return null
        var successful=false
        output.delete()
        try {
            FileInputStream(input).use { raw ->
                val extractor=MediaExtractor()
                var muxer: MediaMuxer?=null
                var started=false
                try {
                    extractor.setDataSource(raw.fd)
                    val count=extractor.trackCount
                    val formats=(0 until count).map(extractor::getTrackFormat)
                    val mimes=formats.map { it.getString(MediaFormat.KEY_MIME).orEmpty().lowercase() }
                    if(!compatibleTracks(mimes)) return null
                    val activeMuxer=MediaMuxer(output.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    muxer=activeMuxer
                    val tracks=formats.map(activeMuxer::addTrack)
                    formats.indices.forEach(extractor::selectTrack)
                    activeMuxer.start()
                    started=true
                    val buffer=ByteBuffer.allocateDirect(BUFFER_BYTES)
                    val info=MediaCodec.BufferInfo()
                    val lastTime=LongArray(count) {-1L}
                    val samples=IntArray(count)
                    var copied=0L
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val track=extractor.sampleTrackIndex
                        if(track<0) break
                        if(track !in 0 until count) error("Faixa fora do limite")
                        buffer.clear()
                        val declared=extractor.sampleSize
                        require(declared in 1..BUFFER_BYTES.toLong()) {"Amostra incompatível"}
                        val length=extractor.readSampleData(buffer,0)
                        if(length<0) break
                        require(length.toLong()==declared) {"Amostra truncada"}
                        val whenUs=extractor.sampleTime
                        require(whenUs>=0 && whenUs>=lastTime[track]) {"Timestamps incompatíveis"}
                        lastTime[track]=whenUs
                        info.set(0,length,whenUs,extractor.sampleFlags)
                        activeMuxer.writeSampleData(tracks[track],buffer,info)
                        samples[track]++
                        copied+=length.toLong()
                        if(!extractor.advance()) break
                    }
                    // Verify every selected track survived; never silently
                    // strip audio when converting a muxed anime episode.
                    require(samples.isNotEmpty() && samples.all { it>0 } && copied>0)
                    activeMuxer.stop()
                    started=false
                } finally {
                    if(started) runCatching { muxer?.stop() }
                    runCatching { muxer?.release() }
                    extractor.release()
                }
            }
            require(output.length()>0)
            val check=MediaExtractor()
            try {
                check.setDataSource(output.absolutePath)
                require(check.trackCount in 1..2)
                require((0 until check.trackCount).any {
                    check.getTrackFormat(it).getString(MediaFormat.KEY_MIME) in
                        listOf("video/avc","video/hevc")
                })
            } finally {check.release()}
            successful=true
            return output
        } catch(cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch(error: Exception) {
            Log.i(TAG,"Mantendo MPEG-TS original: ${error.javaClass.simpleName}")
            return null
        } finally {
            if(!successful) output.delete()
        }
    }
}
