package com.reianix.reianix_local.player

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.*

/** Device-only acoustic similarity. No PCM, fingerprints or derived segments are
 * sent anywhere or persisted. Decode only two short windows per file, sequentially,
 * under a global time/decoder budget. No changes to the active Media3 instance.
 */
internal class LocalSkipAudio(private val context: Context) {
    data class Reference(val uri: Uri, val manual: PlayerLocalMetadataStore.Metadata)
    data class Fingerprint(val startMs: Long, val frames: List<FloatArray?>)

    suspend fun detect(source: Uri, references: List<Reference>, duration: Long, kinds: Set<String>): List<PlayerSkipSegment> {
        if (duration !in 180_000L..5_400_000L || references.isEmpty() || !decoderLock.tryLock()) return emptyList()
        val deadline = SystemClock.elapsedRealtime() + 18_000L
        try {
            val output = mutableListOf<PlayerSkipSegment>()
            for (kind in listOf("op", "ed")) {
                coroutineContext.ensureActive()
                if (kind !in kinds || SystemClock.elapsedRealtime() >= deadline) continue
                val manualRefs = references.filter {
                    if (kind == "op") it.manual.opening != null else it.manual.ending != null
                }
                if (manualRefs.isEmpty() && references.size < 2) continue
                val current = decode(source, kind, duration, deadline) ?: continue
                val decoded = mutableMapOf<Uri, Fingerprint?>()
                suspend fun read(reference: Reference): Fingerprint? {
                    if (!decoded.containsKey(reference.uri)) decoded[reference.uri] = decode(reference.uri, kind, null, deadline)
                    return decoded[reference.uri]
                }
                var anchored: PlayerSkipSegment? = null
                for (reference in manualRefs.take(1)) {
                    val previous = read(reference) ?: continue
                    val marker = if (kind == "op") reference.manual.opening else reference.manual.ending
                    if (marker == null) continue
                    val matches = match(current, previous, kind, duration).filter { match ->
                        abs(match.referenceStartMs - marker.startMs) <= 3500L &&
                            abs(match.referenceEndMs - marker.endMs) <= 3500L
                    }
                    anchored = matches.singleOrNull()?.segment
                }
                if (anchored != null) {
                    output.add(anchored)
                    continue
                }
                // Without a user marker require the same uninterrupted audio in
                // two distinct neighbouring episodes, with matching boundaries.
                if (references.size < 2) continue
                val first = read(references[0]) ?: continue
                val second = read(references[1]) ?: continue
                val a = match(current, first, kind, duration).map { it.segment }
                val b = match(current, second, kind, duration).map { it.segment }
                val agreed = a.flatMap { x -> b.filter { SkipConsensus.agree(x, it) }.map { y ->
                    PlayerSkipSegment(kind, max(x.startMs, y.startMs), min(x.endMs, y.endMs))
                } }.distinct().filter { PlayerEnhancementPolicy.plausibleRemoteSegment(it, duration) }
                agreed.singleOrNull()?.let(output::add)
            }
            return output
        } finally { decoderLock.unlock() }
    }

    private suspend fun decode(uri: Uri, kind: String, expectedDuration: Long?, deadline: Long): Fingerprint? {
        if (SystemClock.elapsedRealtime() >= deadline) return null
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            coroutineContext.ensureActive()
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            val audioDuration = format.getLong(MediaFormat.KEY_DURATION) / 1000L
            val duration = expectedDuration ?: audioDuration
            if (duration <= 0L || (expectedDuration != null && abs(audioDuration - expectedDuration) > 3000L)) return null
            val start = if (kind == "op") 0L else max(0L, duration - 240_000L)
            val end = if (kind == "op") min(240_000L, duration * 45 / 100) else duration
            if (end - start < 40_000L) return null
            val collector = SpectralCollector(start, end)
            extractor.selectTrack(track)
            extractor.seekTo(start * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME) ?: return null)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcm = AudioFormat.ENCODING_PCM_16BIT
            var inputDone = false
            var done = false
            val info = MediaCodec.BufferInfo()
            while (!done && SystemClock.elapsedRealtime() < deadline) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val input = codec.dequeueInputBuffer(5000)
                    if (input >= 0) {
                        val buffer = codec.getInputBuffer(input) ?: return null
                        buffer.clear()
                        val timestamp = extractor.sampleTime
                        val size = if (timestamp < 0L || timestamp > (end + 1000L) * 1000L) -1 else extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(input, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(input, 0, size, timestamp, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 5000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    pcm = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    if (pcm != AudioFormat.ENCODING_PCM_16BIT || sampleRate !in 8000..192000 || channels !in 1..8) return null
                } else if (index >= 0) {
                    try {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0 && pcm == AudioFormat.ENCODING_PCM_16BIT &&
                            sampleRate in 8000..192000 && channels in 1..8) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val data = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val stride = (sampleRate / 4000).coerceAtLeast(1)
                            val frames = data.remaining() / channels
                            var f = 0
                            while (f < frames) {
                                val time = info.presentationTimeUs / 1000L + f * 1000L / sampleRate
                                if (time >= end) { done = true; break }
                                if (time >= start) {
                                    var sum = 0.0
                                    for (ch in 0 until channels) sum += data.get(f * channels + ch).toDouble()
                                    collector.sample(time, sum / (channels * 32768.0), sampleRate.toDouble() / stride)
                                }
                                f += stride
                            }
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                    } finally { codec.releaseOutputBuffer(index, false) }
                }
            }
            if (!done) return null
            return collector.finish()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return null }
        finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private class SpectralCollector(val start: Long, end: Long) {
        private val bins = MutableList<FloatArray?>(((end - start + FRAME_MS - 1) / FRAME_MS).toInt()) { null }
        private val freqs = doubleArrayOf(90.0, 150.0, 230.0, 350.0, 520.0, 760.0, 1100.0, 1550.0)
        private val a = DoubleArray(freqs.size)
        private val z1 = DoubleArray(freqs.size)
        private val z2 = DoubleArray(freqs.size)
        private var index = -1
        private var count = 0
        private var energy = 0.0
        private var rate = 0.0

        fun sample(time: Long, value: Double, sampleRate: Double) {
            val bucket = ((time - start) / FRAME_MS).toInt()
            if (bucket !in bins.indices) return
            if (bucket != index || rate != sampleRate) {
                flush()
                index = bucket
                rate = sampleRate
                for (i in freqs.indices) a[i] = 2.0 * cos(2.0 * PI * freqs[i] / rate)
            }
            count++
            energy += value * value
            for (i in freqs.indices) {
                val next = value + a[i] * z1[i] - z2[i]
                z2[i] = z1[i]
                z1[i] = next
            }
        }

        private fun flush() {
            if (index in bins.indices && count >= 500 && energy / count > 0.00005) {
                val v = DoubleArray(freqs.size) { i ->
                    ln(1.0 + max(0.0, z1[i] * z1[i] + z2[i] * z2[i] - a[i] * z1[i] * z2[i]))
                }
                val mean = v.average()
                val norm = sqrt(v.sumOf { (it - mean) * (it - mean) })
                if (norm > 0.7) bins[index] = FloatArray(v.size) { ((v[it] - mean) / norm).toFloat() }
            }
            z1.fill(0.0); z2.fill(0.0); count = 0; energy = 0.0
        }
        fun finish(): Fingerprint { flush(); return Fingerprint(start, bins) }
    }

    data class AudioMatch(val segment: PlayerSkipSegment, val referenceStartMs: Long, val referenceEndMs: Long)

    companion object {
        private val decoderLock = Mutex()
        private const val FRAME_MS = 500L
        private const val MIN_FRAMES = 80 // 40 s
        private const val MAX_FRAMES = 360 // 180 s

        private fun similarity(a: FloatArray?, b: FloatArray?): Double {
            if (a == null || b == null || a.size != b.size) return -1.0
            var sum = 0.0
            for (i in a.indices) sum += a[i] * b[i]
            return sum
        }

        // Scan offsets, not individual guessed timestamps. A run must have
        // varying acoustic features, strong agreement and distinct boundaries.
        fun match(a: Fingerprint, b: Fingerprint, kind: String, duration: Long): List<AudioMatch> {
            val candidates = mutableListOf<AudioMatch>()
            for (offset in -80..80) {
                var start = -1
                var strong = 0
                var misses = 0
                var variation = 0
                fun finish(end: Int) {
                    val length = end - start
                    if (start >= 0 && length in MIN_FRAMES..MAX_FRAMES && strong >= length * 0.90 && variation >= length * 0.18) {
                        val x = PlayerSkipSegment(kind, a.startMs + start * FRAME_MS, a.startMs + end * FRAME_MS)
                        // Reject clipped runs; their actual start/end is unknown.
                        if (start > 0 && end < a.frames.size && start + offset > 0 && end + offset < b.frames.size &&
                            PlayerEnhancementPolicy.plausibleRemoteSegment(x, duration)) {
                            candidates.add(AudioMatch(x, b.startMs + (start + offset) * FRAME_MS,
                                b.startMs + (end + offset) * FRAME_MS))
                        }
                    }
                    start = -1; strong = 0; misses = 0; variation = 0
                }
                for (i in a.frames.indices) {
                    val j = i + offset
                    val score = similarity(a.frames[i], b.frames.getOrNull(j))
                    if (score >= 0.94) {
                        if (start < 0) start = i
                        strong++
                        misses = 0
                        if (i > start && similarity(a.frames[i], a.frames[i - 1]) in 0.0..0.86) variation++
                    } else if (start >= 0) {
                        misses++
                        if (misses > 2) finish(i - misses + 1)
                    }
                }
                if (start >= 0) finish(a.frames.size - misses)
            }
            // Similar offsets can produce nearly identical runs. Cluster those;
            // if a different long run exists, don't invent which song is OP/ED.
            val sorted = candidates.sortedByDescending { it.segment.endMs - it.segment.startMs }
            val unique = mutableListOf<AudioMatch>()
            for (candidate in sorted) if (unique.none {
                SkipConsensus.agree(it.segment, candidate.segment) &&
                    abs(it.referenceStartMs - candidate.referenceStartMs) <= 3000L
            }) unique.add(candidate)
            return unique.take(2).takeIf { it.size == 1 }.orEmpty()
        }
    }
}
