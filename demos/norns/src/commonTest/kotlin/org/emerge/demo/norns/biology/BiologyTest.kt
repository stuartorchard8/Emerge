package org.emerge.demo.norns.biology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Self-verification harness for biology (subsystem 4): the aging clock advances life stages,
 * organ health responds to injury/repair, and the two C1 death routes (vital-organ failure,
 * old age) fire — while a non-vital organ failing does not. Constants are placeholders
 * (DESIGN.md G1); this proves the physiology MECHANISM.
 */
class BiologyTest {

    // locus layout used by these tests
    private val INJURY = 0; private val REPAIR = 1; private val AGE = 2; private val STAGE = 3
    private val lociSize = 4

    private fun config(
        organCount: Int = 1,
        vital: BooleanArray = booleanArrayOf(true),
        maxAge: Int = 10_000,
    ) = BiologyConfig(
        stageStartAge = intArrayOf(0, 10, 20, 30, 40, 50, 60, 70), // EMBRYO..SENILE
        maxAge = maxAge,
        organCount = organCount,
        vital = vital,
        injuryLocus = INJURY, repairLocus = REPAIR, ageLocus = AGE, lifeStageLocus = STAGE,
    )

    @Test
    fun agingAdvancesThroughLifeStages() {
        val bio = Biology(config())
        val loci = FloatArray(lociSize)
        loci[REPAIR] = 1f // keep organs healthy so only aging is under test

        fun stageAtAge(target: Int): LifeStage {
            while (bio.age < target) bio.tick(loci)
            return bio.lifeStage
        }
        assertEquals(LifeStage.BABY, stageAtAge(10))
        assertEquals(LifeStage.CHILD, stageAtAge(25))
        assertEquals(LifeStage.YOUTH, stageAtAge(45))  // YOUTH=40..49, ADULT starts at 50
        assertEquals(LifeStage.ADULT, stageAtAge(55))
        assertEquals(LifeStage.SENILE, stageAtAge(75))
        // age and stage are published to the bus for other subsystems.
        assertEquals(75f, loci[AGE])
        assertEquals(LifeStage.SENILE.ordinal.toFloat(), loci[STAGE])
    }

    @Test
    fun vitalOrganFailureKills() {
        val bio = Biology(config(vital = booleanArrayOf(true)))
        val loci = FloatArray(lociSize)
        loci[INJURY] = 0.5f // 0.5 is exactly representable: 1.0 -> 0.5 -> 0.0 with no residue
        bio.tick(loci) // -> 0.5
        assertTrue(bio.alive, "still alive while organ > 0")
        bio.tick(loci) // -> 0.0
        assertFalse(bio.alive, "vital organ at 0 should kill")
    }

    @Test
    fun nonVitalOrganFailureDoesNotKill() {
        val bio = Biology(config(vital = booleanArrayOf(false)))
        val loci = FloatArray(lociSize)
        loci[INJURY] = 0.5f
        repeat(5) { bio.tick(loci) } // organ floors at 0
        assertEquals(0f, bio.organHealth[0], "non-vital organ failed")
        assertTrue(bio.alive, "non-vital organ at 0 must NOT kill")
    }

    @Test
    fun repairOffsetsInjuryAndKeepsCreatureAlive() {
        val bio = Biology(config(vital = booleanArrayOf(true)))
        val loci = FloatArray(lociSize)
        loci[INJURY] = 0.3f; loci[REPAIR] = 0.3f // net zero
        repeat(500) { bio.tick(loci) }
        assertTrue(bio.alive, "repaired creature survives")
        assertEquals(1f, bio.organHealth[0], "health holds at full when repair offsets injury")
    }

    @Test
    fun diesOfOldAge() {
        val bio = Biology(config(maxAge = 50))
        val loci = FloatArray(lociSize)
        loci[REPAIR] = 1f // organs healthy; only age can kill
        repeat(49) { bio.tick(loci) }
        assertTrue(bio.alive, "alive at age 49")
        bio.tick(loci) // age 50 == maxAge
        assertFalse(bio.alive, "dies of old age at maxAge")
    }

    @Test
    fun deadCreatureStopsAgingAndStaysDead() {
        val bio = Biology(config(maxAge = 5))
        val loci = FloatArray(lociSize)
        loci[REPAIR] = 1f
        repeat(10) { bio.tick(loci) }
        assertFalse(bio.alive)
        val ageAtDeath = bio.age
        repeat(10) { bio.tick(loci) }
        assertEquals(ageAtDeath, bio.age, "a dead creature does not age further")
    }

    @Test
    fun tickIsDeterministic() {
        fun run(): Biology {
            val bio = Biology(config(maxAge = 200))
            val loci = FloatArray(lociSize)
            repeat(150) { t ->
                loci[INJURY] = if (t % 7 == 0) 0.4f else 0.05f
                loci[REPAIR] = 0.1f
                bio.tick(loci)
            }
            return bio
        }
        val a = run(); val b = run()
        assertEquals(a.age, b.age)
        assertEquals(a.lifeStage, b.lifeStage)
        assertEquals(a.alive, b.alive)
        for (i in a.organHealth.indices) assertEquals(a.organHealth[i].toRawBits(), b.organHealth[i].toRawBits(), "organ[$i]")
    }
}
