package org.emerge.outofspace

import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.SoundPool
import org.emerge.demo.outofspace.audio.ImpactAudioEngine
import org.emerge.demo.outofspace.audio.ImpactSfxRequest

/**
 * The phone's speakers, for [org.emerge.demo.outofspace.audio.ImpactAudioSystem].
 *
 * `SoundPool` rather than anything from `:engine:audio:openal`: Android decodes and mixes short
 * clips itself, so there is nothing here for a shared module to hold — which is why the *interface*
 * is the thing the two platforms have in common and not the implementation. Ported from scavengers'
 * `AndroidOggCrashAudioEngine`, which is the same object against the other game's interface.
 *
 * ⚠️ Clip ids are **sorted by name**, exactly as the desktop bank is, so that a clip index means the
 * same clip on both platforms. It costs one `sorted()` and saves a bug nobody would look for.
 */
internal class AndroidImpactAudioEngine(
    assets: AssetManager,
    maxVoices: Int = 16,
    private val sfxBusGain: Float = 1f,
    /**
     * ⚠️ No `assets/` prefix, unlike the desktop's classpath paths: an [AssetManager] is already
     * rooted there. The APK gets these from `assets.srcDir("$rootDir/assets")` in the module's
     * build file, so they are the same files the desktop plays.
     */
    hullAssetDir: String = "audio/clang",
    rubbleAssetDir: String = "audio/crush",
) : ImpactAudioEngine {

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

    private val hullSoundIds = mutableListOf<Int>()
    private val rubbleSoundIds = mutableListOf<Int>()
    private var released = false

    init {
        hullSoundIds += loadSounds(assets, hullAssetDir)
        rubbleSoundIds += loadSounds(assets, rubbleAssetDir)
    }

    override val clangClipCount: Int get() = hullSoundIds.size
    override val rubbleClipCount: Int get() = rubbleSoundIds.size

    override fun play(request: ImpactSfxRequest) {
        if (released) return
        val soundIds = if (request.isRubble) rubbleSoundIds else hullSoundIds
        if (soundIds.isEmpty()) return
        val clip = soundIds[request.clipIndex.coerceIn(0, soundIds.lastIndex)]
        val gain = (request.volume.coerceIn(0f, 1f) * sfxBusGain).coerceIn(0f, 1f)
        soundPool.play(clip, gain, gain, 1, 0, request.pitch.coerceIn(0.5f, 2f))
    }

    override fun release() {
        if (released) return
        soundPool.release()
        hullSoundIds.clear()
        rubbleSoundIds.clear()
        released = true
    }

    /**
     * ⚠️ Every failure here is silence and never a crash — a missing asset directory, an unreadable
     * file, a clip `SoundPool` will not take. A game that will not start because it could not find a
     * sound effect is a worse game than a quiet one, and on a phone the file that goes missing is
     * whichever one the packaging step dropped.
     */
    private fun loadSounds(assets: AssetManager, dir: String): List<Int> {
        val names = try {
            assets.list(dir)?.toList().orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }.filter { it.endsWith(".ogg", ignoreCase = true) }.sorted()

        val out = mutableListOf<Int>()
        for (name in names) {
            val fd = try {
                assets.openFd("$dir/$name")
            } catch (_: Throwable) {
                null
            } ?: continue
            fd.use {
                val soundId = soundPool.load(it, 1)
                if (soundId > 0) out += soundId
            }
        }
        return out
    }
}
