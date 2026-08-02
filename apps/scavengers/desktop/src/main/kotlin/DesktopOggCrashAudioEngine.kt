package org.emerge.desktop

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.JarURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.nio.ShortBuffer
import java.util.jar.JarFile
import org.emerge.demo.scavengers.audio.CrashAudioEngine
import org.emerge.demo.scavengers.audio.CrashSfxRequest
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
import kotlin.collections.sorted

class DesktopOggCrashAudioEngine(
    private val clangResourceDir: String = "assets/audio/clang",
    private val crushResourceDir: String = "assets/audio/crush",
    private val maxVoices: Int = 16,
    private val sfxBusGain: Float = 1f,
) : CrashAudioEngine {
    private val device: Long = alcOpenDevice(null as ByteBuffer?)
    private val context: Long = if (device != MemoryUtil.NULL) alcCreateContext(device, null as IntBuffer?) else MemoryUtil.NULL
    private val clangClipBuffers = mutableListOf<Int>()
    private val crushClipBuffers = mutableListOf<Int>()
    private val activeSources = ArrayDeque<Int>()
    private var ready = false

    init {
        if (device == MemoryUtil.NULL || context == MemoryUtil.NULL) {
            System.err.println("audio: OpenAL init failed; crash SFX disabled")
        } else {
            alcMakeContextCurrent(context)
            ALC.createCapabilities(device)
            AL.createCapabilities(ALC.getCapabilities())
            ready = true
            loadCrashClips()
        }
    }

    override val clangClipCount: Int
        get() = clangClipBuffers.size

    override val crushClipCount: Int
        get() = crushClipBuffers.size

    override fun playCrash(request: CrashSfxRequest) {
        if (!ready) return
        val clipBuffers = if (request.isCrushing) crushClipBuffers else clangClipBuffers
        if (clipBuffers.isEmpty()) return

        reapFinishedSources()
        if (activeSources.size >= maxVoices) {
            val dropped = activeSources.removeFirstOrNull()
            if (dropped != null) {
                alDeleteSources(dropped)
            }
        }
        val clipIndex = request.clipIndex.coerceIn(0, clipBuffers.lastIndex)
        val source = alGenSources()
        alSourcei(source, AL_BUFFER, clipBuffers[clipIndex])
        alSourcef(source, AL_GAIN, request.volume.coerceIn(0f, 1f) * sfxBusGain)
        alSourcef(source, AL_PITCH, request.pitch.coerceIn(0.6f, 1.5f))
        alSourcePlay(source)
        if (alGetError() != AL_NO_ERROR) {
            alDeleteSources(source)
            return
        }
        activeSources.addLast(source)
    }

    override fun release() {
        if (!ready) return
        for (source in activeSources) {
            alDeleteSources(source)
        }
        activeSources.clear()
        for (buffer in crushClipBuffers) {
            alDeleteBuffers(buffer)
        }
        crushClipBuffers.clear()
        for (buffer in clangClipBuffers) {
            alDeleteBuffers(buffer)
        }
        clangClipBuffers.clear()
        alcMakeContextCurrent(MemoryUtil.NULL)
        alcDestroyContext(context)
        alcCloseDevice(device)
        ready = false
    }

    private fun reapFinishedSources() {
        if (activeSources.isEmpty()) return
        val iterator = activeSources.iterator()
        val finished = mutableListOf<Int>()
        while (iterator.hasNext()) {
            val source = iterator.next()
            val state = alGetSourcei(source, AL_SOURCE_STATE)
            if (state != AL_PLAYING) {
                finished += source
            }
        }
        if (finished.isEmpty()) return
        for (source in finished) {
            activeSources.remove(source)
            alDeleteSources(source)
        }
    }

    private fun loadCrashClips() {
        loadClips(clangClipBuffers, clangResourceDir)
        loadClips(crushClipBuffers, crushResourceDir)
    }

    private fun loadClips(buffers: MutableList<Int>, resourceDir: String) {
        val paths = listOggResources(resourceDir)
        if (paths.isEmpty()) {
            System.err.println("audio: no OGG clips found at $resourceDir")
            return
        }
        for (path in paths.sorted()) {
            val bufferId = decodeToOpenAlBuffer(path)
            if (bufferId != null) {
                buffers += bufferId
            }
        }
        if (buffers.isEmpty()) {
            System.err.println("audio: failed to decode clips in $resourceDir")
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

    private fun listOggResources(dirPath: String): List<String> {
        val classLoader = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
        val urls = classLoader.getResources(dirPath).toList()
        val all = LinkedHashSet<String>()
        for (url in urls) {
            when (url.protocol) {
                "file" -> all += listFileProtocolResources(dirPath, url)
                "jar" -> all += listJarProtocolResources(url)
            }
        }
        if (all.isEmpty()) {
            all += listFilesystemFallbackResources(dirPath)
        }
        return all.toList()
    }

    private fun listFileProtocolResources(dirPath: String, url: URL): List<String> {
        val uri = try {
            url.toURI()
        } catch (_: Throwable) {
            return emptyList()
        }
        val dir = java.nio.file.Paths.get(uri)
        if (!java.nio.file.Files.isDirectory(dir)) return emptyList()
        val output = mutableListOf<String>()
        val paths = java.nio.file.Files.list(dir)
        try {
            paths.forEach { path ->
                val fileName = path.fileName?.toString() ?: return@forEach
                if (fileName.endsWith(".ogg", ignoreCase = true)) {
                    output += "$dirPath/$fileName"
                }
            }
        } finally {
            paths.close()
        }
        return output
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
            if (!name.startsWith(prefix)) continue
            if (entry.isDirectory) continue
            if (!name.endsWith(".ogg", ignoreCase = true)) continue
            val tail = name.removePrefix(prefix)
            if (tail.contains('/')) continue
            output += name
        }
        return output
    }

    private fun listFilesystemFallbackResources(dirPath: String): List<String> {
        val fsPath = java.nio.file.Paths.get(dirPath)
        if (!java.nio.file.Files.isDirectory(fsPath)) return emptyList()
        val output = mutableListOf<String>()
        val paths = java.nio.file.Files.list(fsPath)
        try {
            paths.forEach { path ->
                val fileName = path.fileName?.toString() ?: return@forEach
                if (fileName.endsWith(".ogg", ignoreCase = true)) {
                    output += "$dirPath/$fileName"
                }
            }
        } finally {
            paths.close()
        }
        return output
    }

    private fun readResourceBytes(resourcePath: String): ByteArray? {
        val classLoader = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
        val stream = classLoader.getResourceAsStream(resourcePath) ?: return null
        return stream.readFullyAndClose()
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
