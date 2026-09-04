package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Wiring

/**
 * **The one machine that turns a room's gas into cargo.**
 *
 * Draws from the tile it faces, fills its own store at a rate, and hands **whole packets** to the
 * rail network through an output port on its own tile — a continuum-to-packet converter, which is
 * exactly what an [Extractor] is for rock. That is the whole of it: no pressure, no stall, no
 * gradient to push against, because a belt is not a place that pushes back.
 *
 * ⛔ **It used to fill a pipe**, against a pressure gradient, stalling at four atmospheres. The pipe
 * network is being deleted (`PLAN_fluid_thrusters.md` §9) because a diffusive network equalises and
 * cannot deliver; a rail line moves 800 kg/s where a pipe cell holds 125 g.
 *
 * ⚠️ **It does not separate.** What it packs is whatever the room holds, mixed — a
 * [org.emerge.demo.outofspace.world.machine.Concentrator] is what turns that into oxygen, and it
 * needs no changes to do it because it separates by [org.emerge.demo.outofspace.chem.Mixture.dominant]
 * and is phase-blind.
 */
data class Pump(
    override val center: TileIndex,
    override val facing: Direction,
    override val wiring: Wiring = Wiring.RUNNING,
) : DirectedDeckMachine {
    override val kind: DeckMachineKind get() = DeckMachineKind.Pump
    override fun rotated(): DeckMachine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): DeckMachine = copy(wiring = wiring)
    override fun movedTo(center: TileIndex): DeckMachine = copy(center = center)

    companion object {
        /**
         * **The dial.** How much gas it takes out of the room a tick, at full activation.
         *
         * A quarter of a kilogram — sixteen a second — so a pump fills a 100 kg packet in about six
         * seconds, and it takes some fifty of them to saturate one belt. That is the shape wanted: a
         * gas supply is a *plant* a player builds, not a fitting they bolt on.
         *
         * ⚠️ **Mass, not moles, and it moves with [Budget] like every other rate.** The old
         * `MILLIMOLES_PER_TICK` was molar because its destination was a *pressure*, and a mole is a
         * particle count that must not move when the mass unit does. A packet is a mass, so this is
         * a mass, and the warning that used to live here no longer applies.
         *
         * ⛔ **It will strip a small room in seconds**, since a tile of ordinary air weighs about a
         * kilogram. That is the intended reading rather than an oversight: what a pump is for is a
         * bay full of an asteroid's off-gas, which refills, and a cabin it must not empty is a cabin
         * whose pump is wired to a pressure sensor.
         */
        val MASS_PER_TICK: Long = Capacity.PACKET_MASS / 400L

        /**
         * How much it holds before it stops drawing.
         *
         * Two belt-loads, which is [MACHINE_OUTPUT_CAP]'s size and for its reason: a machine that
         * cannot bank a whole packet cannot ship one, and one that banks many is a warehouse with
         * the wrong name on it.
         */
        val BUFFER_CAP: Long = MACHINE_OUTPUT_CAP
    }
}
