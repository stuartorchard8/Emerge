package org.emerge.audio.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The synthesis primitives, each pinned against the property it exists for.
 *
 * ⚠️ **Audio is hard to test and easy to test uselessly.** "It produced some numbers" is true of a
 * broken filter, and "it matched these 64 samples" breaks the day anybody improves the noise. What
 * is asserted here is the *measurable claim each class makes*: a lowpass passes low and stops high,
 * pink noise has more energy down the bottom than white does, a smoothed parameter never steps, and
 * the mixer's output is bounded. Those survive tuning and catch the failures that matter.
 */
class SynthTest {

    // ── Oscillators ───────────────────────────────────────────────────────────

    /** A sine at a stated frequency really is at that frequency — counted in zero crossings. */
    @Test
    fun `an oscillator runs at the frequency it was given`() {
        val osc = Oscillator(RATE, Wave.Sine, frequency = 100f)
        val block = FloatArray(RATE)
        osc.render(block, 0, RATE)

        // A second of a 100 Hz sine crosses zero 200 times, give or take the one at each end.
        var crossings = 0
        for (i in 1 until RATE) if ((block[i - 1] < 0f) != (block[i] < 0f)) crossings++
        assertTrue(crossings in 198..202, "100 Hz should cross zero ~200 times a second, not $crossings")
        assertTrue(peak(block) <= 1.0001f, "a unit oscillator went past full scale: ${peak(block)}")
    }

    /** Each shape is the shape it says, told apart by how much energy it carries for its peak. */
    @Test
    fun `the waveforms differ in the way their shapes must`() {
        val rms = Wave.entries.associateWith { wave ->
            val block = FloatArray(RATE / 10)
            Oscillator(RATE, wave, frequency = 220f).render(block, 0, block.size)
            rms(block)
        }
        // A square is at full scale the whole time; a sine averages 1/√2 of it; a triangle 1/√3; a
        // saw the same as a triangle. Anything that got these wrong drew a different shape.
        assertNear(1f, rms.getValue(Wave.Square), 0.01f, "square")
        assertNear(0.7071f, rms.getValue(Wave.Sine), 0.01f, "sine")
        assertNear(0.5774f, rms.getValue(Wave.Triangle), 0.01f, "triangle")
        assertNear(0.5774f, rms.getValue(Wave.Saw), 0.01f, "saw")
    }

    /**
     * ⚠️ **A silent oscillator keeps counting.** It is the case nobody writes a test for and the one
     * that clicks: a voice faded out and back in lands mid-cycle, as it would have if it had been
     * audible throughout, rather than restarting from the phase it happened to stop at.
     */
    @Test
    fun `an oscillator at zero amplitude still advances its phase`() {
        val silent = Oscillator(RATE, Wave.Sine, frequency = 100f, amplitude = 0f)
        val loud = Oscillator(RATE, Wave.Sine, frequency = 100f)
        val block = FloatArray(RATE / 100)
        silent.render(block, 0, block.size)
        loud.render(FloatArray(block.size), 0, block.size)

        silent.amplitude = 1f
        val after = FloatArray(block.size)
        val expected = FloatArray(block.size)
        silent.render(after, 0, after.size)
        loud.render(expected, 0, expected.size)
        assertTrue(
            (0 until after.size).all { abs(after[it] - expected[it]) < 1e-5f },
            "the silent oscillator came back at the wrong phase",
        )
    }

    // ── Noise ─────────────────────────────────────────────────────────────────

    /** White noise fills the range, sits around zero, and is the same twice from the same seed. */
    @Test
    fun `white noise is centred, bounded and deterministic`() {
        val a = FloatArray(RATE)
        val b = FloatArray(RATE)
        WhiteNoise(seed = 7L).render(a, 0, a.size)
        WhiteNoise(seed = 7L).render(b, 0, b.size)

        assertTrue(a.contentEquals(b), "the same seed gave two different sequences")
        assertTrue(peak(a) <= 1f, "noise went past full scale: ${peak(a)}")
        assertTrue(abs(mean(a)) < 0.01f, "noise is off-centre by ${mean(a)}, which is a DC offset")
        // A flat distribution over [-1,1) has an RMS of 1/√3. Far off and it is not white noise.
        assertNear(0.5774f, rms(a), 0.02f, "white noise RMS")
    }

    /**
     * Pink noise is *pink*: more of it down the bottom.
     *
     * ⭐ The assertion that actually distinguishes the two, and the reason it is worth having both.
     * Measured as the ratio of energy below 200 Hz to energy above 2 kHz, through a filter rather
     * than a transform — the filter is already here and a DFT would be a second thing to be wrong.
     */
    @Test
    fun `pink noise carries more of its energy low than white does`() {
        fun tilt(source: AudioSource): Float {
            val block = FloatArray(RATE)
            source.render(block, 0, block.size)
            return bandEnergy(block, Response.LowPass, 200f) / bandEnergy(block, Response.HighPass, 2_000f)
        }
        val white = tilt(WhiteNoise(seed = 11L))
        val pink = tilt(PinkNoise(seed = 11L))
        assertTrue(pink > white * 3f, "pink ($pink) is barely tilted against white ($white)")
    }

    /** And it is still noise, at a level that will sit in a mix beside the hiss. */
    @Test
    fun `pink noise arrives at white noise's level`() {
        val block = FloatArray(RATE)
        PinkNoise(seed = 3L).render(block, 0, block.size)
        // ⚠️ **Pink noise cannot be asked to be centred the way white noise can**, and a tight
        // threshold here is a test that fails on a different seed. Equal energy per octave means the
        // lowest octave measured wanders over a whole second — that *is* what pink means — so a
        // second of it has a sample mean of a sixth of its RMS and no amount of running it longer
        // brings that down. What is worth asserting is that there is no offset *large against the
        // signal*, which is what a flipped coefficient sign would produce. Measured: 0.16.
        assertTrue(
            abs(mean(block)) < 0.3f * rms(block),
            "pink noise is off-centre by ${mean(block)} against an RMS of ${rms(block)}",
        )
        // Level-matched to white noise, which is the claim [PinkNoise] makes and the one that stops
        // a rumble layer arriving three times too quiet to hear under the hiss.
        assertNear(0.5774f, rms(block), 0.1f, "pink noise RMS against white's")
    }

    // ── Filter ────────────────────────────────────────────────────────────────

    /** A lowpass keeps the low tone and loses the high one; the highpass does the opposite. */
    @Test
    fun `the filter passes its own band and stops the other`() {
        fun through(response: Response, cutoff: Float, tone: Float): Float {
            val filter = StateVariableFilter(RATE, response, cutoff)
            filter.input = Oscillator(RATE, Wave.Sine, frequency = tone)
            val block = FloatArray(RATE / 4)
            filter.render(block, 0, block.size)
            // The first few milliseconds are the filter settling from silence, and averaging them in
            // understates the passband. Measured over the back half, where it has arrived.
            return rms(block, from = block.size / 2)
        }
        assertTrue(through(Response.LowPass, 1_000f, 100f) > 0.6f, "the lowpass ate its own passband")
        assertTrue(through(Response.LowPass, 1_000f, 10_000f) < 0.05f, "the lowpass passed 10 kHz")
        assertTrue(through(Response.HighPass, 1_000f, 10_000f) > 0.6f, "the highpass ate its own passband")
        assertTrue(through(Response.HighPass, 1_000f, 100f) < 0.05f, "the highpass passed 100 Hz")
    }

    /** A bandpass peaks at its cutoff and falls away either side of it. */
    @Test
    fun `the bandpass is loudest at its centre`() {
        fun through(tone: Float): Float {
            val filter = StateVariableFilter(RATE, Response.BandPass, cutoff = 1_000f, q = 4f)
            filter.input = Oscillator(RATE, Wave.Sine, frequency = tone)
            val block = FloatArray(RATE / 4)
            filter.render(block, 0, block.size)
            return rms(block, from = block.size / 2)
        }
        val centre = through(1_000f)
        assertTrue(centre > through(200f) * 3f, "no peak at the centre against 200 Hz")
        assertTrue(centre > through(6_000f) * 3f, "no peak at the centre against 6 kHz")
    }

    /**
     * ⛔ **The claim the whole filter choice rests on**: the cutoff can be swept as fast as you like
     * and the filter stays bounded. A biquad here rings and, at this resonance, runs away.
     */
    @Test
    fun `a cutoff swept every sample stays stable`() {
        val filter = StateVariableFilter(RATE, Response.LowPass, q = 6f)
        val noise = WhiteNoise(seed = 5L)
        var worst = 0f
        for (i in 0 until RATE) {
            // A full sweep of the audible range fifty times a second, which no game would ask for.
            filter.cutoff = 40f + 12_000f * (0.5f + 0.5f * sin(i * 2f * PI.toFloat() * 50f / RATE))
            worst = maxOf(worst, abs(filter.process(noise.next())))
        }
        assertTrue(worst < 20f, "the filter is running away: peaked at $worst")
        assertTrue(worst > 0.1f, "it produced nothing at all, so this proved nothing")
    }

    // ── Smoothing ─────────────────────────────────────────────────────────────

    /** It approaches, it does not step, and it gets there at the rate it was asked to. */
    @Test
    fun `a smoothed parameter approaches its target without jumping`() {
        val smoothed = Smoothed(RATE, riseSeconds = 0.1f, fallSeconds = 0.1f)
        smoothed.target = 1f

        var previous = smoothed.value
        var biggestStep = 0f
        repeat(RATE / 10) {
            val now = smoothed.next()
            biggestStep = maxOf(biggestStep, abs(now - previous))
            previous = now
        }
        // One time constant is 63.2% of the way. Not "arrived": a single pole never arrives.
        assertNear(0.632f, smoothed.value, 0.01f, "one time constant")
        assertTrue(biggestStep < 0.001f, "it moved $biggestStep in one sample, which is a click")
        assertTrue(smoothed.value < 1f, "a one-pole approach overshot, which it cannot do")
    }

    /**
     * Rising and falling are separately timed — the point of the class.
     *
     * A fast attack and a slow release is what a burn sounds like: it arrives with the key and it
     * dies away with the gas still in the bell.
     */
    @Test
    fun `rise and fall run at their own rates`() {
        val smoothed = Smoothed(RATE, riseSeconds = 0.005f, fallSeconds = 0.5f)
        smoothed.target = 1f
        smoothed.advance(RATE / 100)   // 10 ms: two attack time constants
        assertTrue(smoothed.value > 0.8f, "the fast attack did not arrive: ${smoothed.value}")

        smoothed.target = 0f
        val peak = smoothed.value
        smoothed.advance(RATE / 100)   // the same 10 ms, a fiftieth of the release
        assertTrue(smoothed.value > peak * 0.9f, "the slow release fell like a fast one: ${smoothed.value}")
    }

    /** [Smoothed.snapTo] is the one way to move without a ramp, for a voice being re-used. */
    @Test
    fun `snapping moves both ends at once`() {
        val smoothed = Smoothed(RATE)
        smoothed.snapTo(0.5f)
        assertEquals(0.5f, smoothed.value)
        assertEquals(0.5f, smoothed.next(), "a snapped value drifted on the next sample")
    }

    // ── Mixing ────────────────────────────────────────────────────────────────

    /** Sources sum, and the sum is bounded however many of them agree. */
    @Test
    fun `the mixer sums its sources and never leaves full scale`() {
        val loud = Array(8) { Oscillator(RATE, Wave.Square, frequency = 110f) }
        val mixer = Mixer(*loud)
        val out = FloatArray(512)
        mixer.renderBlock(out, out.size)

        assertTrue(peak(out) <= 1f, "eight square waves left full scale: ${peak(out)}")
        assertTrue(peak(out) > 0.9f, "they should be pinned against the limiter, not quiet")
    }

    /** A block is filled, not added to: what the last one left must not be audible in this one. */
    @Test
    fun `each block starts from silence`() {
        val mixer = Mixer(AudioSource { out, from, to -> for (i in from until to) out[i] += 0.25f })
        val out = FloatArray(64)
        mixer.renderBlock(out, out.size)
        mixer.renderBlock(out, out.size)
        assertNear(0.25f, out[0], 1e-4f, "the second block accumulated onto the first")
    }

    /** And a silent roster really is silent — a mixer of nothing does not hum. */
    @Test
    fun `nothing playing is silence`() {
        val out = FloatArray(64) { 0.9f }
        Mixer().renderBlock(out, out.size)
        assertEquals(0f, peak(out), "a mixer with no sources left something behind")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun rms(block: FloatArray, from: Int = 0): Float {
        var sum = 0.0
        for (i in from until block.size) sum += block[i].toDouble() * block[i]
        return sqrt(sum / (block.size - from)).toFloat()
    }

    private fun mean(block: FloatArray): Float {
        var sum = 0.0
        for (v in block) sum += v
        return (sum / block.size).toFloat()
    }

    private fun peak(block: FloatArray): Float {
        var worst = 0f
        for (v in block) worst = maxOf(worst, abs(v))
        return worst
    }

    /** How much of [block] survives a [response] filter at [cutoff] — a crude but honest band meter. */
    private fun bandEnergy(block: FloatArray, response: Response, cutoff: Float): Float {
        val filter = StateVariableFilter(RATE, response, cutoff)
        filter.prepare()
        var sum = 0.0
        for (v in block) {
            val out = filter.processPrepared(v)
            sum += out.toDouble() * out
        }
        return sqrt(sum / block.size).toFloat()
    }

    private fun assertNear(expected: Float, actual: Float, tolerance: Float, what: String) {
        assertTrue(abs(expected - actual) <= tolerance, "$what: expected ~$expected, got $actual")
    }

    private companion object {
        /** The rate everything here is measured at; nothing in the package depends on it. */
        const val RATE = 48_000
    }
}
