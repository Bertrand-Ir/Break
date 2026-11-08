package app.breakpiano.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/** Decodes any audio Android supports into a raw 16-bit little-endian stereo PCM file. */
object AudioDecoder {
    class Result(val sampleRate: Int, val frames: Int, val rms: Double)

    fun displayName(ctx: Context, uri: Uri, fallback: String): String {
        var name = fallback
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0) ?: name
            }
        }
        return name.substringBeforeLast('.').replace('_', ' ').trim().ifBlank { fallback }
    }

    /** Blocking — call from a background dispatcher. */
    fun decode(ctx: Context, uri: Uri, out: File, maxSeconds: Int = 900, onProgress: (Float) -> Unit): Result {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(ctx, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("That file has no audio track.")
            val inFormat = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L

            var rate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false

            codec = MediaCodec.createDecoderByType(mime).apply { configure(inFormat, null, null, 0); start() }
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var frames = 0L
            var sumSq = 0.0
            var scratch = ByteArray(0)

            BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { stream ->
                while (!outputDone) {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(5_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0 || frames > maxSeconds.toLong() * rate) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 5_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                        outIdx >= 0 -> {
                            val buf = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.nativeOrder())
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            val ch = max(1, channels)
                            val n = (if (isFloat) info.size / 4 else info.size / 2) / ch
                            if (scratch.size < n * 4) scratch = ByteArray(n * 4)
                            val bb = ByteBuffer.wrap(scratch).order(ByteOrder.LITTLE_ENDIAN)
                            val fb = if (isFloat) buf.asFloatBuffer() else null
                            val sb = if (!isFloat) buf.asShortBuffer() else null
                            for (i in 0 until n) {
                                val l: Int; val r: Int
                                if (fb != null) {
                                    l = (fb.get(i * ch).coerceIn(-1f, 1f) * 32767f).toInt()
                                    r = if (ch > 1) (fb.get(i * ch + 1).coerceIn(-1f, 1f) * 32767f).toInt() else l
                                } else {
                                    l = sb!!.get(i * ch).toInt()
                                    r = if (ch > 1) sb.get(i * ch + 1).toInt() else l
                                }
                                bb.putShort(l.toShort()); bb.putShort(r.toShort())
                                val m = (l + r) * 0.5
                                sumSq += m * m
                            }
                            stream.write(scratch, 0, n * 4)
                            frames += n
                            codec.releaseOutputBuffer(outIdx, false)
                            if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            }
            if (frames < rate / 4) error("That file is too short.")
            return Result(rate, frames.toInt(), sqrt(sumSq / frames) / 32768.0)
        } catch (t: Throwable) {
            out.delete()
            throw t
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
