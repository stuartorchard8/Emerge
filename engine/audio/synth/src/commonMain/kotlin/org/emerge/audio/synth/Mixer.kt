package org.emerge.audio.synth

/**
 * **Everything that is making a noise, summed into one buffer** — the thing a host asks for samples.
 *
 * ### ⛔ A fixed roster, and no way to add a voice
 *
 * The sources are handed over once, at construction, and the list never changes. That is not a
 * simplification waiting to be lifted; it is what makes the whole package safe to use across two
 * threads without a single lock. A game thread that could add and remove voices would be mutating a
 * list an audio thread is walking, at a moment it cannot be told about — and the fixes for that are
 * a lock (which can make the audio thread wait, which is a dropout), or a concurrent queue (which
 * allocates), or an atomic swap of the whole list (which is a lock-free algorithm to get right and
 * to maintain).
 *
 * A fixed pool needs none of it. A voice that is not wanted turns its own gain down and renders
 * nothing; a voice that is wanted again turns it up. Both are a `@Volatile` float, which is the one
 * cross-thread operation with no failure mode. **Allocate the pool for the worst case and assign out
 * of it** — Out of Space's exhaust does exactly that, with one voice per engine a vessel could
 * plausibly carry and a voice claimed by whichever is nearest when there are more.
 *
 * ⚠️ **[gain] is the only master control, and there is deliberately no per-source gain here.** A
 * source that wants to be quieter knows why it wants to be quieter, and a second place to set a
 * volume is a second place for one to go missing.
 */
class Mixer(private vararg val sources: AudioSource) {

    /**
     * The master fader, `0` to `1`. Smoothed by whoever writes it if it is going to move while
     * sound is playing — a jump here is a jump in every voice at once, which is the most audible
     * click available.
     */
    @kotlin.concurrent.Volatile
    var gain: Float = 1f

    /**
     * Fills `out[0 until frames]` with the next block: clear, sum every source, fade, limit.
     *
     * ⚠️ **Clears rather than accumulating**, unlike [AudioSource.render] itself. This is the top of
     * the tree: nothing wrote to the buffer before it, and what was in it is whatever the last block
     * left there — which, played, is that block again.
     */
    fun renderBlock(out: FloatArray, frames: Int) {
        val n = minOf(frames, out.size)
        if (n <= 0) return
        out.fill(0f, 0, n)
        for (source in sources) source.render(out, 0, n)
        val g = gain
        for (i in 0 until n) out[i] = softClip(out[i] * g)
    }
}
