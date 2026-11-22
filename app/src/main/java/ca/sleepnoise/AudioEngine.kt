package ca.sleepnoise

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log

/**
 * Owns the AudioTrack and the audio thread that pulls frames from [Renderer]. If the output dies
 * (for example Android's audio server restarts), writes start failing; the thread then releases the
 * track and builds a new one, backing off instead of spinning.
 */
class AudioEngine {
    val sampleRate: Int = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).takeIf { it in 22050..96000 } ?: 48000
    val renderer = Renderer(sampleRate)
    @Volatile private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile var bufferMs = 0; private set
    @Volatile var encoding = ""; private set
    /** How many times the output had to be rebuilt; shown in Diagnostics. */
    @Volatile var restarts = 0; private set

    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "sleepnoise-audio").also { it.start() }
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frames = 1024
        val buf = FloatArray(frames * 2)
        var shorts: ShortArray? = null
        var t: AudioTrack? = null
        var failures = 0
        while (running) {
            if (t == null) {
                t = runCatching { open() }.onFailure { Log.w("SleepNoise", "audio open failed", it) }.getOrNull()
                if (t == null) { failures++; sleep(minOf(5000L, 250L shl minOf(failures, 4))); continue }
                shorts = if (encoding == "float") null else ShortArray(frames * 2)
                track = t
            }
            renderer.render(buf, frames)
            val s = shorts
            val written = if (s == null) t.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING) else {
                for (i in buf.indices) s[i] = (buf[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                t.write(s, 0, s.size)
            }
            when {
                written < 0 -> {                    // dead output (e.g. ERROR_DEAD_OBJECT): rebuild it
                    Log.w("SleepNoise", "AudioTrack write failed ($written); rebuilding")
                    release(t); t = null; track = null; restarts++; failures++
                    sleep(minOf(5000L, 250L shl minOf(failures, 4)))
                }
                written == 0 -> sleep(10)           // shouldn't happen with blocking writes; never spin
                else -> failures = 0
            }
        }
        release(t); track = null
    }

    private fun open(): AudioTrack {
        val f = runCatching { build(AudioFormat.ENCODING_PCM_FLOAT) }.getOrNull()
        val t = f ?: build(AudioFormat.ENCODING_PCM_16BIT)
        encoding = if (f != null) "float" else "16-bit"
        t.play()
        return t
    }

    private fun build(enc: Int): AudioTrack {
        val bytesPerFrame = if (enc == AudioFormat.ENCODING_PCM_FLOAT) 8 else 4
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, enc)
        if (min <= 0) throw IllegalStateException("No output buffer size ($min)")
        val size = maxOf(min * 4, sampleRate * bytesPerFrame / 4)      // at least 250 ms: robust, latency doesn't matter here
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(enc).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) { t.release(); throw IllegalStateException("AudioTrack init failed") }
        bufferMs = size / bytesPerFrame * 1000 / sampleRate
        return t
    }

    private fun release(t: AudioTrack?) { runCatching { t?.stop() }; runCatching { t?.release() } }
    private fun sleep(ms: Long) { runCatching { Thread.sleep(ms) } }

    /** Output underruns reported by Android for the current track: should stay at 0. */
    val underruns: Int get() = runCatching { track?.underrunCount ?: 0 }.getOrDefault(0)

    fun stop() {
        running = false
        runCatching { thread?.join(1500) }
    }
}
