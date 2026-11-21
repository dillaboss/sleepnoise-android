package ca.sleepnoise

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Real-time mixer, run on the audio thread. Everything heavy (the noise loop, the jingle) is
 * precomputed; per sample it only reads buffers and applies smoothed gains. Control threads talk to
 * it through a command queue (sample-accurate timing) and a few volatile targets.
 */
class Renderer(val sampleRate: Int) {
    // ---- live targets, written by the service ----
    @Volatile var volume = 0.75f          // 0..1, squared for a perceptual taper
    @Volatile var width = 1f              // 0 mono .. 1 fully independent L/R
    @Volatile var swellDepth = 0f         // 0..0.6
    @Volatile var swellPeriod = 12f       // seconds
    @Volatile var duckLevel = 1f          // 1 = normal; lower while listening for a command

    private sealed class Cmd {
        class Play(val intro: Boolean, val introVol: Float, val noiseDelay: Float, val fadeIn: Float) : Cmd()
        class Pause(val fadeOut: Float) : Cmd()
        class Preview(val vol: Float) : Cmd()
        class Chime(val up: Boolean, val vol: Float) : Cmd()
        class Noise(val l: FloatArray, val r: FloatArray) : Cmd()
        class Jingle(val l: FloatArray, val r: FloatArray) : Cmd()
    }
    private val q = ConcurrentLinkedQueue<Cmd>()

    fun play(intro: Boolean, introVol: Float, noiseDelay: Float, fadeIn: Float) = q.add(Cmd.Play(intro, introVol, noiseDelay, fadeIn))
    fun pause(fadeOut: Float) = q.add(Cmd.Pause(fadeOut))
    fun previewJingle(vol: Float) = q.add(Cmd.Preview(vol))
    fun chime(up: Boolean, vol: Float) = q.add(Cmd.Chime(up, vol))
    fun setNoise(l: FloatArray, r: FloatArray) = q.add(Cmd.Noise(l, r))
    fun setJingle(l: FloatArray, r: FloatArray) = q.add(Cmd.Jingle(l, r))

    // ---- audio-thread state ----
    private val sr = sampleRate.toFloat()
    private var t = 0L
    private var curL: FloatArray? = null; private var curR: FloatArray? = null
    private var nxtL: FloatArray? = null; private var nxtR: FloatArray? = null
    private var pos = 0; private var xfPos = 0; private val xfLen = (1.5f * sr).toInt()

    private var fade = 0f; private var fadeTarget = 0f; private var fadeK = 1f
    private var pendingFadeAt = -1L; private var pendingFadeK = 1f
    private var duck = 1f; private var master = volume * volume; private var w = width
    private var swellPhase = 0.0

    private var jL: FloatArray? = null; private var jR: FloatArray? = null
    private var jPos = -1; private var jGain = 0f; private var jTarget = 0f; private var jK = 1f; private var jVol = 1f

    private class Note(val freq: Double, val start: Long, val amp: Float)
    private val notes = ArrayList<Note>()

    /** Per-sample one-pole coefficient for a time constant in seconds (like Web Audio setTargetAtTime). */
    private fun k(tau: Float) = if (tau <= 0f) 1f else (1 - exp(-1.0 / (tau * sr))).toFloat()
    private val kDuckDown = k(0.06f); private val kDuckUp = k(0.4f); private val kSlow = k(0.08f)

    var underflowFrames = 0L; private set
    val noiseReady get() = curL != null

    private fun drain() {
        while (true) {
            when (val c = q.poll() ?: return) {
                is Cmd.Play -> {
                    pendingFadeAt = -1
                    if (c.intro && jL != null) {
                        jPos = 0; jGain = 1f; jTarget = 1f; jVol = c.introVol
                        fadeTarget = 0f; fadeK = k(0.15f)               // tuck any leftover noise under the jingle
                        pendingFadeAt = t + (max(0.3f, c.noiseDelay) * sr).toLong()
                        pendingFadeK = k(max(0.05f, c.fadeIn / 3))
                    } else { fadeTarget = 1f; fadeK = k(max(0.05f, c.fadeIn / 3)) }
                }
                is Cmd.Pause -> {
                    pendingFadeAt = -1
                    fadeTarget = 0f; fadeK = k(max(0.03f, c.fadeOut / 3))
                    if (jPos >= 0) { jTarget = 0f; jK = k(max(0.05f, c.fadeOut / 4)) }
                }
                is Cmd.Preview -> if (jL != null) { jPos = 0; jGain = 1f; jTarget = 1f; jVol = c.vol }
                is Cmd.Chime -> {
                    val f = if (c.up) doubleArrayOf(659.3, 987.8) else doubleArrayOf(987.8, 659.3)
                    notes.add(Note(f[0], t, c.vol * 0.5f)); notes.add(Note(f[1], t + (0.13f * sr).toLong(), c.vol * 0.5f))
                }
                is Cmd.Noise -> {
                    if (curL == null || curL!!.size != c.l.size) { curL = c.l; curR = c.r; nxtL = null; pos = 0 }
                    else { nxtL = c.l; nxtR = c.r; xfPos = 0 }              // crossfade to the new loop
                }
                is Cmd.Jingle -> { jL = c.l; jR = c.r; jPos = -1 }
            }
        }
    }

    /** Fill [out] with [frames] interleaved stereo floats. */
    fun render(out: FloatArray, frames: Int) {
        drain()
        val cl = curL; val cr = curR
        val mTarget = volume * volume
        val wTarget = width
        val dTarget = duckLevel
        val sDepth = swellDepth
        val sInc = 2 * PI / (max(1f, swellPeriod) * sr)
        val jl = jL; val jr = jR
        for (i in 0 until frames) {
            if (pendingFadeAt in 0..t) { fadeTarget = 1f; fadeK = pendingFadeK; pendingFadeAt = -1 }
            var l = 0f; var r = 0f
            if (cl != null && cr != null) {
                l = cl[pos]; r = cr[pos]
                val nl = nxtL; val nr = nxtR
                if (nl != null && nr != null) {
                    val a = xfPos.toFloat() / xfLen
                    val g1 = cos(a * PI / 2).toFloat(); val g2 = sin(a * PI / 2).toFloat()
                    l = l * g1 + nl[pos] * g2; r = r * g1 + nr[pos] * g2
                    if (++xfPos >= xfLen) { curL = nl; curR = nr; nxtL = null; nxtR = null }
                }
                pos = (pos + 1) and (cl.size - 1)
            } else underflowFrames++

            // stereo width (mid/side) with level compensation
            w += (wTarget - w) * kSlow
            val g1 = (1 + w) / 2; val g2 = (1 - w) / 2; val comp = 1f / sqrt((1 + w * w) / 2)
            val wl = (g1 * l + g2 * r) * comp; val wr = (g2 * l + g1 * r) * comp

            // slow swell, play/pause fade
            val sw = if (sDepth > 0f) (1 - sDepth / 2 + sDepth / 2 * sin(swellPhase)).toFloat() else 1f
            swellPhase += sInc; if (swellPhase > 2 * PI) swellPhase -= 2 * PI
            fade += (fadeTarget - fade) * fadeK
            var ol = wl * sw * fade; var or = wr * sw * fade

            // jingle
            if (jPos >= 0 && jl != null && jr != null) {
                jGain += (jTarget - jGain) * jK
                ol += jl[jPos] * jGain * jVol; or += jr[jPos] * jGain * jVol
                if (++jPos >= jl.size || (jTarget == 0f && jGain < 1e-4f)) jPos = -1
            }

            // duck (while listening for a command), then the volume control
            duck += (dTarget - duck) * (if (dTarget < duck) kDuckDown else kDuckUp)
            master += (mTarget - master) * kSlow
            ol *= duck * master; or *= duck * master

            // chime goes on top, unaffected by volume
            if (notes.isNotEmpty()) {
                var c = 0f
                val it = notes.iterator()
                while (it.hasNext()) {
                    val n = it.next()
                    val dt = (t - n.start) / sr
                    if (dt < 0) continue
                    if (dt > 0.5f) { it.remove(); continue }
                    val env = if (dt < 0.015f) dt / 0.015f else exp(-(dt - 0.015f) * 18.0).toFloat()
                    c += (sin(2 * PI * n.freq * dt) * env * n.amp).toFloat()
                }
                ol += c; or += c
            }

            out[2 * i] = soft(ol); out[2 * i + 1] = soft(or)
            t++
        }
    }

    /** Transparent below -1 dBFS, smooth saturation above, never exceeds full scale. */
    private fun soft(x: Float): Float {
        val a = abs(x)
        if (a <= 0.89f) return x
        val y = 0.89f + 0.11f * tanh(((a - 0.89f) / 0.11f).toDouble()).toFloat()
        return if (x < 0) -y else y
    }

    val seconds get() = t / sr
}
