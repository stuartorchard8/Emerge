package org.emerge.demo.drockets

import org.emerge.sim.core.TeamId
import org.emerge.sim.core.Tick
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.RenderShapeComponent
import org.emerge.sim.core.physics.components.TeamComponent
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import org.emerge.sim.core.sim.spawnBody
import org.emerge.sim.core.sim.spawnParticle
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DrocketsSaveCodecTest {
    @Test
    fun encodeDecode_strips_particle_entities_from_physics_state() {
        val builder = SimBuilder(SimState())
        val bodyId = builder.spawnBody(
            pos = Coord2.zero,
            vel = Coord2.zero,
            ang = Coord(0),
            angVel = Coord(0),
            mass = 42u,
            radius = Frac(1, 256),
            bounce = Frac(1, 4),
            rough = Frac(1, 2),
            shape = BodyShape.CIRCLE,
        )
        val particleId = builder.spawnParticle(
            pos = Coord2.zero,
            vel = Coord2.zero,
            radius = Frac(1, 1024),
            shape = BodyShape.CIRCLE,
            lifetime = 30,
            teamId = TeamId(7),
        )
        val snapshot = DrocketsSnapshot(
            tick = Tick(123),
            state = builder.build(),
            lineage = DrocketLineageState.EMPTY,
        )

        val decoded = DrocketsSaveCodec.decode(DrocketsSaveCodec.encode(snapshot))

        val motions = decoded.state.components.getTable<MotionComponent>()
        val renderShapes = decoded.state.components.getTable<RenderShapeComponent>()
        val colliders = decoded.state.components.getTable<ColliderComponent>()
        val teams = decoded.state.components.getTable<TeamComponent>()
        assertTrue(motions.contains(bodyId))
        assertFalse(motions.contains(particleId))
        assertFalse(renderShapes.contains(particleId))
        assertFalse(colliders.contains(particleId))
        assertFalse(teams.contains(particleId))
        assertEquals(1, motions.keys().size)
    }

    @Test
    fun encodeDecode_roundtrips_typed_genome_on_genome_component() {
        // Use non-default raw values across every field to confirm each slot survives the wire.
        val genome = Genome(
            aiWalkMinTicks = -1_500_000_000,
            aiWalkMaxTicks = 1_500_000_000,
            aiChargeTicks = 42,
            aiFuelTicks = -42,
            aiSpin = Int.MIN_VALUE + 17,
            aiThrust = Int.MAX_VALUE - 17,
            bodyColor = HsvColorGene(rawH = 100, rawS = -200, rawV = 300),
            fireColor = HsvColorGene(rawH = -400, rawS = 500, rawV = -600),
        )
        val builder = SimBuilder(SimState())
        val bodyId = builder.spawnBody(
            pos = Coord2.zero,
            vel = Coord2.zero,
            ang = Coord(0),
            angVel = Coord(0),
            mass = 100u,
            radius = Frac(1, 256),
            bounce = Frac(1, 4),
            rough = Frac(1, 2),
            shape = BodyShape.CIRCLE,
        )
        builder.update<GenomeComponent>(bodyId) { GenomeComponent(genome) }
        val snapshot = DrocketsSnapshot(
            tick = Tick(0),
            state = builder.build(),
            lineage = DrocketLineageState.EMPTY,
        )

        val decoded = DrocketsSaveCodec.decode(DrocketsSaveCodec.encode(snapshot))

        val decodedGenome = decoded.state.components.getTable<GenomeComponent>()[bodyId]?.genome
        assertEquals(genome, decodedGenome)
    }

    @Test
    fun encodeDecode_roundtrips_reproducer_with_spawn_and_spawn_genome() {
        val spawnGenome = Genome(aiChargeTicks = 999_999, aiFuelTicks = -999_999)
        val reproducer = ReproducerComponent(
            birthdayMs = 1_234_567L,
            sex = Sex.FEMALE,
            maturityAgeMs = 9_999L,
            gestationDuration = 8_888L,
            spawn = ReproducerComponent(
                birthdayMs = 9_999_999L,
                sex = Sex.MALE,
            ),
            spawnGenome = spawnGenome,
            spawnMotherEntityId = 17,
            spawnFatherEntityId = 23,
        )
        val builder = SimBuilder(SimState())
        val bodyId = builder.spawnBody(
            pos = Coord2.zero,
            vel = Coord2.zero,
            ang = Coord(0),
            angVel = Coord(0),
            mass = 100u,
            radius = Frac(1, 256),
            bounce = Frac(1, 4),
            rough = Frac(1, 2),
            shape = BodyShape.CIRCLE,
        )
        builder.update<ReproducerComponent>(bodyId) { reproducer }
        val snapshot = DrocketsSnapshot(
            tick = Tick(0),
            state = builder.build(),
            lineage = DrocketLineageState.EMPTY,
        )

        val decoded = DrocketsSaveCodec.decode(DrocketsSaveCodec.encode(snapshot))

        val decodedReproducer = decoded.state.components.getTable<ReproducerComponent>()[bodyId]
        assertEquals(reproducer, decodedReproducer)
    }

    @Test
    fun encodeDecode_roundtrips_lineage_state_with_parents_and_living_set() {
        val rootGenome = Genome(aiWalkMinTicks = 111)
        val daughterGenome = Genome(aiWalkMinTicks = 222, bodyColor = HsvColorGene(1, 2, 3))
        val lineage = DrocketLineageState(
            nextLineageId = 99L,
            nodes = linkedMapOf(
                1L to DrocketLineageNode(
                    lineageId = 1L,
                    motherLineageId = null,
                    fatherLineageId = null,
                    birthTick = 0L,
                    deathTick = null,
                    sex = Sex.FEMALE,
                    genome = rootGenome,
                ),
                2L to DrocketLineageNode(
                    lineageId = 2L,
                    motherLineageId = 1L,
                    fatherLineageId = null,
                    birthTick = 100L,
                    deathTick = 500L,
                    sex = Sex.MALE,
                    genome = daughterGenome,
                ),
            ),
            livingLineageIds = linkedSetOf(1L),
            entityToLineageId = linkedMapOf(42 to 1L, 43 to 2L),
        )
        val snapshot = DrocketsSnapshot(
            tick = Tick(777),
            state = SimBuilder(SimState()).build(),
            lineage = lineage,
        )

        val decoded = DrocketsSaveCodec.decode(DrocketsSaveCodec.encode(snapshot))

        assertEquals(lineage, decoded.lineage)
    }
}
