package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.DeckMachine
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.Furnace
import org.emerge.demo.outofspace.world.machine.Sensor
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.SignalField
import org.emerge.demo.outofspace.world.SpeciesFilter
import org.emerge.demo.outofspace.world.Wiring
import org.emerge.demo.outofspace.world.conduitBillOfMaterials
import org.emerge.demo.outofspace.world.conductanceOf
import org.emerge.demo.outofspace.world.fillPermille
import org.emerge.demo.outofspace.world.heatCapacityOf
import org.emerge.demo.outofspace.world.materialBefore
import org.emerge.demo.outofspace.world.tileBillOfMaterials
import org.emerge.demo.outofspace.FORMER_MATERIALS

/**
 * **What the fixtures build out of** — a convention of this test suite, and not a rule of the game.
 *
 * ⛔ The game has no answer to "what is a Storage normally made of": every built thing states its
 * own substance, and `DeckArray.plusAssign` was deleted along with the table that used to answer for
 * it. Two hundred fixtures do not each have an opinion about metallurgy, though. They were written
 * when that table existed and their masses, heats and bills were all measured against it, so what
 * they mean by `deck += Storage(...)` is *what it used to mean* — which is a fact about the fixtures'
 * history, exactly as [materialBefore] is a fact about old save files. Reusing that function rather
 * than restating it keeps one table with one meaning: **historical, never normative.**
 *
 * ⚠️ A test that is *about* material choice states its material and does not come through here. This
 * exists so that a test about heat, or ports, or rotation, does not have to have an opinion.
 */
operator fun DeckArray.plusAssign(m: DeckMachine) {
    stand(m, withCasing = true, material = materialBefore(m.kind))
}

/** The same convention for a machine standing without its metal — a ghost. */
fun DeckArray.standGhost(m: DeckMachine) {
    stand(m, withCasing = false, material = materialBefore(m.kind))
}

/** And for a length of track: what a fixture means by "a rail", unless it says otherwise. */
fun railSegment(links: Int = 0, deconstructing: Boolean = false): Segment =
    Segment(Conduit.Rail, links, deconstructing, materialBefore(Conduit.Rail))

/** What a fixture means by a length of [conduit]. */
fun segmentOf(conduit: Conduit, links: Int = 0): Segment =
    Segment(conduit, links, material = materialBefore(conduit))

/** Named so a fixture can say what it means without importing the migration. */
val FIXTURE_RAIL_METAL: Species = materialBefore(Conduit.Rail)
val FIXTURE_MACHINE_METAL: Species = materialBefore(DeckMachineKind.Warehouse)

/** What a brush would once have laid, for the fixtures that do not care. */
fun materialOfBrush(brush: Brush): Species = when (brush) {
    is Brush.Run -> materialBefore(brush.conduit)
    is Brush.Building -> materialBefore(brush.kind)
}

/**
 * [Edit.Place] for a fixture with no opinion about substance.
 *
 * A test that is *about* material choice writes `Edit.Place(...)` out in full and names one; this is
 * for the hundred that are about ports, or air, or footprints, and want the placement they always
 * had. Same convention as [plusAssign], and the same reason.
 */
fun fixturePlace(tile: TileIndex, brush: Brush, facing: Direction): Edit.Place =
    Edit.Place(tile, brush, facing, materialOfBrush(brush))

/** [Edit.Lay] for the same fixtures — a drag that lays what a drag used to lay. */
fun fixtureLay(from: TileIndex, to: TileIndex, conduit: Conduit = Conduit.Rail): Edit.Lay =
    Edit.Lay(from, to, conduit, materialBefore(conduit))

/*
 * ── Reference figures ─────────────────────────────────────────────────────────
 *
 * `DeckMachineKind.massPerTile`, `.capacityPerTile` and `.conductance` — and their `Conduit` twins —
 * were deleted with the table that told them what a kind was made of. A good many tests used them
 * not as a rule but as *a number to measure against*: how heavy is a tile of machinery, how much
 * heat does a length of rail hold, is a rock heavier than a hull plate. That is a legitimate thing
 * for a test to want and an illegitimate thing for the game to answer, so it is answered here, out
 * of the same historical table the rest of these fixtures use.
 */

/** What one tile of [kind] weighs, made of what a fixture assumes it is made of. */
fun fixtureMassPerTile(kind: DeckMachineKind): Long =
    tileBillOfMaterials(kind, materialBefore(kind)).total

/** Millijoules per kelvin for one such tile. */
fun fixtureCapacityPerTile(kind: DeckMachineKind): Long =
    heatCapacityOf(tileBillOfMaterials(kind, materialBefore(kind)))

/** What crosses a contact of one. */
fun fixtureConductance(kind: DeckMachineKind): Long =
    conductanceOf(materialBefore(kind), kind.fillPermille)

/** The same three for a length of bare conduit. */
fun fixtureMassPerTile(conduit: Conduit): Long =
    conduitBillOfMaterials(conduit, materialBefore(conduit)).total

fun fixtureCapacityPerTile(conduit: Conduit): Long =
    heatCapacityOf(conduitBillOfMaterials(conduit, materialBefore(conduit)))

fun fixtureConductance(conduit: Conduit): Long =
    conductanceOf(materialBefore(conduit), conduit.fillPermille)

/**
 * The five substances the deleted `Material` enum named.
 *
 * ⚠️ **A list of what the ship used to be able to be made of, kept for the tests that swept it.**
 * The game has no such shortlist and must not grow one back — see `Material.kt`'s note where the
 * enum stood. Anything asserted over this is asserted about five species that happen to be
 * interesting, not about a category the game recognises.
 */
val FORMER_MATERIALS: List<Species> = listOf(
    Species.Steel, Species.Iron, Species.Copper, Species.Titanium, Species.Firebrick,
)

/*
 * ── Machines whose settings the fixtures have no opinion about ────────────────
 *
 * The same bargain as `plusAssign`, one level down. A [Storage] and a [Sensor] each grew a dial the
 * player is meant to turn, and neither has a default, for the good reason that the two places the
 * game builds one disagree: a warehouse the player drops is born tending itself, and a warehouse the
 * starter vessel lays down is not. That is a choice about play, and it should be made where a
 * warehouse is placed rather than hidden in the constructor.
 *
 * Two hundred fixtures are not making that choice. They were written before the dials existed and
 * they mean the machine they were written against, so they say so here, once — **historical, never
 * normative**, exactly as the material table above is.
 *
 * ⚠️ A test that is *about* a dial sets it and does not come through here.
 */

/**
 * A warehouse that minds only what it is told to — what `Storage(tile, facing)` used to be.
 *
 * Auto-lock and auto-unlock both off, because before they existed a warehouse never locked itself
 * onto a species nor let one go: it took what it was sent and held what it was given, and every
 * fixture that watches material reach a tank was measured against that. [org.emerge.demo.outofspace.world.starterVessel] makes the
 * same choice for the same reason.
 */
fun fixtureStorage(
    center: TileIndex,
    facing: Direction,
    filter: SpeciesFilter? = null,
    /** Which of the three sizes — see [Storage]. A fixture that does not say means the 3×3. */
    kind: DeckMachineKind = DeckMachineKind.Warehouse,
): Storage = Storage(center, facing, kind, filter = filter, autoLock = false, autoUnlock = false)

/**
 * **A consumer that will never stop wanting and will never take another gram** — what a jam is made
 * of, when a test needs one.
 *
 * ⛔ **A full warehouse is not this, and used to be.** Four tests built their jam by filling a tank,
 * because a store's appetite was endless: demand ran the length of the line while its door refused
 * at the brim, and everything between backed up. A store meters itself now — it asks for the room it
 * has and no more — so a full one is not congestion, it is a **dead end**, and material bound for it
 * simply never leaves the source. That is the whole point of the change, and it took the old jam
 * fixture with it.
 *
 * So the jam is built out of the thing the demand pass still calls momentarily full: a **working
 * machine that is switched off**. It states [org.emerge.demo.outofspace.world.Acceptance.ANYTHING]
 * like every other machine, so the network routes at it the length of the line; its input fills to
 * `MACHINE_BUFFER_CAP` and stays there, because a machine told to stop does no work. Hungry for
 * ever, and unable to take anything this tick or any other.
 *
 * ⚠️ **A [Furnace] because its footprint and its doors are a warehouse's** — 3×3, in at
 * `-behind` and out at `+ahead` — so it drops into a fixture where a `fixtureStorage` stood without
 * moving a rail. Nothing about the heat matters here: switched off it never lights, so it conserves
 * what it is handed and the balance ledger sees an ordinary machine holding an ordinary buffer.
 *
 * ⚠️ **[Wiring] with no terms at all, which is off.** Not `RUNNING`, and not a wire that happens to
 * be dead — "no terms means off" is [Wiring.isOn]'s own rule, so this cannot be switched back on by
 * anything the world does.
 */
fun fixtureStalledSink(center: TileIndex, facing: Direction): Furnace =
    Furnace(center, facing, wiring = Wiring(emptyMap()))

/**
 * A sensor with the dials wide open — as near as there is to what `Sensor(tile, facing)` used to be.
 *
 * ⚠️ **There is no value here that restores the old behaviour, because the old behaviour is gone.**
 * A sensor used to put the reading itself on the wire, so a half-full tank drove a half-strength
 * signal; it now compares that reading against [Sensor.threshold] and raises [SignalField.FULL] or
 * nothing. Analogue became binary, and no threshold makes a wire carry 500 again.
 *
 * So this states the *most permissive* tuning rather than an equivalent one: `threshold = 0` fires
 * on any reading above nothing at all, and no delay or release means it answers the same tick it
 * senses. That is what the wiring fixtures want — they fill a tank and ask whether the machine
 * downstream noticed — and it is the tuning [org.emerge.demo.outofspace.world.starterVessel] gives its own demo sensor. A fixture
 * that asserts on the *strength* of a signal is asserting about the sensor that was replaced.
 */
fun fixtureSensor(
    center: TileIndex,
    facing: Direction,
): Sensor = Sensor(center, facing, threshold = 0, delay = 0, release = 0)
