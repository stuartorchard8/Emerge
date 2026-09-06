package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.num.scaledRatio

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.fluid
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species

import org.emerge.demo.outofspace.logistics.FluidPacket
import org.emerge.demo.outofspace.logistics.Packet
import org.emerge.demo.outofspace.logistics.SolidPacket
import org.emerge.demo.outofspace.world.machine.Airlock
import org.emerge.demo.outofspace.world.machine.Bridge
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Extractor
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.Valve
import org.emerge.demo.outofspace.world.machine.Gauge
import org.emerge.demo.outofspace.world.machine.InputKey
import org.emerge.demo.outofspace.world.machine.DeckMachine
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.DirectedDeckMachine
import org.emerge.demo.outofspace.world.machine.Concentrator
import org.emerge.demo.outofspace.world.machine.Electrolyzer
import org.emerge.demo.outofspace.world.machine.Pump
import org.emerge.demo.outofspace.world.machine.Rocket
import org.emerge.demo.outofspace.world.machine.Sensor
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.machine.DockingPort
import org.emerge.demo.outofspace.world.machine.Furnace
import org.emerge.demo.outofspace.world.machine.Thruster
import org.emerge.demo.outofspace.world.machine.ThrusterControl
import org.emerge.demo.outofspace.world.machine.TileEnergy
import org.emerge.demo.outofspace.world.machine.Vent
import org.emerge.demo.outofspace.world.machine.WireButton
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import org.emerge.demo.outofspace.world.machine.SolarPanel
import org.emerge.demo.outofspace.world.PowerCharge

/** A save that could not be read, with the line that stopped it. */
class SaveError(message: String) : Exception(message)


/**
 * **What a machine of [kind] was made of in a file written before [Save.VERSION] 21.**
 *
 * ⛔ **This is a statement about old FILES, not about the game.** It is the table that used to be
 * `DeckMachineKind.material` and answer "what is a Storage normally made of" for every Storage that
 * would ever be built. Deleting that answer is the whole point of the change it belongs to — but a
 * save written under it recorded no substance for anything the player had not explicitly chosen, and
 * a *ghost holds no matter to read one back from*, so the file genuinely does not say. What the file
 * meant is a historical fact, and a historical fact has one honest home: the reader.
 *
 * ⚠️ **It is faithful, not a guess**, and that is checkable rather than hopeful: the metal the old
 * reader laid under a thing the file did not describe came from *this table*, through
 * `Conduits.finished` and `DeckArray.stand`. Anything whose matter differed from it — a chosen
 * material, contaminated track, a part-built ghost — has a `trackstuff` or `deckstuff` line of its
 * own that still overrides. So a version 20 file loads to the same world it always did.
 *
 * Not `when`-exhaustive by accident: every branch is a kind that existed at version 20, and a kind
 * added later cannot appear in a file that old.
 */
fun materialBefore(kind: DeckMachineKind): Species = when (kind) {
    // ⚠️ Unreachable and stated anyway: a panel postdates version 20, so no file this function reads
    // can contain one. Steel because that is what every other plate defaulted to.
    DeckMachineKind.SolarPanel -> Species.Steel
    DeckMachineKind.Hull, DeckMachineKind.Airlock -> Species.Steel
    DeckMachineKind.Vent, DeckMachineKind.Storage,
    DeckMachineKind.Sensor, DeckMachineKind.KeyInput, DeckMachineKind.Pump,
    DeckMachineKind.Thruster, DeckMachineKind.Concentrator,
    DeckMachineKind.Extractor,
    // ⚠️ A docking port cannot appear in a pre-v21 file — it did not exist — so this branch is
    // unreachable and is here only because the `when` is exhaustive. Titanium keeps it consistent
    // with every other installation rather than inventing an answer for a case that cannot arise.
    DeckMachineKind.DockingPort,
    // Unreachable for the docking port's reason: neither existed at version 21 either.
    DeckMachineKind.Electrolyzer,
    DeckMachineKind.Rocket,
    -> Species.Titanium
    DeckMachineKind.Furnace -> Species.Firebrick
    DeckMachineKind.Bridge, DeckMachineKind.Gauge -> materialBefore(Conduit.Rail)
    // A valve stands over track now — it marks where a run may let go of its volatiles.
    DeckMachineKind.Valve -> materialBefore(Conduit.Rail)
}

/** The same, for a length of conduit — see the overload above for what it is and is not. */
/**
 * A filter's purity state, from either the field that states it or the percentages that used to.
 *
 * `filterpure` is what a file at [Save.PURITY_STATE_VERSION] or above writes. Below it there were
 * two numeric fields and this is the fold — see that constant for why the middle of the old range
 * widens to "no opinion" rather than rounding to the nearest end.
 */
fun purityBefore(fields: Map<String, String?>): Boolean? {
    fields["filterpure"]?.let { return it.toBooleanStrictOrNull() }
    if (fields["filterpct"]?.toIntOrNull() == 100) return true
    if (fields["filterunder"]?.toIntOrNull() == 100) return false
    return null
}

fun materialBefore(conduit: Conduit): Species = when (conduit) {
    Conduit.Rail -> Species.Iron
    Conduit.Power, Conduit.Signal -> Species.Copper
}

/**
 * Save/load: the whole world as text. Format is line-oriented, greppable, diffable, and hand-editable.
 *
 * Only writes non-derivable state (ledgers, baselines, body momentum). Structure/occupancy/signals
 * are recomputed from machines each tick. Round-trip test: save/load/run must match never-saved.
 */
object Save {

    /** Bump when a field's meaning changes. An old save is migrated, or refused rather than misread. */
    /**
     * What a pipe segment called itself, in files written before the network was deleted.
     *
     * Kept as a name rather than an enum entry precisely because there is no entry any more — see
     * `PLAN_fluid_thrusters.md` §9. A reader has to recognise it to *ignore* it.
     */
    const val LEGACY_PIPE = "Pipe"

    /**
     * ⚠️ **27 adds `charge`** — what every tile of [Conduit.Power] is holding, written sparsely in
     * the same `tag tile=value` form as the heat fields. A file written before it loads with an
     * empty field, which is the *true* state of a world that has never had a solar panel: charge has
     * exactly one source and it is a machine that did not exist.
     */
    const val VERSION = 27

    /**
     * The first version whose filters say **pure / mixed / no opinion** rather than a percentage.
     *
     * Below this a filter carried an inclusive floor `filterpct` (25/50/75/90/95/100) and an
     * exclusive ceiling `filterunder`. Both are read and folded onto the three states by
     * [purityBefore]: a floor of 100 is *pure*, a ceiling of 100 is *mixed*, and anything in between
     * becomes **no opinion**.
     *
     * ⛔ **The middle widens rather than rounds**, and the direction is the whole of the decision. A
     * tank locked at "at least 75% iron" holds material a stricter filter would now refuse, so
     * rounding up to *pure* would strand a tankful behind a door that had been open when the player
     * shut the game. Widening can only admit more, and never invalidates what is already inside.
     */
    const val PURITY_STATE_VERSION = 26

    /**
     * The first version whose station shelves hold only what a station **put** there — see
     * [worked].
     *
     * Below this, every sale was absorbed onto the shelves species by species, so a shelf is not a
     * record of the station's own separation at all: it is every lump anybody ever sold, taken apart
     * for free. A file written by that build describes a station quoting a hundred and forty species
     * in sub-gram quantities, and no amount of playing it forward tidies that up, because nothing
     * takes matter *off* a shelf except a purchase.
     */
    const val WORKED_SHELVES_VERSION = 24

    /**
     * The first version that writes `weld` lines instead of the single `dock` line.
     *
     * A file below it carries at most one berth, always the vessel's, so it reads straight into a
     * one-weld [Assembly] with no migration pass — see the `dock` case in [read]. Nothing older can
     * describe an assembly the new shape could not.
     */
    const val ASSEMBLY_VERSION = 25

    /**
     * The first version that can carry a [org.emerge.demo.outofspace.world.BodyKind.STATION].
     *
     * ⚠️ **The bump is a record, not a guard.** A `station` line is a new line type, so an older
     * build handed a v22 file skips it and loads a world with no trading posts in it — a save that
     * opens successfully and is missing the thing the player was doing. Nothing refuses a
     * newer-than-known file today (the reader has no upper-version check at all), so this number is
     * what a person diagnosing that would read, and not something that prevents it.
     */
    const val STATION_VERSION = 22

    /**
     * The first version whose `position` records a **centre of mass** rather than a grid origin —
     * for the vessel and for every body. See `PLAN_com_anchored_frames.md`.
     *
     * A file below this is not wrong about where anything is, it is anchored differently: the same
     * world, described from the corner of the grid instead of from its mass. The migration is the
     * one conversion between the two, and it needs the layers, so it runs once they are read.
     */
    const val COM_ANCHOR_VERSION = 23

    /**
     * The first version whose thrusters have a bell — see [ThrusterMigration].
     *
     * A file older than this states a motor that occupied exactly the tile it was stored at. This
     * build's occupies that tile *and* the one in front of it, so an old file describes a world this
     * one cannot represent until something is done about the second tile.
     */
    private const val THRUSTER_BELL_VERSION = 18

    /**
     * The first version whose casings and conduit metal are **one species** — see [purifyFabric].
     */
    private const val PURE_FABRIC_VERSION = 20

    /**
     * From this version on, **every** built thing writes what it is made of.
     *
     * Before it, `made=` was written only where a player had chosen and its absence meant "the
     * kind's default" — and the game held a table saying what that was. That table is gone: nothing
     * is normally made of anything now, so a file below this version is a file with a hole in it,
     * and [materialBefore] is how the hole is filled. See [Segment.material].
     */
    private const val STATED_MATERIAL_VERSION = 21

    /**
     * The tick rate version 1 saves were written at, and so the number that converts their
     * `rate` field from mass per second into the mass per tick version 2 stores.
     *
     * Frozen here as a literal rather than read from the config, because it is a fact about old
     * files and must not move when the config's tick rate does. Applied in [readMachine].
     */
    const val V1_TICKS_PER_SECOND = 4L

    // ── Writing ───────────────────────────────────────────────────────────────

    fun write(state: VesselState): String {
        val out = StringBuilder()
        // The unit the numbers below are in, stated in the file rather than implied by its
        // version. See [readScale]: a save is only interpretable if you know what one integer meant
        // when it was written, and the version cannot say that once the unit is a knob.
        out.append("outofspace ").append(VERSION)
            .append(' ').append(Budget.MICROGRAMS_PER_UNIT)
            .append(' ').append(Budget.NANOJOULES_PER_UNIT).append('\n')
        out.append("grid ").append(state.grid.width).append(' ').append(state.grid.height).append('\n')
        out.append("gravity ").append(state.gravity.x.raw).append(' ').append(state.gravity.y.raw).append('\n')
        // Position absent = origin.
        out.append("position ").append(state.positionX).append(' ').append(state.positionY).append('\n')
        // Felt gravity baseline; a world reloaded without it coasts for one tick under plating alone.
        out.append("thrust ").append(state.netImpulseX).append(' ').append(state.netImpulseY).append('\n')
        out.append("tick ").append(state.tick).append('\n')
        out.append("extracted ").append(state.extractedMass).append('\n')
        out.append("vented ").append(state.ventedMass).append('\n')
        out.append("generated ").append(state.generatedEnergy).append('\n')
        out.append("radiated ").append(state.radiatedEnergy).append('\n')
        out.append("airvented ").append(state.airVentedMass).append('\n')
        out.append("baselinejoules ").append(state.baselineEnergy).append('\n')
        out.append("inserted ").append(state.insertedEnergy).append('\n')
        out.append("built ").append(state.builtMass).append('\n')
        // The solids the world started with. Absent reads as zero, which is what every world
        // written before a ship had to build its own track began with.
        out.append("baselinecargo ").append(state.baselineCargoMass).append('\n')
        // Appended rather than versioned in the usual sense: absent reads as zero, which is right
        // for every world that has never needed a write-off. What the VERSION bump buys is the
        // *migration* below — telling "no drift" apart from "written before anybody looked".
        out.append("reconciled ").append(state.reconciledMass).append('\n')
        // Absent reads as creative, which is what every world written before the switch existed was
        // — and what every world still is until a ghost can finish building itself.
        out.append("creative ").append(if (state.creative) 1 else 0).append('\n')
        // Absent reads as off, which is what every world written before the autopilot existed was.
        out.append("sas ").append(if (state.sas) 1 else 0).append('\n')
        // The bank. Not a mass, so it is written bare rather than through the mass scale — see
        // [VesselState.credits], which is deliberately outside every ledger. Absent reads as zero,
        // which is what every world written before there was anything to buy had.
        out.append("credits ").append(state.credits).append('\n')
        // ⛔ **A berth has to survive a save.** A docked world that reloaded flying free would drop
        // the player through the station they were bolted to, at whatever attitude they had. Written
        // only when docked, so an undocked world's file is unchanged. Position is in [Flight] units
        // like a body's, so it does not go through the mass scale.
        // ⛔ **A berth has to survive a save.** One line per weld, so an assembly of any size round
        // trips; an undocked world writes none and its file is unchanged.
        for (weld in state.assembly.welds) {
            out.append("weld ").append(weld.childId).append(' ').append(weld.parentId)
                .append(' ').append(weld.portTile.index).append(' ').append(weld.nodeIndex)
                .append(' ').append(weld.childX).append(' ').append(weld.childY)
                .append(' ').append(weld.childAng)
                .append('\n')
        }
        // The interlock is a fact about the ship and not about any one joint, so it is its own line
        // rather than a column on the first weld — where it lived while there could only be one.
        if (state.dockedThrustAllowed) out.append("dockthrust 1\n")
        out.append("acquired ").append(state.acquiredEnergy).append('\n')
        out.append("solidtoair ").append(state.solidToAirEnergy).append('\n')
        out.append("baselineair ").append(state.baselineAirMass).append('\n')
        // Bodies: free mass, no tracking beyond the list itself.

        // Body momentum AND position both in the world frame since v15 — see [Pose]. Shape as a
        // 0/1 run for hand-editing.
        for (b in state.bodies) {
            // ⛔ **A station is written as its own line type**, sharing the first twelve fields with a
            // body and adding three of its own. Widening `body` would have put an economy on every
            // rock in the file; a second tag keeps a rock's line exactly what it has always been, and
            // an older build refuses a v22 file outright rather than loading it minus its stations.
            out.append(if (b.kind == BodyKind.STATION) "station " else "body ")
                .append(b.width).append(' ').append(b.height)
                .append(' ').append(b.positionX).append(' ').append(b.positionY)
                .append(' ').append(b.impulseX).append(' ').append(b.impulseY)
                .append(' ').append(writeTileEnergy(b.energy))
                .append(' ').append(writeMixture(b.oreComposition!!))
                .append(' ')
            for (c in b.cells) out.append(if (c) '1' else '0')
            // Orientation last, after the shape, so that every field a v15 file had keeps the index
            // it had — a v15 save loads into this build unchanged and simply arrives unturned, which
            // is what it meant.
            out.append(' ').append(b.ang.raw).append(' ').append(b.angImpulse)
            b.station?.let { st ->
                // Fill is written only here because only a station has ever needed it back: a rock is
                // solid through, and a fragment — the other kind that carries one — is never
                // constructed. ⚠️ Both mixtures are masses and go through the scale; the ore's energy
                // is not written because a station has none. See [Station.ore].
                out.append(' ').append(b.fillPermille)
                    .append(' ').append(writeMixture(st.ore))
                    .append(' ').append(writeMixture(st.market.holdings()))
                    .append(' ').append(st.id)
                    // `x:y:facing` per berth. Written even when there are none ("-"), so the field
                    // count of a station line does not depend on its contents.
                    .append(' ').append(
                        if (st.docks.isEmpty()) "-"
                        else st.docks.joinToString(",") { "${it.cellX}:${it.cellY}:${it.facing.name}" },
                    )
            }
            out.append("   # ").append(b.filled).append(" cells, ").append(b.mass).append("g\n")
        }

        // Tiles are written as indices because that is what the world is indexed by, but an index is
        // unreadable to a person and the whole point of the format is that a person can read it. So
        // every placed thing carries its coordinates in a comment, and track spells its links out —
        // `links=5` says nothing, `R-L-` says the run goes left to right through this tile.
        for (tile in state.grid.tiles) {
            val m = state.deck[tile] ?: continue
            out.append("deckmachine ").append(tile.index).append(' ').append(writeDeckMachine(m, state.grid, state.buffers))
            // The same spelling a segment being taken apart uses, for the same fact. Absent reads as
            // "not marked", so no version bump and no migration.
            if (tile in state.scrapping) out.append(" scrapping=1")
            // Always written, never omitted: there is no default for its absence to mean. By name,
            // like every other species on disk, so the enum stays free to be reordered.
            out.append(" made=").append(state.deck.materialOf(m).name)
            out.append("   # ").append(where(state.grid, tile)).append('\n')
        }
        // One line per segment per layer, keyed `conduit` rather than `rail` since version 6 — the
        // record always named its own network, but while there was one list per tile the keyword
        // could pretend otherwise. A version 5 file writes `rail 42 PIPE ...` and means it.
        state.conduits.all { _, tile, r ->
            out.append("conduit ").append(tile.index).append(' ').append(writeSegment(r, tile, state.rail, state.conduits))
            out.append("   # ").append(where(state.grid, tile)).append(' ').append(linkLetters(r)).append('\n')
        }
        for (tile in state.grid.tiles) {
            val cursor = state.diverters.forkCursors[tile] ?: 0
            if (cursor != 0) out.append("diverter ").append(tile.index).append(' ').append(cursor).append('\n')
        }
        // Which feeder a merge takes from next. A separate line from `diverter` because a tile can
        // be both, and an older save simply has none of these.
        for (tile in state.grid.tiles) {
            val cursor = state.diverters.mergeCursors[tile] ?: 0
            if (cursor != 0) out.append("merge ").append(tile.index).append(' ').append(cursor).append('\n')
        }

        // Solid heat lives on machines/segments (their `k=` field), not a separate per-tile block.
        // Air per tile: mixture is wordy but readable/editable.
        for (tile in state.grid.tiles) {
            val mix = state.air.mixtureAt(tile)
            if (mix.isEmpty) continue
            out.append("air ").append(tile.index).append(' ').append(writeMixture(mix)).append('\n')
        }

        // Packed sparsely like heat. Version 3 and earlier stored per-tile heat; absent loads ambient.
        writeSparse(out, "airheat", state.air.copyEnergy().data)
        // ⚠️ Unscaled, unlike the heat fields beside it: charge is its own quantity and does not
        // ride the mass unit, so a rescale must not touch it.
        writeSparse(out, "charge", state.charge.toLongArray())
        writeDeckHeat(out, state.deck)
        writeDeckStuff(out, state.deck)
        writeTrackStuff(out, state.conduits)
        out.append("airventedheat ").append(state.airVentedEnergy).append('\n')
        // The debug bellows' admission. Appended rather than versioned, like the impulse line:
        // absent reads as zero, which is exactly what a world that never cheated has.
        out.append("airinjected ").append(state.injectedAirMass)
            .append(' ').append(state.injectedAirEnergy).append('\n')
        out.append("baselineairheat ").append(state.baselineAirEnergy).append('\n')

        // Packed like heat. Momentum saved because reloading without it resumes becalmed.
        // Pipes: same format, empty network = zero cost.


        // Twelve impulse values (ledger grew). Appended, not versioned: absent reads as zero.
        //
        // ⚠️ **Slots 5-6 and 11-12 are written as zero and read into nothing.** They held
        // `undeliveredImpulse` and `frameTurnImpulse`, both retired with the per-edge gas momentum
        // they existed to account for. The line is **positional**, so the slots are held rather
        // than closed up — shifting them would make every older save read its body impulse as its
        // debug impulse, silently and in the player's favour.
        out.append("impulse ").append(state.vesselImpulseX).append(' ').append(state.vesselImpulseY)
            .append(' ').append(state.exhaustMomentumX).append(' ').append(state.exhaustMomentumY)
            .append(" 0 0")
            .append(' ').append(state.debugImpulseX).append(' ').append(state.debugImpulseY)
            .append(' ').append(state.bodyImpulseX).append(' ').append(state.bodyImpulseY)
            .append(" 0 0")
            .append('\n')
        // Rotation. A new keyword rather than more fields on `thrust`, because `thrust` means the
        // linear pair and a reader that had to count tokens to find out otherwise is a reader that
        // will eventually miscount. Appended, not versioned: absent reads as zero, which is a ship
        // pointing the way the grid is drawn and not turning — exactly what every save before this
        // was. Only the angular momentum carries the mass unit, so only it is rescaled on the way in.
        out.append("rotation ").append(state.ang.raw)
            .append(' ').append(state.angImpulse).append(' ').append(state.netTorque)
            .append('\n')
        // The angular ledger's two stores, on their own line and appended for the same reason
        // `rotation` was: absent reads as zero, which is a ship that has thrown nothing and hit
        // nothing. Both carry the mass unit, so both are rescaled on the way in.
        out.append("angularstores ").append(state.exhaustAngImpulse)
            .append(' ').append(state.bodyAngImpulse)
            .append(' ').append(state.ventAngImpulse)
            .append('\n')
        // What the atmosphere carried overboard, in the world. Appended like the rest: absent reads
        // as zero, which is a ship that has never been holed.
        out.append("ventmomentum ").append(state.ventMomentumX)
            .append(' ').append(state.ventMomentumY)
            .append('\n')
        // The atmosphere's own momentum, in the world, and its angular half. Appended: absent reads
        // as zero, which is air that has never been dragged anywhere.
        out.append("airmomentum ").append(state.airMomentumX)
            .append(' ').append(state.airMomentumY)
            .append(' ').append(state.airAngImpulse)
            .append('\n')
        return out.toString()
    }

    private const val HEAT_PER_LINE = 12

    /** Index=value pairs, several to a line, skipping zeros. The idiom the heat field already uses. */
    /**
     * The deck's energy, in the same `deckheat tile=value` lines [writeSparse] emits — the layer is
     * row-allocated now, so there is no tile-indexed array to hand it, but the **format is
     * unchanged** and old saves load without a version bump.
     *
     * Tiles are emitted in ascending order rather than in row order. Row order is an artefact of the
     * order things were built and demolished in, and letting it reach the file would make two
     * identical worlds save as different text.
     */
    private fun writeDeckHeat(out: StringBuilder, deck: DeckArray) {
        val tiles = ArrayList<Int>()
        val readings = HashMap<Int, Long>()
        // Walked by **machine**, not by occupied row, because the tile this has to be able to talk
        // about is the one with no row at all: a bare construction site holds nothing, and "nothing"
        // is precisely the reading the loader will not arrive at on its own.
        for (anchor in deck.grid.tiles) {
            val m = deck[anchor] ?: continue
            val laid = laidDeckEnergy(deck, m)
            for (part in m.tiles(deck.grid)) {
                val energy = deck.stuff.energyAt(part)
                if (energy == laid) continue
                tiles.add(part.index)
                readings[part.index] = energy
            }
        }
        tiles.sort()
        var onLine = 0
        for (tile in tiles) {
            if (onLine == 0) out.append("deckheat")
            out.append(' ').append(tile).append('=').append(readings[tile])
            if (++onLine == HEAT_PER_LINE) { out.append('\n'); onLine = 0 }
        }
        if (onLine != 0) out.append('\n')
    }

    /**
     * **What [readDeckMachine]'s `stand` will put on one tile of [m] if this file says nothing.**
     *
     * ⛔ The bill's capacity, and **not** `deck.stuff.heatCapacityAt(part)`. The two agree for every
     * finished tile — its matter *is* its bill — and disagree for the one case that matters: a ghost
     * holds nothing, so the tile-derived figure is `0 × 293 = 0`, which reads as "already ambient,
     * omit the line", while the loader stands the machine with its whole casing and seeds a whole
     * tile's worth of heat. Omitting against one quantity and reconstructing from another is the
     * entire bug: a construction site came back holding 12.9 GJ of nothing, and melted the instant
     * the first iron gave that heat a gram to divide by.
     *
     * The rule this states, and the one [writeSegment] states again for track: **a writer may omit a
     * reading only when it equals what the reader will reconstruct.** Never when it merely looks
     * default.
     */
    private fun laidDeckEnergy(deck: DeckArray, m: DeckMachine): Long =
        energyAtKelvin(thermalMassOf(tileBillOfMaterials(m.kind, deck.materialOf(m))), Temperature.AMBIENT_KELVIN)

    /**
     * The deck's **matter**, one `deckstuff tile <mixture>` line per tile whose casing is no longer
     * what it was built as.
     *
     * ### Why only the tiles that differ
     *
     * A casing starts as [tileBillOfMaterials] of its kind, and until something reacts with it that
     * is *all* it ever is — a hull tile is a hull tile. Writing the composition of every deck tile
     * would put thousands of identical lines in the file, one per plate, and none of them would say
     * anything the machine record does not already say. So absence means "made of what its kind is
     * made of", which is exactly what every file written before this line existed meant, and no
     * version bump is needed to read one.
     *
     * The same reasoning as the conduit `k=` field, and the same trap: the comparison must be
     * against **this tile's** bill, not a per-kind constant, because [Mixture.scaledTo] apportions
     * and a multi-species material lands a unit or two off the round figure the table states.
     */
    private fun writeDeckStuff(out: StringBuilder, deck: DeckArray) {
        for (tile in deck.grid.tiles) {
            val m = deck[tile] ?: continue
            val bill = tileBillOfMaterials(m.kind, deck.materialOf(m))
            for (part in m.tiles(deck.grid)) {
                if (Species.ALL.all { deck.stuff[part, it] == bill[it] }) continue
                out.append("deckstuff ").append(part.index).append(' ')
                    .append(writeMixture(deck.stuff.mixtureAt(part))).append('\n')
            }
        }
    }

    /**
     * What each length of conduit is **made of**, one `trackstuff <conduit> <tile> <mixture>` line
     * per tile whose metal is no longer its kind's bill of materials.
     *
     * The twin of [writeDeckStuff], written for the same reason and read with the same rule: absence
     * means "made of what its kind is made of", which is what every file written before this line
     * existed meant, so no version bump is needed to read one.
     *
     * ⚠️ It stops being an optimisation and becomes **required** the moment a segment can hold less
     * than its bill. A ghost is exactly that — track with a representation and no mass — and before
     * this line a ghost saved as a finished rail, because the loader re-derived every segment's
     * metal from its kind. See `apps/outofspace/PLAN_self_building_rails.md`.
     */
    private fun writeTrackStuff(out: StringBuilder, conduits: Conduits) {
        for (conduit in Conduit.entries) {
            val stuff = conduits.tracks[conduit]
            for (tile in 0 until conduits.tileCount) {
                val t = TileIndex(tile)
                val segment = conduits.at(conduit, t) ?: continue
                // ⚠️ **Against the segment's own material**, which is what the reader lays when this
                // line is absent — see [Conduits.finished]. Weighed against the conduit's default
                // instead, a whole run built of something else would be written out tile by tile and
                // — worse — a file that omitted the line would come back made of the wrong metal.
                val bill = conduitBillOfMaterials(conduit, segment.material)
                if (Species.ALL.all { stuff[t, it] == bill[it] }) continue
                out.append("trackstuff ").append(conduit.name).append(' ').append(tile).append(' ')
                    .append(writeMixture(stuff.mixtureAt(t))).append('\n')
            }
        }
    }

    private fun writeSparse(out: StringBuilder, tag: String, values: LongArray) {
        var onLine = 0
        for (i in values.indices) {
            if (values[i] == 0L) continue
            if (onLine == 0) out.append(tag)
            out.append(' ').append(i).append('=').append(values[i])
            if (++onLine == HEAT_PER_LINE) { out.append('\n'); onLine = 0 }
        }
        if (onLine != 0) out.append('\n')
    }

    private fun where(grid: Grid, tile: TileIndex): String = "(${grid.xOf(tile)}, ${grid.yOf(tile)})"

    /** A segment's links as `RDLU`, with a dash for each direction it is not joined to. */
    private fun linkLetters(s: Segment): String =
        Direction.ALL.joinToString("") { if (s.linkedTo(it)) it.name.take(1) else "-" }

    /**
     * The field name a machine's [role] store has always had in the file.
     *
     * The roles were named after the fact and the keys were not, so two machines spell them their
     * own way: an extractor's jaws are `in` and its ground ore is `buffer`, and a storage's one
     * pooled store is `stored`. Kept exactly as they were, so the storage migration does not
     * invalidate a single save.
     */
    private fun storeKey(m: DeckMachine, role: BufferRole): String = when {
        m is Storage -> "stored"
        // An extractor's one store has always been `buffer`, and it stays `buffer` now that its
        // jaws are gone.
        m is Extractor -> "buffer"
        // A bridge's middle slot has always been `span`, and it stays `span`: the three slots became
        // three role tiles without the file needing to know.
        m is Bridge && role == BufferRole.Inside -> "span"
        role == BufferRole.Input -> "in"
        role == BufferRole.Inside -> "inside"
        role == BufferRole.Product -> "out"
        role == BufferRole.Waste -> "waste"
        // The one key that was named after its role rather than before it, because it arrived after
        // them — see [BufferRole.Oxidiser].
        else -> "oxid"
    }

    private fun writeDeckMachine(m: DeckMachine, grid: Grid, buffers: BufferLayer): String {
        val f = StringBuilder(m.kind.name)
        fun put(key: String, value: String?) {
            if (value != null) f.append(' ').append(key).append('=').append(value)
        }
        if (m is DirectedDeckMachine) put("facing", m.facing.name)
        when (m) {
            // An airlock is its wiring, and the common code around this writes that. A bridge is
            // its facing and its three slots, and both of those are written by the common code too.
            // A valve is its position and nothing else. An airlock is its wiring, and a bridge its
            // facing and its three slots — all of those are written by the common code around this.
            // A panel is where it stands and what it is wired to, and the common code writes both.
            is Hull, is Airlock, is Bridge, is Valve, is SolarPanel -> {}
            // A gauge's reading persists after the packet has gone, so it is state, not decoration.
            // The field names are the ones the `conduit` record used while a gauge was a segment.
            is Gauge -> {
                m.lastDominant?.let { put("lastspecies", it.name) }
                if (m.lastPurity != 0) put("lastpurity", m.lastPurity.toString())
                if (m.lastMass != 0L) put("lastmass", m.lastMass.toString())
            }
            is Vent -> put("vented", m.ventedMass.toString())
            // A warehouse holds nothing itself: its contents are a tile of the buffer layer, and
            // the store loop below writes them under the key the record has always used.
            is Concentrator -> {
                put("carry", m.carry.toString())
                put("progress", m.progress.toString())
                put("eff", m.efficiencyPermille.toString())
            }
            // A thermostat and nothing else: the setpoint is its whole state. An older file's
            // `carry` and `progress` are simply not read — they belonged to a tick counter that no
            // longer decides anything, the same disposal the `Extractor` note below describes.
            is Furnace -> {
                put("temp", m.setTemperature.toString())
                // Both halves of the dwell, because a charge part-way through its residence time is
                // a real state: dropping `held` would silently restart every hold on every load.
                put("dwell", m.dwellTicks.toString())
                put("held", m.heldTicks.toString())
            }
            // An extractor is its facing and its one store, both written by the common code around
            // this. Its `carry` and `rate` went with the second store: the rail sets the throughput.
            is Extractor -> {}
            // A lock is the player's decision and so is state. Two fields rather than one, and
            // written only when locked: an unlocked warehouse is the overwhelming majority and adds
            // nothing to the line, and an older file with neither field loads unlocked, which is
            // exactly what it was.
            is Storage -> {
                m.filter?.let {
                    put("filter", it.species?.name)
                    // ⚠️ Written only when the filter has an opinion, so a species-only lock adds
                    // nothing to the line — the same economy the percentage keys had.
                    it.pure?.let { pure -> put("filterpure", pure.toString()) }
                }
                val autoLock = if (m.autoLock)     0b01 else 0
                val autoUnlock = if (m.autoUnlock) 0b10 else 0
                put("auto", (autoLock+autoUnlock).toString())
            }
            is DockingPort -> {
                // ⚠️ **One signed field, because the port holds one signed number per species.**
                // `SPECIES:mass`, negative selling and positive buying; an unbounded permission is
                // written `+` or `-` with no number, so it cannot be rescaled into a merely very
                // large one. The ore figure rides the same list under `ORE`.
                val book = m.orders.entries.map { it.key.name to it.value } +
                    (if (m.ore != 0L) listOf("ORE" to m.ore) else emptyList())
                if (book.isNotEmpty()) put("orders", book.joinToString(",") { (name, value) ->
                    name + ":" + when (value) {
                        DockingPort.ENDLESS -> "+"
                        -DockingPort.ENDLESS -> "-"
                        else -> value.toString()
                    }
                })
            }
            is Sensor -> {
                put("threshold", m.threshold.toString())
                put("delay", m.delay.toString())
                put("delayedFor", m.delayedFor.toString())
                put("release", m.release.toString())
                put("releasedFor", m.releasedFor.toString())
            }
            // A button is its key and its wiring; the common code writes the second.
            is WireButton -> put("key", m.key.name)
            // A pump holds nothing: what it moves is in the two air fields. Facing and wiring are
            // written by the common code around this.
            is Pump -> {}
            // Neither does an electrolyzer: what it has made is in its two output stores.
            is Electrolyzer -> {}
            // The exhaust path is derived from the hull every tick and so is not state — see
            // [exhaustPath]; the propellant is a store, written by the role loop below.
            is Thruster -> {
                put("carry", m.carry.toString())
                put("rate", m.massPerTick.toString())
                // Omitted when unlocked, like the wiring: the file shows the choices somebody made.
                // The same three keys a locked warehouse writes, and read back the same way — a
                // filter is a filter whichever machine is wearing it.
                m.filter?.let {
                    put("filter", it.species?.name)
                    it.pure?.let { pure -> put("filterpure", pure.toString()) }
                }
                // Omitted at the default, like the wiring below it: the file shows the choices
                // somebody actually made. ⚠️ A file written before the flight controls existed has
                // no key here and so loads FLIGHT, which is the intended migration — see
                // [ThrusterControl].
                if (m.control != ThrusterControl.Flight) put("control", m.control.name)
                // A readout rather than a setting, and saved for the reason a gauge's last reading
                // is: a loaded world should show what it was doing, not a panel full of zeroes.
                if (m.firing != 0) put("firing", m.firing.toString())
            }
            // ⚠️ **No rate**, unlike the thruster above: a rocket's is a constant on the companion
            // rather than a field, so writing one would pin a balance decision into every save and
            // make the constant unmovable. The two dials are the state, and the three stores are
            // written by the role loop below.
            is Rocket -> {
                put("carry", m.carry.toString())
                // Omitted at the defaults, like the wiring: the file shows the choices somebody made.
                if (m.fuelPermille != Rocket.DEFAULT_FUEL_PERMILLE) put("mix", m.fuelPermille.toString())
                if (m.setTemperature != Rocket.DEFAULT_SETPOINT) put("temp", m.setTemperature.toString())
                if (m.control != ThrusterControl.Flight) put("control", m.control.name)
                if (m.firing != 0) put("firing", m.firing.toString())
            }
        }
        // The same store loop the machine record has, and it is not optional: a deck machine's
        // buffers are on the same layer under the same keys, and leaving it out wrote a warehouse
        // back empty while the record itself looked perfectly well-formed.
        for (role in bufferRolesOf(m)) {
            val store = bufferTile(grid, m, m.center, role) ?: continue
            put(storeKey(m, role), buffers.resourceAt(store)?.let { writeResource(it) })
        }
        // Omitted when a machine is wired the way a freshly placed one is, which is almost all of
        // them — the file should show the wiring somebody actually did.
        if (m.wiring != Wiring.RUNNING) put("wire", writeWiring(m.wiring))
        return f.toString()
    }

    /**
     * A machine's per-tile heat, comma-separated in footprint order. Version 12 and later.
     *
     * One field rather than one per tile because a machine's tiles are not addressable from the
     * file: the footprint is derived from the kind and the centre, so the *order* is the address.
     */
    private fun writeTileEnergy(j: TileEnergy): String =
        (0 until j.size).joinToString(",") { j[it].toString() }

    /**
     * Reads that back, and migrates a file that predates it.
     *
     * ⚠️ Up to version 11 the `k=` field was a single number: everything the machine held, because a
     * machine had one temperature. Spreading it evenly is the only faithful reading — an isothermal
     * machine *is* one whose tiles are all at the same temperature, so the old state is not being
     * approximated, it is being written out in full. What the file cannot tell us is a gradient it
     * was never able to express, and a saved smelter will now develop one as it runs.
     *
     * The remainder is not dropped: [TileMixture.plusSpread] hands it to the first tiles, so a
     * reloaded machine holds exactly what it held before, to the joule. Anything else would make
     * save/load a slow leak, and the energy ledger would eventually notice.
     */
    /**
     * A free body's per-cell energy, or a pre-v14 single figure spread across [cells].
     *
     * Spread rather than given to one cell, because that is what the single figure meant: a body has
     * never conducted with anything, including itself, so its energy was always uniform and the only
     * question is how many numbers it took to say so.
     */
    private fun bodyEnergy(
        field: String,
        cells: Int,
        scale: Rescale,
        fail: (String) -> Nothing,
    ): TileEnergy {
        if (',' !in field) {
            val total = scale.of(field.toLongOrNull() ?: fail("bad body energy '$field'"))
            return TileEnergy.uniform(cells, 0L).plusEnergySpread(total)
        }
        val parts = field.split(',')
        if (parts.size != cells) fail("a $cells-cell body has ${parts.size} per-cell energy")
        return TileEnergy.of(LongArray(cells) { scale.of(parts[it].toLongOrNull() ?: fail("bad body energy '$field'")) })
    }

    private fun writeSegment(s: Segment, tile: TileIndex, rail: RailLayer, conduits: Conduits): String {
        val f = StringBuilder(s.conduit.name)
        f.append(" links=").append(s.links)
        // The load moved to [RailLayer]; the record keeps the field name it always had, so a file
        // written before that change still loads its belts full.
        if (s.conduit == Conduit.Rail) rail.packetAt(tile)?.let { f.append(" held=").append(writePacket(it)) }
        // Read off the layer, which is where a segment's heat lives now. The record is unchanged:
        // still `k=`, still omitted when the tile is at the temperature a freshly laid one would be,
        // so a file written before the migration and one written after are the same file.
        // Absent reads as "not marked", which is what every file written before deconstruction
        // existed meant, so no version bump.
        if (s.deconstructing) f.append(" scrapping=1")
        // Always written, never omitted: a length of track with no substance is not a thing the
        // game can represent, so there is no default for an absent field to mean. By name, like
        // every other species on disk, so the enum stays free to be reordered.
        f.append(" made=").append(s.material.name)
        val energy = conduits.energyAt(s.conduit, tile)
        // Compared against what [TrackLayers.lay] will reconstruct — the **bill's** capacity at
        // ambient, not this tile's current capacity. Against the kind's round figure every tile
        // would look non-ambient and be written out, because the bill apportions and lands a part
        // per million off; against the tile's *own* matter a bare site compares `0` with `0 × 293`
        // and omits the line, after which the loader lays a full length of metal and seeds a full
        // length's heat onto a site holding nothing. See [laidDeckEnergy] — same rule, same trap,
        // and this is the half that looked correct because it is right for every finished tile.
        if (energy != energyAtKelvin(thermalMassOf(conduitBillOfMaterials(s.conduit, s.material)), Temperature.AMBIENT_KELVIN)) {
            f.append(" k=").append(energy)
        }
        return f.toString()
    }

    private fun writeWiring(w: Wiring): String =
        Action.entries.joinToString(";") { action ->
            action.name + ":" + w.triggers(action).joinToString(",") { (if (it.negated) "!" else "") + it.source.name }
        }

    /**
     * A mixture's species and masses, and — only where asked for — the heat in it.
     *
     * ⚠️ [withEnergy] is **not** a default, and must not become one. Most mixtures in the file have
     * their energy written on a line of their own: the air's is in `airheat`, a casing's in
     * `deckheat`, a length of conduit's in its `k=` field. Emitting it inline as well would have the
     * reader add the same joules twice.
     *
     * A [Mixture] is the case with nowhere else to put it, which is why it is the one caller — see
     * [writeResource].
     */
    private fun writeMixture(m: Mixture, withEnergy: Boolean = false): String {
        if (m.isEmpty) return "-"
        val species = Species.ALL.filter { m[it] > 0L }.joinToString(",") { "${it.name}=${m[it]}" }
        if (!withEnergy || m.energy <= 0L) return species
        return "$species,energy=${m.energy}"
    }

    /**
     * A resource, **with its heat**.
     *
     * The reader has always understood `energy=`; the writer never emitted it, so every gram of ore
     * in a tank or riding on a belt arrived back from a save at whatever temperature the reader
     * defaulted to. Invisible while everything a fixture stored happened to be at ambient, and it
     * surfaced the moment the starting vessel was given a stock of iron that had a temperature.
     */
    private fun writeResource(r: Mixture): String = writeMixture(r, withEnergy = true)

    private fun writePacket(p: Packet): String = when (p) {
        is SolidPacket -> "S:" + writeResource(p.contents)
        is FluidPacket -> "F:" + writeMixture(p.contents)
    }

    // ── Reading ───────────────────────────────────────────────────────────────

    /**
     * What a mass-, energy- or momentum-dimensioned number in the file must be multiplied by.
     *
     * ### Why the file states its units instead of the version implying them
     *
     * A save is a pile of bare integers, and an integer only means something once you know what one
     * of them was worth when it was written. Keying that to the version number would work exactly
     * once: both units are knobs, and the moment either moves twice there is no way to recover which
     * of two units a version-14 file was written in. Stating them in the file makes every save
     * self-describing and makes any future rescale free of save work entirely.
     *
     * ### Why there are now two factors and not one
     *
     * Version 13 needed only one, because `Budget.MILLIJOULE == Budget.GRAM` held the energy unit
     * equal to the mass unit and one number rescaled the lot. **That lock is gone** — it is what
     * stopped the rescale at step 8, since a millionfold finer gram made a millionfold finer joule
     * and a rock's energy stopped fitting a `Long`. Its own KDoc named this function as the first
     * thing that would have to grow a second factor, and here it is.
     *
     * Momentum stays on the mass factor: it is `gram·tiles/tick`, a mass times a dimensionless
     * velocity.
     *
     * A missing unit means the pre-knob default — one gram and one millijoule per integer, which is
     * what every file before version 13 (mass) and 14 (energy) was written in. Both have only ever
     * gone in the widening direction, so the factor is always a whole number.
     */
    private fun readScale(stated: String?, line: Int, what: String, mine: Long): Rescale {
        val fileUnit = if (stated == null) 1_000_000L else stated.toLongOrNull()
            ?: throw SaveError("line $line: unreadable $what unit '$stated'")
        if (fileUnit <= 0L) throw SaveError("line $line: $what unit must be positive, got $fileUnit")
        return Rescale(from = fileUnit, to = mine)
    }

    /**
     * Carries a file's unit into this build's, in whichever direction that turns out to be.
     *
     * ⚠️ It used to be a plain multiplier with a `require` that the file's unit divided evenly by
     * this build's, on the stated grounds that a unit "only ever goes down". That was true of mass
     * and **is not true in general** — v14 makes the energy unit ten times *coarser*, and the guard
     * promptly refused every save the game had ever written, including its own from the line before.
     * A rule derived from one dimension's history, applied to a dimension that had none.
     *
     * Both directions are legitimate and they are not symmetrical. Widening (a coarser file into a
     * finer build) is exact. Narrowing (a finer file into a coarser build) rounds, and that rounding
     * is fine precisely because what it discards is **below what this build can represent at all** —
     * it is the same loss any store into a coarser field takes, not the silent halving of somebody's
     * cargo the old guard was written to prevent.
     *
     * Through [scaledRatio] rather than a bare `v * from / to`, because `from` is a whole unit
     * conversion — up to a million — and the values are the largest quantities in the game.
     */
    private class Rescale(private val from: Long, private val to: Long) {
        fun of(value: Long): Long = if (from == to) value else scaledRatio(value, to, from)

        companion object {
            /** For a field that is a *proportion* and has no unit to carry — a rock's composition. */
            val NONE = Rescale(1L, 1L)
        }
    }

    fun read(text: String): VesselState {
        val lines = text.lineSequence().withIndex()
            .map { (n, raw) -> n + 1 to raw.substringBefore('#').trim() }
            .filter { it.second.isNotEmpty() }
            .toList()
        if (lines.isEmpty()) throw SaveError("empty save")

        var at = 0
        fun next(): Pair<Int, List<String>> {
            val (n, line) = lines[at++]
            return n to line.split(' ').filter { it.isNotEmpty() }
        }

        val (headerLine, header) = next()
        if (header.size !in 2..4 || header[0] != "outofspace") {
            throw SaveError("line $headerLine: not an Out of Space save")
        }
        val version = header[1].toIntOrNull()
            ?: throw SaveError("line $headerLine: unreadable version '${header[1]}'")
        if (version !in 1..VERSION) {
            throw SaveError("save is version $version, this build reads version 1..$VERSION")
        }
        val scale = readScale(header.getOrNull(2), headerLine, "mass", Budget.MICROGRAMS_PER_UNIT)
        val energyScale =
            readScale(header.getOrNull(3), headerLine, "energy", Budget.NANOJOULES_PER_UNIT)

        val (gridLine, gridTokens) = next()
        if (gridTokens.size != 3 || gridTokens[0] != "grid") throw SaveError("line $gridLine: expected a grid")
        val grid = Grid(
            gridTokens[1].toIntOrNull() ?: throw SaveError("line $gridLine: bad width"),
            gridTokens[2].toIntOrNull() ?: throw SaveError("line $gridLine: bad height"),
        )
        if (grid.size <= 0) throw SaveError("line $gridLine: grid has no tiles")

        val deck = DeckArray(grid)
        val buffers = BufferLayer.empty(grid.size)
        val rail = RailLayer.empty(grid.size)
        val layers = Array(Conduit.entries.size) { arrayOfNulls<Segment>(grid.size) }
        /** `k=` readings held aside by (conduit ordinal, tile index) — see where they are applied. */
        var creative = false
        var sas = false
        var credits = 0L
        var welds = ArrayList<Weld>()
        var dockedThrust = false
        val scrapping = mutableSetOf<TileIndex>()
        var built = 0L
        var reconciled: Long? = null
        var baselineCargo: Long? = null
        val segmentEnergy = HashMap<Pair<Int, Int>, Long>()
        // Held aside for the same reason [segmentEnergy] is: the layers do not exist until every
        // segment has been read, and this line *replaces* the metal [Conduits.of] lays.
        val trackStuff = HashMap<Pair<Int, Int>, Mixture>()
        // Thrusters out of a file older than [THRUSTER_BELL_VERSION], and the lines that describe
        // them, held aside until every other machine is standing — see [standMigratedThrusters].
        // Propellant out of a file written when a motor had a store — see [readDeckMachine]. It has
        // left the world, so the cargo baseline is re-anchored by it exactly as a dropped motor's is.
        var droppedPropellant = 0L
        val legacyThrusters = LinkedHashMap<TileIndex, Thruster>()
        val legacyThrusterCasing = HashMap<TileIndex, Mixture>()
        val legacyThrusterEnergy = HashMap<TileIndex, Long>()
        val legacyThrusterScrapping = mutableSetOf<TileIndex>()
        val diverters = HashMap<TileIndex, Int>()
        val merges = HashMap<TileIndex, Int>()
        val airMass = MassArray(grid.size)
        val airEnergy = EnergyArray(grid.size)
        /** Zero until a `charge` line says otherwise — see [VERSION] on why that is the true default. */
        val charge = LongArray(grid.size)
        val edges = EdgeGrid(grid)
        val pipeMass = MassArray(grid.size)
        val pipeEnergy = EnergyArray(grid.size)
        var impulseX = 0L
        var impulseY = 0L
        var exhaustX = 0L
        var exhaustY = 0L
        var debugX = 0L
        var debugY = 0L
        var bodyImpulseX = 0L
        var bodyImpulseY = 0L
        val bodies = ArrayList<RigidBody>()

        // Absent = freefall. Older saves store one-g explicitly.
        var gravity = VesselState.FREEFALL
        var positionX = 0L
        var positionY = 0L
        var ang = Coord(0)
        var angImpulse = 0L
        var netTorque = 0L
        var exhaustAngImpulse = 0L
        var bodyAngImpulse = 0L
        var ventAngImpulse = 0L
        var ventMomentumX = 0L
        var ventMomentumY = 0L
        var airMomentumX = 0L
        var airMomentumY = 0L
        var airAngImpulse = 0L
        var netImpulseX = 0L
        var netImpulseY = 0L
        var tick = 0L
        var extracted = 0L
        var vented = 0L
        var generated = 0L
        var radiated = 0L
        var airVented = 0L
        var airVentedEnergy = 0L
        var injectedAirMass = 0L
        var injectedAirEnergy = 0L
        var baselineAirEnergy: Long? = null
        var baselineEnergy: Long? = null
        var inserted = 0L
        var acquired = 0L
        var solidToAir = 0L
        var baselineAir: Long? = null

        while (at < lines.size) {
            val (n, tokens) = next()
            fun fail(why: String): Nothing = throw SaveError("line $n: $why")
            fun long(i: Int): Long = tokens.getOrNull(i)?.toLongOrNull() ?: fail("expected a number")
            // Every mass-, energy- or momentum-dimensioned field reads through this one and every
            // dimensionless field reads through `long`. Which of the two a line uses IS the
            // statement of what that field means — see [readScale].
            fun scaled(i: Int): Long = scale.of(long(i))

            /**
             * The energy-dimensioned twin. Named separately at every site rather than defaulted,
             * because which dimension a ledger is in is exactly the thing that is easy to get wrong
             * and impossible to see in a diff — the field names are the only evidence, so the call
             * has to name it too.
             */
            fun energy(i: Int): Long = energyScale.of(long(i))
            fun tile(i: Int): TileIndex {
                val t = tokens.getOrNull(i)?.toIntOrNull() ?: fail("expected a tile index")
                if (t !in 0 until grid.size) fail("tile $t is outside a ${grid.width}x${grid.height} grid")
                return TileIndex(t)
            }

            when (tokens[0]) {
                "gravity" -> gravity = Frac2(Frac(long(1)), Frac(long(2)))
                "position" -> { positionX = long(1); positionY = long(2) }
                "thrust" -> { netImpulseX = scaled(1); netImpulseY = scaled(2) }
                "rotation" -> {
                    ang = Coord(tokens.getOrNull(1)?.toIntOrNull() ?: fail("expected an angle"))
                    angImpulse = scaled(2); netTorque = scaled(3)
                }
                "angularstores" -> {
                    exhaustAngImpulse = scaled(1); bodyAngImpulse = scaled(2)
                    if (tokens.size > 3) ventAngImpulse = scaled(3)
                }
                "ventmomentum" -> { ventMomentumX = scaled(1); ventMomentumY = scaled(2) }
                "airmomentum" -> {
                    airMomentumX = scaled(1); airMomentumY = scaled(2); airAngImpulse = scaled(3)
                }
                "tick" -> tick = long(1)
                // `mined` is v9's name for it: the same quantity, counted at the miner instead.
                "mined", "extracted" -> extracted = scaled(1)
                "vented" -> vented = scaled(1)
                "generated" -> generated = energy(1)
                "radiated" -> radiated = energy(1)
                "airvented" -> airVented = scaled(1)
                "airventedheat" -> airVentedEnergy = energy(1)
                "airinjected" -> { injectedAirMass = scaled(1); injectedAirEnergy = energy(2) }
                "baselineairheat" -> baselineAirEnergy = energy(1)
                "baselinejoules" -> baselineEnergy = energy(1)
                "inserted" -> inserted = energy(1)
                "acquired" -> acquired = energy(1)
                // Old spelling: the energy the player inserted, now [insertedEnergy].
                "construction" -> inserted = energy(1)
                "solidtoair" -> solidToAir = energy(1)
                "baselineair" -> baselineAir = scaled(1)

                // Every kind that was ever written under this keyword now lives on the deck, so
                // `machine` is purely a legacy spelling of `deckmachine` — see [readMachine], which
                // is what is left of the machine list.
                "machine" -> {
                    val t = tile(1)
                    if (deck[t] != null || t in legacyThrusters) fail("two machines at tile $t")
                    val dm = readMachine(tokens.drop(2), version, t, grid, buffers, scale, energyScale, ::fail)
                    // A motor out of a file that predates its bell cannot be stood yet — the tile
                    // in front of it may belong to a machine listed further down. See
                    // [standMigratedThrusters].
                    if (dm is Thruster && version < THRUSTER_BELL_VERSION) {
                        legacyThrusters[t] = dm
                        readMigratedDeckHeat(tokens.drop(2), energyScale, ::fail)
                            ?.let { legacyThrusterEnergy[t] = it }
                    } else {
                        // A `machine` record predates stated materials by many versions, so what it
                        // is made of is what a file that old meant — see [materialBefore].
                        deck.stand(dm, withCasing = true, material = materialBefore(dm.kind))
                        // Its heat rode that record in `k=`, and `deckheat` was not written for it,
                        // so without this the machine comes back at ambient and the thermal ledger
                        // reports the difference on the first tick after the load.
                        readMigratedDeckHeat(tokens.drop(2), energyScale, ::fail)?.let { total ->
                            val tiles = dm.tiles(grid)
                            val each = total / tiles.size
                            for (tile in tiles) deck.stuff.setEnergy(tile, each)
                            deck.stuff.addEnergy(dm.center, total % tiles.size)
                        }
                    }
                }
                "deckmachine" -> {
                    val t = tile(1)
                    if (deck[t] != null || t in legacyThrusters) fail("two machines at tile $t")
                    val dm = readDeckMachine(tokens.drop(2), version, t, grid, buffers, scale, energyScale, ::fail) { droppedPropellant += it }
                    // Read off the raw tokens rather than through the machine, because the mark is a
                    // fact about the vessel and not about the machine — see [VesselState.scrapping].
                    val marked = tokens.any { it == "scrapping=1" }
                    // Likewise a fact about the *site* rather than about the machine, which is why
                    // it lives on the deck's own column and is read off the raw tokens here.
                    val made = tokens.firstOrNull { it.startsWith("made=") }?.removePrefix("made=")
                        ?.let { name ->
                            Species.ALL.firstOrNull { it.name == name } ?: fail("unknown material '$name'")
                        }
                        // See [readSegment]: absent is an old file, not an under-specified one.
                        ?: if (version < STATED_MATERIAL_VERSION) materialBefore(dm.kind)
                        else fail("a machine at $t does not say what it is made of")
                    if (dm is Thruster && version < THRUSTER_BELL_VERSION) {
                        legacyThrusters[t] = dm
                        if (marked) legacyThrusterScrapping.add(t)
                    } else {
                        deck.stand(dm, withCasing = true, material = made)
                        if (marked) scrapping.add(t)
                    }
                }
                // `rail` = v5 spelling; record carries conduit name, so old files land on the right layer.
                "rail", "conduit" -> {
                    val t = tile(1)
                    // ⛔ **A legacy PIPE segment is skipped, not refused.** The pipe network is
                    // deleted (`PLAN_fluid_thrusters.md` §9) and a save written before that is still
                    // a good save; failing on it would make every world that ever had plumbing in it
                    // unloadable. What the pipe was *holding* is not lost — it joins the room on its
                    // own tile, further down.
                    //
                    // ⚠️ **The fitting standing on it is kept, and that is the point of doing this
                    // here rather than at the parse.** A valve was written as a flag on the pipe
                    // record it stood over; dropping the record whole would take the player's
                    // machine and its metal with it. It comes back on bare deck, which is a valve
                    // that vents nothing until they lay track under it — see [Valve].
                    if (tokens.getOrNull(2) == LEGACY_PIPE) {
                        readMigratedFitting(tokens.drop(2), t, scale, ::fail)?.let { fitting ->
                            if (deck[t] != null) fail("a fitting and a machine both stand at tile $t")
                            deck.stand(fitting, withCasing = true, material = materialBefore(fitting.kind))
                        }
                        continue
                    }
                    val segment = readSegment(tokens.drop(2), t, rail, version, scale, energyScale, ::fail)
                    // The heat is the layer's, and the layer does not exist until every segment has
                    // been read — so it is held aside by (conduit, tile) and applied below, once
                    // [Conduits.of] has laid the metal that carries it.
                    readSegmentEnergy(tokens.drop(2), energyScale, ::fail)
                        ?.let { segmentEnergy[segment.conduit.ordinal to t.index] = it }
                    val layer = layers[segment.conduit.ordinal]
                    // Per layer, not per tile. Two segments on one tile is what layers are *for*;
                    // two of the same conduit on one tile is still a corrupt file.
                    if (layer[t.index] != null) fail("two ${segment.conduit.label} segments at tile $t")
                    layer[t.index] = segment
                    // A gauge or a valve the record was carrying as a flag. It becomes a building
                    // standing on that tile — unless something is already standing there, which a
                    // hand-edited file can say and the game cannot represent.
                    readMigratedFitting(tokens.drop(2), t, scale, ::fail)?.let { fitting ->
                        if (deck[t] != null) fail("a fitting and a machine both stand at tile $t")
                        deck.stand(fitting, withCasing = true, material = materialBefore(fitting.kind))
                    }
                }
                // A `bridge` record is how every file up to this one spelled it, back when a bridge
                // was its own list. It is a deck machine now, so the keyword is a legacy spelling of
                // `deckmachine` and lands in the same place — the same routing a `machine` record
                // for a vent gets. The slot keys (`in`, `span`, `out`) did not change; see
                // [storeKey].
                "bridge" -> {
                    val t = tile(1)
                    if (deck[t] != null) fail("two machines at tile $t")
                    val dm = readDeckMachine(tokens.drop(2), version, t, grid, buffers, scale, energyScale, ::fail) { droppedPropellant += it }
                    deck.stand(dm, withCasing = true, material = materialBefore(dm.kind))
                }
                "diverter" -> diverters[tile(1)] = long(2).toInt()
                "merge" -> merges[tile(1)] = long(2).toInt()
                // V4 stored heat per tile — averaged, which is why it was replaced. Parse for well-formedness, drop.
                "heat" -> for (i in 1 until tokens.size) {
                    val eq = tokens[i].indexOf('=')
                    if (eq < 0) fail("expected tile=energy, got '${tokens[i]}'")
                    val t = tokens[i].substring(0, eq).toIntOrNull() ?: fail("bad tile in '${tokens[i]}'")
                    if (t !in 0 until grid.size) fail("tile $t is outside the grid")
                    tokens[i].substring(eq + 1).toLongOrNull() ?: fail("bad energy in '${tokens[i]}'")
                }
                "airheat" -> readSparse(tokens, airEnergy.data, energyScale, ::fail)
                "charge" -> readSparse(tokens, charge, Rescale.NONE, ::fail)
                "deckheat" -> readDeckHeat(
                    tokens, deck.stuff, energyScale, ::fail, legacyThrusterEnergy, legacyThrusters.keys,
                )
                "deckstuff" -> {
                    val t = tile(1)
                    val mix = readMixture(tokens.getOrNull(2) ?: fail("expected a mixture"), scale, ::fail)
                    // The machine must already be down: this line *replaces* the bill of materials
                    // `+=` laid, and writing it onto a bare tile would claim a row the deck has no
                    // machine for. The writer emits it after the machine records for that reason.
                    // A legacy thruster is the one machine that is deliberately *not* down yet, so
                    // its composition is held aside with it — see [standMigratedThrusters].
                    if (t in legacyThrusters) legacyThrusterCasing[t] = mix
                    else if (!deck.stuff.occupies(t)) fail("deckstuff at $t, where no deck machine stands")
                    else for (s in Species.ALL) deck.stuff[t, s] = mix[s]
                }
                "trackstuff" -> {
                    val name = tokens.getOrNull(1) ?: fail("expected a conduit")
                    // The metal of a deleted pipe, skipped with the segment that carried it.
                    if (name == LEGACY_PIPE) continue
                    val conduit = Conduit.entries.firstOrNull { it.name == name }
                        ?: fail("unknown conduit '$name'")
                    val t = tile(2)
                    val mix = readMixture(tokens.getOrNull(3) ?: fail("expected a mixture"), scale, ::fail)
                    trackStuff[conduit.ordinal to t.index] = mix
                }
                "pipeair" -> {
                    val t = tile(1)
                    val mix = readMixture(tokens.getOrNull(2) ?: fail("expected a mixture"), scale, ::fail)
                    readFluids(mix, ::fail) { f, mass -> pipeMass[t, f] = mass }
                }
                "pipeairheat" -> readSparse(tokens, pipeEnergy.data, energyScale, ::fail)
                // Retired: the per-edge gas momentum. Accepted and dropped so a file written
                // before the vessel boundary became the only place momentum crosses still loads.
                "pipemomx", "pipemomy", "momx", "momy" -> Unit
                "creative" -> creative = tokens.getOrNull(1) != "0"
                "sas" -> sas = tokens.getOrNull(1) == "1"
                // ⚠️ Not through `scale.of` — credits are a count, not a mass, and reading them
                // through the mass scale would multiply the player's bank by 10⁶ on any file
                // written at a different unit.
                "credits" -> credits = tokens.getOrNull(1)?.toLongOrNull() ?: fail("unreadable credits")
                // A pre-[ASSEMBLY_VERSION] berth: one joint, the station held by the vessel, with
                // the interlock riding in the last column. Exactly a one-weld assembly.
                "dock" -> {
                    welds.add(Weld(
                        childId = tokens.getOrNull(1)?.toIntOrNull() ?: fail("unreadable dock station"),
                        parentId = Member.VESSEL,
                        childX = long(4),
                        childY = long(5),
                        childAng = tokens.getOrNull(6)?.toIntOrNull() ?: 0,
                        portTile = TileIndex(tokens.getOrNull(2)?.toIntOrNull() ?: fail("unreadable dock port")),
                        nodeIndex = tokens.getOrNull(3)?.toIntOrNull() ?: fail("unreadable dock berth"),
                    ))
                    dockedThrust = tokens.getOrNull(7) == "1"
                }
                "weld" -> welds.add(Weld(
                    childId = tokens.getOrNull(1)?.toIntOrNull() ?: fail("unreadable weld child"),
                    parentId = tokens.getOrNull(2)?.toIntOrNull() ?: fail("unreadable weld parent"),
                    portTile = TileIndex(tokens.getOrNull(3)?.toIntOrNull() ?: fail("unreadable weld port")),
                    nodeIndex = tokens.getOrNull(4)?.toIntOrNull() ?: fail("unreadable weld berth"),
                    childX = long(5),
                    childY = long(6),
                    childAng = tokens.getOrNull(7)?.toIntOrNull() ?: 0,
                ))
                "dockthrust" -> dockedThrust = tokens.getOrNull(1) == "1"
                // Grams that stopped being cargo and became fabric. Absent reads as zero, which is
                // what a world where nothing has ever been built out of its own stores has.
                "baselinecargo" -> baselineCargo = scale.of(tokens.getOrNull(1)?.toLongOrNull() ?: fail("unreadable baseline cargo"))
                "built" -> built = scale.of(tokens.getOrNull(1)?.toLongOrNull() ?: fail("unreadable built mass"))
                "reconciled" -> reconciled = scale.of(tokens.getOrNull(1)?.toLongOrNull() ?: fail("unreadable reconciled mass"))
                "captured" -> {} // consumed, ignored — legacy field
                "baselinebody", "baselinerock" -> {} // consumed, ignored — legacy field
                // Twelve fields shared with `body`, then fill, the mixed reserve and the shelves.
                "station" -> {
                    val w = tokens[1].toIntOrNull() ?: fail("unreadable station width")
                    val h = tokens[2].toIntOrNull() ?: fail("unreadable station height")
                    val bits = tokens.getOrNull(9) ?: fail("a station needs a shape")
                    if (bits.length != w * h) fail("a ${w}x$h station has ${bits.length} cells")
                    bodies.add(
                        RigidBody(
                            kind = BodyKind.STATION,
                            width = w, height = h,
                            cells = BooleanArray(bits.length) { bits[it] == '1' },
                            positionX = long(3), positionY = long(4),
                            impulseX = scaled(5), impulseY = scaled(6),
                            energy = bodyEnergy(tokens[7], bits.count { it == '1' }, energyScale, ::fail),
                            // ⚠️ NOT scaled — proportions, like a rock's. See the `body` branch.
                            oreComposition = readMixture(tokens[8], Rescale.NONE, ::fail),
                            ang = Coord(tokens.getOrNull(10)?.toIntOrNull() ?: 0),
                            angImpulse = if (tokens.size > 11) scaled(11) else 0L,
                            fillPermille = tokens.getOrNull(12)?.toIntOrNull() ?: 1_000,
                            // ⚠️ These two ARE scaled: they are masses, not proportions.
                            // ⚠️ One decision, not two — an old file's shelves go to the heap and
                            // the shelves go empty, and writing the version test twice would let
                            // half a migration exist. See [worked].
                            station = worked(
                                version,
                                heap = readMixture(tokens.getOrNull(13) ?: "-", scale, ::fail),
                                shelves = readMixture(tokens.getOrNull(14) ?: "-", scale, ::fail),
                                id = tokens.getOrNull(15)?.toIntOrNull() ?: 0,
                                docks = tokens.getOrNull(16)
                                    ?.takeIf { it != "-" }
                                    ?.split(",")
                                    ?.mapNotNull { entry ->
                                        val p = entry.split(":")
                                        val x = p.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                                        val y = p.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                                        val f = Direction.entries.firstOrNull { it.name == p.getOrNull(2) }
                                            ?: return@mapNotNull null
                                        DockNode(x, y, f)
                                    } ?: emptyList(),
                            ),
                        ),
                    )
                }
                "body", "rock" -> {
                    val w = tokens[1].toIntOrNull() ?: fail("unreadable body width")
                    val h = tokens[2].toIntOrNull() ?: fail("unreadable body height")
                    val bits = tokens.getOrNull(9) ?: fail("a ${tokens[0]} needs a shape")
                    if (bits.length != w * h) fail("a ${w}x$h ${tokens[0]} has ${bits.length} cells")
                    bodies.add(
                        RigidBody(
                            kind = BodyKind.ROCK,
                            width = w, height = h,
                            cells = BooleanArray(bits.length) { bits[it] == '1' },
                            // Flight units — tiles, not mass — so the mass rescale does not touch it.
                            // World frame since v15; a v14 file is converted below.
                            positionX = long(3), positionY = long(4),
                            impulseX = scaled(5), impulseY = scaled(6),
                            // Per filled cell since v14; one figure before that, spread evenly —
                            // which is exactly what it meant, since a body has never been anything
                            // but isothermal.
                            energy = bodyEnergy(tokens[7], bits.count { it == '1' }, energyScale, ::fail),
                            // ⚠️ NOT scaled. A rock's composition is *proportions*, the same shape
                            // of value as `Material.composition`, and multiplying it would be
                            // meaningless rather than merely wrong — see `capacityPerTileOf`.
                            oreComposition = readMixture(tokens[8], Rescale.NONE, ::fail),
                            // v16. Absent from every earlier file, and absent means zero: a body
                            // that predates step 3 was never turned, because nothing could turn it.
                            ang = Coord(tokens.getOrNull(10)?.toIntOrNull() ?: 0),
                            // Mass·tile²/tick, so it carries the mass unit exactly once and is
                            // rescaled like any other momentum.
                            angImpulse = if (tokens.size > 11) scaled(11) else 0L,
                        ),
                    )
                }
                "impulse" -> {
                    impulseX = scaled(1); impulseY = scaled(2)
                    exhaustX = scaled(3); exhaustY = scaled(4)
                    // Absent = zero (ledger had fewer stores). Slots 5-6 and 11-12 are read past:
                    // they held retired stores, and the positions are kept so the ones after them
                    // stay where every existing file put them.
                    if (tokens.size > 8) { debugX = scaled(7); debugY = scaled(8) }
                    if (tokens.size > 10) { bodyImpulseX = scaled(9); bodyImpulseY = scaled(10) }
                }
                "air" -> {
                    val t = tile(1)
                    val mix = readMixture(tokens.getOrNull(2) ?: fail("expected a mixture"), scale, ::fail)
                    readFluids(mix, ::fail) { f, mass -> airMass[t, f] = mass }
                }
                else -> fail("unknown entry '${tokens[0]}'")
            }
        }

        // Last of the deck to go down, and it has to be: a motor's bell can only be asked for once
        // everything that might already be standing there is. A no-op — and an empty migration — for
        // every file from [THRUSTER_BELL_VERSION] on, which held nothing aside.
        val thrusters = standMigratedThrusters(
            grid, deck, buffers, legacyThrusters, legacyThrusterCasing, legacyThrusterEnergy,
            legacyThrusterScrapping, scrapping,
        )

        val structure = StructureMap.derive(grid, deck)
        val occupancy = Occupancy.derive(grid, deck)
        // A file is hand-editable, so it is hand-breakable: a typed world that puts a belt and a
        // pipe on one tile violates the exclusion rule, and that is a *bad save*, not a bug in the
        // reader. Restated as a [SaveError] so the loader reports it the way it reports any other
        // unreadable line rather than throwing something the caller has no reason to expect.
        val conduits = try {
            Conduits.of(
                grid.size,
                *Conduit.entries.map { it to layers[it.ordinal].toList() }.toTypedArray(),
            )
        } catch (e: IllegalArgumentException) {
            throw SaveError(e.message ?: "the conduit layers do not describe a world")
        }
        // A tile the file gave a composition for is *not* made of its bill — it is a ghost, or a
        // length of track something has reacted with. Applied before the heat, since the energy line
        // is the last word on what this metal holds and must not be overwritten by a re-lay.
        for ((key, mix) in trackStuff) {
            val stuff = conduits.tracks[Conduit.entries[key.first]]
            val t = TileIndex(key.second)
            for (sp in Species.ALL) stuff[t, sp] = mix[sp]
        }
        // Every laid tile came back at ambient; the ones the file had a reading for get theirs back.
        // A tile with no `k=` is left exactly as laid, which is what the writer's omission meant.
        for ((key, energy) in segmentEnergy) {
            conduits.tracks.setEnergy(Conduit.entries[key.first], TileIndex(key.second), energy)
        }
        // ⛔ **The pipe layer is gone, and what it was holding joins the room on its own tile.**
        // `atmosphereMass` used to be `air + pipeAir`, so folding one into the other moves nothing:
        // the air ledger is untouched and no save reads as a leak. Dropping it instead would make
        // every world that ever had plumbing in it report the loss for the rest of its life. See
        // `PLAN_fluid_thrusters.md` §9.
        //
        // ⚠️ **Except where there is no room to join.** A pipe threaded through a bulkhead has no
        // gas cell on its tile, and putting gas inside a wall is the one thing `SealedTileGasTest`
        // forbids outright — so that much is vented, which is a departure the ledger already has a
        // term for.
        val air: Stuff
        var pipeVentedMass = 0L
        var pipeVentedEnergy = 0L
        run {
            val sealed = StructureMap.derive(grid, deck)
            for (i in 0 until grid.size) {
                val tile = TileIndex(i)
                val heat = pipeEnergy[tile]
                var held = 0L
                pipeMass.forEachFluid(tile) { _, mass -> held += mass }
                if (held == 0L && heat == 0L) continue
                if (sealed.blocksAir(tile)) {
                    pipeVentedMass += held
                    pipeVentedEnergy += heat
                } else {
                    pipeMass.forEachFluid(tile) { f, mass -> airMass.add(tile, f, mass) }
                    airEnergy[tile] += heat
                }
            }
            air = Stuff.from(airMass, airEnergy)
        }

        // V9: body momentum moved from vessel frame to world frame. `p_world = p_vessel + m_body · v_ship`.
        val momentumFixed = if (version >= 9 || bodies.isEmpty()) bodies.toList() else {
            val shipMass = vesselMass(grid, rail, conduits, deck, buffers)
            if (shipMass <= 0L) bodies.toList() else bodies.map {
                it.copy(
                    impulseX = it.impulseX + it.mass * impulseX / shipMass,
                    impulseY = it.impulseY + it.mass * impulseY / shipMass,
                )
            }
        }
        // V15: body *position* followed its momentum out of the vessel frame and into the world —
        // step 1 of `PLAN_rigid_bodies.md`, which deleted the vessel frame rather than correcting
        // for it. A version 14 file states a body's corner in grid coordinates, so it is placed
        // through the pose the same file describes.
        //
        // In practice every such file has `ang == 0` and the conversion is a translation, because
        // nothing could turn a ship until the tick before this format changed. It goes through
        // [Pose] anyway rather than adding `positionX`: a migration that is only correct for the
        // saves that happen to exist is a migration that breaks on the first one that does not.
        //
        // ⚠️ A zero local centre is what makes this the *origin*-anchored transform these old files
        // were written against — `toWorld(local) = origin + R·local`, which is what [Pose] meant
        // before [COM_ANCHOR_VERSION]. Not a placeholder: it is the old law, stated exactly.
        val loaded = if (version >= 15 || bodies.isEmpty()) momentumFixed else {
            val pose = Pose(positionX, positionY, ang, 0L, 0L)
            momentumFixed.map {
                it.copy(
                    positionX = pose.toWorldX(it.positionX, it.positionY),
                    positionY = pose.toWorldY(it.positionX, it.positionY),
                )
            }
        }
        val asRead = VesselState(
            grid = grid,
            gridPad = GRID_PAD,
            deck = deck,
            // The layer the machine records were read into. Omitting it does not fail loudly: the
            // default derived empty stores, so every warehouse came back with its store standing and
            // its contents gone. Hence: no default.
            buffers = buffers,
            rail = rail,
            conduits = conduits,
            charge = PowerCharge.of(charge),
            diverters = FlowCursors(diverters, merges),
            gravity = gravity,
            positionX = positionX,
            positionY = positionY,
            netImpulseX = netImpulseX,
            netImpulseY = netImpulseY,
            ang = ang,
            angImpulse = angImpulse,
            netTorque = netTorque,
            exhaustAngImpulse = exhaustAngImpulse,
            bodyAngImpulse = bodyAngImpulse,
            ventAngImpulse = ventAngImpulse,
            ventMomentumX = ventMomentumX,
            ventMomentumY = ventMomentumY,
            airMomentumX = airMomentumX,
            airMomentumY = airMomentumY,
            airAngImpulse = airAngImpulse,
            tick = tick,
            extractedMass = extracted,
            ventedMass = vented,
            generatedEnergy = generated,
            radiatedEnergy = radiated,
            airVentedMass = airVented + pipeVentedMass,
            structure = structure,
            occupancy = occupancy,
            creative = creative,
            sas = sas,
            credits = credits,
            assembly = Assembly(welds),
            // ⛔ **The counterparty is not written, it is found again.** A market is the station's
            // own shelves and the vessel merely holds the one it is berthed at, so writing it would
            // put a second copy of the same stock in the file for the two to disagree about. What a
            // load that skipped this got instead was a berth with nobody behind it: the trade sheet
            // read "not berthed" while the clamps were plainly shut, and the mouth traded nothing
            // ever again, because only [Edit.Dock] ever opened a counterparty and the ship was
            // already docked.
            dockedMarket = welds.firstOrNull()?.let { weld -> loaded.firstOrNull { it.station?.id == weld.childId } }
                ?.station?.market,
            dockedThrustAllowed = dockedThrust,
            scrapping = scrapping,
            builtMass = built,
            // A file that predates the field means "started empty", not "recompute from what is
            // aboard now" -- recomputing would launder every leak the save was written to catch.
            // Re-anchored by whatever a dropped motor took overboard with it — see
            // [ThrusterMigration]. Zero for every file that has no such motor, which is all of them
            // from [THRUSTER_BELL_VERSION] on.
            baselineCargoMass = (baselineCargo ?: 0L) - thrusters.cargo - droppedPropellant,
            // Whatever the file said; a file that predates the field gets the migration below.
            reconciledMass = reconciled ?: 0L,
            insertedEnergy = inserted,
            acquiredEnergy = acquired,
            solidToAirEnergy = solidToAir,
            // A missing baseline means the world's own totals, which is right for a handwritten
            // world and harmless for a saved one, where the line is always present. A version 4
            // file's baseline described the per-tile field, so it is not carried across: the
            // ledger is re-anchored to what the bodies actually hold.
            // Re-anchored by the casing this load minted and the casing it dropped — see
            // [ThrusterMigration]. Only against a baseline the file actually stated: the fallback is
            // computed from the world as it now is, so it already counts the bells.
            baselineEnergy = baselineEnergy?.plus(thrusters.energy) ?: solidEnergy(conduits),
            air = air,
            // Both fields, because they share one ledger — see VesselState.baselineAirMass.
            baselineAirMass = baselineAir ?: air.totalMass,
            airVentedEnergy = airVentedEnergy + pipeVentedEnergy,
            injectedAirMass = injectedAirMass,
            injectedAirEnergy = injectedAirEnergy,
            baselineAirEnergy = baselineAirEnergy ?: air.totalEnergy,
            vesselImpulseX = impulseX,
            vesselImpulseY = impulseY,
            exhaustMomentumX = exhaustX,
            exhaustMomentumY = exhaustY,
            debugImpulseX = debugX,
            debugImpulseY = debugY,
            bodyImpulseX = bodyImpulseX,
            bodyImpulseY = bodyImpulseY,
            bodies = loaded,
        )
        // ⛔ **The anchor flip — and it can only happen here, with the layers standing.**
        //
        // Below [COM_ANCHOR_VERSION] every `position` in the file is a grid origin, and a centre of
        // mass cannot be recovered from one without walking the mass that defines it. So the state
        // is built as written and re-anchored once, rather than the header trying to convert a
        // number whose meaning depends on layers it has not read yet.
        val state = if (version >= COM_ANCHOR_VERSION) asRead else asRead.comAnchored()
        // ⛔ **The one-time write-off, and it is gated on the file never having stated the term.**
        //
        // A world saved before `reconciled` existed may carry a drift in the mass ledger from some
        // forgotten tick, and a tripwire that has been tripped since before anybody was looking is
        // worse than no tripwire: the next real leak arrives as a slightly larger number nobody
        // reads. Stu's save carried 1.0 t. So the drift is measured once, here, and recorded as
        // something that was written off rather than something that never happened.
        //
        // ⚠️ **`reconciled == null` and not `version < N`.** The question is whether this file has
        // ever had its ledger anchored, and the field's own absence is the exact answer; a version
        // test would additionally re-anchor any *future* file that happened to be old, which is the
        // laundering `baselinecargo` above is careful to avoid.
        //
        // ⛔ **It must be computed from the finished state**, because `inTransitMass` is derived from
        // the world rather than stored — which is the whole reason this cannot happen up in the
        // field list with the others.
        // ⛔ **Purity first, and then the ledger.** Normalising a casing preserves its mass exactly,
        // so the two migrations cannot interact — but they are ordered anyway, because the anchor's
        // whole job is to measure the world as it finally is and a migration running after it would
        // be a drift it had already decided did not exist.
        if (version < PURE_FABRIC_VERSION) purifyFabric(state)
        if (reconciled != null) return state
        val drift = state.inTransitMass + state.ventedMass + state.builtMass -
            state.extractedMass - state.baselineCargoMass
        return if (drift == 0L) state else state.copy(reconciledMass = drift)
    }

    /**
     * The `k=` heat off a pre-migration `machine` record, summed over its tiles.
     *
     * Summed rather than kept per tile because the two representations do not line up: a machine's
     * `k=` had one entry per tile of the machine, and a deck machine's heat is one entry per
     * tile of its footprint. For the one kind this applies to — a one-tile vent — the two are the
     * same number anyway, and the total is the quantity the ledger is checked against.
     */
    private fun readMigratedDeckHeat(
        tokens: List<String>,
        energyScale: Rescale,
        fail: (String) -> Nothing,
    ): Long? {
        val f = fields(tokens.drop(1), fail)
        val field = f["k"] ?: return null
        var sum = 0L
        for (part in field.split(',')) sum += energyScale.of(part.toLongOrNull() ?: fail("bad energy '$field'"))
        return sum
    }

    /**
     * The reading half of [writeSparse].
     *
     * A reading for a tile in [heldTiles] goes into [aside] rather than into the layer. That is for
     * the legacy thruster, which is not standing yet when this line is read (see
     * [standMigratedThrusters]): the deck layer is row-allocated, so writing an energy at a tile
     * with no machine on it claims a row there — and a claimed row is exactly what refuses the
     * `stand` the migration is waiting to do. Both are empty for every file that has no such motor.
     */
    private fun readDeckHeat(
        tokens: List<String>,
        deck: StuffLayer,
        scale: Rescale,
        fail: (String) -> Nothing,
        aside: MutableMap<TileIndex, Long>,
        heldTiles: Set<TileIndex>,
    ) {
        for (i in 1 until tokens.size) {
            val eq = tokens[i].indexOf('=')
            if (eq < 0) fail("expected index=value, got '${tokens[i]}'")
            val at = tokens[i].substring(0, eq).toIntOrNull() ?: fail("bad index in '${tokens[i]}'")
            if (at !in 0 until deck.tileCount) fail("index $at is outside the field")
            val energy = scale.of(tokens[i].substring(eq + 1).toLongOrNull() ?: fail("bad value in '${tokens[i]}'"))
            val tile = TileIndex(at)
            if (tile in heldTiles) aside[tile] = energy else deck.setEnergy(tile, energy)
        }
    }

    /**
     * What giving every thruster in an old save a bell cost the two ledgers that would otherwise
     * notice.
     *
     * A migration that changes what the world is made of has to say so, or the first tick after the
     * load reports the difference as a leak. Both terms are re-anchorings of a *baseline* rather
     * than income: nothing arrived from off-world and nothing was spent, the world is simply
     * constituted differently from the one the file described.
     */
    private class ThrusterMigration(
        /**
         * Casing energy the deck holds that the file's `baselineenergy` never counted — a freshly
         * minted bell at room temperature — less the casing of any motor that had to be dropped.
         */
        val energy: Long,
        /** Propellant that went with a dropped motor, and so has left [VesselState.inTransitMass]. */
        val cargo: Long,
    )

    /**
     * Stands the thrusters of a pre-[THRUSTER_BELL_VERSION] file, now that they are two tiles long.
     *
     * ### Why they are not stood where they are read
     *
     * A machine record is applied the moment it is read, and that works because a file lists no two
     * machines on one tile. It stops working the instant a kind grows: this build's thruster claims
     * a tile the file never mentioned in connection with it, and that tile may well be the middle of
     * a warehouse listed forty lines further down. Whichever of the two was read second would fail
     * its "nothing here yet" check and the whole save would be refused.
     *
     * So a legacy motor is held aside — along with the `deckstuff` and `deckheat` lines that name
     * its tile — until every other machine in the file is standing and the deck can be *asked*
     * whether there is room for a bell. Files at [THRUSTER_BELL_VERSION] or later go down the
     * ordinary path: they already state the world this build represents.
     *
     * ### What happens to a motor with nowhere to put its bell
     *
     * **It is dropped**, and its propellant with it. That is Stu's call and it is the right one: the
     * alternatives are refusing to load a legal old save, or inventing a rotation for the player's
     * engine, and a motor pointing a way nobody chose is worse than a motor that is gone.
     *
     * ⚠️ It goes **quietly**. Both ledgers are re-anchored so the loss is stated rather than showing
     * up later as a leak, but nothing tells the player, because [read] has no channel to tell them
     * on. A motor that vanishes between two loads is therefore this, and there is nowhere else it
     * could have gone.
     *
     * A surviving motor keeps its chamber exactly as the file described it — composition, heat,
     * propellant, wiring, throttle — and its bell arrives as new metal at room temperature. A
     * half-built motor stays half-built: its bill doubled along with its footprint, so a chamber
     * that was short of one tile's worth is still short of two tiles' worth.
     */
    private fun standMigratedThrusters(
        grid: Grid,
        deck: DeckArray,
        buffers: BufferLayer,
        pending: Map<TileIndex, Thruster>,
        casing: Map<TileIndex, Mixture>,
        energies: Map<TileIndex, Long>,
        markedScrapping: Set<TileIndex>,
        scrapping: MutableSet<TileIndex>,
    ): ThrusterMigration {
        var minted = 0L
        var lostCargo = 0L
        // Every chamber, whether it has been stood yet or not — and whether it ends up standing at
        // all. Two reasons: a motor read earlier in the file is not on the deck while a later one is
        // being placed, so `deck.stuff` alone would let the second put its bell through the first's
        // chamber; and keeping a *dropped* motor's tile reserved is what makes the outcome
        // independent of the order the file happens to list its engines in. Slightly conservative,
        // and deliberately so: "which of my thrusters survived" must not depend on line numbers.
        val chambers = pending.keys
        for ((tile, m) in pending) {
            val footprint = m.kind.footprint(tile, grid, m.facing)
            // Off the rim, or over something already standing. Its own chamber is not in its way.
            val room = footprint != null &&
                footprint.all { it == tile || (!deck.stuff.occupies(it) && it !in chambers) }
            if (!room) {
                lostCargo += bufferRolesOf(m).sumOf { role ->
                    bufferTile(grid, m, tile, role)?.let { buffers.massAt(it) } ?: 0L
                }
                buffers.releaseRoles(grid, m, tile)
                // Its casing heat was held aside and is never written, so the file's baseline is
                // over by exactly that much.
                minted -= energies[tile] ?: 0L
                continue
            }
            deck.stand(m, withCasing = true, material = materialBefore(m.kind))
            // The chamber goes back to exactly what the file said it was; the bell keeps the bill
            // `stand` just laid, which is the metal this migration is minting.
            casing[tile]?.let { mix -> for (sp in Species.ALL) deck.stuff[tile, sp] = mix[sp] }
            energies[tile]?.let { deck.stuff.setEnergy(tile, it) }
            for (part in footprint!!) if (part != tile) minted += deck.stuff.energyAt(part)
            if (tile in markedScrapping) scrapping.add(tile)
        }
        return ThrusterMigration(minted, lostCargo)
    }

    private fun readSparse(tokens: List<String>, into: LongArray, scale: Rescale, fail: (String) -> Nothing) {
        for (i in 1 until tokens.size) {
            val eq = tokens[i].indexOf('=')
            if (eq < 0) fail("expected index=value, got '${tokens[i]}'")
            val at = tokens[i].substring(0, eq).toIntOrNull() ?: fail("bad index in '${tokens[i]}'")
            if (at !in into.indices) fail("index $at is outside the field")
            into[at] = scale.of(tokens[i].substring(eq + 1).toLongOrNull() ?: fail("bad value in '${tokens[i]}'"))
        }
    }

    private fun readMachine(
        tokens: List<String>,
        version: Int,
        tile: TileIndex,
        grid: Grid,
        buffers: BufferLayer,
        scale: Rescale,
        energyScale: Rescale,
        fail: (String) -> Nothing,
        onDroppedCargo: (Long) -> Unit = {},
    ): DeckMachine {
        val kindName = tokens.firstOrNull() ?: fail("expected a machine kind")
        // A v9 world's `Miner` loads as the [Extractor] that replaced it: same buffer, same port,
        // same place in the line. Its `ore` field is dropped on purpose — an extractor has no ore
        // body of its own, because the rock it is standing on is the ore body now. The rename is
        // applied here rather than in the deck reader so that both spellings land on one path.
        val deckName = canonicalKindName(if (version < 10 && kindName == "Miner") "Extractor" else kindName)
        if (deckName !in DeckMachineKind.ALL.map { it.toString() }) {
            fail("$kindName is a conduit, not a machine")
        }
        return readDeckMachine(
            listOf(deckName) + tokens.drop(1), version, tile, grid, buffers, scale, energyScale, fail,
        )
    }

    /**
     * A machine kind's name on disk, mapped to what [DeckMachineKind] calls it today.
     *
     * ⛔ **A kind is written to disk by [DeckMachineKind.name]**, so renaming a constant renames the
     * format — silently, and only for people who already have a save. `Processor` became
     * `Concentrator` and `ThermalDecomposer` became `Furnace` because neither old name said what the
     * machine does; without this, every world containing either simply stops loading, with
     * "unknown machine 'Processor'" as the whole of the explanation.
     *
     * ⚠️ **Not keyed on the save version, unlike `Miner`.** A rename that changes no field and no
     * meaning does not earn a version bump — nothing about the record differs — so there is no
     * version at which the old spelling stops being possible and the mapping is unconditional. The
     * cost is that these two strings are reserved for ever, which is the right price: they are two
     * entries in a `when`, and the alternative is a save file that cannot say what it is.
     *
     * Applied at both readers rather than at one, because [readMachine] checks membership before it
     * delegates and would reject the old spelling before [readDeckMachine] ever saw it.
     */
    /**
     * **Makes every casing and every length of conduit one species**, in place, on a world loaded
     * from a file written before that was true.
     *
     * ⛔ **Every save older than this is otherwise a vessel that cannot be rebuilt.**
     * `BUILD_PURITY_PERCENT` is 100, so a construction site refuses anything off its recipe — and a
     * hull built when steel was `Iron 990 : Carbon 10` is a mixture, so deconstructing one yields
     * salvage no site will accept. The same for track laid out of that salvage, which is how a
     * player's whole ship ends up made of material it can no longer use. Measured on a real save:
     * every metal read **zero** in the stockpile's fabric column across 187 machines.
     *
     * ### What it does, and what that costs
     *
     * Each occupied tile keeps its **total mass exactly** and has that mass restated as the single
     * species the thing standing there is made of. Mass is therefore untouched and no ledger moves,
     * which is why this can run before the ledger anchor without the two interacting.
     *
     * ⚠️ **Energy is preserved and temperature is not.** Steel costs 490 J/kg/K to warm and the
     * iron-and-carbon mixture it replaces cost 453, so the same heat in the same mass now reads
     * about 8% cooler — a hull at room temperature comes back at roughly 271 K. The alternative is
     * to preserve the temperature, which means minting the difference, and a migration that mints
     * energy to keep a number looking right is exactly the kind of silent correction the ledger
     * exists to catch. The ship is briefly a little cold; it warms up.
     *
     * ⛔ **It transmutes, and that is the point rather than a side effect.** Iron and carbon in a
     * hull become steel — which is the alloying reaction the player would have had to run, applied
     * retrospectively to a ship built before there was one. A tile that had swallowed something
     * unrelated (a rail that took titanium, which was reachable in ordinary play) becomes iron. In
     * every case the player keeps the mass and gets back a thing they can actually take apart.
     *
     * ⚠️ **Gated on the save version and not on "is this tile impure"**, because from this version on
     * an impure tile is a legitimate thing — a site being fed, or a casing a future reaction has
     * altered — and a rule that purified whatever looked wrong would keep quietly rewriting worlds
     * that were right.
     */
    /**
     * A station off the file — with a pre-[WORKED_SHELVES_VERSION] one's whole stock tipped back
     * into the heap to be worked properly.
     *
     * ⛔ **The shelves in an old file are not shelves.** Every sale was absorbed onto them species by
     * species, so what is recorded is every lump anybody ever sold, taken apart for free — a hundred
     * and forty species in sub-gram quantities, quoted at prices nobody would pay. Nothing in the
     * game takes matter *off* a shelf except a purchase, so playing forward never tidies it; the
     * only way back to a shelf that means something is to tip the lot into the heap and let the
     * separator earn it, a tonne at a time.
     *
     * ✅ **All of it, including the pure metal a station was seeded with.** A shelf below this
     * version cannot say which of its species got there honestly, and picking the ones that *look*
     * legitimate would be guessing about the player's history — the same reason [purifyFabric] is
     * gated on the version rather than on what a tile looks like. The mass is all still there and
     * the station will work it back out; what it costs is that a migrated station has nothing to
     * sell until it has separated something, which is a fair description of a business that has
     * just tipped its stockroom into the hopper.
     *
     * ⚠️ **Not a write-off.** The heap and the shelves both count toward nothing the vessel's
     * ledgers watch — a station is outside every one of them — so this moves matter between two
     * piles that are each other's whole world, and the total is unchanged by construction.
     */
    /**
     * One signed permission off the file: a mass through the scale, or `+`/`-` for an unbounded one.
     *
     * ⚠️ **The unbounded pair carry no number on purpose.** [DockingPort.ENDLESS] is `Long.MAX_VALUE`
     * and every mass in the file goes through the mass scale, so writing it as a number would come
     * back multiplied — or overflowed — into something that is merely very large, which behaves like
     * a bound and is not one.
     */
    private fun readPermission(field: String?, scale: Rescale): Long? = when (field) {
        null, "" -> null
        "+" -> DockingPort.ENDLESS
        "-" -> -DockingPort.ENDLESS
        else -> field.toLongOrNull()?.let { scale.of(it) }
    }

    private fun worked(
        version: Int,
        heap: Mixture,
        shelves: Mixture,
        id: Int,
        docks: List<DockNode>,
    ): Station =
        if (version < WORKED_SHELVES_VERSION) Station(heap + shelves, Market.empty(), id, docks)
        else Station(heap, Market.holding(shelves), id, docks)

    private fun purifyFabric(state: VesselState) {
        fun purify(layer: StuffLayer, tile: TileIndex, species: Species) {
            val mass = layer.massAt(tile)
            if (mass <= 0L) return
            if (layer.pureSpeciesAt(tile) == species) return
            val energy = layer.energyAt(tile)
            layer.release(tile)
            layer[tile, species] = mass
            layer.setEnergy(tile, energy)
        }

        for (i in 0 until state.deck.size) {
            val tile = TileIndex(i)
            val m = state.deck[tile] ?: continue
            if (m.center != tile) continue
            // Every tile of the footprint, because a casing is spread across it rather than held at
            // one address — the same reason the stockpile counts fabric per tile and not per machine.
            val species = state.deck.materialOf(m)
            for (part in m.tiles(state.grid)) purify(state.deck.stuff, part, species)
        }
        for (conduit in Conduit.entries) {
            val layer = state.conduits.tracks[conduit]
            for (tile in state.grid.tiles) {
                if (state.conduits.at(conduit, tile) == null) continue
                purify(layer, tile, state.conduits.materialAt(conduit, tile) ?: continue)
            }
        }
    }

    private fun canonicalKindName(name: String): String = when (name) {
        "Processor" -> DeckMachineKind.Concentrator.name
        "ThermalDecomposer" -> DeckMachineKind.Furnace.name
        else -> name
    }

    private fun readDeckMachine(
        tokens: List<String>,
        version: Int,
        tile: TileIndex,
        grid: Grid,
        buffers: BufferLayer,
        scale: Rescale,
        energyScale: Rescale,
        fail: (String) -> Nothing,
        onDroppedCargo: (Long) -> Unit = {},
    ): DeckMachine {
        val kindName = tokens.firstOrNull() ?: fail("expected a machine kind")
        // The mineral vaporizer is gone (see PLAN_ambient_chemistry.md): it put whatever it was
        // handed into the atmosphere, which is precisely what a typed air field forbids. Refused by
        // name rather than dropped, because a machine holds cargo and dropping it would take that
        // mass out of the ledger without saying so — a silent loss is the worse of the two failures.
        if (kindName == "VAPORIZER") fail("the mineral vaporizer no longer exists")
        val kind = DeckMachineKind.ALL.firstOrNull { it.name == canonicalKindName(kindName) }
            ?: fail("unknown machine '$kindName'")
        val f = fields(tokens.drop(1), fail)

        fun facing(): Direction = f["facing"]?.let { name ->
            Direction.ALL.firstOrNull { it.name == name } ?: fail("unknown direction '$name'")
        } ?: fail("$kindName needs a facing")
        /**
         * A store's contents.
         *
         * ⚠️ Tolerates the `S:` packet prefix, because a **legacy `bridge` record wrote its three
         * slots as packets** rather than as resources — a bridge carried `Packet`s while every other
         * machine's store held a `Mixture`, and that difference reached the file. Now that its slots
         * are ordinary role tiles there is one spelling going forward, and this reads the old one.
         * `F:` is refused rather than unwrapped: there was never a fluid bridge, and a file claiming
         * one is a file that means something this build cannot honour.
         */
        fun res(key: String): Mixture? = f[key]?.let {
            when {
                it.startsWith("S:") -> readResource(it.substring(2), scale, fail)
                it.startsWith("F:") -> fail("a $kindName store cannot hold a fluid packet: '$it'")
                else -> readResource(it, scale, fail)
            }
        }
        fun num(key: String, fallback: Long): Long =
            f[key]?.let { it.toLongOrNull() ?: fail("bad number '$it'") } ?: fallback
        // ⚠️ Scales the value read from the file but NOT the fallback, which is a current-unit
        // constant off the machine's own data class. Scaling a default would rescale a number that
        // was never in the old unit to begin with.
        fun massNum(key: String, fallback: Long): Long =
            f[key]?.let { scale.of(it.toLongOrNull() ?: fail("bad number '$it'")) } ?: fallback

        // V1 rate was per second; V2+ is per tick. Convert v1 by dividing by V1_TICKS_PER_SECOND.
        /**
         * A machine's throughput, defaulting to **that machine kind's own current default**.
         *
         * ⚠️ The fallback used to be a literal per machine — a fourth copy of a number already
         * stated on the data class — so a save with no `rate` field loaded a machine running at
         * whatever the rate was when this function was written. That is the "caller restates a
         * constant it does not own" family again, and it survived a rate change silently.
         */
        fun rate(fallback: Long): Long {
            val stored = massNum("rate", fallback * V1_TICKS_PER_SECOND)
            return if (version < 2) stored / V1_TICKS_PER_SECOND else massNum("rate", fallback)
        }

        val machine: DeckMachine = when (kind) {
            DeckMachineKind.Hull -> Hull(tile)
            DeckMachineKind.SolarPanel -> SolarPanel(tile)
            DeckMachineKind.Airlock -> Airlock(tile)
            DeckMachineKind.Vent -> Vent(tile, ventedMass = massNum("vented", 0L))
            // Two lists, each one field. ⚠️ `wiring` is applied after this `when` for every kind
            // (see `withWiring` below), so it is not passed here.
            DeckMachineKind.DockingPort -> DockingPort(
                tile,
                facing(),
                orders = buildMap {
                    for (entry in f["orders"]?.split(",").orEmpty()) {
                        val parts = entry.split(":")
                        val species = Species.ALL.firstOrNull { it.name == parts[0] } ?: continue
                        put(species, readPermission(parts.getOrNull(1), scale) ?: continue)
                    }
                },
                ore = f["orders"]?.split(",").orEmpty()
                    .firstOrNull { it.startsWith("ORE:") }
                    ?.let { readPermission(it.removePrefix("ORE:"), scale) } ?: 0L,
            )
            DeckMachineKind.Storage -> Storage(
                tile,
                facing(),
                filter = f["filter"].let { name ->
                    val species = Species.ALL.firstOrNull { it.name == name }
                    val pure = purityBefore(f)
                    if (species == null && pure == null) null
                    else SpeciesFilter(species, pure)
                },
                autoLock = (f["auto"]?.toIntOrNull() ?: 0)%2==1,
                autoUnlock = (f["auto"]?.toIntOrNull() ?: 0)/2%2==1,
            )
            DeckMachineKind.Sensor -> Sensor(
                tile,
                facing(),
                wiring = Wiring.RUNNING,
                f["threshold"]?.toIntOrNull() ?: 500,
                f["delay"]?.toIntOrNull() ?: 0,
                f["delayedFor"]?.toIntOrNull() ?: 0,
                f["release"]?.toIntOrNull() ?: 0,
                f["releasedFor"]?.toIntOrNull() ?: 0,
            )
            DeckMachineKind.KeyInput -> WireButton(
                tile,
                key = f["key"]?.let { name ->
                    InputKey.ALL.firstOrNull { it.name == name } ?: fail("unknown key '$name'")
                } ?: InputKey.Up,
            )
            DeckMachineKind.Pump -> Pump(tile, facing())
            // Holds nothing of its own: both gases are stores, written by the role loop.
            DeckMachineKind.Electrolyzer -> Electrolyzer(tile, facing())
            DeckMachineKind.Concentrator -> Concentrator(
                tile,
                facing = facing(),
                carry = massNum("carry", 0L),
                progress = num("actionProgress", 0L).toInt(),
                efficiencyPermille = num("eff", 900L).toInt(),
            )
            DeckMachineKind.Furnace -> Furnace(
                tile,
                facing = facing(),
                setTemperature = num("temp", 900L).toInt(),
                dwellTicks = num("dwell", 0L).toInt(),
                heldTicks = num("held", 0L).toInt(),
            )
            // ⚠️ An older file's `carry`, `rate` and `in` (the cell in its jaws) are simply not read.
            // The first two no longer exist, and the third is a hopper's worth of ore that a loaded
            // save quietly drops — accepted rather than migrated, Stu's call.
            DeckMachineKind.Extractor -> Extractor(tile, facing = facing())
            DeckMachineKind.Thruster -> Thruster(
                tile,
                facing = facing(),
                carry = massNum("carry", 0L),
                massPerTick = rate(Thruster(tile, Direction.Right).massPerTick),
                filter = f["filter"].let { name ->
                    val species = Species.ALL.firstOrNull { it.name == name }
                    val pure = purityBefore(f)
                    if (species == null && pure == null) null
                    else SpeciesFilter(species, pure)
                },
                control = f["control"]?.let { name ->
                    ThrusterControl.ALL.firstOrNull { it.name == name } ?: fail("unknown thruster control '$name'")
                } ?: ThrusterControl.Flight,
                firing = num("firing", 0L).toInt(),
            )
            DeckMachineKind.Rocket -> Rocket(
                tile,
                facing = facing(),
                carry = massNum("carry", 0L),
                fuelPermille = num("mix", Rocket.DEFAULT_FUEL_PERMILLE.toLong()).toInt(),
                setTemperature = num("temp", Rocket.DEFAULT_SETPOINT.toLong()).toInt(),
                control = f["control"]?.let { name ->
                    ThrusterControl.ALL.firstOrNull { it.name == name } ?: fail("unknown thruster control '$name'")
                } ?: ThrusterControl.Flight,
                firing = num("firing", 0L).toInt(),
            )
            // Both are fittings that stand over a run and hold nothing. A gauge's reading is state
            // and comes back with it; a valve is only a position.
            DeckMachineKind.Gauge -> Gauge(
                tile,
                lastDominant = f["lastspecies"]?.let { name ->
                    Species.ALL.firstOrNull { it.name == name } ?: fail("unknown species '$name'")
                },
                lastPurity = num("lastpurity", 0L).toInt(),
                lastMass = massNum("lastmass", 0L),
            )
            DeckMachineKind.Valve -> Valve(tile)
            DeckMachineKind.Bridge -> {
                // ⛔ A pipe bridge is refused rather than quietly turned into a rail one. There was
                // never a way to build one — `Edit.Place` only ever made rail bridges — so a file
                // that says otherwise was hand-edited, and converting it would silently reroute a
                // network. Every real save says Rail or says nothing.
                f["conduit"]?.let { if (it != Conduit.Rail.name) fail("a bridge carries rail, not $it") }
                Bridge(tile, facing())
            }
        }
        // Falls back to what a *freshly placed one of these* is wired to, not to RUNNING. They are
        // the same for every machine but the airlock, which ships sealed — and a door that defaulted
        // to running would come back from a hand-written save wide open.
        val wiring = f["wire"]?.let { readWiring(it, fail) } ?: machine.wiring
        // Claimed and filled exactly as the machine record's are — see the twin in [readMachine]
        // for why claiming is separate from filling.
        buffers.claimRoles(grid, machine, tile)
        for (role in bufferRolesOf(machine)) {
            val store = bufferTile(grid, machine, tile, role) ?: continue
            buffers.put(store, res(storeKey(machine, role)))
        }
        return machine.withWiring(wiring)
    }

    private fun readSegment(
        tokens: List<String>,
        tile: TileIndex,
        rail: RailLayer,
        version: Int,
        scale: Rescale,
        energyScale: Rescale,
        fail: (String) -> Nothing,
    ): Segment {
        val conduitName = tokens.firstOrNull() ?: fail("expected a conduit")
        val conduit = Conduit.entries.firstOrNull { it.name == conduitName } ?: fail("unknown conduit '$conduitName'")
        val f = fields(tokens.drop(1), fail)
        val links = f["links"]?.toIntOrNull() ?: fail("a segment needs its links")
        if (links !in 0..15) fail("links must be a 4-bit mask, got $links")
        // The load is the layer's now, but the field is still the segment's — the record was written
        // before the two were separate and nothing about the file has changed.
        f["held"]?.let { held ->
            val packet = readPacket(held, scale, fail)
            if (packet is SolidPacket) rail.put(tile, packet.contents)
            else fail("only a solid rides the track; tile $tile carries $held")
        }
        return Segment(
            conduit = conduit,
            links = links,
            deconstructing = f["scrapping"] == "1",
            // Absent means the file predates [STATED_MATERIAL_VERSION] and simply does not say —
            // see [materialBefore]. From that version on every segment states it, so an absence is
            // a corrupt record rather than an old one.
            material = f["made"]?.let { name ->
                Species.ALL.firstOrNull { it.name == name } ?: fail("unknown material '$name'")
            } ?: if (version < STATED_MATERIAL_VERSION) materialBefore(conduit)
            else fail("a segment at $tile does not say what it is made of"),
        )
    }

    /**
     * The fitting a **legacy** conduit record was carrying, if any — the gauge or the valve that
     * used to be a flag on the segment itself.
     *
     * Both are buildings standing over their run now, so an old file's `gauge=1` or `valve=1` puts
     * one on the deck at that tile. Nothing writes these fields any more; this is migration only,
     * and it is why the reader needs the record after [readSegment] has finished with it.
     */
    private fun readMigratedFitting(
        tokens: List<String>,
        tile: TileIndex,
        scale: Rescale,
        fail: (String) -> Nothing,
    ): DeckMachine? {
        val f = fields(tokens.drop(1), fail)
        // `gauge=1` since v11; before that, *having* a channel was what made a segment a gauge.
        if (f["gauge"] == "1" || f["channel"] != null) {
            return Gauge(
                tile,
                lastDominant = f["lastspecies"]?.let { name ->
                    Species.ALL.firstOrNull { it.name == name } ?: fail("unknown species '$name'")
                },
                lastPurity = f["lastpurity"]?.toIntOrNull() ?: 0,
                lastMass = scale.of(f["lastmass"]?.toLongOrNull() ?: 0L),
            )
        }
        if (f["valve"] == "1") return Valve(tile)
        return null
    }

    /**
     * The `k=` reading off a segment record, or null where the file omitted it.
     *
     * Same rule as massNum: the stored figure scales, and it is the ENERGY factor, not the mass one
     * — a conduit's stored heat rode the mass factor until v14 split them, which was correct only
     * while the two units were locked together.
     */
    private fun readSegmentEnergy(
        tokens: List<String>,
        energyScale: Rescale,
        fail: (String) -> Nothing,
    ): Long? {
        val f = fields(tokens.drop(1), fail)
        return f["k"]?.let { energyScale.of(it.toLongOrNull() ?: fail("bad energy '$it'")) }
    }

    private fun readWiring(text: String, fail: (String) -> Nothing): Wiring {
        var wiring = Wiring(emptyMap())
        for (part in text.split(';')) {
            if (part.isEmpty()) continue
            val colon = part.indexOf(':')
            if (colon < 0) fail("expected ACTION:terms, got '$part'")
            val actionName = part.substring(0, colon)
            val action = Action.entries.firstOrNull { it.name == actionName } ?: fail("unknown action '$actionName'")
            val terms = part.substring(colon + 1)
            val triggers = if (terms.isEmpty()) emptyList() else terms.split(',').map { term ->
                readTrigger(term, fail)
            }
            wiring = wiring.with(action, triggers)
        }
        return wiring
    }

    /**
     * One wiring term, reading both spellings.
     *
     * v21 and earlier wrote `SOURCE@weight`, where the weight was a signed permille that scaled the
     * signal. A term carries only a sign now — see [Trigger] — so an old file keeps the sign and
     * drops the magnitude. That is lossy, and it is the loss the change was: a term written `@500`
     * meant "at half strength", and there is no half strength to restore it to.
     *
     * ⚠️ **It is behaviour-preserving where behaviour was reachable.** Every weight the UI could
     * actually produce was ±250..±1000, and against a network that now only ever carries 0 or
     * [SignalField.FULL] the sign is the whole of what those terms decided. A vessel wired
     * `ALWAYS + WIRE@-1000` stops when its wire goes live exactly as it did; one wired `ALWAYS@500`
     * runs at full rate rather than half, because half is not a thing a wire can ask for any more.
     * No version bump: the sign is recoverable from every file ever written, so there is nothing a
     * migration would get right that this does not.
     */
    private fun readTrigger(term: String, fail: (String) -> Nothing): Trigger {
        val atSign = term.indexOf('@')
        if (atSign < 0) {
            val negated = term.startsWith('!')
            return Trigger(readSource(if (negated) term.substring(1) else term, fail), negated)
        }
        val weight = term.substring(atSign + 1).toIntOrNull() ?: fail("bad weight in '$term'")
        return Trigger(readSource(term.substring(0, atSign), fail), negated = weight < 0)
    }

    /**
     * A term's source, reading both spellings.
     *
     * v10 and earlier wrote a colour: `ALWAYS` for the constant and one of six colours otherwise.
     * Every colour becomes [SignalSource.Wire], which is **lossy on purpose** — six global channels
     * cannot survive into a world that has none, and there is no wire in an old file to guess at.
     *
     * It is nonetheless behaviour-preserving in the case that matters. An unwired `Wire` term reads
     * 0, which is exactly what an unemitted channel read, so a vessel wired `ALWAYS − RED` goes on
     * running at full until its owner lays the run that stops it.
     */
    private fun readSource(name: String, fail: (String) -> Nothing): SignalSource =
        when (name) {
            "ALWAYS", "Always" -> SignalSource.Always
            "WIRE", "Wire" -> SignalSource.Wire
            // The v10 palette. Named rather than matched loosely, so a typo is still an error.
            "Red", "Green", "Blue", "Amber", "Cyan", "Violet" -> SignalSource.Wire
            else -> fail("unknown signal source '$name'")
        }

    /**
     * ⚠️ [scale] is **1** for a mixture that states proportions and the file's factor for one that
     * states a mass. The same syntax carries both — air in a tile is mass, a rock's `oreComposition`
     * is parts — so the distinction cannot be made here and every caller has to declare it.
     */
    /**
     * Species names that older saves use, and what they mean now.
     *
     * The species table was rebuilt around real minerals in version 17, and two entries could not
     * survive it. `Silica` was a compound sitting in a list of elements — it was always SiO₂, at
     * quartz's density, so it becomes [Species.Quartz] with every number unchanged and no rescale.
     * `RareEarth` was a fiction: there is no such element, and its 140 g/mol and 7010 kg/m³ were a
     * blend of cerium's mass and neodymium's density. It becomes [Species.Monazite], the ore a
     * player mining "rare earth" was standing in.
     *
     * ⚠️ Monazite is **235** g/mol against RareEarth's 140, so a loaded world's rare-earth pile has
     * the same *mass* — which is what is conserved and what the ledger checks — but a different
     * particle count. Nothing on a belt or in a stockpile notices; a tile of it in the atmosphere
     * would read a lower pressure. That is a real difference and it is the right one, because the
     * old number described nothing.
     *
     * Kept as a map rather than a version-gated branch because a name is a name whatever version
     * wrote it, and a file that says `Silica` means quartz regardless of what else it says.
     */
    private val RENAMED_SPECIES: Map<String, Species> = mapOf(
        "Silica" to Species.Quartz,
        "RareEarth" to Species.Monazite,
    )

    /**
     * A mixture read from an air or pipe record, handed over one [Fluid] at a time — and **refused**
     * if it names anything that cannot be one.
     *
     * A file is the one place a solid can still try to get into the atmosphere: the field is typed
     * now, but a save names its species in text, and a hand-written or older file may say
     * `Serpentine=5` in an `air` line. Dropping that quietly is the worse failure of the two — it
     * loses mass from the ledger and reads as a bug somewhere else entirely — so it fails loudly
     * here, at the line that says it, which is what would have caught the original invariant.
     */
    private inline fun readFluids(mix: Mixture, fail: (String) -> Nothing, put: (Fluid, Long) -> Unit) {
        for (s in Species.ALL) {
            val mass = mix[s]
            if (mass == 0L) continue
            val fluid = s.fluid ?: fail("'${s.name}' cannot be in the air or a pipe")
            put(fluid, mass)
        }
    }

    private fun readMixture(text: String, scale: Rescale, fail: (String) -> Nothing): Mixture {
        if (text == "-") return Mixture.EMPTY
        val masses = LongArray(Species.COUNT)
        var energy = 0L
        for (part in text.split(',')) {
            val eq = part.indexOf('=')
            if (eq < 0) fail("expected SPECIES=mass, got '$part'")
            val name = part.substring(0, eq)
            if (name == "energy") {
                val energyPart = part.substring(eq + 1).toLongOrNull() ?: fail("bad energy in '$part'")
                if (energyPart < 0L) fail("negative energy in '$part'")
                energy += energyPart
            } else {
                val species = Species.ALL.firstOrNull { it.name == name }
                    ?: RENAMED_SPECIES[name]
                    ?: fail("unknown species '$name'")
                val mass = part.substring(eq + 1).toLongOrNull() ?: fail("bad mass in '$part'")
                if (mass < 0L) fail("negative mass in '$part'")
                masses[species.ordinal] += scale.of(mass)
            }
        }
        return Mixture.of(masses, energy)
    }

    /**
     * A pile of matter, read.
     *
     * ⚠️ **A leading `FORM/` is read and discarded.** Every file written before form was deleted
     * names one here, and what it named is not recoverable into anything — there is nowhere left to
     * put it. Dropping it silently is the migration, and it is lossless in the only sense that
     * matters: form never affected a gram of mass or a joule of energy, so a save round-trips to
     * exactly the same world it did before.
     */
    private fun readResource(text: String, scale: Rescale, fail: (String) -> Nothing): Mixture {
        val slash = text.indexOf('/')
        val body = if (slash < 0) text else text.substring(slash + 1)
        return readMixture(body, scale, fail)
    }

    private fun readPacket(text: String, scale: Rescale, fail: (String) -> Nothing): Packet = when {
        text.startsWith("S:") -> SolidPacket(readResource(text.substring(2), scale, fail))
        text.startsWith("F:") -> FluidPacket(readMixture(text.substring(2), scale, fail))
        else -> fail("expected S: or F:, got '$text'")
    }

    private fun fields(tokens: List<String>, fail: (String) -> Nothing): Map<String, String> {
        val out = HashMap<String, String>()
        for (token in tokens) {
            val eq = token.indexOf('=')
            if (eq < 0) fail("expected key=value, got '$token'")
            out[token.substring(0, eq)] = token.substring(eq + 1)
        }
        return out
    }
}


/**
 * A world written against the grid-origin anchor, read into the centre-of-mass one.
 *
 * The world does not change: every tile, every body and every relative pose stays exactly where the
 * file put it. What changes is which point each `position` names, so each one is re-read under the
 * old law and re-stated under the new — see `PLAN_com_anchored_frames.md` and [Save.COM_ANCHOR_VERSION].
 *
 * ⚠️ **The dock link is recomputed rather than converted.** It recorded the station's *origin* in
 * the vessel's frame; it records the station's centre now, and the difference is that centre turned
 * by the station's own relative angle. Deriving that by hand is a rotation to get wrong for no gain,
 * when the two migrated poses can simply be asked — which is also exactly what [Weld.capture] does,
 * so a migrated berth and a fresh one are computed by the same expression.
 */
private fun VesselState.comAnchored(): VesselState {
    /** The file's numbers under the law they were written with: no local centre is the origin. */
    fun asWritten(x: Long, y: Long, at: Coord) = Pose(x, y, at, 0L, 0L)

    val shipWasAt = asWritten(positionX, positionY, ang)
    val about = distribution
    val movedBodies = bodies.map {
        val was = asWritten(it.positionX, it.positionY, it.ang)
        it.copy(
            positionX = was.toWorldX(it.about.comX, it.about.comY),
            positionY = was.toWorldY(it.about.comX, it.about.comY),
        )
    }
    val anchored = copy(
        positionX = shipWasAt.toWorldX(about.comX, about.comY),
        positionY = shipWasAt.toWorldY(about.comX, about.comY),
        bodies = movedBodies,
    )

    if (anchored.assembly.isEmpty) return anchored
    val berth = anchored.pose
    // ⚠️ Only welds the **vessel** holds are re-stated here: below [COM_ANCHOR_VERSION] the vessel
    // is the only member anything can hang off, so a deeper chain cannot occur in such a file.
    return anchored.copy(
        assembly = Assembly(anchored.assembly.welds.map { weld ->
            val child = movedBodies.firstOrNull { it.station?.id == weld.childId } ?: return@map weld
            Weld(
                childId = weld.childId,
                parentId = weld.parentId,
                childX = berth.toLocalX(child.comX, child.comY),
                childY = berth.toLocalY(child.comX, child.comY),
                childAng = weld.childAng,
                portTile = weld.portTile,
                nodeIndex = weld.nodeIndex,
            )
        }),
    )
}
