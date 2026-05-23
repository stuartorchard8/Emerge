package org.emerge.demo.scavengers

import org.emerge.sim.codec.physics.PhysicsNetCodecs
import org.emerge.sim.sync.ecs.ColliderCodec
import org.emerge.sim.sync.ecs.DamageCodec
import org.emerge.sim.sync.ecs.ForceFieldCodec
import org.emerge.sim.sync.ecs.LandingCodec
import org.emerge.sim.sync.ecs.MaterialCodec
import org.emerge.sim.sync.ecs.MotionCodec
import org.emerge.sim.sync.ecs.ParticleCodec
import org.emerge.sim.sync.ecs.PlanetCodec
import org.emerge.sim.sync.ecs.PlayerIdCodec
import org.emerge.sim.sync.ecs.RenderShapeCodec
import org.emerge.sim.sync.ecs.TeamCodec
import org.emerge.sim.sync.ecs.TransformCodec

/**
 * Scavengers' component codec registry. Engine-shared codecs plus the Scavengers-only
 * [HomePlanetCodec]. The order here defines the wire format — do not reorder without
 * bumping save/protocol compatibility.
 */
val ScavengersCodecs: PhysicsNetCodecs = PhysicsNetCodecs(
    componentCodecs = listOf(
        TransformCodec,
        MotionCodec,
        ColliderCodec,
        MaterialCodec,
        RenderShapeCodec,
        PlanetCodec,
        TeamCodec,
        ForceFieldCodec,
        PlayerIdCodec,
        LandingCodec,
        ParticleCodec,
        DamageCodec,
        HomePlanetCodec,
    ),
)
