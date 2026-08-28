package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.DeckMachine
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.materialBefore

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
val FIXTURE_MACHINE_METAL: Species = materialBefore(DeckMachineKind.Storage)

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
