package org.emerge.demo.cyto.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The numeric field's **select-all-on-open** contract (efficiency gear, `BIO > n` condition values).
 *
 * The field opens pre-filled with the current value so the player can see what they are changing. It used to
 * *append* to that: typing `5` over a 2000 cap set the cap to 20005, silently, and the only clue was a number
 * too wide to read at a glance. The first keystroke now replaces the pre-fill; everything after it appends.
 */
class GeneConstantEntryTest {

    /** Open a field on [current] and return the editor plus the value it eventually commits. */
    private fun field(current: Int, min: Int = 0, max: Int = 1_000_000): Pair<GeneEditor, () -> Int?> {
        val ed = GeneEditor()
        var set: Int? = null
        ed.startConstantCapture(current, min, max) { set = it }
        return ed to { set }
    }

    private fun GeneEditor.type(s: String) { for (c in s) typeConstantChar(c) }

    @Test fun theFirstDigitReplacesThePrefilledValue() {
        val (ed, committed) = field(2000)
        assertEquals("2000", ed.capturedConstantValue, "the field opens showing what it will change")
        ed.type("5")
        assertEquals("5", ed.capturedConstantValue)
        ed.confirmConstantValue()
        assertEquals(5, committed())
    }

    @Test fun digitsAfterTheFirstAppendNormally() {
        val (ed, committed) = field(2000)
        ed.type("350")
        assertEquals("350", ed.capturedConstantValue)
        ed.confirmConstantValue()
        assertEquals(350, committed())
    }

    /** Backspace on the still-selected pre-fill clears the lot, as it does over any selected text. */
    @Test fun backspaceOnTheUntouchedPrefillClearsItWhole() {
        val (ed, _) = field(2000)
        ed.constantBackspace()
        assertEquals("", ed.capturedConstantValue)
    }

    /** ...but once they have started typing it is an ordinary per-character delete. */
    @Test fun backspaceAfterTypingDeletesOneCharacter() {
        val (ed, _) = field(2000)
        ed.type("350")
        ed.constantBackspace()
        assertEquals("35", ed.capturedConstantValue)
    }

    /** Committing without touching the field leaves the value exactly as it was — the pre-fill is the value,
     *  so "open it and press ENTER" must not be a way to change anything. */
    @Test fun confirmingAnUntouchedFieldKeepsTheOriginalValue() {
        val (ed, committed) = field(2000)
        ed.confirmConstantValue()
        assertEquals(2000, committed())
    }

    @Test fun cancellingLeavesTheValueUnset() {
        val (ed, committed) = field(2000)
        ed.type("7")
        ed.cancelConstantValue()
        assertEquals(null, committed())
        assertFalse(ed.capturingConstantValue)
    }

    /** The Android path submits the whole string at once; the pristine flag must not eat its first digit. */
    @Test fun aSoftKeyboardSubmissionLandsWhole() {
        val (ed, committed) = field(2000)
        ed.submitConstantValue("412")
        assertEquals(412, committed())
    }

    /** Range clamping still applies to a replaced value (it is the field's only guard). */
    @Test fun aReplacedValueIsStillClampedToTheFieldsRange() {
        val (ed, committed) = field(5, min = 1, max = 9)
        ed.type("40")
        ed.confirmConstantValue()
        assertEquals(9, committed())
    }

    /** Reopening re-arms the select-all — otherwise only the first edit of a session would behave. */
    @Test fun reopeningTheFieldRearmsTheReplace() {
        val ed = GeneEditor()
        var set: Int? = null
        ed.startConstantCapture(2000, 0, 1_000_000) { set = it }
        ed.type("5"); ed.confirmConstantValue()
        ed.startConstantCapture(set!!, 0, 1_000_000) { set = it }
        assertEquals("5", ed.capturedConstantValue)
        ed.type("8"); ed.confirmConstantValue()
        assertEquals(8, set, "the second edit replaced too, rather than making 58")
    }
}
