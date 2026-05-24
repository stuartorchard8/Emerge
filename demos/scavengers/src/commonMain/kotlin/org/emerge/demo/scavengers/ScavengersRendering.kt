package org.emerge.demo.scavengers

import org.emerge.render.torus.EdgeIndicator
import org.emerge.render.torus.RgbColor
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Vec2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

// Must match WorldShaderParams.compute(): the world wraps as a 2x2 torus.
private const val WORLD_SIZE = 2f
private const val INDICATOR_ALPHA_MAX = 0.8f

/**
 * Per-entity body tint for the engine renderer. Hashes each entity's team id to a
 * deterministic color so all entities on a team paint the same. Matches the legacy
 * primaryId-seeded color the engine shader used to compute itself.
 */
fun ScavengersState.scavengersBodyTint(entityId: EntityId): RgbColor =
    teamColor(core.components.getTable<TeamComponent>()[entityId]?.teamId?.value)

/**
 * Off-screen edge indicators, one per [LandingSurfaceComponent]. Bright for the local
 * player's team's home planet, fading with world-space distance for others. Returns
 * empty if [myId] is null (no local player → no anchor for "my team").
 */
fun ScavengersState.scavengersEdgeIndicators(myId: PlayerId?): List<EdgeIndicator> {
    if (myId == null) return emptyList()
    val playerEntityId = playerEntities[myId] ?: return emptyList()
    val teams = core.components.getTable<TeamComponent>()
    val transforms = core.components.getTable<TransformComponent>()
    val planets = core.components.getTable<LandingSurfaceComponent>()
    val playerTeamId = teams[playerEntityId]?.teamId?.value
    val focusPos = transforms[playerEntityId]?.pos

    val out = ArrayList<EdgeIndicator>()
    for (entityId in planets.keys()) {
        val transform = transforms[entityId] ?: continue
        val planetTeamId = teams[entityId]?.teamId?.value
        val lenWorld =
            if (focusPos != null && playerTeamId != null && playerTeamId == planetTeamId) 0f
            else if (focusPos != null) {
                val dx = wrapDelta(transform.pos.x.toFloat() - focusPos.x.toFloat(), WORLD_SIZE)
                val dy = wrapDelta(transform.pos.y.toFloat() - focusPos.y.toFloat(), WORLD_SIZE)
                hypot(dx, dy)
            } else 0f
        val alpha = INDICATOR_ALPHA_MAX * max(1f - lenWorld * 2f, 0f)
        if (alpha <= 0f) continue
        out += EdgeIndicator(
            worldPos = transform.pos,
            color = teamColor(planetTeamId),
            alpha = alpha,
        )
    }
    return out
}

/**
 * Deterministic team→color hash. Reproduces the engine shader's old
 * `mod(vec3(c/1.9, c/2.9, c/4.9), 1.0)` palette so existing screenshots and the
 * player's mental map of "the blue team" remain stable through the renderer
 * decoupling.
 */
private fun teamColor(teamIdValue: Int?): RgbColor {
    val primaryId = if (teamIdValue == null) 0f else (teamIdValue + 1).toFloat()
    val c = primaryId + 1f
    return RgbColor(
        positiveFractional(c / 1.9f),
        positiveFractional(c / 2.9f),
        positiveFractional(c / 4.9f),
    )
}

private fun positiveFractional(x: Float): Float {
    val m = x - floor(x)
    return m
}

private fun wrapDelta(d: Float, size: Float): Float {
    val half = 0.5f * size
    val a = d + half
    val m = a - floor(a / size) * size
    return m - half
}
