package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.num.isqrt
import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.TileEnergy
import org.emerge.sim.core.physics.primitives.Coord

/** What kind of rigid body this is. */
enum class BodyKind {
    /** An ore rock from the field. */
    ROCK,
    /** A machine casing torn loose by dismantling. */
    FRAGMENT,

    /**
     * Somebody else's vessel: a trading post, permanent, with an economy of its own.
     *
     * ⛔ **A body and not a second kind of world.** `PLAN_economy.md` §1 — a station needs a shape to
     * collide with, a pose that persists, and a stockpile; a `RigidBody` already gives the first two,
     * and the third hangs off it as [RigidBody.station]. Nothing here brings the vessel/rigid-body
     * unification forward, and it must not: see `PLAN_rigid_bodies.md` step 6, which is a project of
     * its own.
     */
    STATION,
}

/**
 * What a [BodyKind.STATION] body trades with — `PLAN_economy.md` §6.
 *
 * ⛔ **A station has no thermal model, deliberately.** Matter sold to one leaves the vessel's ledgers
 * through `exportedMass`/`exportedEnergy` and is then outside every ledger in the game. Half-modelling
 * its heat — an ore reserve that warms while a stock pile does not — would be worse than not modelling
 * it: the boundary would be somewhere inside the station rather than at its door. So the door is the
 * boundary, and everything past it is mass.
 */
class Station(
    ore: Mixture,
    /** The pure shelves, and the prices they imply. */
    val market: Market,
    /**
     * Which station this is, for as long as the world lasts.
     *
     * ⛔ **A body has no identity and a station needs one.** A dock is a link between the vessel and
     * one particular trading post, and the obvious key — an index into
     * [VesselState.bodies] — is wrong: the spawner rebuilds that list every tick and rocks despawning
     * *around* a station shift it. So the link names the station, and the station says who it is.
     */
    val id: Int = 0,
    /**
     * Where a ship may berth, in the body's own cells.
     *
     * Several, because a station approachable from exactly one bearing is one the player has to fly
     * around before they may trade, which is a chore rather than a manoeuvre.
     */
    val docks: List<DockNode> = emptyList(),
) {
    /**
     * What has been bought from passing ships and not yet separated — the mixed reserve.
     *
     * Held apart from the [market]'s shelves because **prices are quoted against pure stock**, and
     * that is what makes purification mean something: a station glutted with ore it has not worked
     * through is still paying well for the metals in it.
     *
     * ⛔ **Energy is stripped here, so "no thermal model" is true rather than aspirational.** A
     * `Mixture` carries heat and a station's shelves do not, so a reserve that kept its energy would
     * put the boundary of the thermal model somewhere *inside* the station: ore would warm and cool
     * while the pure pile it turns into could not. The boundary is the door.
     */
    val ore: Mixture = if (ore.energy == 0L) ore else Mixture.of(ore.masses, 0L)

    override fun equals(other: Any?): Boolean =
        this === other || (other is Station && id == other.id && ore == other.ore &&
            market.holdings() == other.market.holdings() && docks == other.docks)

    override fun hashCode(): Int = (id * 31 + ore.hashCode()) * 31 + market.holdings().hashCode()

    override fun toString(): String = "Station#$id(ore ${ore.total}g, stock ${market.holdings().total}g)"
}

/**
 * A berth on a station's hull: which cell of it, and which way is **out**.
 *
 * The cell is the hull cell itself; the mouth a ship meets is one step along [facing] from it, the
 * same way a [org.emerge.demo.outofspace.world.machine.DockingPort]'s berth is one step outside its
 * own footprint. Stating the hull cell rather than the mouth means a node is always a cell the
 * station actually has, which a test can check.
 */
data class DockNode(val cellX: Int, val cellY: Int, val facing: Direction)

/**
 * Free-floating solid (rock or fragment). Own grid, momentum, temperature.
 *
 * **One frame: the world.** Both [positionX]/[positionY] and [impulseX]/[impulseY] are world
 * quantities, so there is nothing to convert between them and no rotating reference frame to carry
 * fictitious forces. Step 1 of `PLAN_rigid_bodies.md` moved the position here; it used to be in the
 * vessel's grid frame, which was only valid while the vessel's angle was zero and which could not
 * survive vessels and bodies being one kind of thing.
 *
 * Everything that speaks **tiles** — the hull test, the extractor, the spawner, the renderer — asks
 * for [localX]/[localY] against [VesselState.pose] instead. Astern drift is not a rule any more: a
 * body holds still in the world and the grid slides out from under it, which is what it always
 * meant.
 *
 * Not stored in bodiesOf (derives from machines/conduits/bridges).
 */
class RigidBody(
    /** What kind of body this is — determines composition vs machineKind metadata. */
    val kind: BodyKind,
    /** The shape's bounding box, in cells. */
    val width: Int,
    val height: Int,
    /** Which cells of that box are solid, row-major. A body is rarely a rectangle. */
    val cells: BooleanArray,
    /** Top-left corner of [cells], **in the world**, in billionths [Flight.PER_TILE] counts. */
    val positionX: Long,
    val positionY: Long,
    /** Momentum in world frame (not vessel frame — ship's frame accelerates). */
    val impulseX: Long,
    val impulseY: Long,
    /**
     * How far it is turned relative to open space — the exact counterpart of [VesselState.ang], in
     * the same unit and stored for the same reason.
     *
     * Step 3 of `PLAN_rigid_bodies.md`. A body's orientation is a history, not a derivation: it is
     * the integral of every twist it has ever taken and nothing else in the save can reconstruct it.
     */
    val ang: Coord = Coord(0),
    /**
     * Its angular momentum, in mass·tile²/tick — the counterpart of [VesselState.angImpulse].
     *
     * Stored rather than the angular *velocity* because that is the conserved quantity: a body that
     * loses a cell to an extractor keeps its momentum and changes its spin, which is the way round
     * that stays right without anybody having to remember to fix it up.
     */
    val angImpulse: Long = 0L,
    /**
     * **What this body is made of**, as proportions — a rock's ore, a fragment's casing.
     *
     * ⛔ **Both kinds carry one now.** A fragment used to answer from [machineKind] instead, which
     * meant its mass, its heat capacity and its grip all came from what a machine of that kind is
     * *normally* made of — and there is no such thing any more: a copper storage's debris is copper.
     * A body is a heap of matter, and every physical question asked of it is asked of the matter.
     */
    val oreComposition: Mixture? = null,
    /**
     * Which machine a fragment came off, or null for a rock. **Provenance, and nothing physical.**
     *
     * ⚠️ It answered for the fragment's substance until the line above took that over. What it is
     * left holding is where the debris came from, which is what rendering and the planned grinder
     * interaction want — see `PLAN_rigid_debris.md`. A length of conduit produces no fragment.
     */
    val machineKind: DeckMachineKind? = null,
    /**
     * **How much of a tile the body's matter actually fills**, in parts per thousand.
     *
     * A rock is solid through, so it is a full tile. A fragment is a machine's casing — a few
     * centimetres of plate over a metre of face — so it is whatever [DeckMachineKind.fillPermille]
     * says that kind occupies.
     *
     * ⚠️ **The second half of "what does this weigh", and it has to be separate from
     * [oreComposition].** A composition is *proportions*; how much of a tile there is of it is a
     * different fact, and the two were folded together while a fragment answered from its kind — the
     * kind knew both, so nobody had to say which was which. Splitting them is what lets a fragment
     * of a copper storage be copper, at a storage's fill, without a table saying what a storage is.
     */
    val fillPermille: Int = 1_000,
    /**
     * Its economy, for a [BodyKind.STATION], and null for everything else.
     *
     * The same shape [oreComposition] and [machineKind] already are: a payload that only one kind
     * carries, hanging off the one class that knows where the thing *is*.
     */
    val station: Station? = null,
    /**
     * Thermal energy, **per filled cell**, in the energy unit [org.emerge.demo.outofspace.num.Budget]
     * states — indexed by a cell's ordinal among the filled ones, not by its position in [cells].
     *
     * One figure for the whole body until step 8 of `PLAN_unit_rescale.md`, and that was the single
     * tightest quantity in the game: twenty-one tiles of solid rock at three thousand kelvin, in one
     * `Long`. Measured, it supported a mass unit 516 times coarser than the target, where a *tile* of
     * the same rock supports 966,000. Spreading the same energy over the cells that hold it is what
     * makes the microgram rebaseline reachable.
     *
     * ⚠️ It buys **range and nothing else today**. A free body is not in [bodiesOf], so it conducts
     * with nothing — not with the ship, and not internally between its own cells. A rock is still
     * isothermal in practice; this only stops it having to say so in one integer. Putting bodies into
     * `stepSolidHeat` would make the per-cell figures mean something, and is not this step.
     */
    val energy: TileEnergy,
) {
    init {
        require(cells.size == width * height) { "a ${width}x$height body cannot have ${cells.size} cells" }
        // Every body says what it is made of; only a fragment says where it came from.
        require(oreComposition != null) { "a $kind body must say what it is made of" }
        require(kind == BodyKind.FRAGMENT || machineKind == null) {
            "a $kind body cannot have come off a ${machineKind?.label}"
        }
        // A station is defined by having somebody to trade with; a rock is defined by not having one.
        require((kind == BodyKind.STATION) == (station != null)) {
            "a $kind body ${if (station == null) "has no" else "has an"} economy"
        }
    }

    /** How many cells of it there are — what everything about the body scales with. */
    val filled: Int = cells.count { it }

    /**
     * What one of its tiles weighs, from **what the body is actually made of**: a rock's ore, a
     * fragment's casing. Held rather than recomputed because [mass] is read every tick by
     * every body, and a mixture's density is a loop over the species table.
     */
    val massPerTile: Long =
        massPerTileOf(oreComposition ?: Mixture.EMPTY) * fillPermille / 1_000L

    /** Millijoules per kelvin for one of its tiles, from that same composition and the same fill. */
    val capacityPerTile: Long =
        capacityPerTileOf(oreComposition ?: Mixture.EMPTY) * fillPermille / 1_000L

    val mass: Long get() = filled * massPerTile

    /** Millijoules per kelvin, from the same two numbers every other solid's capacity comes from. */
    val capacity: Long get() = filled * capacityPerTile

    val kelvin: Int get() =
        if (capacity <= 0L) Temperature.SPACE_KELVIN else (energy.total / capacity).toInt()

    /** How fast it is going **through the world**, which is not how fast it crosses the grid. */
    val velocityX: Long get() = scaledRatio(impulseX, mass, Flight.PER_TILE)
    val velocityY: Long get() = scaledRatio(impulseY, mass, Flight.PER_TILE)

    /**
     * Where it is and how far it is turned — everything a transform needs, in one object.
     *
     * ⚠️ Built fresh on every read, and [Pose] runs a CORDIC loop in its constructor. Hoist it out
     * of a loop over cells; it is cheap once a tick and not cheap once a cell.
     */
    val pose: Pose get() = Pose(positionX, positionY, ang)

    /**
     * The same pose expressed in [ship]'s grid, which is where every tile index in the game lives.
     *
     * Composed rather than converted: the corner goes through [Pose.toLocalX] and the angles simply
     * subtract, because rotations commute in two dimensions. That identity is what lets a body have
     * an orientation without anything downstream of it learning about world coordinates.
     */
    fun poseIn(ship: Pose): Pose = Pose(
        ship.toLocalX(positionX, positionY),
        ship.toLocalY(positionX, positionY),
        Coord(ang.raw - ship.ang.raw),
    )

    /**
     * Where its mass is and how reluctantly it spins — [cellDistribution] over its own cells.
     *
     * Held, not computed per read: it is a double walk of the cell grid and the solver asks for it
     * several times a substep. A body's cells never change without a new body being built, so there
     * is nothing for it to go stale against — unlike the vessel's, which moves as cargo does.
     */
    val about: MassDistribution = cellDistribution(width, height, cells, massPerTile)

    /**
     * The circle about its bounding-box centre that contains all of it, whatever angle it is at —
     * the broad phase's whole rejection test, and the reason a body pair costs one compare rather
     * than a double walk of two cell grids.
     *
     * Half the box's diagonal. It has to be the diagonal and not the half-width: the box turns with
     * the body, so its corner is what sweeps the widest circle, and a bound taken on the width would
     * miss a pair touching corner-first at 45°. Held rather than recomputed because it is a square
     * root and the pair loop asks for it every substep.
     *
     * ⚠️ **Squared in millitiles, not in [Flight.PER_TILE]s.** Half a five-cell body is 2.5e9 and its
     * square is 6.3e18 against a `Long`'s 9.2e18, so the sum of two of them wraps for any body bigger
     * than about three cells — the §5.3 hazard, in a constant that would then read as a bound of
     * nothing and let every pair through. In millitiles a hundred-tile body squares to 2.5e9, which
     * is not close to anything. Rounded **up**, because a bound that is a millitile short is a bound.
     */
    val boundRadius: Long = (
        isqrt(
            (width * (Rotation.MILLI_TILE / 2L)) * (width * (Rotation.MILLI_TILE / 2L)) +
                (height * (Rotation.MILLI_TILE / 2L)) * (height * (Rotation.MILLI_TILE / 2L)),
        ) + 1L
        ) * COM_SCALE

    /** Its spin, in [Coord] raw per tick — derived from [angImpulse] exactly as [velocityX] is. */
    val angVel: Long get() = angularVelocity(angImpulse, about)

    /** Its centre of mass, in the world — the point it actually spins about. */
    val comX: Long get() = pose.toWorldX(about.comX * COM_SCALE, about.comY * COM_SCALE)
    val comY: Long get() = pose.toWorldY(about.comX * COM_SCALE, about.comY * COM_SCALE)

    /**
     * The centre of its **bounding box**, in the world — where it looks like it is, for the plating
     * test, the spawner and the tests that watch it fall.
     *
     * Not the centre of mass, which is [comX], and the difference is real for anything that is not a
     * rectangle. This is the "is it over the deck" question, and that one wants the silhouette.
     */
    val centreX: Long get() = pose.toWorldX(width * Flight.PER_TILE / 2L, height * Flight.PER_TILE / 2L)
    val centreY: Long get() = pose.toWorldY(width * Flight.PER_TILE / 2L, height * Flight.PER_TILE / 2L)

    /**
     * Its local origin in [pose]'s frame — grid coordinates, which is what every tile index in the
     * game is built from.
     *
     * ⚠️ This is the origin **only**, not the placement: since step 3 a body has an orientation of
     * its own, so knowing where its corner landed does not tell you where its cells are. Anything
     * that walks cells wants [poseIn] instead.
     */
    fun localX(pose: Pose): Long = pose.toLocalX(positionX, positionY)

    fun localY(pose: Pose): Long = pose.toLocalY(positionX, positionY)

    /** Its bounding-box centre in [pose]'s frame — what plating and the extractor's reach want. */
    fun localCentreX(pose: Pose): Long = pose.toLocalX(centreX, centreY)

    fun localCentreY(pose: Pose): Long = pose.toLocalY(centreX, centreY)

    /** Its centre of mass in [pose]'s frame — what a torque arm wants, and only that. */
    fun localComX(pose: Pose): Long = pose.toLocalX(comX, comY)

    fun localComY(pose: Pose): Long = pose.toLocalY(comX, comY)

    /**
     * What [cell] of this body is, geometrically — a disc, for every cell of every body today.
     *
     * ⚠️ A function rather than a stored array, and that is the whole of what requirement 4 of
     * `PLAN_rigid_bodies.md` needs right now. Stu wants cells to be **selectively** OBBs or triangles
     * later; storing a shape per cell before any of them differ would be a column of identical values
     * in every body and in every save, and the narrow phase cannot tell the difference — it asks this
     * question and dispatches on the answer. When shapes start to differ, this reads an array.
     */
    fun shapeAt(cell: Int): CellShape = CellShape.CELL

    fun copy(
        kind: BodyKind = this.kind,
        width: Int = this.width,
        height: Int = this.height,
        cells: BooleanArray = this.cells,
        positionX: Long = this.positionX,
        positionY: Long = this.positionY,
        impulseX: Long = this.impulseX,
        impulseY: Long = this.impulseY,
        ang: Coord = this.ang,
        angImpulse: Long = this.angImpulse,
        oreComposition: Mixture? = this.oreComposition,
        machineKind: DeckMachineKind? = this.machineKind,
        // ⚠️ Carried explicitly, like every other field here: this `copy` is hand-written, so a new
        // field that is not listed silently reverts to its default on every copy — which for a
        // fragment would mean it filled a whole tile the first time anything touched it.
        fillPermille: Int = this.fillPermille,
        // ⚠️ The trap the comment above names, and this is the field it was waiting for: `driftBodies`
        // copies every body every tick, so a station left off this list would lose its entire economy
        // on the first tick it moved — silently, and only in a running world.
        station: Station? = this.station,
        energy: TileEnergy = this.energy,
    ): RigidBody = RigidBody(
        kind = kind, width = width, height = height, cells = cells,
        positionX = positionX, positionY = positionY,
        impulseX = impulseX, impulseY = impulseY,
        ang = ang, angImpulse = angImpulse,
        oreComposition = oreComposition, machineKind = machineKind, fillPermille = fillPermille,
        station = station,
        energy = energy,
    )

    override fun equals(other: Any?): Boolean =
        this === other || (other is RigidBody &&
            kind == other.kind &&
            width == other.width && height == other.height && cells.contentEquals(other.cells) &&
            positionX == other.positionX && positionY == other.positionY &&
            impulseX == other.impulseX && impulseY == other.impulseY &&
            ang == other.ang && angImpulse == other.angImpulse &&
            oreComposition == other.oreComposition && machineKind == other.machineKind &&
            fillPermille == other.fillPermille && station == other.station && energy == other.energy)

    override fun hashCode(): Int = (kind.ordinal * 31 + (positionX * 31 + positionY).toInt()) * 31 + cells.contentHashCode()

    override fun toString(): String =
        "${kind.name}(${width}x$height, ${filled} cells, ${mass}g at " +
            "${positionX / Flight.PER_TILE},${positionY / Flight.PER_TILE})"

    companion object {
        /**
         * Millitiles to [Flight.PER_TILE]s: what a centre of mass has to be multiplied by to stand
         * next to a position. A thousandth of a tile is as fine as a lever arm is ever measured.
         */
        const val COM_SCALE: Long = Flight.PER_TILE / Rotation.MILLI_TILE

        /*
         * ⛔ **`MATERIAL = Material.Firebrick` stood here and was read by nothing.** It said a rock
         * conducts like a furnace lining because [Species] carried no conductivity of its own; it
         * does now, and `conductivityOf(oreComposition)` answers from what the rock actually assays
         * at. The constant had already been made dead by that and was only waiting to be noticed.
         */

        /**
         * Tolerance for fragment shape derivation: 0.1 tile, shaved on exposed edges.
         * Not applied to rocks — rockBlob already rasterises a disc shape.
         */
        val TOLERANCE: Long = Flight.PER_TILE / 10L

        /**
         * A blob roughly [radius] cells across.
         *
         * A disc rather than a square, because the first thing anyone will do is look at it, and a
         * square body reads as a crate. Rasterised on the cell centres so it is symmetric.
         */
        /**
         * A station: a **hollow rectangular shell**, [width] by [height] cells, [thickness] deep.
         *
         * ⛔ **Hollow is a performance requirement before it is an aesthetic one, and it is exact.**
         * `collectBodyContacts` is O(cells × cells), culled only by a whole-body bound-radius test —
         * so a *solid* 100×100 station is 10,000 cells with a ~70-tile radius that culls nothing in
         * its own neighbourhood, and every rock nearby pays 10,000 pair tests a substep. The
         * perimeter is ~400: **twenty-five times cheaper**, and it is also what a station *is*, which
         * is why this is not a trick. `cellDistribution` walks the mask and skips empties, so the
         * centre of mass, the radius of gyration and [boundRadius] all come out right for a ring with
         * no code anywhere else knowing it is one.
         *
         * ⛔ `CellShape.Box` would not have helped: the cost is per cell whichever shape a cell is.
         * The coarse decomposition that *would* — a dozen boxes for the whole station — needs a body
         * whose collider set is decoupled from its cell mask, which does not exist. See
         * `PLAN_economy.md` §6.
         */
        /**
         * How big a station is, per side — **twenty tiles** (Stu, 2026-09-01).
         *
         * ⛔ **Chosen against a measurement, not a feeling.** A 100×100 shell adjacent to the player
         * costs **12.1 ms a tick** against a budget of ~3.5 ms mean and 8.5 ms p95
         * (`reference_oos_perf_levers`); 20×20 costs **0.7 ms**. The cost is entirely *contacts* —
         * the same station a thousand tiles away costs nothing at all — because a station's bound
         * radius culls nothing in its own neighbourhood and every rock nearby then pays
         * `cells × cells`. Full numbers in `PLAN_economy.md` §10b.
         *
         * ⏸ **This is a holding position and it is meant to move.** A hundred tiles is the station
         * Stu wants; what unlocks it is the coarse box decomposition parked in `PLAN_economy.md` §6
         * — a dozen colliders for the whole hull instead of one per cell. Nothing but this number
         * has to change when that lands.
         */
        const val STATION_TILES: Int = 20

        fun stationShell(
            width: Int = STATION_TILES,
            height: Int = STATION_TILES,
            positionX: Long,
            positionY: Long,
            composition: Mixture,
            station: Station,
            thickness: Int = 1,
            fillPermille: Int = 1_000,
            kelvin: Int = Temperature.AMBIENT_KELVIN,
        ): RigidBody {
            require(width > 2 * thickness && height > 2 * thickness) {
                "a ${width}x$height shell $thickness deep has no inside"
            }
            // A berth on the middle of each side unless the caller says otherwise. Four, because a
            // station approachable from one bearing only is one the player must fly around before
            // they may trade — a chore, not a manoeuvre.
            val berths = station.docks.ifEmpty {
                listOf(
                    DockNode(width / 2, 0, Direction.Up),
                    DockNode(width - 1, height / 2, Direction.Right),
                    DockNode(width / 2, height - 1, Direction.Down),
                    DockNode(0, height / 2, Direction.Left),
                )
            }
            val cells = BooleanArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    cells[y * width + x] =
                        x < thickness || y < thickness ||
                            x >= width - thickness || y >= height - thickness
                }
            }
            val filled = cells.count { it }
            return RigidBody(
                kind = BodyKind.STATION,
                width = width, height = height, cells = cells,
                positionX = positionX, positionY = positionY,
                impulseX = 0L, impulseY = 0L,
                oreComposition = composition,
                fillPermille = fillPermille,
                station = Station(station.ore, station.market, station.id, berths),
                energy = TileEnergy.uniform(
                    filled,
                    capacityPerTileOf(composition) * fillPermille / 1_000 * kelvin,
                ),
            )
        }

        fun rockBlob(
            radius: Int,
            positionX: Long,
            positionY: Long,
            composition: Mixture,
            impulseX: Long = 0L,
            impulseY: Long = 0L,
            kelvin: Int = Temperature.AMBIENT_KELVIN,
        ): RigidBody {
            val d = radius * 2 + 1
            val cells = BooleanArray(d * d)
            for (y in 0 until d) {
                for (x in 0 until d) {
                    val dx = x - radius
                    val dy = y - radius
                    cells[y * d + x] = dx * dx + dy * dy <= radius * radius + radius
                }
            }
            val filled = cells.count { it }
            return RigidBody(
                kind = BodyKind.ROCK,
                width = d, height = d, cells = cells,
                positionX = positionX, positionY = positionY,
                impulseX = impulseX, impulseY = impulseY,
                oreComposition = composition,
                energy = TileEnergy.uniform(filled, capacityPerTileOf(composition) * kelvin),
            )
        }
    }
}

/**
 * The only force a vessel exerts on a body at a distance: the deck plating, and only over the deck.
 *
 * [platingGravity] is [VesselState.gravity], the *setting* — a field the ship makes, which is why it
 * stops where the ship does. There is no second term. A body's momentum is written in the world
 * frame, and the world frame is inertial, so the vessel's own acceleration is not a force on
 * anything: it shows up in [driftBodies] as the grid sliding under the body instead. See [RigidBody].
 */
fun platingFeltBy(grid: Grid, centreX: Long, centreY: Long, platingGravity: org.emerge.sim.core.physics.primitives.Frac2): org.emerge.sim.core.physics.primitives.Frac2 {
    val tx = centreX / Flight.PER_TILE
    val ty = centreY / Flight.PER_TILE
    val aboard = centreX >= 0L && centreY >= 0L && tx < grid.width && ty < grid.height
    return if (aboard) platingGravity else org.emerge.sim.core.physics.primitives.Frac2(
        org.emerge.sim.core.physics.primitives.Frac(0L),
        org.emerge.sim.core.physics.primitives.Frac(0L)
    )
}

/**
 * What one tick did to every body, and what it therefore did to the ship.
 *
 * [handedX] is every gram·tile of momentum the **vessel** gave the bodies this tick, by any means; the
 * ship owes itself the negative of it, and booking both in the same breath is what makes the whole
 * business conserve by construction. See [VesselState.bodyImpulseX].
 */
class BodyStep(
    val bodies: List<RigidBody>,
    val handedX: Long,
    val handedY: Long,
    /**
     * The twist that went with it, about the vessel's centre of mass — booked at the body's own
     * centre, because a rock bouncing off a nacelle spins the ship and a rock bouncing off the nose
     * does not. The ship owes itself the negative of this, exactly as it does of [handedX].
     */
    val handedTorque: Long = 0L,
    /**
     * What the tick sounded like — see [Impact]. Passed straight through from the sweep, because
     * nothing between here and a host has anything to add to it.
     */
    val impacts: List<Impact> = emptyList(),
) {
    companion object {
        /**
         * The bodies exactly as they are, nothing exchanged and nothing struck — what a tick that
         * did not sweep them at all produces. See `OutofspaceReducer.freeze`.
         *
         * ⚠️ **Not the same as sweeping them zero distance.** A sweep still resolves the contacts a
         * body is already standing in and still reports them, so a rock resting on the plating would
         * report a hit on every frozen tick and a paused game would clang once a tick for as long as
         * it was stopped. This reports none, which is what "nothing happened" means.
         */
        fun still(bodies: List<RigidBody>) = BodyStep(bodies, 0L, 0L)
    }
}

/**
 * Body drift: grid moves by ship velocity, body advances by (body - ship) velocity.
 * Sweep (not jump) prevents bulkhead stepping. Plating applied after sweep (tick ordering).
 * ⚠️ Plating costs the ship (prevents momentum pump from gravity).
 */
fun driftBodies(
    grid: Grid,
    structure: StructureMap,
    bodies: List<RigidBody>,
    platingGravity: org.emerge.sim.core.physics.primitives.Frac2,
    ship: ShipMotion,
    shipMass: Long,
    about: MassDistribution = MassDistribution.EMPTY,
    /** The deck, carried this far for one reason: [frictionBetween]. */
    deck: DeckArray? = null,
): BodyStep {
    if (bodies.isEmpty()) return BodyStep(bodies, 0L, 0L)
    /**
     * ⚠️ The resting threshold is about a **closing** speed, so it is built from the acceleration
     * that closes the gap — and that depends on the body, which is why it is computed per body
     * rather than once for the tick.
     *
     * Two changes from the version this replaces, and they pull the same way.
     *
     * **It is the relative acceleration.** The plating pushes the body toward the deck and the
     * deck's reaction pushes the ship the other way, so one tick of free flight opens the gap by
     * `a(1 + m/M)`, not by `a`. Against a hull that dwarfed every rock those were the same number.
     * Against a 40-tonne box and an 83-tonne rock they differ by a factor of three.
     *
     * **[shipAcceleration] is gone from it**, and its being there is what actually did the damage.
     * It is the ship's *net* acceleration, which includes the ship's reaction to this very body's
     * plating — the one term the deck cancels the instant the body is resting. So the threshold was
     * circular: the harder the body pressed, the lower the bar for calling it asleep went, and in
     * the steady state the two terms cancelled outright and left nothing but [RockContact.REST_FLOOR].
     * A rock that should have been lying on the floor bounced 1.4 tiles for ever in a perfectly
     * stable limit cycle, with every quantity conserved and nothing to see in the ledger.
     *
     * Thrust does not belong here for the same reason: a ship under burn drags a resting body along
     * *through the deck*, and a force transmitted by the contact cannot also be a force opening it.
     */
    fun restingSpeed(felt: Long, mass: Long): Long =
        if (shipMass <= 0L) RockContact.restingSpeed(felt)
        else RockContact.restingSpeed(felt + scaledRatio(felt, shipMass, mass))

    // ⚠️ **One sweep for all of them, not one sweep each**, and that is step 5 of
    // `PLAN_rigid_bodies.md` rather than a tidy-up. A body swept on its own has nothing to touch but
    // the hull — whatever the narrow phase can do, the *shape of the tick* decided that two rocks
    // pass through each other — and a stack cannot converge while the rock underneath is solved in a
    // loop iteration of its own, after the one above it has already spent its whole tick.
    //
    // The stale-wall fix that used to live here, feeding each body's recoil to the next, is gone
    // with the loop: there is one ship operand now and every body argues with it in the same pass.
    val restingX = LongArray(bodies.size)
    val restingY = LongArray(bodies.size)
    val platingX = LongArray(bodies.size)
    val platingY = LongArray(bodies.size)
    for (i in bodies.indices) {
        val body = bodies[i]
        val mass = body.mass
        if (mass <= 0L) continue
        // Plating is a field the *ship* makes, so whether a body is over the deck is a question
        // about the grid, asked in the grid's frame.
        val felt = platingFeltBy(
            grid,
            body.localCentreX(ship.pose),
            body.localCentreY(ship.pose),
            platingGravity,
        )
        // ⚠️ The mass is the *scale*, not the numerator. `mass × raw` is a mass times a fixed-point
        // one — one whole g of plating is a raw of [Flight.FRAC_ONE], 2.1e9 — so written the obvious
        // way round it wraps for any body over about four kilograms at a microgram per unit. An 83 kg
        // rock therefore did not fall at all, and `RockContactTest` reported that as "the body never
        // landed": a wrapped impulse reads as an *absence*, which is the rescale's standing lesson.
        platingX[i] = scaledRatio(felt.x.raw, Flight.FRAC_ONE, mass)
        platingY[i] = scaledRatio(felt.y.raw, Flight.FRAC_ONE, mass)
        // ⚠️ **The resting pair is turned into the world before the threshold is taken**, and step 6
        // is what made that necessary. A contact's normal used to arrive in the grid's axes, so a
        // threshold quoted per grid axis met it in its own frame; contacts come back in the world
        // now, and [contactAt] blends these two by the normal's components. Left in the grid they
        // would have answered the sideways question for the upward one aboard a rolled ship — a rock
        // on the deck of a ship on its side would be held to the threshold for sliding rather than
        // the one for settling, which reads as a rock that will not go to sleep.
        //
        // Turned as a *vector* and then taken per component, rather than the two magnitudes being
        // turned: [restingSpeed] is not linear in what it is given, so the turn has to happen to the
        // field while it is still a field. Plating above keeps the grid pair on purpose — it is
        // turned at the ledger boundary, and its torque is booked against a grid-frame arm.
        val worldFeltX = ship.pose.turnedX(felt.x.raw, felt.y.raw)
        val worldFeltY = ship.pose.turnedY(felt.x.raw, felt.y.raw)
        restingX[i] = restingSpeed(worldFeltX, mass)
        restingY[i] = restingSpeed(worldFeltY, mass)
    }

    val swept = sweepBodies(
        grid, structure, bodies,
        ship, shipMass, about,
        restingX, restingY,
        deck,
    )

    // The contact half of the ledger comes back from the sweep already booked at the points the
    // touches actually happened, which is the only place it can be booked correctly — a rock landing
    // on one corner twists the ship differently from a rock landing flat, and a figure derived from
    // the total impulse cannot tell those apart.
    var handedX = swept.handedX
    var handedY = swept.handedY
    var handedTorque = swept.handedTorque

    val moved = swept.bodies.mapIndexed { i, body ->
        if (bodies[i].mass <= 0L) return@mapIndexed body
        // ⚠️ Plating pulls toward the *deck*, so what comes back is a direction in the grid, while a
        // body's momentum is in the world — the same frame boundary the vessel's own ledger crosses
        // in [OutofspaceSim.step], and the same failure if it is not crossed: a rock aboard a ship
        // turned on its side fell along the grid's y rather than toward the plating under it. The
        // torque keeps the **grid-frame** pair, because it is booked against a grid-frame arm.
        val worldPlatingX = ship.pose.turnedX(platingX[i], platingY[i])
        val worldPlatingY = ship.pose.turnedY(platingX[i], platingY[i])
        handedX += worldPlatingX
        handedY += worldPlatingY
        // ⚠️ The **plating** torque is a separate arm from the contact one: a field acts at the
        // body's centre of mass, not at whatever it happens to be touching, and it acts whether the
        // body is touching anything at all. Booked in millitiles, which is what [torqueAbout] works
        // in, and about the arm the body had at the *start* of the tick, which is where the field
        // was sampled.
        handedTorque += torqueAbout(
            about,
            bodies[i].localComX(ship.pose) / RigidBody.COM_SCALE,
            bodies[i].localComY(ship.pose) / RigidBody.COM_SCALE,
            platingX[i], platingY[i],
        )
        body.copy(
            impulseX = body.impulseX + worldPlatingX,
            impulseY = body.impulseY + worldPlatingY,
        )
    }
    return BodyStep(moved, handedX, handedY, handedTorque, swept.impacts)
}
