package ca.sleepnoise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

class LogicTest {
    private fun rms(a: FloatArray, from: Int = 0, to: Int = a.size): Double { var s = 0.0; for (i in from until to) s += a[i] * a[i].toDouble(); return sqrt(s / (to - from)) }

    @Test fun noiseLoopIsNormalisedSeamlessAndStereo() {
        val loop = NoiseSynth.generate(48000, NoiseShape(168.0, 65.0, 0.7), seed = 1, n = 1 shl 17)
        assertEquals(NoiseSynth.TARGET_RMS, rms(loop.left), 1e-3)
        assertEquals(NoiseSynth.TARGET_RMS, rms(loop.right), 1e-3)
        var step = 0.0; for (i in 1 until loop.left.size) step += abs(loop.left[i] - loop.left[i - 1]); step /= loop.left.size
        assertTrue("seam must look like any other step", abs(loop.left[0] - loop.left.last()) < 4 * step)
        var c = 0.0; for (i in loop.left.indices) c += loop.left[i] * loop.right[i].toDouble()
        assertTrue("left/right independent", abs(c / loop.left.size) / (NoiseSynth.TARGET_RMS * NoiseSynth.TARGET_RMS) < 0.1)
    }

    @Test fun playJingleThenNoiseThenPause() {
        val sr = 48000
        val r = Renderer(sr)
        val loop = NoiseSynth.generate(sr, NoiseShape(168.0, 65.0, 0.7), seed = 2, n = 1 shl 17)
        r.setNoise(loop.left, loop.right)
        r.setJingle(FloatArray(sr) { 0.1f }, FloatArray(sr) { 0.1f })
        val out = FloatArray(sr * 2 * 20); val blk = FloatArray(2048)
        var f = 0
        fun run(s: Double) { val end = f + (s * sr).toInt(); while (f < end) { val n = minOf(1024, end - f); r.render(blk, n); System.arraycopy(blk, 0, out, 2 * f, 2 * n); f += n } }
        run(0.5); r.play(true, 1f, 2f, 1f); run(11.5); r.pause(1f); run(8.0)
        val left = FloatArray(out.size / 2) { out[2 * it] }
        assertTrue("silent before play", rms(left, 0, sr / 2) < 1e-6)
        assertTrue("jingle audible", rms(left, (0.6 * sr).toInt(), (1.4 * sr).toInt()) > 0.03)
        val steady = 20 * log10(rms(left, 8 * sr, 12 * sr))
        assertEquals("75% volume gives the reference loudness", -14.7, steady, 0.6)
        assertTrue("silent after fade-out", rms(left, 18 * sr, 20 * sr) < 1e-3)
        assertTrue("never clips", out.all { abs(it) <= 1f })
    }

    @Test fun wakeWordAndCommands() {
        val w = WakeLogic("hey brownie", 0.6)
        fun ws(vararg p: Pair<String, Double>) = p.map { WakeLogic.Word(it.first, it.second) }
        assertEquals("stop", (w.decide(ws("hey" to .9, "brownie" to .9, "stop" to .9), false) as WakeLogic.Decision.Run).cmd.id)
        assertEquals(WakeLogic.Decision.OpenWindow, w.decide(ws("hey" to .9, "brownie" to .9), false))
        assertEquals("louder", (w.decide(ws("louder" to .8), true) as WakeLogic.Decision.Run).cmd.id)
        assertEquals(WakeLogic.Decision.Nothing, w.decide(ws("louder" to .8), false))
        assertEquals(WakeLogic.Decision.Nothing, w.decide(ws("hey" to .3, "brownie" to .4, "stop" to .9), false))
        assertEquals("start", (w.decide(ws("good" to .9, "night" to .9), true) as WakeLogic.Decision.Run).cmd.id)
        w.setWake("Okay, Brownie!"); assertEquals(listOf("okay", "brownie"), w.wake)
        assertTrue(w.grammar().contains("okay brownie") && w.grammar().last() == "[unk]")
    }

    @Test fun scheduleNextOccurrence() {
        val now = java.time.ZonedDateTime.of(2026, 11, 20, 22, 30, 0, 0, java.time.ZoneId.of("America/Toronto"))
        val later = Scheduler.nextMillis("23:15", now)!!; assertEquals(45 * 60_000L, later - now.toInstant().toEpochMilli())
        val tomorrow = Scheduler.nextMillis("07:00", now)!!; assertEquals((8 * 60 + 30) * 60_000L, tomorrow - now.toInstant().toEpochMilli())
        assertEquals(null, Scheduler.nextMillis("", now))
    }
}
