package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.condensedDensityAt
import org.emerge.demo.outofspace.chem.reducedDensity
import org.emerge.demo.outofspace.chem.vapourMass
import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2

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
 * reason to average to nothing on a hull that is not symmetric. the pressure field reads that
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
    val air: Stuff,
    val ventedMass: Long,
    val ventedEnergy: Long,
    /**
     * Mass and energy drawn **in** from [Ambient] — the mirror of [ventedMass], and zero in vacuum.
     *
     * ⛔ **It brings no momentum with it, and that omission is the drag.** The ambient is at rest in
     * the world, so a gram entering carries nothing; the coupling then drags that gram up to the
     * hull's speed at the ship's expense. Booking an impulse here as well would charge the ship
     * twice for the same gram. See [airCoupling] and [Ambient].
     */
    val enteredMass: Long,
    val enteredEnergy: Long,
    /**
     * The momentum [ventedMass] carried off the grid with it, in the **grid's** axes, and the twist
     * it took about the centre of mass.
     *
     * ⛔ **This is the only way the atmosphere may push the hull.** Everything a gas does to a wall
     * it is sealed behind is internal to ship-plus-air and cancels; what does not cancel is mass
     * that genuinely leaves. So the reaction is booked from the same number the mass ledger books,
     * once, and the two cannot disagree. See `PLAN_grid_vs_continuous.md`.
     *
     * ⚠️ Zero unless a `ventSpeed` was given — a caller that only wants gas moved is not made to
     * care what it weighs or where the ship's centre is.
     */
    val ventImpulseX: Long,
    val ventImpulseY: Long,
    val ventTorque: Long,
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
/**
 * Is [tile] already holding as much of [fluid] as that species can be at [kelvin]?
 *
 * ⚠️ **Not [condensedVolumeFraction], which cannot answer this** — it is the lever rule and so
 * saturates at 1.0, reading "completely condensed" and "nineteen times over-packed" as the same
 * number. The density and the dome have to be compared directly.
 *
 * False for a species with no critical point on file: nothing on record says how dense it can get,
 * so nothing here may refuse it.
 */
private fun isPacked(masses: MassArray, tile: TileIndex, fluid: Fluid, kelvin: Int): Boolean {
    val held = masses[MassIndex(tile, fluid)]
    if (held <= 0L) return false
    val limit = condensedDensityAt(kelvin, fluid.species) ?: return false
    val density = reducedDensity(held, fluid.species, VolumeField.FULL, VolumeField.FULL) ?: return false
    return density >= limit
}

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
 * that is the pressure field's job, wired up separately.
 */
fun diffuseFluid(
    edges: EdgeGrid,
    apertures: ApertureField,
    masses: MassArray,
    energies: EnergyArray? = null,
    kelvin: IntArray? = null,
    subSteps: Int = SUB_STEPS,
    /**
     * How fast gas leaves through a hole, in tiles per tick — a multiplier on a mass to get an
     * impulse, exactly as [org.emerge.demo.outofspace.world.machine.Thruster.tilesPerTick] is, and
     * like it nothing is ever integrated at this speed. Zero books no reaction at all.
     */
    ventSpeed: Long = 0L,
    /**
     * The point the vented gas's torque is taken about, and the axis the centrifugal drift is
     * measured from — the vessel's centre of mass.
     */
    about: MassDistribution = MassDistribution.EMPTY,
    /**
     * What the gas feels, everywhere at once: the plating plus whatever the engines are doing. The
     * per-tile half — centrifugal — is derived here from [spin] and [about].
     */
    feltGravity: Frac2 = Frac2(Frac(0L), Frac(0L)),
    /** The vessel's spin, [Coord] raw per tick, for the centrifugal half of the drift. */
    spin: Long = 0L,
    /** What is outside the grid — see [Ambient]. Vacuum by default, which is every save so far. */
    ambient: Ambient = Ambient.VACUUM,
    /**
     * How fast the vessel is going through [ambient], world frame at [Flight.PER_TILE] to the tile.
     *
     * ⚠️ **The ship's velocity, not the gas's.** The ambient is at rest, so this *is* the closing
     * speed, and it is what makes the leading face scoop more than the trailing one — the ram that
     * turns a still atmosphere into drag.
     */
    throughX: Long = 0L,
    throughY: Long = 0L,
): DiffusionStep {
    val grid = edges.grid
    val tiles = grid.size

    // ── What is allowed to move ───────────────────────────────────────────────
    //
    // **Only the vapour.** See [vapourMass], and see `PhaseTransportTest` for why it matters: a
    // condensed phase is not a steep gradient, it is a different substance sitting there, and
    // differencing across the interface dissolves it.
    //
    // ⚠️ **Derived when it is not given, rather than defaulted to "no phases".** A caller that
    // tracks [energies] knows its temperatures whether or not it happened to pass them, and a pass
    // that silently moved ice because an argument was omitted is exactly the kind of hole that
    // would go unnoticed for a year. Absent energies there is genuinely nothing to derive from, and
    // that is the only case that gets the old behaviour — which is what the mass-only tests want.
    val temperature = kelvin ?: energies?.let { gasKelvin(it, heatCapacity(tiles, masses)) }

    // One tile's worth of the outside, as energy — computed once rather than per face per species.
    val ambientEnergyPerTile: Long =
        if (ambient.massPerTile <= 0L) 0L
        else {
            val parcel = MassArray(1)
            for (fluid in Fluid.ALL) parcel.add(TileIndex(0), fluid, ambient.perTile[fluid.species])
            heatCapacityAt(parcel, TileIndex(0)) * ambient.kelvin
        }

    val startingMass = tileMass(tiles, masses)
    val deltaMass = MassArray(tiles)
    val deltaEnergy = if (energies == null) null else EnergyArray(tiles)

    var ventedMass = 0L
    var ventedEnergy = 0L
    var enteredMass = 0L
    var enteredEnergy = 0L
    var ventImpulseX = 0L
    var ventImpulseY = 0L
    var ventTorque = 0L

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
    /** How much felt gravity opens or closes each face, in permille — see [driftPermille]. */
    val faceSkew = IntArray(FACES)

    for (i in 0 until tiles) {
        val tile = TileIndex(i)
        val ownMass = startingMass[tile.index]
        // ⚠️ **Not `continue` on an empty cell, and that is the whole of what makes an ambient
        // work.** Shedding is skipped for a cell with nothing in it — obviously — but the rim
        // faces are on exactly the cells that have nothing in them, because the tiles between a
        // hull and the edge of the grid are vacuum. Guarding the whole tile skipped every face
        // gas could have come *in* through, and the atmosphere outside was measured entering at
        // precisely zero grams however dense it was and however fast the ship went.
        if (ownMass <= 0L && ambient.massPerTile <= 0L) continue

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

        // ── Which way is down, here ──
        //
        // ⛔ **The one place the atmosphere is treated as living in a rotating frame**, because it
        // is the only thing that does: gas is addressed by tile, so a turning grid turns the gas
        // with it. Bodies are stored in the world and get the outward spiral for free — see
        // [centrifugalAt], which must never be applied to one.
        //
        // The uniform part is the plating plus the engines; the per-tile part is `ω²r` outward from
        // the axis, which is what makes a spun ring hold its air against the rim.
        var downX = feltGravity.x.raw
        var downY = feltGravity.y.raw
        if (spin != 0L && about.mass > 0L) {
            val spun = centrifugalAt(
                spin,
                tileCentre(grid.xOf(tile)) - about.comX,
                tileCentre(grid.yOf(tile)) - about.comY,
            )
            downX += spun.x.raw
            downY += spun.y.raw
        }
        // Faces are ordered up, down, left, right — see where [faceAperture] is filled. A face is
        // opened by the gravity pointing *through* it and its opposite closed by the same amount, so
        // the four still sum to what the cell was going to shed either way.
        faceSkew[0] = -driftPermille(downY)
        faceSkew[1] = driftPermille(downY)
        faceSkew[2] = -driftPermille(downX)
        faceSkew[3] = driftPermille(downX)

        var outMass = 0L
        if (ownMass > 0L) masses.forEachFluid(tile) { fluid, count ->
            // Whatever of this species is *not* frost or puddle. Free for a fluid with no critical
            // point on file — the overwhelming majority of what a vessel's air is made of — because
            // [vapourMass] answers those from the first line without touching the dome.
            val mobile =
                if (temperature == null) count
                else vapourMass(count, fluid.species, VolumeField.FULL, VolumeField.FULL, temperature[tile.index])
            val share = mobile * FACE_SHARE / SLOTS
            if (share <= 0L) return@forEachFluid

            for (f in 0 until FACES) {
                val aperture = faceAperture[f]
                if (aperture <= 0) continue

                // A partly-open face passes its share in proportion to how open it is; what will not
                // fit stays home, which is the same thing a shut face does. Full openness is the
                // overwhelmingly common case and is exact — the rounding here is confined to valves
                // and doors mid-cycle, where a stranded gram is a gram behind a nearly-shut door.
                val even =
                    if (aperture >= ApertureField.OPEN) share
                    else share * aperture / ApertureField.OPEN
                // Downhill passes more, uphill less, and the pair cancels — see [driftPermille].
                val out = if (faceSkew[f] == 0) even else even + even / 1000L * faceSkew[f]
                if (out <= 0L) continue

                val neighbour = faceNeighbour[f]
                // ⛔ **A cell already at its own condensed density for this species takes no more of
                // it.** Without this the pass is a *one-way valve*: only [vapourMass] may leave a
                // tile, so anything that condenses on arrival can never go back out, and nothing in
                // the game moves a puddle except an extractor standing on it (`liftFrost`). A cold
                // dead end therefore ratchets — measured on a live save, a sealed nose cone at 25 K
                // had collected 241 kg into one tile, half of it hydrogen at **19.3x its own liquid
                // density**, still climbing monotonically at +630 g per 10,000 ticks after 17M ticks
                // of it, and reading 3,025 atm on Peng-Robinson's compressed branch.
                //
                // The refused mass **stays home** — the same thing a shut face does, and the same
                // rule the partial aperture above already follows. Gas then banks up in the tile
                // *outside* the cold one, which is how a cold trap really behaves: frost grows
                // outward from the cold surface rather than compressing into a single cell.
                //
                // ⚠️ Per species, not per tile: this asks whether there is room for *this* gas, and
                // says nothing about the cell being physically full of everything together. That is
                // a volume-competition model and it is deliberately not being invented here.
                //
                // ⚠️ Asked of [masses], which is this pass's *starting* state — [deltaMass] is not
                // applied until the loop ends. So every tile is judged against the same world and
                // the pass stays order-independent, as the rest of it already is.
                if (neighbour != TileIndex.NONE && temperature != null &&
                    isPacked(masses, neighbour, fluid, temperature[neighbour.index])
                ) continue

                deltaMass.add(tile, fluid, -out)
                if (neighbour == TileIndex.NONE) ventedMass += out
                else deltaMass.add(neighbour, fluid, out)
                faceOut[f] += out
                outMass += out
            }
        }

        // ── What came the other way, and what the rim therefore owes ──
        //
        // ⛔ **The rim is an exchange now, not a drain.** Outside used to be stated by absence — a
        // face with nothing on the far side only ever shed. A vessel near a planet is *in*
        // something, and that something pushes back: see [Ambient], which is the only place in the
        // game a planet exists.
        for (f in 0 until FACES) {
            if (faceNeighbour[f] != TileIndex.NONE) continue
            val aperture = faceAperture[f]
            if (aperture <= 0) continue
            var came = 0L
            if (ambient.massPerTile > 0L) {
                // ⚠️ **Two terms, and only the second one knows the ship is moving.** The first is
                // the ambient shedding its share inward exactly as a neighbouring cell would, which
                // is what fills a hull left sitting in air. The second is the **ram**: a face whose
                // outward normal points into the oncoming stream sweeps up whatever it drives
                // through, and it is that asymmetry between the leading and the trailing face that
                // turns a still atmosphere into drag.
                val approach = when (f) {
                    0 -> -throughY
                    1 -> throughY
                    2 -> -throughX
                    else -> throughX
                }.coerceIn(0L, Flight.PER_TILE)
                for (fluid in Fluid.ALL) {
                    val held = ambient.perTile[fluid.species]
                    if (held <= 0L) continue
                    var inward = held / SLOTS * FACE_SHARE
                    if (approach > 0L) inward += scaledRatio(held, Flight.PER_TILE, approach)
                    if (aperture < ApertureField.OPEN) inward = inward * aperture / ApertureField.OPEN
                    if (inward <= 0L) continue
                    deltaMass.add(tile, fluid, inward)
                    came += inward
                }
            }
            enteredMass += came
            if (came > 0L && ambientEnergyPerTile > 0L) {
                enteredEnergy += scaledRatio(came, ambient.massPerTile, ambientEnergyPerTile)
            }
            // ⚠️ **Momentum on the NET, and that is not a rounding nicety.** The rocket term is gas
            // expanding into vacuum; a hull submerged in a gas giant swaps enormous quantities both
            // ways through the same face and is not a rocket at all. Booked on the gross outflow it
            // would read as thrust proportional to how thick the soup was. Only mass the ship
            // actually *loses* through a face is a rocket.
            val net = faceOut[f] - came
            if (ventSpeed > 0L && net > 0L) {
                val p = net * ventSpeed
                val px = if (f == 2) -p else if (f == 3) p else 0L
                val py = if (f == 0) -p else if (f == 1) p else 0L
                ventImpulseX += px
                ventImpulseY += py
                // ⚠️ Taken about the **cell's centre** rather than the face it left by, and that is
                // exact rather than close enough: the face is offset from the centre along its own
                // normal, the impulse is along that same normal, and moving the arm parallel to the
                // force does not change `r × F` at all.
                ventTorque += torqueAbout(
                    about, tileCentre(grid.xOf(tile)), tileCentre(grid.yOf(tile)), px, py,
                )
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

    // Only the species that actually moved. This was `tiles × Species.COUNT` — 165 loads per tile to
    // apply the six deltas a tile of air really has — and it is the pass the presence bitmask was
    // added for. A delta of zero adds nothing, so skipping it is not an approximation.
    for (t in grid.tiles) deltaMass.forEachFluid(t) { fluid, delta -> masses.add(t, fluid, delta) }
    if (energies != null && deltaEnergy != null) for (t in grid.tiles) energies[t] += deltaEnergy[t]

    // Snapshotted rather than folded on demand: [masses] belongs to the caller, which goes on editing
    // it after the pass, and a mass read later would not be the mass this flux came out of.
    val endingMass = tileMass(tiles, masses)

    return DiffusionStep(
        air = if (energies == null) Stuff.gas(masses) else Stuff.from(masses, energies),
        ventedMass = ventedMass,
        ventedEnergy = ventedEnergy,
        enteredMass = enteredMass,
        enteredEnergy = enteredEnergy,
        ventImpulseX = ventImpulseX,
        ventImpulseY = ventImpulseY,
        ventTorque = ventTorque,
        edges = edges,
        fluxX = fluxX,
        fluxY = fluxY,
        startingMass = startingMass,
        endingMass = endingMass,
    )
}
