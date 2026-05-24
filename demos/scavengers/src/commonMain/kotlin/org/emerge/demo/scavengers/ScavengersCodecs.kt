package org.emerge.demo.scavengers

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
 * Scavengers' component codec registry. Engine-shared codecs plus the Scavengers-only
 * [HomePlanetCodec]. The order here defines the wire format — do not reorder without
 * bumping save/protocol compatibility.
 */
object ScavengersCodecs {
    val physicsNetCodecs: EcsNetCodecs = EcsNetCodecs(
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

    /** Scavengers-specific input wire codec (thrust + turn ints). */
    val inputCodec = scavengersInputCodec

    /** Wire codec for [ScavengersState] — wraps the engine state codec. */
    val stateCodec = scavengersStateCodec(physicsNetCodecs)

    /** Wire codec for the thin-client per-tick crash audio event payload. */
    val crashImpactAudioEventsCodec = org.emerge.demo.scavengers.crashImpactAudioEventsCodec
}
