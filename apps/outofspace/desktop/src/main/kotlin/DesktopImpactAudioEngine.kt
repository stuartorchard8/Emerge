package org.emerge.desktop

import org.emerge.audio.ClipBank
import org.emerge.audio.OggSfxPlayer
import org.emerge.demo.outofspace.audio.ImpactAudioEngine
import org.emerge.demo.outofspace.audio.ImpactSfxRequest

/**
 * The desktop's speakers, for [org.emerge.demo.outofspace.audio.ImpactAudioSystem].
 *
 * Everything hard is in `:engine:audio:openal`, which knows nothing about rocks; everything about
 * *rocks* is in `:apps:outofspace:core`, which knows nothing about OpenAL. What is left is this:
 * which directory holds which bank.
 */
class DesktopImpactAudioEngine(
    private val player: OggSfxPlayer = OggSfxPlayer(),
    /** Struck metal — a body against the hull. Shared with scavengers, where the clips came from. */
    hullDir: String = "assets/audio/clang",
    /** Rock against rock, which is a duller and more granular thing. */
    rubbleDir: String = "assets/audio/crush",
) : ImpactAudioEngine {

    private val hull: ClipBank = player.loadBank(hullDir)
    private val rubble: ClipBank = player.loadBank(rubbleDir)

    override val clangClipCount: Int get() = hull.size
    override val rubbleClipCount: Int get() = rubble.size

    override fun play(request: ImpactSfxRequest) {
        player.play(
            if (request.isRubble) rubble else hull,
            request.clipIndex,
            request.volume,
            request.pitch,
        )
    }

    override fun release() = player.release()
}
