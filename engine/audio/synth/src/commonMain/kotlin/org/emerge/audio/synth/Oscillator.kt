package org.emerge.audio.synth

import kotlin.math.PI
import kotlin.math.sin

/** The shape one cycle traces. */
enum class Wave {
    /** One partial and nothing else: a pure tone, a hum, the body of a whistle. */
    Sine,

    /** Odd partials falling off fast: soft, hollow, a flute or an ocarina. */
    Triangle,

    /** Every partial: bright and brassy, and what a filter has something to bite on. */
    Saw,

    /** Odd partials only: hollow and reedy, and the loudest of the four for its amplitude. */
    Square,
}

/**
 * **A tone at a frequency** — a phase that wraps, read through one of four shapes.
 *
 * Not band-limited. A [Wave.Saw] or [Wave.Square] with a corner in it aliases at high frequencies,
 * folding energy back down the spectrum as a metallic ring. That is a real limitation and it is the
 * right trade here: the sounds this package exists for are noise-dominated, the tonal layers in them
 * sit low, and a band-limited oscillator is several times the cost and a table to go with it. Keep a
 * saw below about a tenth of the sample rate, or filter it, and nothing is audible.
 *
 * ⚠️ **[frequency] may be written from another thread** — it is what a game modulates. Changing it
 * does not reset the phase, so a swept tone is continuous and nothing clicks.
 */
class Oscillator(
    private val sampleRate: Int,
    var wave: Wave = Wave.Sine,
    frequency: Float = 440f,
    var amplitude: Float = 1f,
) : AudioSource {

    /** Hertz. Clamped to something sane on read rather than on write, so a writer cannot be wrong. */
    var frequency: Float = frequency

    /**
     * Where in the cycle it is, in `[0, 1)`.
     *
     * A normalised phase rather than radians, because three of the four shapes are arithmetic on
     * exactly this number and only the sine wants an angle — so the conversion happens once, in the
     * one branch that needs it, instead of being undone in the other three.
     */
    private var phase: Float = 0f

    override fun render(out: FloatArray, from: Int, to: Int) {
        val gain = amplitude
        if (gain == 0f) {
            // Still advanced, not skipped: a silent oscillator that stops counting comes back at
            // the phase it left, which is a click exactly when a voice fades back in.
            //
            // ⚠️ **One sample at a time, and not `advance(to − from)`.** The two are the same
            // number in algebra and not in floats: a phase accumulated in n small steps drifts from
            // one taken in a single multiply, so the block form would come back a few samples out
            // and the whole point of advancing would be lost. Measured at ~1e-4 of a cycle over
            // 10 ms — inaudible, and exactly the sort of "nearly right" that survives a test.
            for (i in from until to) advance(1)
            return
        }
        for (i in from until to) out[i] += next() * gain
    }

    /** One sample at unit amplitude, for a caller doing its own shaping. */
    fun next(): Float {
        val value = shapeOf(phase)
        advance(1)
        return value
    }

    /** Puts the cycle back at its start — for a voice that wants a note to begin somewhere known. */
    fun reset() {
        phase = 0f
    }

    private fun advance(samples: Int) {
        val step = frequency.coerceIn(0f, sampleRate * 0.5f) / sampleRate
        phase += step * samples
        // A subtraction rather than a modulo: `phase` is small and this runs per sample. The `while`
        // is for the block form above, where a whole block's worth can be several cycles.
        while (phase >= 1f) phase -= 1f
        while (phase < 0f) phase += 1f
    }

    private fun shapeOf(p: Float): Float = when (wave) {
        Wave.Sine -> sin(p * TWO_PI)
        Wave.Triangle -> if (p < 0.5f) 4f * p - 1f else 3f - 4f * p
        Wave.Saw -> 2f * p - 1f
        Wave.Square -> if (p < 0.5f) 1f else -1f
    }

    private companion object {
        const val TWO_PI = (2.0 * PI).toFloat()
    }
}
