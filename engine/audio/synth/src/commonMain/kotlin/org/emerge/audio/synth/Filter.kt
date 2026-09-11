package org.emerge.audio.synth

import kotlin.math.PI
import kotlin.math.tan

/** Which of a [StateVariableFilter]'s three simultaneous outputs is wanted. */
enum class Response {
    /** Keeps what is below the cutoff: the body of a rumble. */
    LowPass,

    /** Keeps a band around it: a resonance, a formant, the "throat" of a jet. */
    BandPass,

    /** Keeps what is above it: hiss, air, the top of a hot exhaust. */
    HighPass,
}

/**
 * **A filter whose cutoff can be swept every sample without exploding** — a topology-preserving
 * state-variable filter, after Simper's derivation of the analogue circuit.
 *
 * ### Why this one, and not a biquad
 *
 * A biquad is cheaper and is the right answer for a filter that is *set* and then left alone. Every
 * filter in a game sound is the other kind: the cutoff is a function of how fast the exhaust is
 * going, how hurt the ship is, how close the listener stands, and it moves continuously. A biquad's
 * coefficients are a nonlinear function of its cutoff, so sweeping one makes its internal state
 * briefly meaningless — it rings, and at high resonance it blows up. This form stores its state as
 * the two integrator outputs, which stay meaningful whatever the coefficients do, so it can be swept
 * from 20 Hz to 20 kHz between one sample and the next and merely *sound* like that.
 *
 * ⚠️ It also gives all three responses from the same arithmetic, which is why [response] is a
 * selection rather than three classes.
 *
 * ### The cost
 *
 * One `tan` per coefficient update. Recomputed only when [cutoff] or [q] actually move, so a static
 * filter pays for it once and a swept one pays it once per block, not once per sample — see
 * [prepare], which every entry point calls.
 */
class StateVariableFilter(
    private val sampleRate: Int,
    var response: Response = Response.LowPass,
    cutoff: Float = 1_000f,
    q: Float = 0.7071f,
) : AudioSource {

    /**
     * Corner frequency in hertz. Clamped on use to `[20, 0.45 × sampleRate]` — the top end because
     * `tan` goes to infinity at Nyquist and takes the filter with it.
     */
    var cutoff: Float = cutoff

    /**
     * Resonance. `0.7071` is flat (Butterworth); above about 4 the peak is a whistle in its own
     * right, which is a sound and not a fault. Clamped at the bottom so a caller's zero is a filter
     * rather than an infinity.
     */
    var q: Float = q

    /** What the filter is filtering, when it is used as a source rather than sample by sample. */
    var input: AudioSource? = null

    private var ic1 = 0f
    private var ic2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var a3 = 0f
    private var k = 0f
    private var lastCutoff = Float.NaN
    private var lastQ = Float.NaN

    /** Scratch for [render], sized on first use — never on the audio thread more than once. */
    private var scratch = FloatArray(0)

    /**
     * Filters [input] into [out].
     *
     * ⚠️ **Needs a buffer of its own**, because a filter is the one thing here that cannot work in
     * the additive convention: it has to see its input *alone*, and [out] already holds whatever
     * earlier sources put there. The scratch grows once to the largest block it is ever handed and
     * is then reused for ever.
     */
    override fun render(out: FloatArray, from: Int, to: Int) {
        val source = input ?: return
        val count = to - from
        if (count <= 0) return
        if (scratch.size < count) scratch = FloatArray(count)
        scratch.fill(0f, 0, count)
        source.render(scratch, 0, count)
        prepare()
        for (i in 0 until count) out[from + i] += processPrepared(scratch[i])
    }

    /**
     * One sample in, one out — for a voice that is assembling its own chain and does not want a
     * buffer per stage.
     *
     * ⚠️ Recomputes the coefficients per call when the dials have moved. That is the same `tan` the
     * block form amortises over a whole block, so a voice sweeping a filter *and* going sample by
     * sample should hoist [prepare] itself and call [processPrepared].
     */
    fun process(sample: Float): Float {
        prepare()
        return processPrepared(sample)
    }

    /**
     * Brings the coefficients up to date with [cutoff] and [q], if either has moved.
     *
     * Public because a voice rendering sample by sample wants to call it **once per block** and then
     * use [processPrepared] — which is the whole of the difference between one `tan` a block and one
     * `tan` a sample.
     */
    fun prepare() {
        val fc = cutoff.coerceIn(MIN_CUTOFF, sampleRate * MAX_CUTOFF_FRACTION)
        val res = q.coerceAtLeast(MIN_Q)
        if (fc == lastCutoff && res == lastQ) return
        lastCutoff = fc
        lastQ = res
        val g = tan(PI.toFloat() * fc / sampleRate)
        k = 1f / res
        a1 = 1f / (1f + g * (g + k))
        a2 = g * a1
        a3 = g * a2
    }

    /** One sample through the filter as it is currently tuned — see [prepare]. */
    fun processPrepared(sample: Float): Float {
        val v3 = sample - ic2
        val v1 = a1 * ic1 + a2 * v3
        val v2 = ic2 + a2 * ic1 + a3 * v3
        ic1 = 2f * v1 - ic1
        ic2 = 2f * v2 - ic2
        return when (response) {
            Response.LowPass -> v2
            Response.BandPass -> v1
            Response.HighPass -> sample - k * v1 - v2
        }
    }

    /** Forgets everything the filter has heard — for a voice being reused for a new sound. */
    fun reset() {
        ic1 = 0f
        ic2 = 0f
    }

    private companion object {
        const val MIN_CUTOFF = 20f
        const val MIN_Q = 0.05f

        /** Short of Nyquist, where `tan` is infinite and so is the filter. */
        const val MAX_CUTOFF_FRACTION = 0.45f
    }
}
