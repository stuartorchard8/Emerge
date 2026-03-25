package org.emerge.androidapp

import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.SoundPool
import org.emerge.demo.physics.audio.CrashAudioEngine
import org.emerge.demo.physics.audio.CrashSfxRequest

internal class AndroidOggCrashAudioEngine(
    assets: AssetManager,
    private val maxVoices: Int = 16,
    private val sfxBusGain: Float = 1f,
    private val clangAssetDir: String = "audio/clang",
    private val crushAssetDir: String = "audio/crush",
) : CrashAudioEngine {
    private val soundPool =
        SoundPool.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setMaxStreams(maxVoices)
            .build()

    private val clangSoundIds = mutableListOf<Int>()
    private val crushSoundIds = mutableListOf<Int>()
    private var released = false

    init {
        clangSoundIds += loadSounds(assets, clangAssetDir)
        crushSoundIds += loadSounds(assets, crushAssetDir)
    }

    override val clangClipCount: Int
        get() = clangSoundIds.size

    override val crushClipCount: Int
        get() = crushSoundIds.size

    override fun playCrash(request: CrashSfxRequest) {
        if (released) return
        val soundIds = if (request.isCrushing) crushSoundIds else clangSoundIds
        if (soundIds.isEmpty()) return
        val clip = soundIds[request.clipIndex.coerceIn(0, soundIds.lastIndex)]
        val gain = (request.volume.coerceIn(0f, 1f) * sfxBusGain).coerceIn(0f, 1f)
        val rate = request.pitch.coerceIn(0.5f, 2f)
        soundPool.play(clip, gain, gain, 1, 0, rate)
    }

    override fun release() {
        if (released) return
        soundPool.release()
        clangSoundIds.clear()
        crushSoundIds.clear()
        released = true
    }

    private fun loadSounds(assets: AssetManager, dir: String): List<Int> {
        val names =
            try {
                assets.list(dir)?.toList().orEmpty()
            } catch (_: Throwable) {
                emptyList()
            }.filter { it.endsWith(".ogg", ignoreCase = true) }
                .sorted()
        val out = mutableListOf<Int>()
        for (name in names) {
            val fd = try {
                assets.openFd("$dir/$name")
            } catch (_: Throwable) {
                null
            } ?: continue
            fd.use {
                val soundId = soundPool.load(it, 1)
                if (soundId > 0) {
                    out += soundId
                }
            }
        }
        return out
    }
}
