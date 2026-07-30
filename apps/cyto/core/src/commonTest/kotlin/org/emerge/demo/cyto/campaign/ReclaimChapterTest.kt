package org.emerge.demo.cyto.campaign

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.host.CampaignContent
import org.emerge.demo.cyto.sim.GeneCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `ch05-reclaim` — where the campaign's one branch closes, and the first chapter about the world rather than
 * the cell. Both tails arrive carrying the same three genes; this one shows what that lineage does to the
 * world it lives in, and adds the fourth gene that stops it.
 */
class ReclaimChapterTest {

    private val chapter = CampaignContent.PLAYABLE_CHAPTERS.first { it.id == CampaignContent.RECLAIM }

    private fun lineageOfText(text: String) = lineageOf(GeneCodec.parse(text))

    /** The three-gene shape both branches finish on — grow on the duomer, divide on it, recycle it in light. */
    private val settled = """
        Bond b g : Biomass < 3000 : Convert bg
        Bond b g : Biomass > 2000 : Divide sever
        Light : bg > 100 : Break b g
    """.trimIndent()

    private fun query(genome: String, cells: Int) = CampaignQuery(
        WorldStats(
            0L, cells, mapOf(CellType.Collector to cells), 3000, emptySet(),
            if (cells > 0) FocusedCell(CellType.Collector, 3000, mapOf("bg" to 150)) else null,
            lineageOfText(genome),
        ),
        paused = false, selectedGenome = null,
    )

    // ── the reading the chapter is built on ────────────────────────────────────────────────────────────

    /**
     * The import reading only counts an IMPORT of the molecule the lineage actually SHEDS. Importing anything
     * else leaves the leak exactly where it was: a bonded molecule crosses the membrane only with an import
     * bias (`CytoBiologyCore.passiveEnvExchange`), so the waste stays unreachable however much else the cell
     * hauls in.
     */
    @Test
    fun importOfSomethingElseIsNotTheReclaimingGene() {
        val wrongMolecule = lineageOfText("$settled\nLight : br < 200 : Import br")
        assertFalse(wrongMolecule.importsExhaust, "importing a molecule the lineage never sheds closes nothing")
        assertNull(wrongMolecule.importCeiling, "no reclaiming gene means no ceiling to report")

        val right = lineageOfText("$settled\nLight : bg < 200 : Import bg")
        assertTrue(right.importsExhaust, "an IMPORT of the shed molecule is the gene the chapter asks for")
        assertEquals(200, right.importCeiling)
    }

    /** Light vs chemistry, and the gear — read off the import gene itself, not off the genome at large. */
    @Test
    fun importPowerAndGearAreReadFromTheImportGene() {
        val onLight = lineageOfText("$settled\nLight : bg < 200 : Import bg")
        assertFalse(onLight.importOnChem, "a Light-powered import is not chemistry-powered")
        assertEquals(0, onLight.importGear, "no gear token reads as gear 0")

        // The DIVIDE gene is chemistry-powered in both genomes, so a reading taken from the wrong gene would
        // report chemistry here too.
        val onChem = lineageOfText("$settled\nBond b g : bg < 200 : Import bg @15")
        assertTrue(onChem.importOnChem)
        assertEquals(15, onChem.importGear)
    }

    /** The ceiling is a CEILING (`bg < N`), the mirror of the recycler's floor — a floor must not read as one. */
    @Test
    fun onlyAnUpperBoundCountsAsTheImportCeiling() {
        val floored = lineageOfText("$settled\nLight : bg > 200 : Import bg")
        assertNull(floored.importCeiling, "`bg > 200` is not a ceiling, however sensible it looks")
    }

    // ── the chapter's shape ────────────────────────────────────────────────────────────────────────────

    /**
     * The die-off beat gates on an EMPTY world, which is also what keeps the coach's extinction offer off it
     * (`CampaignDirector.goalIsExtinction`): the step ASKED for this death, so replacing its copy with a
     * recovery prompt would talk the player out of the lesson.
     */
    @Test
    fun theDieOffIsTheGoalSoTheExtinctionOfferStaysAway() {
        val gate = chapter.steps.first { (it.gate as? Gate.World)?.desc?.contains("die out") == true }.gate as Gate.World
        assertTrue(gate.met(query(settled, cells = 0)), "an empty world satisfies the beat")
        assertFalse(gate.met(query(settled, cells = 1)), "one living cell must un-satisfy it, or the offer fires")
    }

    /** The fix is gated in three parts, and each must reject the genome that is missing only that part. */
    @Test
    fun theFixIsGatedGenePowerThenCeiling() {
        fun gate(fragment: String) =
            chapter.steps.first { (it.gate as? Gate.World)?.desc?.contains(fragment) == true }.gate as Gate.World

        val none = query(settled, 1)
        val bare = query("$settled\nLight : bg < 200 : Import bg", 1)
        val onChem = query("$settled\nBond b g : bg < 200 : Import bg", 1)
        // An empty condition is ALWAYS, and still occupies its ':' slot.
        val noCeiling = query("$settled\nLight : : Import bg", 1)

        assertFalse(gate("IMPORT").met(none), "three genes is not yet the fix")
        assertTrue(gate("IMPORT").met(bare))

        // Light specifically: a spent world has no loose atoms to pay chemistry with, so this chapter's
        // rescue must not accept a chemistry-powered import.
        assertTrue(gate("with light").met(bare))
        assertFalse(gate("with light").met(onChem), "chemistry cannot bootstrap from a spent world")

        assertTrue(gate("{bond} is high").met(bare))
        assertFalse(gate("{bond} is high").met(noCeiling), "an import with no ceiling hauls all day")
    }

    /** Both tails lead here and this chapter starts its own world - the one deliberate break in continuity. */
    @Test
    fun itMergesBothBranchesAndStartsClean() {
        assertEquals(
            setOf(CampaignContent.LEFTOVERS, CampaignContent.BRANCH_LOCKED_UP),
            CampaignContent.predecessorsOf(CampaignContent.RECLAIM, CampaignContent.PLAYABLE_CHAPTERS).toSet(),
        )
        assertTrue(chapter.startsFreshWorld, "the boom-and-crash has to be watched from a virgin world")
        assertTrue(chapter.spawnCopiesHeldCell, "the founder carries whichever genome the player arrived with")
    }
}
