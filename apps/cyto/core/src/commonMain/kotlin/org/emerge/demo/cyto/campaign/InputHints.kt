package org.emerge.demo.cyto.campaign

/**
 * Platform **input phrasing** the coach copy interpolates, so a single authored string reads correctly on
 * mouse or touch. A step writes the gesture as a `{token}` — `"{pan} to move around, and {zoom} to zoom"` —
 * and the host supplies the table matching its actual controls ([MOUSE] on desktop, [TOUCH] on a phone).
 *
 * The token vocabulary is deliberately tiny: only the **camera / grab gestures** genuinely differ between
 * hosts. Plain instruction verbs that read on both (e.g. "Select the cell") stay literal — they don't need a
 * token. An unknown token is left verbatim (so a typo is visible, not silently dropped).
 *
 * **Casing note:** the phrases are written capitalised-as-sentence-start, and steps put a `{token}` at the
 * start of its sentence. This is fine today because the whole UI renders upper-case, so case is irrelevant on
 * screen. If a step ever needs a gesture *mid*-sentence (where the phrase should be lower-case), add cased
 * token pairs (`{Pan}` / `{pan}`) rather than trying to re-case at expand time.
 */
class InputHints(private val map: Map<String, String>) {
    /** Replace every `{token}` in [s] with its phrase; unknown tokens are left as-is. */
    fun expand(s: String): String = TOKEN.replace(s) { m -> map[m.groupValues[1]] ?: m.value }

    companion object {
        // The closing brace must be escaped: Android's ICU regex engine rejects a bare `}` here (stricter than
        // the JVM, which tolerates it), crashing at class-init on device.
        private val TOKEN = Regex("\\{(\\w+)\\}")

        /** Desktop pointer controls: right-drag pans (left-drag grabs a cell), the wheel zooms. */
        val MOUSE = InputHints(mapOf(
            "pan" to "Click and drag",
            "zoom" to "scroll",
            "grab" to "drag",
            "panelLocation" to "at the top-right of the screen",
        ))

        /** Touch controls: a one-finger drag on empty space pans, a two-finger pinch zooms. */
        val TOUCH = InputHints(mapOf(
            "pan" to "Drag",
            "zoom" to "pinch",
            "grab" to "drag",
            "panelLocation" to "at the bottom of the screen. Drag up to expand it",
        ))
    }
}
