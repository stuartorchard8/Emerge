package org.emerge.audio.synth

import kotlin.math.exp

/**
 * **A number that will not jump** — the single most important object in this package, and the reason
 * a sound driven by a simulation does not crackle.
 *
 * A game writes [target] whenever it likes, from whatever thread it likes, at whatever rate it
 * likes: once a tick, once a frame, in bursts, not at all for a second. The audio thread reads
 * [next] once per sample and gets a value that moves toward the target exponentially and never
 * steps. Feed a raw parameter straight into a gain or a frequency instead and every change is a
 * discontinuity in the waveform, which an ear hears as a click — and a sim that updates sixty-four
 * times a second produces sixty-four clicks a second, which is itself a tone.
 *
 * ### Rise and fall are separate, and that is the whole design
 *
 * They are different musical events. A thruster lighting should arrive *now* — a few milliseconds,
 * so the sound and the press feel simultaneous — and a thruster shutting should fall away over a
 * tenth of a second, because a jet has gas still in it and an instant stop sounds like a cut tape.
 * One time constant cannot be both: fast enough to feel responsive is fast enough to click on the
 * way down.
 *
 * ### ⚠️ It is one pole, so it never quite arrives
 *
 * After [riseSeconds] it is 63% of the way, after three of them 95%, and it approaches the target
 * for ever without reaching it. That is the right behaviour for a gain and the wrong assumption for
 * a test: assert an approach, not an arrival. [snapTo] is there for the one case that genuinely
 * wants no ramp at all — a voice being handed to a new sound, which has nothing to glide from.
 */
class Smoothed(
    private val sampleRate: Int,
    /** How long a rise takes to cover 63% of its distance. */
    var riseSeconds: Float = 0.01f,
    /** The same going down. Usually the longer of the two — see the class note. */
    var fallSeconds: Float = 0.08f,
    initial: Float = 0f,
) {
    /**
     * Where it is heading.
     *
     * ⚠️ **Written from another thread**, which is safe for exactly one reason: a `Float` write is
     * atomic on every platform here, so a reader sees the old value or the new one and never half of
     * each. Anything wider than 32 bits would need more than a keyword.
     */
    @kotlin.concurrent.Volatile
    var target: Float = initial

    /** Where it actually is. Read this rather than [target] for anything a listener can hear. */
    var value: Float = initial
        private set

    /** The next value, one sample on. */
    fun next(): Float {
        val goal = target
        value += (goal - value) * if (goal > value) riseCoefficient() else fallCoefficient()
        return value
    }

    /** Advances [samples] samples at once, for a caller that only needs the value at a block edge. */
    fun advance(samples: Int): Float {
        for (i in 0 until samples) next()
        return value
    }

    /** Both ends at once: no glide, no click to worry about because there is nothing to glide from. */
    fun snapTo(v: Float) {
        target = v
        value = v
    }

    // ⚠️ **Cached, because this is per sample and `exp` is not free.** A voice with six of these in
    // it would otherwise spend six transcendental calls per sample doing arithmetic whose inputs
    // change about once a second. Recomputed only when the time constant itself is written.
    private var lastRise = Float.NaN
    private var lastFall = Float.NaN
    private var rise = 0f
    private var fall = 0f

    private fun riseCoefficient(): Float {
        if (riseSeconds != lastRise) {
            lastRise = riseSeconds
            rise = coefficientFor(riseSeconds)
        }
        return rise
    }

    private fun fallCoefficient(): Float {
        if (fallSeconds != lastFall) {
            lastFall = fallSeconds
            fall = coefficientFor(fallSeconds)
        }
        return fall
    }

    /**
     * The per-sample fraction of the remaining distance to cover, for a time constant of [seconds].
     *
     * `1 − e^(−1/(τ·fs))` — the exact one-pole coefficient rather than the `τ·fs` approximation
     * everybody writes, because the approximation is only good while the time constant is long
     * against a sample, and the interesting case here is a two-millisecond attack where it is not.
     */
    private fun coefficientFor(seconds: Float): Float {
        if (seconds <= 0f) return 1f
        return 1f - exp(-1f / (seconds * sampleRate))
    }
}

/**
 * A gentle limiter: unity gain for anything quiet, bending over towards ±1, and never past it.
 *
 * ⚠️ **Something has to do this.** Five noise layers and a couple of oscillators, each perfectly
 * reasonable alone, sum past 1.0 the moment they agree — and a sample past full scale does not merely
 * get louder, it *wraps* or hard-clips in the conversion to integers, which is the loudest and
 * ugliest noise a program can make. `tanh` is the textbook answer and this is its cheap cousin — a
 * cubic, monotone, continuous in value and slope, and close enough that nothing can tell.
 */
fun softClip(sample: Float): Float {
    // ⛔ **Below the knee it is the identity, exactly.** The textbook cubic `u − u³/3` is smooth and
    // bounded and is *not* this: it starts bending at zero, so a quiet mix comes back a per cent
    // down and a loud one several — a change of mix dressed up as a safety net. Everything a game
    // actually plays lives below the knee and must come out as it went in.
    //
    // Above it, `u/(1+u)` — which starts with slope 1 (so there is no corner at the knee), rises for
    // ever, and approaches 1 without reaching it. No input, however absurd, leaves full scale.
    val magnitude = if (sample < 0f) -sample else sample
    if (magnitude <= KNEE) return sample
    val over = (magnitude - KNEE) / (1f - KNEE)
    val bent = KNEE + (1f - KNEE) * (over / (1f + over))
    return if (sample < 0f) -bent else bent
}

/** Below this, nothing happens to the signal at all. */
private const val KNEE = 0.5f

/**
 * **Decibels as a gain** — `10^(dB/20)`, so −6 dB is about half the amplitude and −60 is silence.
 *
 * Here because every volume in a mix wants to be stated this way and worked with the other: an ear
 * hears ratios, so "half as loud" is a fixed number of decibels and a wildly different number of
 * amplitude units depending on where you started.
 */
fun decibels(db: Float): Float = exp(db * LN10_OVER_20)

private const val LN10_OVER_20 = 0.11512925f

/**
 * **Semitones as a frequency ratio** — twelve of them double the pitch.
 *
 * The pitch twin of [decibels], and wanted for the same reason: an ear hears pitch as a ratio, so a
 * sound that should be "a bit lower when the thing is heavier" wants to be detuned by an interval
 * and not by a number of hertz.
 */
fun semitones(steps: Float): Float = exp(steps * LN2_OVER_12)

private const val LN2_OVER_12 = 0.057762265f
