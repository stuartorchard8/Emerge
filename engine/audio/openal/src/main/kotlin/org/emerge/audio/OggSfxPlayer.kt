package org.emerge.audio

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.JarURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.nio.ShortBuffer
import java.util.jar.JarFile
import org.lwjgl.openal.AL
import org.lwjgl.openal.AL10.AL_BUFFER
import org.lwjgl.openal.AL10.AL_FORMAT_MONO16
import org.lwjgl.openal.AL10.AL_FORMAT_STEREO16
import org.lwjgl.openal.AL10.AL_GAIN
import org.lwjgl.openal.AL10.AL_NO_ERROR
import org.lwjgl.openal.AL10.AL_PITCH
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
import org.lwjgl.openal.AL10.alSourcef
import org.lwjgl.openal.AL10.alSourcei
import org.lwjgl.openal.ALC
import org.lwjgl.openal.ALC10.alcCloseDevice
import org.lwjgl.openal.ALC10.alcCreateContext
import org.lwjgl.openal.ALC10.alcDestroyContext
import org.lwjgl.openal.ALC10.alcMakeContextCurrent
import org.lwjgl.openal.ALC10.alcOpenDevice
import org.lwjgl.stb.STBVorbis.stb_vorbis_decode_memory
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.MemoryUtil.memAlloc
import org.lwjgl.system.MemoryUtil.memFree

/**
 * A directory of OGG clips, decoded and sitting in the sound card — see [OggSfxPlayer.loadBank].
 *
 * Opaque on purpose: what a caller does with a bank is ask how many clips are in it and play one of
 * them, and an OpenAL buffer id is not something a game should be able to get hold of.
 */
class ClipBank internal constructor(internal val buffers: List<Int>) {
    val size: Int get() = buffers.size

    companion object {
        /** What a failed load hands back, so that no host has to branch on a null. */
        val EMPTY = ClipBank(emptyList())
    }
}

/**
 * Fire-and-forget sound effects on the desktop: OGG in, a bang out, at a volume and a pitch.
 *
 * ### What this is not
 *
 * It is **not** a game's audio system, and the split matters. Which clip a collision picks, how loud
 * a distant one should be, and how soon the same thing may be heard twice are all judgements about a
 * game, they must be identical on every platform a game runs on, and they therefore live in that
 * game's shared code. What is left here is the part that is genuinely about this machine: an OpenAL
 * device, a Vorbis decoder, and a fixed number of voices.
 *
 * Lifted out of scavengers' desktop host, where it grew, when Out of Space wanted the same thing.
 * Both hosts wrap it in a handful of lines that implement their own game's audio interface.
 *
 * ### Failure is silence
 *
 * A machine with no sound device, a build with no clips in it, and a clip that will not decode all
 * end the same way: [ready] is false or the bank is empty, [play] does nothing, and the game runs.
 * Audio is the one subsystem where refusing to start is worse than the thing it was protecting.
 */
class OggSfxPlayer(
    /**
     * How many clips may be sounding at once. The oldest is stopped to make room, which is the right
     * end to drop from — the newest bang is the one the player is looking at.
     */
    private val maxVoices: Int = 16,
    /** A master gain over everything this player emits. */
    private val busGain: Float = 1f,
) {
    private val device: Long = alcOpenDevice(null as ByteBuffer?)
    private val context: Long =
        if (device != MemoryUtil.NULL) alcCreateContext(device, null as IntBuffer?) else MemoryUtil.NULL
    private val banks = mutableListOf<ClipBank>()
    private val activeSources = ArrayDeque<Int>()

    /** False when there is no sound device, or its context could not be made. */
    var ready = false
        private set

    init {
        if (device == MemoryUtil.NULL || context == MemoryUtil.NULL) {
            System.err.println("audio: OpenAL init failed; sound effects disabled")
        } else {
            alcMakeContextCurrent(context)
            ALC.createCapabilities(device)
            AL.createCapabilities(ALC.getCapabilities())
            ready = true
        }
    }

    /**
     * Every `.ogg` directly inside [resourceDir] on the classpath, decoded, **sorted by name** — so
     * that a clip index means the same clip on every machine and in every build.
     */
    fun loadBank(resourceDir: String): ClipBank {
        if (!ready) return ClipBank.EMPTY
        val paths = listOggResources(resourceDir)
        if (paths.isEmpty()) {
            System.err.println("audio: no OGG clips found at $resourceDir")
            return ClipBank.EMPTY
        }
        val buffers = mutableListOf<Int>()
        for (path in paths.sorted()) {
            decodeToOpenAlBuffer(path)?.let { buffers += it }
        }
        if (buffers.isEmpty()) {
            System.err.println("audio: failed to decode clips in $resourceDir")
            return ClipBank.EMPTY
        }
        val bank = ClipBank(buffers)
        banks += bank
        return bank
    }

    /** [clipIndex] is clamped rather than checked: a caller's off-by-one is not worth silence. */
    fun play(bank: ClipBank, clipIndex: Int, volume: Float, pitch: Float) {
        if (!ready || bank.buffers.isEmpty()) return

        reapFinishedSources()
        if (activeSources.size >= maxVoices) {
            activeSources.removeFirstOrNull()?.let { alDeleteSources(it) }
        }
        val source = alGenSources()
        alSourcei(source, AL_BUFFER, bank.buffers[clipIndex.coerceIn(0, bank.buffers.lastIndex)])
        alSourcef(source, AL_GAIN, volume.coerceIn(0f, 1f) * busGain)
        alSourcef(source, AL_PITCH, pitch.coerceIn(0.6f, 1.5f))
        alSourcePlay(source)
        if (alGetError() != AL_NO_ERROR) {
            alDeleteSources(source)
            return
        }
        activeSources.addLast(source)
    }

    fun release() {
        if (!ready) return
        for (source in activeSources) alDeleteSources(source)
        activeSources.clear()
        for (bank in banks) for (buffer in bank.buffers) alDeleteBuffers(buffer)
        banks.clear()
        alcMakeContextCurrent(MemoryUtil.NULL)
        alcDestroyContext(context)
        alcCloseDevice(device)
        ready = false
    }

    private fun reapFinishedSources() {
        if (activeSources.isEmpty()) return
        val finished = mutableListOf<Int>()
        for (source in activeSources) {
            if (alGetSourcei(source, AL_SOURCE_STATE) != AL_PLAYING) finished += source
        }
        for (source in finished) {
            activeSources.remove(source)
            alDeleteSources(source)
        }
    }

    private fun decodeToOpenAlBuffer(resourcePath: String): Int? {
        val bytes = readResourceBytes(resourcePath) ?: return null
        val oggBuffer = memAlloc(bytes.size)
        oggBuffer.put(bytes)
        oggBuffer.flip()
        return try {
            MemoryStack.stackPush().use { stack ->
                val channels = stack.mallocInt(1)
                val sampleRate = stack.mallocInt(1)
                val pcm: ShortBuffer = stb_vorbis_decode_memory(oggBuffer, channels, sampleRate) ?: return null
                try {
                    val format = when (channels[0]) {
                        1 -> AL_FORMAT_MONO16
                        2 -> AL_FORMAT_STEREO16
                        else -> return null
                    }
                    val buffer = alGenBuffers()
                    alBufferData(buffer, format, pcm, sampleRate[0])
                    if (alGetError() != AL_NO_ERROR) {
                        alDeleteBuffers(buffer)
                        return null
                    }
                    buffer
                } finally {
                    memFree(pcm)
                }
            }
        } finally {
            memFree(oggBuffer)
        }
    }

    // ── Finding the clips ─────────────────────────────────────────────────────────
    //
    // Three ways, because a classpath directory is three different things depending on how the game
    // was started: unpacked next to the classes when Gradle runs it, an entry in a jar when it is
    // distributed, and — when neither answers — a plain directory relative to the working directory,
    // which is what makes `assets/` work for a run launched from the repo root.

    private fun listOggResources(dirPath: String): List<String> {
        val classLoader = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
        val all = LinkedHashSet<String>()
        for (url in classLoader.getResources(dirPath).toList()) {
            when (url.protocol) {
                "file" -> all += listFileProtocolResources(dirPath, url)
                "jar" -> all += listJarProtocolResources(url)
            }
        }
        if (all.isEmpty()) all += listFilesystemFallbackResources(dirPath)
        return all.toList()
    }

    private fun listFileProtocolResources(dirPath: String, url: URL): List<String> {
        val uri = try {
            url.toURI()
        } catch (_: Throwable) {
            return emptyList()
        }
        return listOggFilenames(java.nio.file.Paths.get(uri)).map { "$dirPath/$it" }
    }

    private fun listJarProtocolResources(url: URL): List<String> {
        val connection = (url.openConnection() as? JarURLConnection) ?: return emptyList()
        val jar: JarFile = connection.jarFile
        val prefix = connection.entryName.trimEnd('/') + "/"
        val output = mutableListOf<String>()
        val entries = jar.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val name = entry.name
            if (entry.isDirectory || !name.startsWith(prefix)) continue
            if (!name.endsWith(".ogg", ignoreCase = true)) continue
            // Directly inside, not nested — a bank is a directory and not a tree.
            if (name.removePrefix(prefix).contains('/')) continue
            output += name
        }
        return output
    }

    private fun listFilesystemFallbackResources(dirPath: String): List<String> =
        listOggFilenames(java.nio.file.Paths.get(dirPath)).map { "$dirPath/$it" }

    private fun listOggFilenames(dir: java.nio.file.Path): List<String> {
        if (!java.nio.file.Files.isDirectory(dir)) return emptyList()
        val output = mutableListOf<String>()
        java.nio.file.Files.list(dir).use { paths ->
            paths.forEach { path ->
                val fileName = path.fileName?.toString() ?: return@forEach
                if (fileName.endsWith(".ogg", ignoreCase = true)) output += fileName
            }
        }
        return output
    }

    private fun readResourceBytes(resourcePath: String): ByteArray? {
        val classLoader = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
        return classLoader.getResourceAsStream(resourcePath)?.readFullyAndClose()
    }

    private fun InputStream.readFullyAndClose(): ByteArray {
        use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
