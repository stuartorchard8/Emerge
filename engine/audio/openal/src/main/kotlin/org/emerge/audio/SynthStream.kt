package org.emerge.audio

import org.lwjgl.openal.AL10.AL_BUFFERS_PROCESSED
import org.lwjgl.openal.AL10.AL_BUFFERS_QUEUED
import org.lwjgl.openal.AL10.AL_FORMAT_MONO16
import org.lwjgl.openal.AL10.AL_GAIN
import org.lwjgl.openal.AL10.AL_NO_ERROR
import org.lwjgl.openal.AL10.AL_PLAYING
import org.lwjgl.openal.AL10.AL_SOURCE_STATE
import org.lwjgl.openal.AL10.alBufferData
import org.lwjgl.openal.AL10.alDeleteBuffers
import org.lwjgl.openal.AL10.alDeleteSources
import org.lwjgl.openal.AL10.alGenBuffers
import org.lwjgl.openal.AL10.alGenSources
import org.lwjgl.openal.AL10.alGetError
import org.lwjgl.openal.AL10.alGetSourcei
import org.lwjgl.openal.AL10.alSourcePlay
import org.lwjgl.openal.AL10.alSourceQueueBuffers
import org.lwjgl.openal.AL10.alSourceStop
import org.lwjgl.openal.AL10.alSourceUnqueueBuffers
import org.lwjgl.openal.AL10.alSourcef
import org.lwjgl.system.MemoryUtil
import java.nio.ShortBuffer

/**
 * **A speaker that never runs out**, fed by a function instead of by a file.
 *
 * A clip player ([OggSfxPlayer]) answers "play this bang now". This answers the other half: a sound
 * that is *always going on*, whose loudness and colour are functions of the world — an engine, a
 * wind, a reactor. There is no clip to choose, because no recording could hold the sound of a motor
 * whose exhaust velocity is a number the player is changing.
 *
 * ### How it works
 *
 * OpenAL's streaming shape: a source with a small ring of buffers queued on it. A thread wakes every
 * few milliseconds, asks how many buffers the sound card has finished with, refills each of those
 * from [render] and queues it again. The sound card is therefore always a few blocks ahead, which is
 * what makes it immune to a stuttering frame.
 *
 * ⚠️ **[render] is called on the pump thread, never on the caller's.** Everything it touches must be
 * safe to touch there — which, for `org.emerge.audio.synth`, means parameters arrive as `@Volatile`
 * floats and nothing allocates. See that package's `AudioSource`, which states the rules.
 *
 * ### Latency, and why it is not lower
 *
 * [bufferCount] × [blockFrames] samples of sound are in flight at any moment — by default four
 * blocks of 1024 at 48 kHz, which is 85 ms. That is the delay between a key going down and the
 * sound of it changing, and it is the number to lower if the game feels loose. ⛔ **Lower it too far
 * and a single late wake-up empties the queue**, which is a gap, and a gap is a click — far worse
 * than the delay. Four is a conservative default for a game rather than a low figure for an
 * instrument.
 *
 * ### Failure is silence
 *
 * No device, no source, no buffers: [ready] is false, the thread never starts, and the game runs.
 */
class SynthStream(
    /** Samples a second. 48 kHz unless you have a reason; 44.1 resamples on most hardware. */
    val sampleRate: Int = 48_000,
    /** Samples per buffer. A power of two by convention, not by requirement. */
    private val blockFrames: Int = 1_024,
    /** How many buffers are in flight. See the latency note. */
    private val bufferCount: Int = 4,
    /**
     * Fills `out[0 until frames]` with the next block of sound, in `[-1, 1]`.
     *
     * ⛔ **Called from the pump thread.** It must not block, allocate or take a lock the game thread
     * can hold: every one of those is a dropout, and a dropout is a click.
     */
    private val render: (out: FloatArray, frames: Int) -> Unit,
) {
    private val source: Int
    private val buffers: IntArray
    private val block = FloatArray(blockFrames)

    /**
     * The block on its way to the sound card, as 16-bit samples.
     *
     * Off-heap and allocated once: `alBufferData` takes a direct buffer, and a fresh one per block
     * would be a hundred allocations a second on the one thread that must never pause for a
     * collector.
     */
    private val pcm: ShortBuffer

    /** False when there is no sound device. Everything below is then a no-op. */
    val ready: Boolean

    @Volatile
    private var running = false
    private var pump: Thread? = null

    init {
        val haveDevice = AlDevice.retain()
        if (!haveDevice) {
            ready = false
            source = 0
            buffers = IntArray(0)
            pcm = MemoryUtil.memAllocShort(1)
        } else {
            source = alGenSources()
            buffers = IntArray(bufferCount) { alGenBuffers() }
            pcm = MemoryUtil.memAllocShort(blockFrames)
            ready = alGetError() == AL_NO_ERROR
            if (!ready) {
                System.err.println("audio: could not make a streaming source; continuous sound disabled")
                AlDevice.release()
            }
        }
    }

    /** A master fader for everything this stream carries, 0 to 1. Safe to call at any time. */
    fun setGain(gain: Float) {
        if (!ready) return
        alSourcef(source, AL_GAIN, gain.coerceIn(0f, 1f))
    }

    /**
     * Starts the pump. Idempotent — a host that starts twice gets one thread.
     *
     * The thread is a **daemon**: a game that exits without calling [release] should still exit.
     */
    fun start() {
        if (!ready || running) return
        running = true
        // Primed before playing: a source told to play with nothing queued stops immediately and has
        // to be noticed and restarted, which is an audible hiccup at the start of every session.
        for (buffer in buffers) fill(buffer)
        alSourcePlay(source)
        pump = Thread({ pumpLoop() }, "synth-stream").apply {
            isDaemon = true
            // Above a game's own threads: this one has a hard deadline and nothing else here does.
            priority = Thread.NORM_PRIORITY + 2
            start()
        }
    }

    /** Stops the pump, frees the source and lets go of the device. Safe to call twice. */
    fun release() {
        running = false
        pump?.join(SHUTDOWN_MILLIS)
        pump = null
        if (!ready) {
            MemoryUtil.memFree(pcm)
            return
        }
        alSourceStop(source)
        drainQueue()
        alDeleteSources(source)
        for (buffer in buffers) alDeleteBuffers(buffer)
        MemoryUtil.memFree(pcm)
        AlDevice.release()
    }

    private fun pumpLoop() {
        while (running) {
            var processed = alGetSourcei(source, AL_BUFFERS_PROCESSED)
            while (processed-- > 0) {
                val buffer = alSourceUnqueueBuffers(source)
                fill(buffer)
            }
            // ⚠️ **A starved source stops playing and does not start again by itself.** The queue
            // runs dry whenever the machine is busy enough that this thread misses its slot; without
            // this line the sound goes off for the rest of the session, and the cause is a hitch
            // that happened minutes earlier.
            if (alGetSourcei(source, AL_SOURCE_STATE) != AL_PLAYING &&
                alGetSourcei(source, AL_BUFFERS_QUEUED) > 0
            ) {
                alSourcePlay(source)
            }
            // Half a buffer, so there is always a whole one of slack between waking and the queue
            // running out. Sleeping is right rather than spinning: the work is genuinely periodic
            // and a busy-wait on a game's CPU costs a frame somewhere else.
            try {
                Thread.sleep((blockFrames * 500L / sampleRate).coerceAtLeast(1L))
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /** Renders one block into [buffer] and queues it back on the source. */
    private fun fill(buffer: Int) {
        java.util.Arrays.fill(block, 0f)
        render(block, blockFrames)
        pcm.clear()
        for (i in 0 until blockFrames) {
            // Clamped, not wrapped: a sample past full scale that wraps is the loudest noise a
            // program can make, and a caller's limiter failing should cost distortion and not a bang.
            val clamped = block[i].coerceIn(-1f, 1f)
            pcm.put((clamped * Short.MAX_VALUE).toInt().toShort())
        }
        pcm.flip()
        alBufferData(buffer, AL_FORMAT_MONO16, pcm, sampleRate)
        alSourceQueueBuffers(source, buffer)
    }

    /** Unqueues whatever is still on the source, so the buffers can be deleted. */
    private fun drainQueue() {
        var queued = alGetSourcei(source, AL_BUFFERS_QUEUED)
        while (queued-- > 0) alSourceUnqueueBuffers(source)
    }

    private companion object {
        /** Long enough for the pump to finish a block, short enough not to hang an exit. */
        const val SHUTDOWN_MILLIS = 500L
    }
}
