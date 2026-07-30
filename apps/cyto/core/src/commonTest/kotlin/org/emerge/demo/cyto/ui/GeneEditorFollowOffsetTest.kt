package org.emerge.demo.cyto.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where the camera puts a followed cell while the phone's cell **sheet** is up.
 *
 * [GeneEditor.freeAreaOffsetPx] tells the host how far off the true screen centre to hold the followed cell,
 * so it sits in the strip of world the sheet doesn't cover. Editing a gene must not change that: a gene is
 * edited *in place in the sheet*, so the obscured area is the same before and after the first token tap.
 *
 * It used to return `(0, 0)` whenever a draft was parked — correct back when editing raised a full-screen L3
 * modal that hid the world outright, and wrong the moment editing moved into the sheet. The visible symptom
 * was the camera snapping the cell back to the middle of the screen — behind the sheet — as soon as you
 * touched a gene, exactly as if the sheet weren't open.
 */
class GeneEditorFollowOffsetTest {

    private val resW = 1080f
    private val resH = 2400f

    private fun narrowOffset(ed: GeneEditor, topObscuredPx: Float = 0f) =
        ed.freeAreaOffsetPx(narrow = true, cellShown = true, resW = resW, resH = resH, scale = 3f, topObscuredPx = topObscuredPx)

    /** The sheet covers the bottom of the screen, so the free band is above centre: offset is negative (up). */
    @Test fun theSheetPushesTheFollowedCellUpFromTheScreenCentre() {
        val (dx, dy) = narrowOffset(GeneEditor())
        assertEquals(0f, dx, "a bottom sheet obscures nothing horizontally")
        assertTrue(dy < 0f, "the cell is held above the true centre, clear of the sheet (got $dy)")
    }

    @Test fun editingAGeneDoesNotMoveTheCameraAtThePeekDetent() {
        val before = narrowOffset(GeneEditor())
        val editing = GeneEditor().apply { parkInlineDraftForTest() }
        assertEquals(before, narrowOffset(editing), "the sheet is still open at the same height while editing")
    }

    @Test fun editingAGeneDoesNotMoveTheCameraAtTheFullDetent() {
        val before = GeneEditor().apply { parkInlineDraftForTest(gene = null, expanded = true) }
        val editing = GeneEditor().apply { parkInlineDraftForTest(expanded = true) }
        assertEquals(narrowOffset(before), narrowOffset(editing))
    }

    /** The full sheet covers more than the peek, so it pushes the cell further up. Guards the regression from
     *  being "fixed" by making the offset constant. */
    @Test fun theFullSheetPushesFurtherUpThanThePeek() {
        val peek = narrowOffset(GeneEditor()).second
        val full = narrowOffset(GeneEditor().apply { parkInlineDraftForTest(gene = null, expanded = true) }).second
        assertTrue(full < peek, "the taller sheet leaves a higher free band (peek $peek, full $full)")
    }

    /** The campaign coach docks at the top on a phone, so it eats into the band from the other side and pulls
     *  the cell back down. Still true mid-edit. */
    @Test fun theCoachBannerPullsTheCellBackDown() {
        val ed = GeneEditor().apply { parkInlineDraftForTest() }
        assertTrue(
            narrowOffset(ed, topObscuredPx = 300f).second > narrowOffset(ed).second,
            "a top-docked coach shifts the free band down",
        )
    }

    /** With no cell held there is no sheet, so nothing is reserved — on either width. */
    @Test fun noHeldCellMeansNoOffset() {
        val ed = GeneEditor().apply { parkInlineDraftForTest() }
        assertEquals(0f to 0f, ed.freeAreaOffsetPx(narrow = true, cellShown = false, resW, resH, scale = 3f))
    }

    /** Wide docks the panel on the right, so the free area is to its left — a horizontal offset, and likewise
     *  unchanged by editing (that branch was already fixed; this pins it alongside the narrow one). */
    @Test fun wideOffsetsHorizontallyAndIsAlsoUnchangedByEditing() {
        fun wide(ed: GeneEditor) = ed.freeAreaOffsetPx(narrow = false, cellShown = true, resW = 1920f, resH = 1080f, scale = 1f)
        val idle = wide(GeneEditor())
        assertTrue(idle.first < 0f, "the cell sits left of centre, clear of the right-hand dock (got ${idle.first})")
        assertEquals(0f, idle.second)
        assertEquals(idle, wide(GeneEditor().apply { parkInlineDraftForTest() }))
    }
}
