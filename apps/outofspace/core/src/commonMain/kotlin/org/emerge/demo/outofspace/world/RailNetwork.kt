package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.machine.Bridge
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
        if (!tracks.holdsFullBill(Conduit.Rail, tile, segment.material)) out.add(tile)
    }
    return out
}

/**
 * Ghosts on the *other* conduit layers, keyed by the tile they are fed at — which is their own.
 *
 * **Plumbing cannot carry ingots**, so a pipe ghost cannot be fed by pipes. It is fed by a rail port
 * on its own tile: the player runs temporary track over the line, lets it build, and takes the track
 * up again — the walking-rail trick applied to a different layer. That is the whole reason rail and
 * pipe stopped excluding each other; see `apps/outofspace/PLAN_self_building_rails.md`.
 *
 * ⚠️ **Deliberately not [railGhosts], and the difference is `stopsTraffic`.** Unpaid *track* is a
 * wall: material may not cross a rail the player has not paid for, which is the anti-exploit that
 * stops a run building itself out of what merely passes over it. A ghost pipe is not track — the
 * rail beneath it is finished and paid for — so traffic crosses it exactly as it crosses a ghost
 * machine. Folding the two together would either brick every pipe site or hole the exploit.
 *
 * ⚠️ **No rail on the tile, no delivery.** A pipe drawn where no track runs is a legal thing to
 * want; it simply has no way to be fed until the player gives it one, the same answer
 * [railMachineGhosts] gives a machine whose construction tile is bare.
 *
 * At most one conduit per tile, lowest ordinal first, because a tile carrying a pipe ghost *and* a
 * wire ghost is one address with two appetites and the acceptance bookkeeping is keyed by tile. The
 * loser is not starved: the winner stops being a ghost the moment it is finished, and the next pass
 * picks the other up.
 */
fun conduitGhosts(
    rails: List<Segment?>,
    layers: (Conduit) -> List<Segment?>,
    tracks: TrackLayers,
): Map<TileIndex, Conduit> {
    val out = HashMap<TileIndex, Conduit>()
    for (conduit in Conduit.entries) {
        if (conduit == Conduit.Rail) continue
        val line = layers(conduit)
        for (i in line.indices) {
            val segment = line[i] ?: continue
            if (segment.deconstructing) continue
            if (rails[i] == null) continue
            val tile = TileIndex(i)
            if (tile in out) continue
            if (!tracks.holdsFullBill(conduit, tile, segment.material)) out[tile] = conduit
        }
    }
    return out
}

/**
 * Tiles where a pipe or a wire is coming apart and has somewhere to put what it is made of.
 *
 * The mirror of [conduitGhosts] and gated the same way: a marked segment with no rail on its tile
 * has no route back onto the network, so it is not a source and simply waits. Rail is excluded for
 * the reason it always is — [railEnds] reads the rail layer's own `deconstructing` bit directly.
 */
fun conduitScrapping(rails: List<Segment?>, layers: (Conduit) -> List<Segment?>): Set<TileIndex> {
    val out = mutableSetOf<TileIndex>()
    for (conduit in Conduit.entries) {
        if (conduit == Conduit.Rail) continue
        val line = layers(conduit)
        for (i in line.indices) {
            if (line[i]?.deconstructing != true) continue
            if (rails[i] == null) continue
            out.add(TileIndex(i))
        }
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
        if (tile in scrapping) continue
        // ⛔ **Track under the tile it is FED at, which is not always its centre.** A bridge is fed
        // at one end, and its centre is the tile over the gap it spans — the one tile it is
        // guaranteed to have no track on. Asking about the centre dropped every ghost bridge out of
        // this map, so the absorb pass fell through to the ordinary port delivery and the bridge
        // pulled its own construction iron into its **buffers** instead of building itself with it.
        // Stuck at 23% for ever, holding the metal it needed. Found in Stu's save.
        val fed = constructionTileOf(grid, m)
        if (rails[fed.index] == null) continue
        if (deck.isGhost(tile)) out[fed] = m
    }
    return out
}

/**
 * Where a span sets down what it took on: the input port of every standing bridge, to its output.
 *
 * ⛔ **This is not a route a packet may travel.** A lump crosses a bridge by being lifted off the
 * track into a slot, shuffled along and put back down; nothing walks from one end to the other. What
 * these pairs carry is **appetite**, in the opposite direction — see [FlowGraph.hopTo] for why the
 * graph holds them at all, and [Whitelist.of] for what it does with them.
 *
 * ⚠️ **A ghost carries nothing.** An unbuilt span is a shell that cannot hold a gram, so its near
 * end has no far side to speak for and the tile falls back to being an ordinary construction site
 * with a bill. That is the same rule a warehouse's lock and a mill's appetite already follow, and
 * for the same reason: a machine that does not exist yet has no appetite but its own.
 *
 * ⚠️ **Only the near end needs track, and the far end is named whether it has any or not.** A span
 * with nothing to set material down on carries it nowhere, and the pair is what says so: the near
 * end inherits the appetite of a tile that is not on the network, which is no appetite at all, and
 * the span asks for nothing. Drop the pair instead and the near end falls back to being an ordinary
 * input port — "anything, for ever" — which is the very hole this exists to close, kept alive for
 * exactly the spans that lead nowhere.
 */
fun railHops(
    grid: Grid,
    rails: List<Segment?>,
    deck: DeckArray,
): Map<TileIndex, TileIndex> {
    val out = HashMap<TileIndex, TileIndex>()
    for (i in 0 until deck.size) {
        val tile = TileIndex(i)
        val m = deck[tile] ?: continue
        if (m !is Bridge || m.center != tile) continue
        if (deck.isGhost(tile)) continue
        val ports = portsOf(grid, m, tile)
        val from = ports.firstOrNull { it.kind == PortKind.Input }?.tile ?: continue
        val to = ports.firstOrNull { it.kind == PortKind.Output }?.tile ?: continue
        if (rails[from.index] == null) continue
        out[from] = to
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
    conduitGhosts: Map<TileIndex, Conduit> = emptyMap(),
    conduitScrapping: Set<TileIndex> = emptySet(),
): RailEnds {
    val sinks = ports.entries
        .filter { (tile, at) -> rails[tile.index] != null && at.any { it.kind == PortKind.Input } }
        .map { it.key }
        .toMutableSet()
    sinks.addAll(ghosts)
    sinks.addAll(conduitGhosts.keys)

    val sources = ports.entries
        .filter { (tile, at) -> rails[tile.index] != null && at.any { it.kind == PortKind.Output } }
        .map { it.key }
        .toMutableSet()
    for (i in rails.indices) if (rails[i]?.deconstructing == true) sources.add(TileIndex(i))
    // A pipe or a wire coming apart hands its copper back the only way anything does: onto the rail
    // network, at its own tile. No track there and it waits, exactly as a marked rail waits for
    // somewhere to put its iron — see [scrapDeconstructing].
    sources.addAll(conduitScrapping)
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

/**
 * Which consumers can use which standing material — the flow graph's one question about matter.
 *
 * ⛔ **Bills only, never shortfalls.** How much a site still wants is the whitelist's business and
 * changes every tick; *what it is made of* is a fact about its kind, and that is all this needs. So
 * this can be derived from the ghosts alone, which is what keeps it here — shared with anything
 * looking in from outside — rather than tangled up in the reducer's acceptance bookkeeping.
 *
 * One class per distinct set of bills, since a tile carries two appetites where a ghost machine
 * stands on ghost track. **Class 0 takes anything**, and is what every sink not listed here gets:
 * an input port is a machine, and a machine takes anything for ever.
 *
 * See [Appetites] for what the graph does with it, and why it is told this and nothing else.
 */
fun railAppetites(
    grid: Grid,
    ghosts: Set<TileIndex>,
    machineGhosts: Map<TileIndex, DeckMachine>,
    conduitGhosts: Map<TileIndex, Conduit> = emptyMap(),
    /**
     * What a construction site is to be built from, asked per tile.
     *
     * A lambda for the reason [lumpAt] is one: this function is given the *shape* of the problem and
     * nothing about where the world keeps things.
     *
     * ⛔ **No default, and it must not get one back.** It had one — the conduit's material — and a
     * caller that forgot to ask silently grouped every site by a bill it had not chosen. There are
     * two callers, the reducer and [railFlow], and the entire point of there being one function is
     * that those two agree; a default is what let them stop agreeing without anybody noticing.
     * `Conduits::materialAt` is the answer wherever a settled world is to hand.
     */
    materialAt: (Conduit, TileIndex) -> Species,
    /**
     * The same question for a machine site, which keeps its choice on the deck rather than a
     * segment. `DeckArray::materialOf` answers it; no default, for the reason above.
     */
    machineMaterialAt: (DeckMachine) -> Species,
    lumpAt: (TileIndex) -> Mixture?,
): Appetites {
    if (ghosts.isEmpty() && machineGhosts.isEmpty() && conduitGhosts.isEmpty()) return Appetites.BLIND

    val billsAt = HashMap<TileIndex, MutableList<Mixture>>()
    // ⚠️ **Per tile, because two ghosts of the same conduit can now want different metals.** The
    // grouping below is by bill *identity*, and bills are interned per (conduit, species) — so
    // sites that agree still share one instance and land in one class, while sites that differ
    // correctly do not. Hoisting one bill out of this loop, as it used to be, would have put every
    // rail ghost in the same class whatever it was being built from.
    for (tile in ghosts) {
        billsAt.getOrPut(tile) { mutableListOf() }
            .add(conduitBillOfMaterials(Conduit.Rail, materialAt(Conduit.Rail, tile)))
    }
    for ((tile, conduit) in conduitGhosts) {
        billsAt.getOrPut(tile) { mutableListOf() }
            .add(conduitBillOfMaterials(conduit, materialAt(conduit, tile)))
    }
    for ((tile, m) in machineGhosts) {
        billsAt.getOrPut(tile) { mutableListOf() }
            .add(machineBillOfMaterials(m.kind, m.tiles(grid).size, machineMaterialAt(m)))
    }

    // Class 0 is the boundless appetite and owns no bills.
    val classBills = ArrayList<List<Mixture>>()
    classBills.add(emptyList())
    val classOfTile = HashMap<TileIndex, Int>()
    for ((tile, bills) in billsAt) {
        // ⚠️ **Identity, not equality.** Bills are interned per kind and footprint, so the same site
        // kind always yields the same instance and a class is found without comparing 165 longs —
        // see [machineBillOfMaterials]. The lists are one or two long.
        var found = classBills.indexOfFirst { it.size == bills.size && it.indices.all { i -> it[i] === bills[i] } }
        if (found < 0) { classBills.add(bills); found = classBills.size - 1 }
        classOfTile[tile] = found
    }

    return object : Appetites {
        override val classes: Int get() = classBills.size
        override fun classOf(sink: TileIndex): Int = classOfTile[sink] ?: 0
        override fun admits(cls: Int, lump: TileIndex): Boolean {
            val bills = classBills[cls]
            if (bills.isEmpty()) return true
            val standing = lumpAt(lump) ?: return false
            return bills.any { buildableFrom(it, standing) }
        }
    }
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
 *
 * ⛔ **Every material is stated, exactly as the reducer states them.** This function exists so that
 * a harness looking in and the tick doing the work cannot form two opinions about the flow — which
 * is the one thing under suspicion whenever anybody calls it. Letting [railAppetites] fall back to
 * the conduit's default made it precisely the second opinion it was written to avoid: a run built of
 * anything but iron was grouped by a bill it had not chosen, so `flow` could report an edge the tick
 * would never take. Nothing here may be allowed to default again.
 */
fun VesselState.railFlow(): FlowGraph {
    val rails = conduits[Conduit.Rail]
    val ghosts = railGhosts(rails, conduits.tracks)
    val otherGhosts = conduitGhosts(rails, { conduits[it] }, conduits.tracks)
    val otherScrapping = conduitScrapping(rails) { conduits[it] }
    val ends = railEnds(
        grid, rails, portsByTile(Conduit.Rail), deck, buffers, scrapping, ghosts, otherGhosts, otherScrapping,
    )
    val machineGhosts = railMachineGhosts(grid, rails, deck, scrapping)
    return FlowGraph.build(
        railTiles(rails),
        ends.sources,
        ends.sinks,
        { tile, dir -> rails[tile.index]?.linkedTo(dir) == true },
        grid,
        { tile -> !rail.isEmpty(tile) },
        railAppetites(
            grid,
            ghosts,
            machineGhosts,
            otherGhosts,
            { conduit, tile ->
                // Only ever asked of a tile that carries a construction site, so the segment is
                // there by construction; a null here is a corrupt world, not a case.
                conduits.materialAt(conduit, tile) ?: error("no $conduit at $tile to have a material")
            },
            deck::materialOf,
        ) { rail.resourceAt(it) },
        // ⛔ Unpaid track, and nothing else: a ghost rail is a wall to the graph — see
        // [FlowGraph.build]. Ghost *machines* stand on finished track and are deliberately absent.
        walls = ghosts,
    )
}
