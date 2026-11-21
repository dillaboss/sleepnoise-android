package ca.sleepnoise

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** vosk_model_find_word isn't wrapped by vosk-android, so map it ourselves (used to validate custom wake words). */
object VoskExtra {
    private val ok = runCatching { Native.register(VoskExtra::class.java, "vosk") }.isSuccess
    @JvmStatic external fun vosk_model_find_word(model: Pointer, word: String): Int
    fun hasWord(model: Model, word: String): Boolean? =
        if (!ok) null else runCatching { vosk_model_find_word(model.pointer, word) >= 0 }.getOrNull()
}

/**
 * Microphone + offline speech recognition on one background thread. Listens with a small grammar
 * (wake phrase + commands + [unk]), so only those phrases can ever be recognised.
 */
class VoiceEngine(private val ctx: Context, private val cb: Callbacks) {
    interface Callbacks {
        fun onStatus(state: String, text: String)     // state: busy | ready | err | off
        fun onPartial(text: String)
        fun onResult(json: JSONObject)
        fun onLevel(level: Float)
    }

    @Volatile var enabled = true
    @Volatile var micGain = 1f
    @Volatile private var micFx = false
    @Volatile private var grammar: List<String> = emptyList()
    @Volatile private var grammarVersion = 0
    @Volatile private var restartMic = false
    @Volatile private var running = false
    @Volatile var model: Model? = null; private set
    var modelLoadMs = 0L; private set
    var micInfo = ""; private set
    private var thread: Thread? = null

    fun setGrammar(g: List<String>) { grammar = g; grammarVersion++ }
    fun setMicFx(on: Boolean) { if (on != micFx) { micFx = on; restartMic = true } }
    fun restart() { restartMic = true }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "sleepnoise-voice").also { it.start() }
    }

    fun stop() { running = false; runCatching { thread?.join(1500) } }

    private fun loop() {
        try {
            if (!loadModel()) return
            var rec: AudioRecord? = null
            var effects = listOf<AudioEffect>()
            var recognizer: Recognizer? = null
            var builtVersion = -1
            val buf = ShortArray(1600)                         // 0.1 s at 16 kHz
            val hp = HighPass(220.0, 16000.0)
            var lastStatus = ""
            fun status(s: String, t: String) { if (lastStatus != s + t) { lastStatus = s + t; cb.onStatus(s, t) } }
            fun closeMic() { effects.forEach { runCatching { it.release() } }; effects = listOf(); runCatching { rec?.stop(); rec?.release() }; rec = null }

            while (running) {
                if (!enabled) { closeMic(); status("off", "Voice off"); Thread.sleep(250); continue }
                if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    closeMic(); status("err", "Microphone permission needed"); Thread.sleep(1000); continue
                }
                if (restartMic) { closeMic(); restartMic = false }
                if (rec == null) {
                    val r = openMic()
                    if (r == null) { status("err", "Microphone busy (turn off Hey Google / Bixby)"); Thread.sleep(3000); continue }
                    rec = r; effects = if (micFx) attachEffects(r.audioSessionId) else listOf()
                    micInfo = "voice-recognition source" + if (effects.isNotEmpty()) " + " + effects.joinToString { it.javaClass.simpleName } else ""
                }
                if (recognizer == null || builtVersion != grammarVersion) {
                    runCatching { recognizer?.close() }
                    builtVersion = grammarVersion
                    val g = org.json.JSONArray(grammar).toString()
                    recognizer = Recognizer(model!!, 16000f, g).apply { setWords(true) }
                }
                status("ready", "")
                val n = rec!!.read(buf, 0, buf.size)
                if (n <= 0) { closeMic(); Thread.sleep(500); continue }
                // gain + 2x high-pass at 220 Hz: removes nearly all of the (very deep) noise, keeps speech
                var sum = 0.0
                val g = micGain
                for (i in 0 until n) {
                    val x = hp.process(buf[i] * g.toDouble())
                    sum += x * x
                    buf[i] = x.coerceIn(-32768.0, 32767.0).toInt().toShort()
                }
                cb.onLevel((sqrt(sum / n) / 32768.0).toFloat())
                val rz = recognizer!!
                if (rz.acceptWaveForm(buf, n)) cb.onResult(JSONObject(rz.result))
                else JSONObject(rz.partialResult).optString("partial").let { if (it.isNotBlank()) cb.onPartial(it) }
            }
            closeMic(); runCatching { recognizer?.close() }
        } catch (t: Throwable) {
            Log.e("SleepNoise", "voice thread", t)
            cb.onStatus("err", "Voice unavailable: ${t.javaClass.simpleName}")
        }
    }

    private fun loadModel(): Boolean {
        if (model != null) return true
        cb.onStatus("busy", "Loading voice model…")
        val t0 = System.currentTimeMillis()
        return try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val dir = ModelAssets.ensure(ctx)
            model = Model(dir.absolutePath)
            modelLoadMs = System.currentTimeMillis() - t0
            true
        } catch (t: Throwable) {
            Log.e("SleepNoise", "model load", t)
            cb.onStatus("err", "Voice model failed to load")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun openMic(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val r = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 4, 16000 * 2))
        }.getOrNull() ?: return null
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return null }
        r.startRecording()
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { r.release(); return null }
        return r
    }

    private fun attachEffects(session: Int): List<AudioEffect> {
        val out = mutableListOf<AudioEffect>()
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session)?.let { it.enabled = true; out += it }
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session)?.let { it.enabled = true; out += it }
        if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(session)?.let { it.enabled = true; out += it }
        return out
    }

    /** Two cascaded Butterworth high-pass biquads (24 dB/oct). */
    private class HighPass(f0: Double, sr: Double) {
        private val b0: Double; private val b1: Double; private val b2: Double; private val a1: Double; private val a2: Double
        private val s = DoubleArray(8)
        init {
            val w0 = 2 * PI * f0 / sr; val al = sin(w0) / (2 * 0.70710678); val c = cos(w0); val a0 = 1 + al
            b0 = (1 + c) / 2 / a0; b1 = -(1 + c) / a0; b2 = (1 + c) / 2 / a0; a1 = -2 * c / a0; a2 = (1 - al) / a0
        }
        fun process(x0: Double): Double {
            var x = x0
            for (st in 0 until 2) {
                val o = st * 4
                val y = b0 * x + b1 * s[o] + b2 * s[o + 1] - a1 * s[o + 2] - a2 * s[o + 3]
                s[o + 1] = s[o]; s[o] = x; s[o + 3] = s[o + 2]; s[o + 2] = y
                x = y
            }
            return x
        }
    }
}

/** The model ships inside the APK (assets/model) and is copied to app storage once per app version. */
object ModelAssets {
    fun ensure(ctx: Context): File {
        val version = ctx.packageManager.getPackageInfo(ctx.packageName, 0).let {
            if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
        }
        val dir = File(ctx.filesDir, "model")
        val marker = File(dir, ".copied-$version")
        if (marker.exists()) return dir
        dir.deleteRecursively(); dir.mkdirs()
        copy(ctx, "model", dir)
        marker.writeText("ok")
        return dir
    }

    private fun copy(ctx: Context, path: String, to: File) {
        val children = ctx.assets.list(path) ?: emptyArray()
        if (children.isEmpty()) {
            ctx.assets.open(path).use { input -> to.outputStream().use { input.copyTo(it, 1 shl 16) } }
            return
        }
        to.mkdirs()
        for (c in children) copy(ctx, "$path/$c", File(to, c))
    }
}
