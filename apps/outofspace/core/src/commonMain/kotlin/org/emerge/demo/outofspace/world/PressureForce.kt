package org.emerge.demo.outofspace.world

/** The reaction the vessel takes from its own air pressing on its bulkheads, by axis. */
class PressureForceResult(val vesselX: Long, val vesselY: Long)

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
    tileGrams: LongArray,
    pressure: LongArray,
): PressureForceResult {
    var vesselX = 0L
    var vesselY = 0L

    // Converted to impulse units once per *tile*, before any difference is taken — see [potentialOf].
    val potential = potentialOf(pressure)

    for (e in 0 until edges.xEdgeCount) {
        val drop = beyond(potential, edges.xEdgeBefore(e)) - beyond(potential, edges.xEdgeAfter(e))
        if (drop == 0L) continue
        if (apertures.isXOpen(e)) {
            val faceGrams = xFaceGrams(edges, tileGrams, e)
            if (faceGrams <= 0L) continue
            val toGas = drop * apertures.xAt(e) / ApertureField.OPEN
            mx[e] += toGas
            // What the solid part of a restriction took. Zero for a fully open face.
            vesselX += drop - toGas
        } else {
            vesselX += drop
        }
    }
    for (e in 0 until edges.yEdgeCount) {
        val drop = beyond(potential, edges.yEdgeBefore(e)) - beyond(potential, edges.yEdgeAfter(e))
        if (drop == 0L) continue
        if (apertures.isYOpen(e)) {
            val faceGrams = yFaceGrams(edges, tileGrams, e)
            if (faceGrams <= 0L) continue
            val toGas = drop * apertures.yAt(e) / ApertureField.OPEN
            my[e] += toGas
            vesselY += drop - toGas
        } else {
            vesselY += drop
        }
    }

    return PressureForceResult(vesselX, vesselY)
}

/** A tile's potential, or zero off the grid — space pushes back with nothing. */
private fun beyond(potential: LongArray, tile: Int): Long =
    if (tile < 0) 0L else potential[tile]

/**
 * Pressure in impulse units, converted before differencing.
 * Pre-conversion enables exact telescoping (per-face conversion truncates separately → rounding bias accumulates).
 */
private fun potentialOf(pressure: LongArray): LongArray =
    LongArray(pressure.size) { pressure[it] * SOUND_IMPULSE / AMBIENT_PRESSURE }

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
private val SOUND_IMPULSE: Long = AMBIENT_TILE_GRAMS / 4
