package org.emerge.desktop

import org.emerge.audio.ClipBank
import org.emerge.audio.OggSfxPlayer
import org.emerge.demo.scavengers.audio.CrashAudioEngine
import org.emerge.demo.scavengers.audio.CrashSfxRequest

/**
 * The desktop's speakers, for [org.emerge.demo.scavengers.audio.CrashAudioSystem].
 *
 * This used to be the OpenAL and Vorbis plumbing itself. It moved to `:engine:audio:openal` when Out
 * of Space wanted the same thing and apps may not depend on each other — and it was always the wrong
 * place for it, because none of what moved knows or cares that a ship crashed. What is left is the
 * part that is about scavengers: which directory is which bank.
 */
class DesktopOggCrashAudioEngine(
    private val player: OggSfxPlayer = OggSfxPlayer(),
    clangDir: String = "assets/audio/clang",
    crushDir: String = "assets/audio/crush",
) : CrashAudioEngine {

    private val clang: ClipBank = player.loadBank(clangDir)
    private val crush: ClipBank = player.loadBank(crushDir)

    override val clangClipCount: Int get() = clang.size
    override val crushClipCount: Int get() = crush.size

    override fun playCrash(request: CrashSfxRequest) {
        player.play(
            if (request.isCrushing) crush else clang,
            request.clipIndex,
            request.volume,
            request.pitch,
        )
    }

    override fun release() = player.release()
}
