package org.emerge.demo.drockets

import org.emerge.sim.codec.ecs.EcsNetCodecs
import org.emerge.sim.sync.ecs.ColliderCodec
import org.emerge.sim.sync.ecs.DamageCodec
import org.emerge.sim.sync.ecs.ForceFieldCodec
import org.emerge.sim.sync.ecs.LandingCodec
import org.emerge.sim.sync.ecs.MaterialCodec
import org.emerge.sim.sync.ecs.MotionCodec
import org.emerge.sim.sync.ecs.ParticleCodec
import org.emerge.sim.sync.ecs.PlanetCodec
import org.emerge.sim.sync.ecs.RenderShapeCodec
import org.emerge.sim.sync.ecs.TransformCodec

/**
 * Drockets' component codec registry. Only the engine-shared codecs — Drockets-specific
 * components are encoded directly by [DrocketsSaveCodec] outside this registry. The order
 * here defines the wire format for the embedded physics state; reordering must bump the
 * save format version in [DrocketsSaveCodec].
 */
val DrocketsCodecs: EcsNetCodecs = EcsNetCodecs(
    componentCodecs = listOf(
        TransformCodec,
        MotionCodec,
        ColliderCodec,
        MaterialCodec,
        RenderShapeCodec,
        PlanetCodec,
        ForceFieldCodec,
        LandingCodec,
        ParticleCodec,
        DamageCodec,
    ),
)
