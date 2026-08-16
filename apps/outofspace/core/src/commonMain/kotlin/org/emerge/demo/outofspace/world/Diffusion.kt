package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.demo.outofspace.chem.Species

/**
 * Rapid diffusion: the replacement for the momentum solver.
 *
 * Every cell splits its contents [SLOTS] ways each pass — [FACE_SHARE] of them for each of its four
 * faces and the rest staying put — and hands the face shares to its neighbours, expecting them to do
 * the same. There is no velocity and no pressure solve; what emerges is a fast-moving equilibrium,
 * which is the only thing the vessel ever wanted from the atmosphere. How *fast* is [SUB_STEPS]'
 * business, since a pass on its own is pinned near the stability limit and cannot be sped up by more
 * than a quarter — see [SLOTS] for why. Three properties are load-bearing:
 *
 * **The remainder stays home.** Each face takes `count * FACE_SHARE / SLOTS`, and whatever will not
 * divide is simply left where it is.
 *
 * It was briefly handed round the faces instead, in a rotation that turned with the tick, so that a
 * cell holding fewer than [SLOTS] units of a species could still shed them — otherwise a breached
 * room drains to a few units a tile and stops. **Reverted 2026-08-12 (Stu), because it flew the
 * ship.** The rotation moved a gram or two per tile per tick, and since every tile shared one offset
 * it moved them *in step*: a grid-wide ripple in the pressure field, on a five-tick cycle, with no
 * reason to average to nothing on a hull that is not symmetric. [applyPressureForce] reads that
 * field, so the ripple became a small standing shove and a sealed vessel slowly departed.
 *
 * What is given up is stated plainly, because it is a real effect: a trace below the divisor **stays
 * put**, so a vented room keeps a few units a tile. Nothing is lost — rounding down strands mass
 * here, it never destroys it — and the cheap fix, if the residue ever matters, is a rule for rim
 * faces alone, which empties the room without perturbing a single interior pressure.
 *
 * **Conservation is by construction, not by correction.** Every gram subtracted from one tile is
 * added to a named neighbour or booked as vented in the same statement, so there is no ledger to
 * reconcile and no fix-up pass to get wrong.
 *
 * **A fixed divisor, never a per-degree one.** Dividing by a tile's own degree looks fairer and
 * biases high-degree tiles into piling up about twice their neighbours; a fixed divisor is
 * edge-symmetric, so the steady state is uniform and the divisor sets only the speed, never the
 * bias. See [SLOTS].
 *
 * Deltas are accumulated into a scratch field and applied at the end, so a tile's neighbours are read
 * as they were at the start of the tick and never mid-move. Within that, this scatters — a tile pushes
 * into its neighbours' deltas rather than each tile gathering its own. Integer addition commutes, so
 * the result does not depend on visit order either way; what scattering gives up is being trivially
 * parallel by index, and what it buys is not having to replay a neighbour's entire remainder rotation
 * and aperture arithmetic to learn one number.
 */
class DiffusionStep(
    val air: Atmosphere,
    val ventedMass: Long,
    val ventedEnergy: Long,
    /**
     * What actually crossed each face this pass, and the mass it came out of — the raw material for
     * [flow], kept separate so a caller that never asks the question never pays for the answer.
     */
    private val edges: EdgeGrid,
    private val fluxX: LongArray,
    private val fluxY: LongArray,
    private val startingMass: LongArray,
    private val endingMass: LongArray,
) {

    /**
     * Where the fluid went, as a picture — see [FlowField].
     *
     * Lazy because the pipes run the same step and never ask, and because the room only asks when
     * the flow overlay is up.
     */
    val flow: FlowField by lazy { FlowField.derive(edges, fluxX, fluxY, startingMass, endingMass) }
}

/**
 * The ways a cell's contents are divided each tick. Each face is handed [FACE_SHARE] of these and
 * whatever is left over stays home, so the per-face fraction is `FACE_SHARE / SLOTS`.
 *
 * **Invariant: `4 * FACE_SHARE < SLOTS`, strictly.** A cell must never promise its faces more than it
 * holds, and the margin — the share that stays home — is what keeps this stable rather than a cell
 * emptying itself completely into a ring that empties straight back.
 *
 * The pair is tunable, but the range is narrow and the ceiling is not a matter of taste. This is FTCS
 * on the five-point Laplacian, whose amplification factor for the checkerboard mode is `1 - 8α` with
 * `α = FACE_SHARE / SLOTS`. At the 1/5 here that is −0.6, so a checkerboard loses 40% of itself a
 * tick. Raising α drives it toward −1, where the checkerboard flips sign forever and never decays,
 * and α = 1/4 *is* that boundary. Since 1/4 is also the supremum of `k / (4k + 1)`, the entire
 * available range is 1/5 → 1/4: **a 25% speedup, bought against ringing that takes longer to die.**
 *
 * So this is not the speed lever it looks like. [SUB_STEPS] is, and it has no ceiling. What this pair
 * is good for is exploring behaviour near the stability boundary, and it is free — `k` outflows per
 * face is one multiply, not `k` transfers.
 *
 * Raising [SLOTS] does *not* meaningfully worsen the stranding floor, which is the intuitive fear and
 * is wrong: a tile stops shedding when `count * FACE_SHARE / SLOTS` floors to zero, so the threshold is
 * `SLOTS / FACE_SHARE` — that is `1/α`, which the ceiling above pins between 4 and 5 whatever [SLOTS]
 * is. Measured on a 20×12 room drained through one rim hole: 2,930 units left at 5/1, 3,288 at 13/3.
 */
const val SLOTS = 5

/** How many of the [SLOTS] each face takes. See [SLOTS] for the invariant and the ceiling. */
const val FACE_SHARE = 1

/**
 * How many diffusion passes a tick runs.
 *
 * The actual speed lever: `n` passes multiply the effective diffusivity by `n`, linearly and without
 * the 25% ceiling [SLOTS] runs into, because each pass is individually inside the stability limit.
 * It costs what it says — `n` times the pass — which is the honest price, and the same price a kernel
 * over a wider neighbourhood would charge for the same diffusivity, without the kernel's symmetry
 * hazard or its larger stranding floor.
 *
 * One is the identity. Everything a caller sees is summed across the passes, so raising this changes
 * how fast the air moves and nothing else about the contract.
 *
 * Measured on a 20×12 room drained through one rim hole: 11,352 ticks to lose 99% at one pass, 2,838
 * at four — linear to three significant figures, which is the whole argument for preferring this to a
 * wider kernel.
 */
const val SUB_STEPS = 1

/**
 * A tile's four faces, in the order they are walked: up, down, left, right. The share that stays home
 * has no slot here, because nothing is done with it — it is simply what is not subtracted.
 */
private const val FACES = 4

/** Convenience overload deriving the face connectivity from [structure]. */
fun diffuseFluid(
    grid: Grid,
    structure: StructureMap,
    entities: TileArray,
    masses: MassArray,
    energies: EnergyArray? = null,
): DiffusionStep {
    val edges = EdgeGrid(grid)
    return diffuseFluid(edges, ApertureField.derive(edges, structure), masses, energies)
}

/**
 * One tick of diffusion over [masses] (tiles × species) and, if it is being tracked, [energies] (per
 * tile). Both are edited in place.
 *
 * Joules ride the mass rather than diffusing on their own account: what leaves across a face is the
 * same fraction of the tile's energy as of its mass, so temperature travels with the gas carrying it.
 * Diffusing energy separately would put energies in cells with no capacity to have them, where they
 * read as ambient and are silently lost to every gauge in the vessel. The split is *telescoped* —
 * each face is given the difference between two running totals rather than its own rounded-down
 * fraction — so a tile whose mass leaves entirely has no energy left behind either. Ghost heat in an
 * evacuated cell would be exactly the stranding problem again, in the other ledger.
 *
 * [subSteps] defaults to [SUB_STEPS] and exists so a test that pins the arithmetic of a *single* pass
 * can ask for one explicitly, rather than silently becoming a test of the tuning constant.
 *
 * **Venting to the rim stays.** A face with no tile on the far side sheds its share into space and
 * books it, which is what keeps breaches and the vent ledger working. It no longer produces thrust —
 * that is [applyPressureForce]'s job, wired up separately.
 */
fun diffuseFluid(
    edges: EdgeGrid,
    apertures: ApertureField,
    masses: MassArray,
    energies: EnergyArray? = null,
    subSteps: Int = SUB_STEPS,
): DiffusionStep {
    val grid = edges.grid
    val tiles = grid.size

    val startingMass = tileMass(tiles, masses)
    val deltaMass = MassArray(tiles)
    val deltaEnergy = if (energies == null) null else EnergyArray(tiles)

    var ventedMass = 0L
    var ventedEnergy = 0L

    // Net mass across each face, signed toward +x / +y. Both ends of a face add into the same slot,
    // so gas crossing in both directions cancels and what is left is the net movement — which is the
    // only thing a flow picture should claim. Accumulated across every sub-step, so the overlay shows
    // what the whole tick moved rather than whatever the last pass happened to be doing.
    val fluxX = LongArray(edges.xEdgeCount)
    val fluxY = LongArray(edges.yEdgeCount)

    // Reused across tiles: what actually crossed each of the four faces, and who was on the far side.
    val faceAperture = IntArray(FACES)
    val faceNeighbour = Array(FACES) { TileIndex.NONE }
    val faceOut = LongArray(FACES)
    val faceEdge = IntArray(FACES)

    for (i in 0 until tiles) {
        val tile = TileIndex(i)
        val ownMass = startingMass[tile.index]
        if (ownMass <= 0L) continue

        val left = edges.leftEdgeOf(tile)
        val right = edges.rightEdgeOf(tile)
        val up = edges.upEdgeOf(tile)
        val down = edges.downEdgeOf(tile)
        faceAperture[0] = apertures.yAt(up); faceNeighbour[0] = edges.yEdgeBefore(up)
        faceAperture[1] = apertures.yAt(down); faceNeighbour[1] = edges.yEdgeAfter(down)
        faceAperture[2] = apertures.xAt(left); faceNeighbour[2] = edges.xEdgeBefore(left)
        faceAperture[3] = apertures.xAt(right); faceNeighbour[3] = edges.xEdgeAfter(right)
        faceEdge[0] = up; faceEdge[1] = down; faceEdge[2] = left; faceEdge[3] = right
        faceOut.fill(0L)

        var outMass = 0L
        for (s in Species.ALL) {
            val count = masses[MassIndex(tile, s)]
            if (count <= 0L) continue
            val share = count * FACE_SHARE / SLOTS
            if (share <= 0L) continue

            for (f in 0 until FACES) {
                val aperture = faceAperture[f]
                if (aperture <= 0) continue

                // A partly-open face passes its share in proportion to how open it is; what will not
                // fit stays home, which is the same thing a shut face does. Full openness is the
                // overwhelmingly common case and is exact — the rounding here is confined to valves
                // and doors mid-cycle, where a stranded gram is a gram behind a nearly-shut door.
                val out =
                    if (aperture >= ApertureField.OPEN) share
                    else share * aperture / ApertureField.OPEN
                if (out <= 0L) continue

                deltaMass[MassIndex(tile, s)] -= out
                val neighbour = faceNeighbour[f]
                if (neighbour == TileIndex.NONE) ventedMass += out else deltaMass[MassIndex(neighbour, s)] += out
                faceOut[f] += out
                outMass += out
            }
        }

        // ── Which way that gas went ──
        //
        // Signed toward +x and +y, so the neighbour on the other side of the face will subtract what
        // it sends back through the same slot. Gas shed into space over the rim counts too: a breach
        // is the clearest flow in the vessel and the arrows should say so.
        fluxY[faceEdge[0]] -= faceOut[0]
        fluxY[faceEdge[1]] += faceOut[1]
        fluxX[faceEdge[2]] -= faceOut[2]
        fluxX[faceEdge[3]] += faceOut[3]

        // ── The energy on the gas that just left ──
        //
        // Telescoped: each face is handed the difference between two running totals of
        // `energy × massSoFar / ownMass`, so the shares sum to exactly `energy × outMass / ownMass`
        // and, when everything leaves, to exactly `energy`. Giving each face its own floored fraction
        // instead would leave a few units of energy behind every time, and behind in an empty cell they are
        // heat with nothing to hold it.
        //
        // The running total goes through [scaledRatio] for exactly the reason
        // [org.emerge.demo.outofspace.chem.apportion] does, and this is the same construction:
        // `energy × carried` multiplies an energy by a mass, so it is **quadratic in the mass
        // unit** and reaches 2.9e22 for an ambient tile at one microgram per unit. The wrap does
        // not merely lose precision — it hands a face more energy than the tile has, and the
        // cell is left holding negative energy, a negative kelvin, and eventually a negative
        // reduced temperature that indexes a saturation table at −1.
        //
        // Telescoping survives the reduction because it rests on [scaledRatio]'s two documented
        // properties and on nothing else: monotonic in the numerator, so no face can be handed a
        // negative share, and exact at the ends, so a tile that empties completely hands over
        // precisely `energy` and keeps nothing back.
        if (energies != null && deltaEnergy != null && outMass > 0L) {
            val energy = energies[tile]
            var carried = 0L
            var assigned = 0L
            for (f in 0 until FACES) {
                if (faceOut[f] <= 0L) continue
                carried += faceOut[f]
                val upTo = scaledRatio(carried, ownMass, energy)
                val out = upTo - assigned
                assigned = upTo
                if (out == 0L) continue
                deltaEnergy[tile] -= out
                val neighbour = faceNeighbour[f]
                if (neighbour == TileIndex.NONE) ventedEnergy += out else deltaEnergy[neighbour] += out
            }
        }
    }

    for (t in grid.tiles) for (s in Species.ALL) masses[MassIndex(t,s)] += deltaMass[MassIndex(t,s)]
    if (energies != null && deltaEnergy != null) for (t in grid.tiles) energies[t] += deltaEnergy[t]

    // Snapshotted rather than folded on demand: [masses] belongs to the caller, which goes on editing
    // it after the pass, and a mass read later would not be the mass this flux came out of.
    val endingMass = tileMass(tiles, masses)

    return DiffusionStep(
        air = if (energies == null) Atmosphere.of(masses) else Atmosphere.of(masses, energies),
        ventedMass = ventedMass,
        ventedEnergy = ventedEnergy,
        edges = edges,
        fluxX = fluxX,
        fluxY = fluxY,
        startingMass = startingMass,
        endingMass = endingMass,
    )
}
