package org.emerge.audio

import java.nio.ByteBuffer
import java.nio.IntBuffer
import org.lwjgl.openal.AL
import org.lwjgl.openal.ALC
import org.lwjgl.openal.ALC10.alcCloseDevice
import org.lwjgl.openal.ALC10.alcCreateContext
import org.lwjgl.openal.ALC10.alcDestroyContext
import org.lwjgl.openal.ALC10.alcMakeContextCurrent
import org.lwjgl.openal.ALC10.alcOpenDevice
import org.lwjgl.system.MemoryUtil

/**
 * **The one sound device, shared by everything in this module.**
 *
 * ⛔ **There can only be one.** OpenAL's "current context" is a property of the *process* — LWJGL's
 * `AL.createCapabilities` sets it with `setCurrentProcess` — so a second device opened beside the
 * first does not give you a second mixer, it silently replaces the first one and every source
 * belonging to it stops. That is exactly what happened the moment this module grew a second client:
 * [OggSfxPlayer] owned the device outright, and a [SynthStream] constructed next to it either killed
 * the clips or was killed by them depending on which was made last.
 *
 * So the device is a refcounted singleton. Every client [retain]s on construction and [release]s when
 * it is done, and the last one out shuts the door.
 *
 * ### Failure is silence
 *
 * A machine with no sound card gets [ready] `== false`, every client turns into a no-op, and the
 * game runs. Audio is the one subsystem where refusing to start is worse than the thing it was
 * protecting.
 */
internal object AlDevice {

    private var device: Long = MemoryUtil.NULL
    private var context: Long = MemoryUtil.NULL
    private var users = 0

    /** False when there is no sound device, or its context could not be made. */
    var ready: Boolean = false
        private set

    /**
     * Opens the device if nobody had, and counts one more user. Returns [ready].
     *
     * `@Synchronized` because clients are constructed from whatever thread a host happens to be on
     * — a GL thread here, a main thread there — and two of them racing would open two devices, which
     * is the failure this object exists to prevent.
     */
    @Synchronized
    fun retain(): Boolean {
        if (users == 0) open()
        users++
        return ready
    }

    /** One fewer user. The last one closes the device. */
    @Synchronized
    fun release() {
        if (users == 0) return
        users--
        if (users > 0 || !ready) return
        alcMakeContextCurrent(MemoryUtil.NULL)
        alcDestroyContext(context)
        alcCloseDevice(device)
        device = MemoryUtil.NULL
        context = MemoryUtil.NULL
        ready = false
    }

    private fun open() {
        device = alcOpenDevice(null as ByteBuffer?)
        if (device == MemoryUtil.NULL) {
            System.err.println("audio: no OpenAL device; sound disabled")
            return
        }
        context = alcCreateContext(device, null as IntBuffer?)
        if (context == MemoryUtil.NULL) {
            System.err.println("audio: OpenAL context could not be created; sound disabled")
            alcCloseDevice(device)
            device = MemoryUtil.NULL
            return
        }
        alcMakeContextCurrent(context)
        ALC.createCapabilities(device)
        // ⚠️ Sets the capabilities for the **process**, not for this thread — which is what lets a
        // [SynthStream]'s pump thread call AL functions at all. See LWJGL's `AL.createCapabilities`,
        // which ends in `setCurrentProcess`.
        AL.createCapabilities(ALC.getCapabilities())
        ready = true
    }
}
