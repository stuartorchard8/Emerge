package org.emerge.audio.synth

/**
 * **Anything that can put samples in a buffer.** One channel, floats nominally in `[-1, 1]`, at
 * whatever rate the thing that made it was told.
 *
 * ### ⛔ It ADDS; it does not write
 *
 * `out[i] += …`, never `out[i] = …`, and that one decision is what makes the rest of this package
 * small. Mixing is then free — a [Mixer] clears the buffer once and hands the same slice to every
 * source in turn — and a source that has nothing to say this block does nothing at all rather than
 * having to write silence over somebody else's sound. A source that overwrites will *work*, alone,
 * and will silence every voice ahead of it the moment there is a second one.
 *
 * ### Real-time rules
 *
 * A host calls this from an audio thread, on a deadline measured in milliseconds, where being late
 * is a click and a click is worse than being wrong. So:
 *
 * - ⛔ **Allocate nothing.** Every buffer, table and filter state a source needs is made when the
 *   source is, and reused for the life of it.
 * - ⛔ **Block on nothing** — no lock, no atomic that can spin, no IO.
 * - ⚠️ **Parameters arrive through plain `@Volatile` floats**, written by a game thread and read
 *   here. A float write is atomic on every platform this runs on, so a reader sees either the old
 *   value or the new one; what it must never see is a *jump*, which is what [Smoothed] is for.
 *
 * It is a `fun interface` on purpose: the simplest possible synthesis function is a lambda, and one
 * ought to be usable wherever a whole class would be.
 */
fun interface AudioSource {

    /**
     * Adds this source's next `to − from` samples into [out], between [from] inclusive and [to]
     * exclusive.
     *
     * ⚠️ **The index is a position in the buffer, not in time.** A source keeps its own phase,
     * envelope and filter state across calls; handing it `0..64` twice is two consecutive blocks of
     * sound and not the same block twice.
     */
    fun render(out: FloatArray, from: Int, to: Int)
}
