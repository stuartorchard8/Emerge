package org.emerge.web

import kotlinx.browser.document
import kotlinx.browser.window
import org.emerge.demo.scavengers.audio.CrashAudioEngine
import org.emerge.demo.scavengers.audio.CrashSfxRequest
import org.khronos.webgl.ArrayBuffer
import kotlin.js.Promise

/**
 * Web Audio API implementation of [CrashAudioEngine].
 *
 * OGG clips are fetched and decoded asynchronously -- the engine reports 0 clips until loading
 * completes, so [CrashAudioSystem][org.emerge.demo.scavengers.audio.CrashAudioSystem] gracefully
 * skips crash events until ready.
 *
 * Handles the browser autoplay policy by resuming the [AudioContext] on the first user interaction.
 */
class WebCrashAudioEngine(
    clangPaths: List<String> = defaultClangPaths(),
    crushPaths: List<String> = defaultCrushPaths(),
    private val sfxBusGain: Float = 1f,
) : CrashAudioEngine {
    private val ctx: dynamic = js("new (window.AudioContext || window.webkitAudioContext)()")
    private val clangBuffers = mutableListOf<dynamic>()
    private val crushBuffers = mutableListOf<dynamic>()
    private var released = false

    override val clangClipCount: Int get() = clangBuffers.size
    override val crushClipCount: Int get() = crushBuffers.size

    init {
        loadAll(clangPaths, clangBuffers)
        loadAll(crushPaths, crushBuffers)
        ensureAudioContextResumed()
    }

    override fun playCrash(request: CrashSfxRequest) {
        if (released) return
        val buffers = if (request.isCrushing) crushBuffers else clangBuffers
        if (buffers.isEmpty()) return
        val clipIndex = request.clipIndex.coerceIn(0, buffers.lastIndex)
        val buffer = buffers[clipIndex]

        val source = ctx.createBufferSource()
        source.buffer = buffer
        source.playbackRate.value = request.pitch.toDouble().coerceIn(0.5, 2.0)

        val gain = ctx.createGain()
        gain.gain.value = (request.volume * sfxBusGain).toDouble().coerceIn(0.0, 1.0)

        source.connect(gain)
        gain.connect(ctx.destination)
        source.start(0)
    }

    override fun release() {
        if (released) return
        released = true
        ctx.close()
        clangBuffers.clear()
        crushBuffers.clear()
    }

    private fun loadAll(paths: List<String>, target: MutableList<dynamic>) {
        for (path in paths) {
            window.fetch(path)
                .then { response: dynamic -> response.arrayBuffer() as Promise<ArrayBuffer> }
                .then { arrayBuffer: ArrayBuffer -> decodeAudio(arrayBuffer) }
                .then { decoded: dynamic ->
                    if (!released) target.add(decoded)
                }
                .catch { err: dynamic ->
                    console.warn("Audio load failed for $path:", err)
                }
        }
    }

    private fun decodeAudio(arrayBuffer: ArrayBuffer): Promise<dynamic> {
        return Promise { resolve, reject ->
            ctx.decodeAudioData(
                arrayBuffer,
                { decoded: dynamic -> resolve(decoded) },
                { err: dynamic -> reject(err) },
            )
        }
    }

    /**
     * Browsers suspend AudioContext until a user gesture. Resume on the first keydown/click/touch.
     * Uses `{once: true}` so the listener auto-removes after firing.
     */
    private fun ensureAudioContextResumed() {
        val resume: (dynamic) -> Unit = { ctx.resume(); Unit }
        val opts = js("({once: true})")
        for (evt in arrayOf("keydown", "click", "touchstart")) {
            document.asDynamic().addEventListener(evt, resume, opts)
        }
    }

    companion object {
        private fun defaultClangPaths(): List<String> = (1..5).map { "/audio/clang/-${it.toString().padStart(2, '0')}.ogg" }
        private fun defaultCrushPaths(): List<String> = (1..3).map { "/audio/crush/-${it.toString().padStart(2, '0')}.ogg" }
    }
}
