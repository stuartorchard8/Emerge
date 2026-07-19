package org.emerge.demo.cyto.campaign

import kotlin.test.Test
import kotlin.test.assertEquals

class InputHintsTest {
    // Regression: the `{token}` pattern once used an unescaped `}` (`\{(\w+)}`), which the JVM tolerates but
    // Android's ICU regex engine rejects — crashing at class-init on device. Constructing/using InputHints
    // here compiles the pattern; the assertions pin the expansion behaviour.
    @Test fun expandsKnownTokensAndLeavesUnknownAlone() {
        val hints = InputHints(mapOf("pan" to "drag", "zoom" to "pinch"))
        assertEquals("drag to move, pinch to zoom", hints.expand("{pan} to move, {zoom} to zoom"))
        assertEquals("keep {mystery} as-is", hints.expand("keep {mystery} as-is"))
    }

    @Test fun builtInHintSetsExpand() {
        assertEquals("scroll", InputHints.MOUSE.expand("{zoom}"))
    }
}
