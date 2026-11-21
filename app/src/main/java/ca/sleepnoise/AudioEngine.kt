package ca.sleepnoise

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log

/** Owns the AudioTrack and the audio thread that pulls frames from [Renderer]. */
class AudioEngine {
    val sampleRate: Int = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).takeIf { it in 22050..96000 } ?: 48000
    val renderer = Renderer(sampleRate)
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    var bufferMs = 0; private set
    var encoding = ""; private set

    fun start() {
        if (running) return
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val float = runCatching { build(attrs, AudioFormat.ENCODING_PCM_FLOAT) }.getOrNull()
        val t = float ?: build(attrs, AudioFormat.ENCODING_PCM_16BIT)
        encoding = if (float != null) "float" else "16-bit"
        track = t
        t.play()
        running = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val frames = 1024
            val buf = FloatArray(frames * 2)
            val shorts = if (float == null) ShortArray(frames * 2) else null
            while (running) {
                renderer.render(buf, frames)
                if (shorts == null) t.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
                else {
                    for (i in buf.indices) shorts[i] = (buf[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                    t.write(shorts, 0, shorts.size)
                }
            }
        }, "sleepnoise-audio").also { it.start() }
    }

    private fun build(attrs: AudioAttributes, enc: Int): AudioTrack {
        val bytesPerFrame = if (enc == AudioFormat.ENCODING_PCM_FLOAT) 8 else 4
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, enc)
        val size = maxOf(min * 4, sampleRate * bytesPerFrame / 4)      // at least 250 ms: robust, latency doesn't matter here
        bufferMs = size / bytesPerFrame * 1000 / sampleRate
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(enc).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) { t.release(); throw IllegalStateException("AudioTrack init failed") }
        return t
    }

    /** Output underruns reported by Android since start: should stay at 0. */
    val underruns: Int get() = runCatching { track?.underrunCount ?: 0 }.getOrDefault(0)

    fun stop() {
        running = false
        runCatching { thread?.join(1000) }
        runCatching { track?.stop(); track?.release() }.onFailure { Log.w("SleepNoise", "track release", it) }
        track = null
    }
}
