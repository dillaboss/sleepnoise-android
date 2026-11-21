package ca.sleepnoise

import java.util.SplittableRandom
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** The parts of the sound that are baked into the noise loop (everything else is a live gain). */
data class NoiseShape(val tone: Double, val rumble: Double, val color: Double)

/**
 * Builds a seamless stereo noise loop by shaping a random spectrum and inverse-transforming it.
 *
 * The spectrum is brown (1/f^2 above a 20 Hz corner) blended with pink (1/f), then multiplied by the
 * response of the old Web Audio chain (two resonant high-passes at [NoiseShape.rumble], one gently
 * resonant low-pass at [NoiseShape.tone]), so it sounds exactly like the earlier version without any
 * filtering at playback time. A random complex spectrum with a real, symmetric magnitude gives two
 * independent real signals (left = real part, right = imaginary part), and because the transform is
 * circular the loop point is seamless. Each channel is normalised to [TARGET_RMS], so tone changes
 * never change loudness; only the volume control does.
 */
object NoiseSynth {
    const val HP_Q_DB = 4.2
    const val LP_Q_DB = 1.9
    /** Per-channel RMS before the volume control. At 75% volume (gain 0.5625) this is -14.7 dBFS. */
    const val TARGET_RMS = 0.327
    const val LOOP_FRAMES = 1 shl 21   // 2,097,152 frames: 43.7 s at 48 kHz

    /** |H(f)|^2 of a Web Audio (RBJ cookbook) low-pass or high-pass biquad, Q given in dB as Web Audio does. */
    fun biquadMag2(lowpass: Boolean, f0: Double, qDb: Double, f: Double, sr: Double): Double {
        val w0 = 2 * PI * f0 / sr
        val a = sin(w0) / (2 * 10.0.pow(qDb / 20))
        val c = cos(w0)
        val b0 = if (lowpass) (1 - c) / 2 else (1 + c) / 2
        val b1 = if (lowpass) 1 - c else -(1 + c)
        val a0 = 1 + a; val a1 = -2 * c; val a2 = 1 - a
        val w = 2 * PI * f / sr
        val cr = cos(w); val ci = -sin(w); val c2r = cos(2 * w); val c2i = -sin(2 * w)
        val nr = b0 + b1 * cr + b0 * c2r; val ni = b1 * ci + b0 * c2i
        val dr = a0 + a1 * cr + a2 * c2r; val di = a1 * ci + a2 * c2i
        return (nr * nr + ni * ni) / (dr * dr + di * di)
    }

    fun filterPower(s: NoiseShape, f: Double, sr: Double): Double {
        val hp = biquadMag2(false, s.rumble, HP_Q_DB, f, sr)
        return hp * hp * biquadMag2(true, s.tone, LP_Q_DB, f, sr)
    }

    /** In-place iterative radix-2 FFT; size must be a power of two. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            val half = len shr 1
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until half) {
                    val a = i + k; val b = a + half
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    class Loop(val left: FloatArray, val right: FloatArray, val millis: Long)

    fun generate(sampleRate: Int, shape: NoiseShape, seed: Long = System.nanoTime(), n: Int = LOOP_FRAMES): Loop {
        val t0 = System.currentTimeMillis()
        val sr = sampleRate.toDouble()
        // Equal-power colour blend of brown and pink, each first normalised to the same total power.
        var sb = 0.0; var sp = 0.0
        for (k in 1..n / 2) { val f = k * sr / n; sb += 1 / (f * f + 400); sp += 1 / sqrt(f * f + 400) }
        val cb = cos(shape.color * PI / 2).pow(2) / sb
        val cp = sin(shape.color * PI / 2).pow(2) / sp

        val re = DoubleArray(n); val im = DoubleArray(n)
        val rnd = SplittableRandom(seed)
        for (k in 1 until n) {
            val f = (if (k <= n / 2) k else n - k) * sr / n
            val p = cb / (f * f + 400) + cp / sqrt(f * f + 400)
            val amp = sqrt(p * filterPower(shape, f, sr))
            val r = sqrt(-2 * ln(1 - rnd.nextDouble())) * amp
            val th = 2 * PI * rnd.nextDouble()
            re[k] = r * cos(th); im[k] = r * sin(th)
        }
        fft(re, im)

        var sl = 0.0; var sr2 = 0.0
        for (i in 0 until n) { sl += re[i] * re[i]; sr2 += im[i] * im[i] }
        val gl = TARGET_RMS / sqrt(sl / n); val gr = TARGET_RMS / sqrt(sr2 / n)
        val left = FloatArray(n) { (re[it] * gl).toFloat() }
        val right = FloatArray(n) { (im[it] * gr).toFloat() }
        return Loop(left, right, System.currentTimeMillis() - t0)
    }
}
