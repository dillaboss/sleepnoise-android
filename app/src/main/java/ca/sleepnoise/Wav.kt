package ca.sleepnoise

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal WAV reader (PCM 8/16/24/32-bit and 32-bit float), resampled to the output rate. */
object Wav {
    class Clip(val left: FloatArray, val right: FloatArray, val sampleRate: Int) {
        val seconds get() = left.size.toDouble() / sampleRate
    }

    fun decode(bytes: ByteArray): Clip {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var p = 12
        var tag = 0; var ch = 0; var sr = 0; var bits = 0
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4, Charsets.US_ASCII)
            val size = b.getInt(p + 4)
            val body = p + 8
            if (id == "fmt ") {
                tag = b.getShort(body).toInt() and 0xffff
                ch = b.getShort(body + 2).toInt()
                sr = b.getInt(body + 4)
                bits = b.getShort(body + 14).toInt()
                if (tag == 0xFFFE) tag = b.getShort(body + 24).toInt() and 0xffff   // WAVE_FORMAT_EXTENSIBLE sub-format
            }
            if (id == "data" && ch > 0) {
                val bps = bits / 8
                val frames = minOf(size, bytes.size - body) / (bps * ch)
                val l = FloatArray(frames); val r = FloatArray(frames)
                for (i in 0 until frames) {
                    for (c in 0 until minOf(ch, 2)) {
                        val o = body + (i * ch + c) * bps
                        val v = when {
                            tag == 3 && bps == 4 -> b.getFloat(o)
                            bps == 2 -> b.getShort(o) / 32768f
                            bps == 3 -> ((bytes[o].toInt() and 0xff) or ((bytes[o + 1].toInt() and 0xff) shl 8) or (bytes[o + 2].toInt() shl 16)) / 8388608f
                            bps == 4 -> b.getInt(o) / 2147483648f
                            else -> ((bytes[o].toInt() and 0xff) - 128) / 128f
                        }
                        if (c == 0) l[i] = v else r[i] = v
                    }
                    if (ch == 1) r[i] = l[i]
                }
                return Clip(l, r, sr)
            }
            p = body + size + (size and 1)
        }
        throw IllegalArgumentException("No audio data in WAV")
    }

    /** Linear-interpolation resample; fine for a short jingle. */
    fun resample(c: Clip, to: Int): Clip {
        if (c.sampleRate == to) return c
        val n = (c.left.size.toLong() * to / c.sampleRate).toInt()
        val step = c.sampleRate.toDouble() / to
        fun res(src: FloatArray) = FloatArray(n) { i ->
            val x = i * step; val k = x.toInt(); val f = (x - k).toFloat()
            val a = src[minOf(k, src.size - 1)]; val b2 = src[minOf(k + 1, src.size - 1)]
            a + (b2 - a) * f
        }
        return Clip(res(c.left), res(c.right), to)
    }
}
