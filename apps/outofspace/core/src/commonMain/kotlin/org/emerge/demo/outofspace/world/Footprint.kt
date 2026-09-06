package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.world.machine.DeckMachineKind

val DeckMachineKind.diameter: Int
    get() = when (this) {
        DeckMachineKind.Hull, DeckMachineKind.Airlock, DeckMachineKind.Vent -> 1
        // A room-sized installation, as it was on the machine list.
        DeckMachineKind.Warehouse -> 3
        // Three tiles end to end, and only ever three *along* its facing — the bridge's shape. See
        // [FootprintShape.Span].
        DeckMachineKind.Silo -> 3
        // One tile wide, like the thruster it shares a shape with — its *length* is two, and that is
        // not this number. See [FootprintShape.Nose].
        DeckMachineKind.Buffer -> 1
        DeckMachineKind.Sensor, DeckMachineKind.KeyInput, DeckMachineKind.Pump,
        DeckMachineKind.Gauge, DeckMachineKind.Valve,
        // A panel is a plate on the hull, not an installation: one tile, and you build a bank of
        // them rather than a bigger one.
        DeckMachineKind.SolarPanel,
        -> 1
        // One tile wide. Its *length* is two, and that is not this number — see [FootprintShape.Nose].
        DeckMachineKind.Thruster -> 1
        DeckMachineKind.Concentrator, DeckMachineKind.Furnace -> 3
        // A room-sized installation with three mouths on three different faces.
        DeckMachineKind.Electrolyzer -> 3
        // Two doors at the back, a chamber in the middle, a bell on the front face. Square rather
        // than [FootprintShape.Nose]: at reach 1 the front-centre tile *is* one step facing-ward of
        // the anchor, so `Engine.bell` lands on it and the exhaust walk needs no new geometry.
        DeckMachineKind.Rocket -> 3
        // A collar big enough to berth against, and the same three tiles a storage claims.
        DeckMachineKind.DockingPort -> 3
        // Five: it should dominate the deck it sits on, and its heat should have somewhere to be.
        DeckMachineKind.Extractor -> 5
        // Three tiles end to end. Only ever three *along* its facing — see [FootprintShape.Span].
        DeckMachineKind.Bridge -> 3
    }

/**
 * How a kind's footprint hangs off the tile the machine is stored at.
 *
 * ### What each shape gives up
 *
 * [Square] is the shape everything started as, and it is the one that made a footprint cheap: the
 * same set of tiles however the machine is turned, centred on the tile it is stored at. Two
 * properties fall out of that and both were relied on all over the codebase — a rotation is *a
 * change of facing and nothing else*, and the anchor tile *is* the centre of mass of the thing
 * standing on it.
 *
 * [Span] gave up the first. A bridge turned is a bridge on two different tiles, so a rotation can be
 * refused and `Edit.Rotate` has to check ([org.emerge.demo.outofspace.OutofspaceSim] and
 * `canStandWhereItWouldTurn`).
 *
 * [Nose] gives up the second, and it is the deeper break. **The anchor is not the middle**: a
 * thruster is stored at the tile you feed, and its bell is the tile in front of that. So
 * `DeckMachine.center` names *where the machine is kept*, not where its middle is, and anything that
 * wants the middle — a lever arm, a bounding box, a body to draw — has to walk [footprint] and work
 * it out. Nothing may reconstruct a footprint from a centre and a half-width; that arithmetic is
 * wrong for two of the three shapes now, and it is wrong *silently*, because it still answers a
 * plausible set of tiles.
 */
enum class FootprintShape {
    /** [diameter]×[diameter], centred on the anchor, and the same tiles whichever way it faces. */
    Square,

    /**
     * A line [diameter] long **along the facing**, centred on the anchor. The bridge and the silo.
     *
     * Worth the exception rather than making a bridge 3×3, which would have it claim nine tiles to
     * cross one, or 1×1, which is what it was when it occupied no floor at all and could be stacked
     * without limit. A [org.emerge.demo.outofspace.world.machine.DeckMachineKind.Silo] is here for
     * the other half of the same argument: a store that fits in a corridor is the point of it, and
     * one made 3×3 would just be a small warehouse.
     */
    Span,

    /**
     * The anchor, plus **one tile in the facing direction**: a 1×2 whose anchor is at the tail.
     *
     * The thruster and the buffer. For a thruster the anchor is the chamber — where the propellant
     * arrives and where the machine's one store sits — and the nose is the bell, which juts out into
     * the exhaust direction so that a motor's plume starts outside its own feed tile. That is what
     * makes an engine cost the deck space its exhaust needs rather than borrowing it from the room.
     *
     * A [org.emerge.demo.outofspace.world.machine.DeckMachineKind.Buffer] reads the same shape the
     * other way round: the anchor is its mouth *and* its store, and the nose is where material
     * leaves. ⚠️ It is therefore the one Nose machine whose nose is a **port** rather than bare
     * casing, which is why `localPorts` states it rather than deriving it from `reach` — at one tile
     * wide `reach` is zero, and both its doors would land on the same tile.
     */
    Nose,
}

/** The shape of this kind's footprint — see [FootprintShape] for what each one gives up. */
val DeckMachineKind.shape: FootprintShape
    get() = when (this) {
        DeckMachineKind.Bridge, DeckMachineKind.Silo -> FootprintShape.Span
        DeckMachineKind.Thruster, DeckMachineKind.Buffer -> FootprintShape.Nose
        else -> FootprintShape.Square
    }

/**
 * Half-width: how far a [FootprintShape.Square] footprint reaches from its centre in each direction.
 *
 * ⚠️ **This is not "how big the machine is"** and it never was — it is where a square kind's ports
 * and stores sit, which is what every caller actually wants it for (see
 * [org.emerge.demo.outofspace.world.localBufferOffset] and `localPorts`). For a [FootprintShape.Nose]
 * or a [FootprintShape.Span] it answers about the machine's *width*, and a footprint rebuilt from it
 * would be wrong. Use [footprint].
 */
val DeckMachineKind.reach: Int get() = diameter / 2

/**
 * Footprint of indexes pointing to the material a machine is made of, or null if it does not fit on
 * [grid] at [center].
 *
 * Tiles come back in **ascending index order**, which is row-major on the grid. Arbitrary but fixed:
 * nothing downstream reads a particular tile out by position, but several places pair a footprint
 * with an array of per-tile values ([org.emerge.demo.outofspace.world.machine.DeckMachine.energy]
 * against `setEnergy`), so two walks of the same machine must agree.
 *
 * [facing] is read for every shape but [FootprintShape.Square], which covers the same tiles whichever
 * way its machine is pointing.
 */
fun DeckMachineKind.footprint(
    center: TileIndex,
    grid: Grid,
    facing: Direction = Direction.Right,
): Array<TileIndex>? {
    val cx = grid.xOf(center)
    val cy = grid.yOf(center)
    return when (shape) {
        FootprintShape.Nose -> {
            val nx = cx + facing.dx
            val ny = cy + facing.dy
            if (!grid.inBounds(cx, cy) || !grid.inBounds(nx, ny)) return null
            val anchor = grid.tile(cx, cy)
            val nose = grid.tile(nx, ny)
            // Right and Down put the nose at the higher index; Left and Up put it at the lower one.
            if (facing == Direction.Right || facing == Direction.Down) arrayOf(anchor, nose)
            else arrayOf(nose, anchor)
        }
        FootprintShape.Square, FootprintShape.Span -> {
            // How far it reaches along x and along y — equal for a square, and all in one axis for a
            // span.
            val square = shape == FootprintShape.Square
            val rx = if (square || facing.isHorizontal) reach else 0
            val ry = if (square || !facing.isHorizontal) reach else 0

            // Check footprint validity
            if (cx - rx < 0 || cx + rx >= grid.width) return null
            if (cy - ry < 0 || cy + ry >= grid.height) return null

            val w = rx * 2 + 1
            Array(w * (ry * 2 + 1)) { grid.tile(cx + (it % w) - rx, cy + (it / w) - ry) }
        }
    }
}
