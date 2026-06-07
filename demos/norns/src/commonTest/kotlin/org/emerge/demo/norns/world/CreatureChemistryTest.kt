package org.emerge.demo.norns.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Self-verification harness for drives-as-chemicals (G8): a creature's hunger/urge are real
 * chemical concentrations driven by the biochemistry engine — they rise from metabolism/fertility
 * and fall when eating reacts hunger away. This is what connects the (separately-tested)
 * biochemistry engine to the living creature.
 */
class CreatureChemistryTest {

    private val cfg = NornsConfig()

    @Test
    fun hungerRisesFromMetabolismAndEatingReducesIt() {
        val chem = CreatureChemistry(metabolism = 0.01f, cfg = cfg)
        repeat(20) { chem.tick(fertileActive = false) }
        val h = chem.hunger
        assertTrue(h > 0.1f, "hunger should accumulate from metabolism: $h") // ~20 * 0.01
        chem.eat(); chem.tick(fertileActive = false) // glucose pulse reacts hunger down
        assertTrue(chem.hunger < h, "eating should reduce hunger (${chem.hunger} < $h)")
    }

    @Test
    fun urgeRisesOnlyWhileFertileAndResetsOnMating() {
        val chem = CreatureChemistry(metabolism = 0.01f, cfg = cfg)
        repeat(15) { chem.tick(fertileActive = false) }
        assertEquals(0f, chem.urge, "no urge before fertility")
        repeat(15) { chem.tick(fertileActive = true) }
        assertTrue(chem.urge > 0f, "urge rises when fertile: ${chem.urge}")
        chem.resetUrge()
        assertEquals(0f, chem.urge, "mating clears the urge")
    }

    @Test
    fun fatigueRisesWhileExertingAndRecoversWhenResting() {
        val chem = CreatureChemistry(metabolism = 0.01f, cfg = cfg)
        repeat(20) { chem.tick(fertileActive = false, exerting = true) }
        val f = chem.fatigue
        assertTrue(f > 0.1f, "fatigue builds while exerting: $f") // ~20 * 0.01
        repeat(20) { chem.recover(0.06f); chem.tick(fertileActive = false, exerting = false) }
        assertTrue(chem.fatigue < f, "resting recovers fatigue (${chem.fatigue} < $f)")
    }

    @Test
    fun chemistryIsDeterministic() {
        fun run(): Float {
            val chem = CreatureChemistry(0.008f, cfg)
            repeat(50) { t -> if (t % 10 == 0) chem.eat(); chem.tick(fertileActive = t > 20) }
            return chem.hunger + chem.urge
        }
        assertEquals(run().toRawBits(), run().toRawBits())
    }
}
