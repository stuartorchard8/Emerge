package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.newDeckMachine

/**
 * Whether a [kind] anchored at [tile] and pointing [facing] may stand there — **the one statement of
 * where a building is allowed**, asked by the reducer before it places one and by the cursor before
 * the player has clicked.
 *
 * ### Why the world arrives as three lambdas
 *
 * The two callers are looking at different worlds. The reducer asks part-way through applying a
 * queue of edits, against working copies that already carry everything earlier in the queue did; the
 * renderer asks against the settled [VesselState] of the frame it is drawing. Handing this a
 * `VesselState` would have meant the reducer could not use it, and the moment only one of them uses
 * it the picture and the rule start to drift — which is the entire failure this exists to prevent. A
 * preview that says yes where the reducer says no is worse than no preview, because it is a promise
 * the game then breaks.
 *
 * So the questions come in and the answers stay out:
 *
 * - [occupied] — is something already standing on this tile?
 * - [portsOn] — what is already plumbed into it? Rail, in practice: every port in the game is a rail
 *   port, and the conduits are compared anyway so a caller that widens it stays correct.
 * - [displaceAir] — has the air in this area got somewhere to go? A caller that is only asking
 *   passes a test; the reducer passes one that also *moves* the air when it is building for real.
 *
 * ⚠️ **The order matters and is the reducer's.** The footprint is checked first because the other
 * two are only meaningful once there is a set of tiles to ask about, and the air last because it is
 * by far the most expensive and the only one that may have a side effect.
 */
fun canStand(
    grid: Grid,
    kind: DeckMachineKind,
    tile: TileIndex,
    facing: Direction,
    occupied: (TileIndex) -> Boolean,
    portsOn: (TileIndex) -> List<Port>,
    displaceAir: (List<TileIndex>) -> Boolean,
): Boolean {
    // Null means it hangs off the grid — half a bridge, or a smelter over the rim. This is where a
    // motor's bell is checked for room, so an engine cannot be nosed into a wall.
    val covered = (kind.footprint(tile, grid, facing) ?: return false).toList()
    // The whole footprint, not just the tile under the cursor.
    if (covered.any(occupied)) return false
    val proposed = portsOf(grid, newDeckMachine(kind, tile, facing), tile)
    if (proposed.any { p -> portsOn(p.tile).any { it.conduit == p.conduit } }) return false
    // A solid deck machine is solid — air must have somewhere to go. Last check, and about air
    // rather than geometry. A permeable one displaces nothing and so may be put down in a sealed
    // room.
    //
    // ⚠️ **A ghost is refused on the same terms and displaces nothing.** It has no metal to push air
    // aside with, so the room it stands in is unchanged until it is finished — but the restriction
    // still governs where it may be put, or a player would draw a frame in a sealed room and be told
    // only at completion that it could never have been built there.
    if (kind.preventAirflow && !displaceAir(covered)) return false
    return true
}

/**
 * [canStand] asked of a settled world, which is what a preview under the cursor has to hand.
 *
 * Nothing is committed and nothing is allocated beyond the footprint itself, because this runs once
 * a frame for as long as the player holds the build tool.
 */
fun VesselState.canStand(kind: DeckMachineKind, tile: TileIndex, facing: Direction): Boolean =
    tile != TileIndex.NONE && tile.index in 0 until deck.size && canStand(
        grid = grid,
        kind = kind,
        tile = tile,
        facing = facing,
        occupied = { occupancy[it] != TileIndex.NONE },
        portsOn = { t -> portsAt(occupancy[t]).filter { it.tile == t } },
        displaceAir = { area -> air.canDisplace(grid, area) { deck.isPermeableToAir(it) } },
    )

/**
 * [canStand] asked of a machine that is **already standing at [from]**, for a move to [to].
 *
 * ⛔ **Its own tiles and its own ports do not count as in the way.** Nudging one tile and turning in
 * place are the two commonest things a move is, and both overlap what the machine is standing on
 * right now — so asked without the exemption this refuses the ordinary case, and a cursor drawn off
 * it would read red for the gesture the tool exists to perform.
 *
 * ⚠️ **Every other refusal is [canStand]'s, unchanged.** The reducer asks the same question again at
 * the moment it matters; a preview that reasoned separately would eventually promise a move the
 * reducer declines. Whether the machine may be *picked up* at all is a different question — see
 * [canBeMoved].
 */
fun VesselState.canStandAfterMoving(from: TileIndex, to: TileIndex, facing: Direction): Boolean {
    val moving = deck[from] ?: return false
    return to != TileIndex.NONE && to.index in 0 until deck.size && canStand(
        grid = grid,
        kind = moving.kind,
        tile = to,
        facing = facing,
        occupied = { occupancy[it] != TileIndex.NONE && occupancy[it] != from },
        portsOn = { t -> portsAt(occupancy[t]).filter { it.tile == t && it.owner != from } },
        displaceAir = { area -> air.canDisplace(grid, area) { deck.isPermeableToAir(it) } },
    )
}

/**
 * Whether the machine anchored at [from] may be picked up at all.
 *
 * ⛔ **A ghost does not move**, building itself up or coming apart: a move stands a whole machine
 * where a part-built one was, which is matter from nowhere. ⛔ **And neither does a docking port with
 * a weld on it** — it is a member of an assembly, and moving the mouth would carry a station with it
 * or tear the joint. Both are limitations Stu accepted as reasonable, 2026-09-09.
 *
 * ⚠️ **Asked at the pick-up rather than at the drop**, so the gesture never starts. A refusal the
 * player discovers on release is one they have already committed a hand movement to.
 */
fun VesselState.canBeMoved(from: TileIndex): Boolean {
    if (deck[from] == null) return false
    if (deck.isGhost(from)) return false
    if (from in scrapping) return false
    return assembly.welds.none { it.portTile == from }
}
