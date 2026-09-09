package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.world.machine.DeckMachine
import org.emerge.demo.outofspace.world.machine.DeckMachineKind

/**
 * **The block of tiles a machine stands on: a width, a height, and which tile of it the deck keeps
 * the machine at.**
 *
 * ⛔ **It used to be a half-width, and that is what this replaces.** A kind stated a `diameter`, the
 * builder derived `reach = diameter / 2` and laid out `w = rx * 2 + 1` tiles — so **every footprint
 * was odd by construction** and a 3×2 could not be said at all. The one even shape aboard, the
 * thruster and the buffer at 1×2, was a hand-written branch that bypassed the arithmetic entirely.
 * See `PLAN_machine_relocation.md` increment 0.
 *
 * ⛔ **It is a value, not a property of the enum.** A shape can be stated and tested without standing
 * a machine kind up to hold it, which is what lets a 3×2 be proved to work before any machine is one.
 *
 * ⚠️ **There is no pivot here and there deliberately is not one.** An earlier design gave a footprint
 * a `Pivot` — `Anchor` or `Centre` — to decide what an *in-place rotation* turned about, and with it
 * a parity rule for which turns landed back on the grid. Machines are **moved** now rather than
 * turned in place, and a machine being placed is under no such constraint, so the whole question
 * stopped being asked. See the superseded `PLAN_footprints_and_rotation.md` for the argument that no
 * longer applies.
 */
data class Footprint(
    /** Along the machine's own facing. */
    val width: Int,
    /** Across it. */
    val height: Int,
    /**
     * Which tile of the block the deck stores the machine at, in the block's own frame.
     *
     * ⚠️ **Not necessarily the middle.** A thruster is stored at the tile you feed and its bell juts
     * out in front, so [DeckMachine.center] names *where the machine is kept* and not where its
     * middle is. Nothing may reconstruct a footprint from a centre and a half-width; that arithmetic
     * is wrong for two of the shapes aboard and it is wrong **silently**, because it still answers a
     * plausible set of tiles.
     */
    val anchorX: Int,
    val anchorY: Int,
) {
    init {
        require(width > 0 && height > 0) { "a footprint is ${width}x$height" }
        require(anchorX in 0 until width && anchorY in 0 until height) {
            "anchor ($anchorX,$anchorY) is outside a ${width}x$height block"
        }
    }

    /**
     * How far the block reaches from its anchor, in the machine's own frame — the frame where the
     * machine faces [Direction.Right], which is the frame every offset table already states its
     * offsets in.
     *
     * ⭐ **This is what replaced `reach`, and it is four numbers because `reach` was one number doing
     * the work of four.** `reach` was a square's half-width, and three separate tables — `localPorts`,
     * `localBufferOffset`, `localTerminalOffset` — hung their geometry off it. For a square that was
     * right by luck: all four of these are equal and all four equal the half-width. For a span it was
     * right along one axis and meaningless across it, and for a nose it was zero and the ports had to
     * be written out by hand.
     *
     * Stated this way every shape answers, oblong ones included, and a 3×2 can say where its doors
     * are without a special case.
     */
    val ahead: Int get() = width - 1 - anchorX

    /** Behind the anchor, against the facing. */
    val behind: Int get() = anchorX

    /** ⚠️ **Toward the floor at [Direction.Right]** — `+y` is down, so this is a machine's "out of the bottom". */
    val below: Int get() = height - 1 - anchorY

    /** And toward the ceiling. */
    val above: Int get() = anchorY

    /** Tiles, if the block fits on [grid] with its anchor at [anchor]. See [DeckMachineKind.footprint]. */
    fun tilesAt(anchor: TileIndex, grid: Grid, facing: Direction): Array<TileIndex>? {
        val cx = grid.xOf(anchor)
        val cy = grid.yOf(anchor)

        // The block's extent about its anchor in the machine's own frame, turned into the grid's.
        // A rectangle turned by a quarter is still a rectangle, so the two turned corners bound it
        // and there is nothing to walk.
        var gx0 = Int.MAX_VALUE; var gx1 = Int.MIN_VALUE
        var gy0 = Int.MAX_VALUE; var gy1 = Int.MIN_VALUE
        for (lx in intArrayOf(-behind, ahead)) {
            for (ly in intArrayOf(-above, below)) {
                val tx = turnedX(lx, ly, facing)
                val ty = turnedY(lx, ly, facing)
                if (tx < gx0) gx0 = tx
                if (tx > gx1) gx1 = tx
                if (ty < gy0) gy0 = ty
                if (ty > gy1) gy1 = ty
            }
        }

        if (cx + gx0 < 0 || cx + gx1 >= grid.width) return null
        if (cy + gy0 < 0 || cy + gy1 >= grid.height) return null

        // ⛔ **Walked over the GRID's box, not the machine's own.** The order has to be ascending tile
        // index — see [DeckMachineKind.footprint] — and a walk of the machine's own frame mapped
        // through the facing gives *local* row-major order, which is ascending only for two of the
        // four facings. That is the trap the old `Nose` branch sorted its pair by hand to avoid.
        val w = gx1 - gx0 + 1
        return Array(w * (gy1 - gy0 + 1)) { grid.tile(cx + gx0 + it % w, cy + gy0 + it / w) }
    }

    companion object {
        /** [size]×[size], anchored at its middle — the shape that needs [size] to be odd. */
        fun square(size: Int) = Footprint(size, size, size / 2, size / 2)

        /**
         * A line [length] long **along the facing**, anchored at its middle. The bridge and the silo.
         *
         * Worth the shape rather than making a bridge 3×3, which would have it claim nine tiles to
         * cross one, or 1×1, which is what it was when it occupied no floor at all and could be
         * stacked without limit. A silo is here for the other half of the same argument: a store that
         * fits in a corridor is the point of it, and one made 3×3 would just be a small warehouse.
         */
        fun span(length: Int) = Footprint(length, 1, length / 2, 0)

        /**
         * A line [length] long along the facing, anchored at its **tail**. The thruster and the
         * buffer.
         *
         * For a thruster the anchor is the chamber — where the propellant arrives and where the
         * machine's one store sits — and what juts out in front is the bell, so a motor's plume
         * starts outside its own feed tile. That is what makes an engine cost the deck space its
         * exhaust needs rather than borrowing it from the room. A buffer reads the same shape the
         * other way round: the anchor is its mouth *and* its store, and the far tile is where
         * material leaves.
         */
        fun nose(length: Int) = Footprint(length, 1, 0, 0)
    }
}

/** One quarter-turn clockwise is `(x, y) -> (-y, x)`; [Direction.Right] is the machine's own frame. */
private fun turnedX(x: Int, y: Int, facing: Direction): Int = when (facing) {
    Direction.Right -> x
    Direction.Down -> -y
    Direction.Left -> -x
    Direction.Up -> y
}

private fun turnedY(x: Int, y: Int, facing: Direction): Int = when (facing) {
    Direction.Right -> y
    Direction.Down -> x
    Direction.Left -> -y
    Direction.Up -> -x
}

/**
 * The block each kind stands on.
 *
 * ⚠️ **A size here is a claim about the deck, and the ports that hang off it are stated separately** —
 * see `localPorts`, [localBufferOffset] and `localTerminalOffset`, which state their offsets in terms
 * of [Footprint.ahead] and its three companions rather than deriving them from a half-width.
 */
val DeckMachineKind.shape: Footprint
    get() = when (this) {
        DeckMachineKind.Hull, DeckMachineKind.Airlock, DeckMachineKind.Vent -> ONE_TILE
        // A room-sized installation, as it was on the machine list.
        DeckMachineKind.Warehouse -> Footprint.square(3)
        // Three tiles end to end, and only ever three *along* its facing.
        DeckMachineKind.Silo -> Footprint.span(3)
        // Its *length* is two and its width is one — the thruster's shape, anchored at the mouth.
        DeckMachineKind.Buffer -> Footprint.nose(2)
        DeckMachineKind.Sensor, DeckMachineKind.KeyInput, DeckMachineKind.Pump,
        DeckMachineKind.Gauge, DeckMachineKind.Valve,
        // One tile, and the panel's constraint does not reach it: a terminal has no second end to
        // keep on a separate body, because it has no ends at all. See [TerminalRole.Bond].
        DeckMachineKind.Terminal,
        -> ONE_TILE
        // ⛔ **Three, and the geometry forced it.** This was one tile, on the argument that *"a panel
        // is a plate on the hull, not an installation: one tile, and you build a bank of them rather
        // than a bigger one"* — which is a good argument and loses to a hard constraint. A machine
        // has two electrical ends and a one-tile machine's casing is a **single body**, so its two
        // ends would be the same node: a dead short no material can fix. The terminals go on the
        // centre line at either end (Stu), which is `Warehouse`'s shape and needs no new machinery.
        // See `PLAN_power_network.md` §4.
        DeckMachineKind.SolarPanel -> Footprint.square(3)
        DeckMachineKind.Thruster -> Footprint.nose(2)
        DeckMachineKind.Concentrator, DeckMachineKind.Furnace -> Footprint.square(3)
        // A room-sized installation with three mouths on three different faces.
        DeckMachineKind.Electrolyzer -> Footprint.square(3)
        // Two doors at the back, a chamber in the middle, a bell on the front face. Square rather
        // than a nose: at three across, the front-centre tile *is* one step facing-ward of the
        // anchor, so `Engine.bell` lands on it and the exhaust walk needs no new geometry.
        DeckMachineKind.Rocket -> Footprint.square(3)
        // A collar big enough to berth against, and the same three tiles a storage claims.
        DeckMachineKind.DockingPort -> Footprint.square(3)
        // Five: it should dominate the deck it sits on, and its heat should have somewhere to be.
        DeckMachineKind.Extractor -> Footprint.square(5)
        // Three tiles end to end. Only ever three *along* its facing.
        DeckMachineKind.Bridge -> Footprint.span(3)
    }

private val ONE_TILE = Footprint.square(1)

/**
 * Footprint of indexes pointing to the material a machine is made of, or null if it does not fit on
 * [grid] at [center].
 *
 * Tiles come back in **ascending index order**, which is row-major on the grid. Arbitrary but fixed:
 * nothing downstream reads a particular tile out by position, but several places pair a footprint
 * with an array of per-tile values ([org.emerge.demo.outofspace.world.machine.DeckMachine.energy]
 * against `setEnergy`), so two walks of the same machine must agree.
 *
 * ⚠️ **And the order must not depend on [facing]**, which is a stronger statement than it looks: two
 * walks in one breath agree for free, because both call `tiles(grid)`. What would break is a walk
 * either side of a **turn** — and a machine can be turned now, so the guarantee has to hold across
 * one. [Footprint.tilesAt] gets it by walking the grid's box rather than the machine's own.
 */
fun DeckMachineKind.footprint(
    center: TileIndex,
    grid: Grid,
    facing: Direction = Direction.Right,
): Array<TileIndex>? = shape.tilesAt(center, grid, facing)
