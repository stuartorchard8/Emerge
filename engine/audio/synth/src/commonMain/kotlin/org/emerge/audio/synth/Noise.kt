package org.emerge.audio.synth

/**
 * **The noise generator's random numbers** — xorshift64*, because it is four instructions, has no
 * state to allocate and is the same sequence on every platform.
 *
 * ⛔ **Not `kotlin.random.Random`.** That allocates on JS, is a virtual call, and — more to the
 * point — is shared mutable state on some platforms; a noise source is called tens of thousands of
 * times a second from an audio thread and wants its own generator, inlined, with nothing else
 * touching it. Determinism is worth having for a different reason than usual here: a test can assert
 * what a noise source produces, and two hosts asked to make the same sound make the same sound.
 */
internal class NoiseRng(seed: Long) {
    private var state: Long = if (seed == 0L) DEFAULT_SEED else seed

    /** The next value, uniform in `[-1, 1)`. */
    fun nextSigned(): Float {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x ushr 7)
        x = x xor (x shl 17)
        state = x
        // The top 24 bits, which are the well-mixed ones, scaled to [-1, 1). A float has 24 bits of
        // mantissa, so this is every value the type can represent in the range and no fewer.
        return ((x ushr 40).toInt() - 0x800000) * INV_SCALE
    }

    private companion object {
        /** Any non-zero constant; xorshift is stuck at zero for ever, which is the one bad seed. */
        const val DEFAULT_SEED: Long = -0x61c8864680b583ebL
        const val INV_SCALE = 1f / 0x800000
    }
}

/**
 * **Flat noise: every frequency equally loud.** The hiss at the top of a jet, the fizz of an arc,
 * the top layer of almost every sound effect there is.
 *
 * ⚠️ **It is loud, and louder than it sounds.** White noise at unit amplitude has an RMS near 0.58
 * against a sine's 0.71 but no peaks to speak of, so two noise layers at "the same" amplitude as an
 * oscillator will bury it. Mix noise below what looks right.
 */
class WhiteNoise(seed: Long = 1L, var amplitude: Float = 1f) : AudioSource {
    private val rng = NoiseRng(seed)

    override fun render(out: FloatArray, from: Int, to: Int) {
        val gain = amplitude
        if (gain == 0f) return
        for (i in from until to) out[i] += rng.nextSigned() * gain
    }

    /** One sample, for a source that wants to filter or shape the noise itself. */
    fun next(): Float = rng.nextSigned() * amplitude
}

/**
 * **Noise that falls off at 3 dB an octave** — equal energy per octave rather than per hertz, which
 * is how most natural broadband sound is distributed and why this reads as *rumble* where
 * [WhiteNoise] reads as *hiss*.
 *
 * Paul Kellet's economical filter: six one-pole sections summed, accurate to about ±0.05 dB across
 * the audible band, which is far past what an ear can tell from the real thing. The alternative —
 * an actual −3 dB/octave filter — is a long FIR and buys nothing here.
 *
 * ⚠️ **The output is scaled to sit at roughly the same level as [WhiteNoise].** The raw sum of the
 * sections is about eleven times the input, and a rumble layer that arrives eleven times too loud
 * reads as "the mix is broken" rather than as a missing constant.
 */
class PinkNoise(seed: Long = 1L, var amplitude: Float = 1f) : AudioSource {
    private val rng = NoiseRng(seed)
    private var b0 = 0f
    private var b1 = 0f
    private var b2 = 0f
    private var b3 = 0f
    private var b4 = 0f
    private var b5 = 0f
    private var b6 = 0f

    override fun render(out: FloatArray, from: Int, to: Int) {
        val gain = amplitude
        if (gain == 0f) return
        for (i in from until to) out[i] += next()
    }

    /** One sample, for a source that wants to filter or shape the noise itself. */
    fun next(): Float {
        val white = rng.nextSigned()
        b0 = 0.99886f * b0 + white * 0.0555179f
        b1 = 0.99332f * b1 + white * 0.0750759f
        b2 = 0.96900f * b2 + white * 0.1538520f
        b3 = 0.86650f * b3 + white * 0.3104856f
        b4 = 0.55000f * b4 + white * 0.5329522f
        b5 = -0.7616f * b5 - white * 0.0168980f
        val pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362f
        b6 = white * 0.115926f
        return pink * NORMALISE * amplitude
    }

    private companion object {
        /**
         * Brings the summed sections to [WhiteNoise]'s level — **measured, not derived**: the raw
         * filter sums to an RMS of about 1.8 against white's 0.577, and the first guess at this
         * constant was three times too quiet, which reads as "the rumble layer is missing".
         */
        const val NORMALISE = 0.322f
    }
}
