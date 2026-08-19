package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.DeckMachine

/**
 * Who produces and who consumes on the rail network — the two sets [FlowGraph.build] needs.
 *
 * Pulled out of the reducer so that **the sim and anything looking at the sim cannot form two
 * opinions**. A flow question is answered by which way the edges point, and until this was shared
 * there was no way to look at that from outside without writing a second derivation — which would
 * have been a second derivation of exactly the thing under suspicion. The same mistake as the two
 * `portsByTile`, and it cost a renderer bug last time.
 *
 * Everything is passed in rather than read off a [VesselState], because the reducer asks this
 * *during* a tick, when its own track and stores are already a step ahead of the state it started
 * from.
 */

/**
 * Track that is short of its bill: a length of run that is being built, and so an input.
 *
 * ⚠️ **A segment being taken apart is never a ghost**, however short of its bill it is — and it is
 * always short of it, from the first load it hands back. Without this the two halves of the feature
 * eat each other: the deconstruction pass puts a packet down on the tile, the tile reads as unbuilt,
 * and it absorbs its own metal straight back off the belt. Perfectly stable, entirely stationary,
 * and from outside it looks like deconstruction silently doing nothing at all.
 */
fun railGhosts(rails: List<Segment?>, tracks: TrackLayers): Set<TileIndex> {
    val out = mutableSetOf<TileIndex>()
    for (i in rails.indices) {
        val segment = rails[i] ?: continue
        if (segment.deconstructing) continue
        val tile = TileIndex(i)
        if (!tracks.holdsFullBill(Conduit.Rail, tile)) out.add(tile)
    }
    return out
}

/**
 * Ghost machines, keyed by **the tile they are fed at** — their centre for everything but a bridge.
 *
 * ⚠️ **A machine being taken apart is never a ghost**, for the reason [railGhosts] gives at the
 * conduit layer: without it the machine puts casing down on its own tile, reads as unbuilt, and
 * absorbs it straight back.
 */
fun railMachineGhosts(
    grid: Grid,
    rails: List<Segment?>,
    deck: DeckArray,
    scrapping: Set<TileIndex>,
): Map<TileIndex, DeckMachine> {
    val out = HashMap<TileIndex, DeckMachine>()
    for (i in 0 until deck.size) {
        val tile = TileIndex(i)
        val m = deck[tile] ?: continue
        if (m.center != tile) continue
        if (rails[i] == null) continue
        if (tile in scrapping) continue
        if (deck.isGhost(tile)) out[constructionTileOf(grid, m)] = m
    }
    return out
}

/** Where material enters the network, and where it is wanted. */
class RailEnds(val sources: Set<TileIndex>, val sinks: Set<TileIndex>)

/**
 * The producers and consumers of the rail network as of [rails].
 *
 * A **ghost** is a sink without owning a port: a length of track short of its bill *is* an input,
 * which is what makes a drawn run build itself. By the same reasoning a segment being taken apart is
 * a source — having been told to empty itself *is* being an output, and it needs no port to say so.
 *
 * A machine being taken apart is a source at every tile it hands something back at: its stores at
 * the tiles their ports stood on, and its casing at its centre. Without that the metal it puts down
 * has nowhere to be pulled to and sits on the tile for ever.
 */
fun railEnds(
    grid: Grid,
    rails: List<Segment?>,
    ports: Map<TileIndex, List<Port>>,
    deck: DeckArray,
    buffers: BufferLayer,
    scrapping: Set<TileIndex>,
    ghosts: Set<TileIndex>,
): RailEnds {
    val sinks = ports.entries
        .filter { (tile, at) -> rails[tile.index] != null && at.any { it.kind == PortKind.Input } }
        .map { it.key }
        .toMutableSet()
    sinks.addAll(ghosts)

    val sources = ports.entries
        .filter { (tile, at) -> rails[tile.index] != null && at.any { it.kind == PortKind.Output } }
        .map { it.key }
        .toMutableSet()
    for (i in rails.indices) if (rails[i]?.deconstructing == true) sources.add(TileIndex(i))
    for (centre in scrapping) {
        val m = deck[centre] ?: continue
        val out = constructionTileOf(grid, m)
        if (rails[out.index] != null) sources.add(out)
        if (rails[centre.index] != null) sources.add(centre)
        for (role in BufferRole.entries) {
            val store = bufferTile(grid, m, centre, role) ?: continue
            if (rails[store.index] != null) sources.add(store)
        }
    }
    return RailEnds(sources, sinks)
}

/** Every tile carrying track — the graph's vertex set. */
fun railTiles(rails: List<Segment?>): Set<TileIndex> =
    rails.mapIndexedNotNullTo(mutableSetOf()) { i, seg -> if (seg != null) TileIndex(i) else null }

/**
 * The whole thing, for a settled world — what the reducer builds each rail step, asked from outside.
 *
 * ⚠️ Reads the *state's* ports and track, so it answers for the tick boundary rather than for the
 * middle of a tick. That is what anything looking in from outside wants, and the reducer does not
 * use it: mid-tick it has its own live copies to pass to the pieces above.
 */
fun VesselState.railFlow(): FlowGraph {
    val rails = conduits[Conduit.Rail]
    val ghosts = railGhosts(rails, conduits.tracks)
    val ends = railEnds(grid, rails, portsByTile(Conduit.Rail), deck, buffers, scrapping, ghosts)
    return FlowGraph.build(
        railTiles(rails),
        ends.sources,
        ends.sinks,
        { tile, dir -> rails[tile.index]?.linkedTo(dir) == true },
        grid,
    )
}
