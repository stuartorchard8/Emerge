package org.emerge.demo.cyto.host

import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoScenario
import org.emerge.demo.cyto.sim.FounderSpec
import org.emerge.demo.cyto.sim.GeneCodec
import org.emerge.demo.cyto.cells.CellType
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The campaign's entry states: the world as each chapter BEGAN, so returning to a chapter from the menu
 * resumes the player's own world rather than rebuilding a canned one from `Chapter.scenario`. The route
 * that led there rides along in a `.campaign` sidecar, because once the campaign branches it can't be
 * re-derived from the world.
 */
class CytoSavesCampaignTest {

    private val prevBase = CytoStorage.baseDir

    @BeforeTest fun useATempStore() {
        CytoStorage.baseDir = Files.createTempDirectory("cyto-campaign-test")
    }

    @AfterTest fun restoreStore() {
        CytoStorage.baseDir.toFile().deleteRecursively()
        CytoStorage.baseDir = prevBase
    }

    private fun cells(c: CytoController): Int =
        c.tick(0f).state.components.getTable<CytoCellComponent>().asMap().size

    @Test fun anEntryStateRoundTripsTheWorldAndTheRouteThatLedToIt() {
        val genes = GeneCodec.parse("Light : Biomass > 0 : Convert r")
        val saved = CytoController()
        saved.newGame(CytoScenario.DEFAULT.copy(
            founders = listOf(FounderSpec(CellType.Collector, 3, genome = genes))))
        val before = cells(saved)
        assertTrue(before > 0)

        val route = listOf("ch00-genesis", "ch01-divide", "ch02-recycle")
        CytoSaves.saveCampaignEntry(saved, "ch02-recycle", route)

        assertTrue(CytoSaves.hasCampaignEntry("ch02-recycle"))
        assertEquals(route, CytoSaves.campaignEntryPath("ch02-recycle"),
            "the route is recorded verbatim - a branch must stay explicit, not be re-derived")

        // A fresh controller on an unrelated world resumes the saved one exactly.
        val resumed = CytoController()
        resumed.newGame(CytoScenario.DEFAULT.copy(founders = emptyList()))
        assertEquals(0, cells(resumed))
        assertTrue(CytoSaves.loadCampaignEntry(resumed, "ch02-recycle"))
        assertEquals(before, cells(resumed), "the player's own world comes back, not a canned one")
        assertEquals(genes, resumed.representativeGenome(), "including the genome they authored")
    }

    /**
     * Chapters are isolated at the menu boundary. `lastAuthoredGenome` outlives a world rebuild on purpose
     * (a mid-chapter Reset hands the player's own organism back), so the entry state has to carry the brush
     * too — otherwise opening a later chapter and going back to Genesis places the *later* organism into an
     * opening beat whose whole point is a gene-less cell.
     */
    @Test fun anEntryStateRestoresTheBrushTheChapterBeganWith() {
        val genes = GeneCodec.parse("Light : Biomass > 0 : Convert r")
        val saved = CytoController()
        saved.setAuthoredGenome(genes)
        CytoSaves.saveCampaignEntry(saved, "ch05-hold", listOf("ch05-hold"))

        val later = CytoController()
        later.setAuthoredGenome(GeneCodec.parse("Light : Biomass > 0 : Mitosis"))
        assertTrue(CytoSaves.loadCampaignEntry(later, "ch05-hold"))
        assertEquals(genes, later.lastAuthoredGenome, "the chapter's own brush comes back")
    }

    /** ...including *no* brush. A chapter entered before the player had authored anything must hand back an
     *  empty hand, not the last thing they wrote in some other chapter. */
    @Test fun aChapterThatBeganWithNoBrushClearsIt() {
        val saved = CytoController()
        CytoSaves.saveCampaignEntry(saved, "ch00-genesis", listOf("ch00-genesis"))

        val later = CytoController()
        later.setAuthoredGenome(GeneCodec.parse("Light : Biomass > 0 : Mitosis"))
        assertTrue(CytoSaves.loadCampaignEntry(later, "ch00-genesis"))
        assertNull(later.lastAuthoredGenome, "Genesis hands out a gene-less cell; the brush must be empty")
    }

    @Test fun aColdStartReportsNothingAndTouchesNothing() {
        val c = CytoController()
        c.newGame(CytoScenario.DEFAULT.copy(founders = emptyList()))
        assertFalse(CytoSaves.hasCampaignEntry("ch00-genesis"))
        assertFalse(CytoSaves.loadCampaignEntry(c, "ch00-genesis"),
            "no stored entry state ⇒ the caller falls back to the chapter's scenario")
        assertEquals(emptyList(), CytoSaves.campaignEntryPath("ch00-genesis"))
    }

    @Test fun entryStatesStayOutOfThePlayersSaveList() {
        val c = CytoController()
        CytoSaves.save(c, "my world")
        CytoSaves.saveCampaignEntry(c, "ch00-genesis", listOf("ch00-genesis"))

        assertEquals(listOf("my world"), CytoSaves.list(),
            "the campaign writes these on the player's behalf; they must not bury the player's own saves")
        assertTrue(CytoSaves.allNames().contains(CytoSaves.campaignSaveName("ch00-genesis")))
        // ...and so the desktop host's boot-time "resume most recent" can never land on one.
        assertEquals("my world", CytoSaves.mostRecent())
    }

    @Test fun deletingASaveTakesItsCampaignSidecarWithIt() {
        val c = CytoController()
        CytoSaves.saveCampaignEntry(c, "ch01-divide", listOf("ch00-genesis", "ch01-divide"))
        CytoSaves.delete(CytoSaves.campaignSaveName("ch01-divide"))
        assertFalse(CytoSaves.hasCampaignEntry("ch01-divide"))
        assertEquals(emptyList(), CytoSaves.campaignEntryPath("ch01-divide"),
            "a stale route outliving its world would misreport how the player got somewhere")
    }
}
