package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.scaledRatio

/**
 * The reaction the vessel takes from its own air pressing on its bulkheads, by axis, plus the twist
 * that reaction puts on it.
 *
 * [torque] is booked face by face rather than worked out afterwards from [vesselX] and [vesselY],
 * because the sum has already lost the positions — and a sealed vessel's faces telescope to zero
 * force while a breach at the bow and a breach at the stern do not telescope to zero *torque*.
 * See [torqueAbout].
 */
class PressureForceResult(val vesselX: Long, val vesselY: Long, val torque: Long)

/**
 * Pressure gradient force: `-∇p/ρ` (speed of sound). Impulse = bare pressure difference (ρ cancels).
 * Vacuum faces get no force (no gas to push). Reaction on hull (rocket thrust emerges from breach).
 * Internal terms telescope exactly (sealed vessel → zero net force).
 */
fun applyPressureForce(
    edges: EdgeGrid,
    apertures: ApertureField,
    mx: LongArray,
    my: LongArray,
    tileMasses: LongArray,
    pressure: LongArray,
    about: MassDistribution = MassDistribution.EMPTY,
): PressureForceResult {
    var vesselX = 0L
    var vesselY = 0L
    var torque = 0L

    // Converted to impulse units once per *tile*, before any difference is taken — see [potentialOf].
    val potential = potentialOf(pressure)

    for (e in 0 until edges.xEdgeCount) {
        val drop = beyond(potential, edges.xEdgeBefore(e)) - beyond(potential, edges.xEdgeAfter(e))
        if (drop == 0L) continue
        val taken: Long
        if (apertures.isXOpen(e)) {
            val faceMass = xFaceMass(edges, tileMasses, e)
            if (faceMass <= 0L) continue
            val toGas = drop * apertures.xAt(e) / ApertureField.OPEN
            mx[e] += toGas
            // What the solid part of a restriction took. Zero for a fully open face.
            taken = drop - toGas
        } else {
            taken = drop
        }
        vesselX += taken
        // A vertical face sits on a column boundary and halfway down its row.
        torque += torqueAbout(
            about,
            edges.xOfXEdge(e) * Rotation.MILLI_TILE, tileCentre(edges.yOfXEdge(e)),
            taken, 0L,
        )
    }
    for (e in 0 until edges.yEdgeCount) {
        val drop = beyond(potential, edges.yEdgeBefore(e)) - beyond(potential, edges.yEdgeAfter(e))
        if (drop == 0L) continue
        val taken: Long
        if (apertures.isYOpen(e)) {
            val faceMass = yFaceMass(edges, tileMasses, e)
            if (faceMass <= 0L) continue
            val toGas = drop * apertures.yAt(e) / ApertureField.OPEN
            my[e] += toGas
            taken = drop - toGas
        } else {
            taken = drop
        }
        vesselY += taken
        // A horizontal face sits on a row boundary and halfway across its column.
        torque += torqueAbout(
            about,
            tileCentre(edges.xOfYEdge(e)), edges.yOfYEdge(e) * Rotation.MILLI_TILE,
            0L, taken,
        )
    }

    return PressureForceResult(vesselX, vesselY, torque)
}

/** A tile's potential, or zero off the grid — space pushes back with nothing. */
private fun beyond(potential: LongArray, tile: TileIndex): Long =
    if (tile == TileIndex.NONE) 0L else potential[tile.index]

/**
 * Pressure in impulse units, converted before differencing.
 * Pre-conversion enables exact telescoping (per-face conversion truncates separately → rounding bias accumulates).
 *
 * `pressure / AMBIENT_PRESSURE` is a ratio of two *pressures*, so the mass unit cancels out of it
 * and only [SOUND_IMPULSE] carries one. Written the obvious way round it was the game's second
 * tightest expression (k², safe mass scale 327,000); taken as a ratio first it is linear, and the
 * headroom is whatever the pressure ratio is. See [scaledRatio], and step 4b of PLAN_unit_rescale.md.
 */
private fun potentialOf(pressure: LongArray): LongArray =
    LongArray(pressure.size) { scaledRatio(pressure[it], AMBIENT_PRESSURE, SOUND_IMPULSE) }

/**
 * The momentum one whole atmosphere of difference puts on a face of ordinary air: a quarter of a
 * tile per tick, per tick.
 *
 * This is the speed of sound, expressed in the only units the grid has. Pinned by the CFL limit
 * rather than by the real figure — sound in air is about 340 m/s and a tile is a metre or so, which
 * would be hundreds of tiles per tick and is not a thing an explicit scheme can integrate. A quarter
 * is the largest value that leaves a breach comfortably inside [MomentumField.isCflSafe] once
 * buoyancy and the projection have also had their say, and it puts the far end of a hundred-tile
 * deck four hundred ticks away by wave *and* immediately by the elliptic solve, which between them
 * is the behaviour wanted: a bang that arrives, and a room that then drains coherently.
 *
 * Sub-stepping is the honest way past this and is the same answer [MomentumField.isCflSafe] already
 * points at for a fast exhaust. Until then the gas is slow and the ordering of events is right,
 * which is the trade every explicit fluid sim makes.
 */
private val SOUND_IMPULSE: Long = AMBIENT_TILE_MASS / 4
