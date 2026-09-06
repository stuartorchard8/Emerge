package org.emerge.demo.outofspace.world.machine

/**
 * Machine kinds that take up deck space, storing their matter and energy in the deck layer.
 *
 * ### The two ways a machine can be in the way
 *
 * There used to be one flag, `isPermeable`, and it answered two questions that are not the same
 * one. A smelter standing inside the hull is a solid object an asteroid bounces off, *and* every
 * tile of it is open to the room's air — which is the whole reason it can rust, and the reason it
 * can cook the room it stands in. With one flag it had to be either a wall the air could not reach
 * or a plate a rock fell through, and neither is a smelter.
 *
 * - [preventAirflow] — gas can neither sit in this tile nor cross it. It is what
 *   [org.emerge.demo.outofspace.world.StructureMap] fills against, so it is what decides where a
 *   *room* is, and placing one displaces the air out of its own tiles. Almost nothing sets it: a
 *   wall and a shut airlock, and that is the list.
 * - [preventThoroughfare] — a rigid body can neither sit in this tile nor cross it. It defaults to
 *   [preventAirflow], because a thing that holds the air out is certainly solid, and the interesting
 *   kinds are the ones that set it on its own.
 */
enum class DeckMachineKind(
    val label: String,
    val preventAirflow: Boolean = false,
    val preventThoroughfare: Boolean = preventAirflow,
    /** Whether this machine holds its product when its RUN activation is zero. */
    val gatesOutput: Boolean = false,
    /**
     * Whether this machine will only ever put a **whole packet** on the track.
     *
     * A machine that produces in packets — a concentrator works a charge and hands out one packet of
     * concentrate and one of tailings — never has a part-packet to offer in the first place. An
     * [Extractor] does: it takes rock a whole *cell* at a time and frost by whatever happens to be
     * lying on its plate, neither of which is a round number, so its store is nearly always some
     * packets plus a remainder. Left to dribble, that remainder goes out as a runt lump which owns
     * its tile for good — packets never merge — and the corridor behind it carries a hundred grams
     * where it could have carried a hundred kilograms.
     *
     * So the remainder waits in the hopper for the next bite to top it up, which is where it is
     * useful and where it costs nothing.
     *
     * ⚠️ **This states the rule; it does not answer it.** Whether a given part-packet may go is
     * asked of the world rather than of the kind — a machine being taken apart lets go of any size,
     * and so does one whose only customer is short of less than a packet, because a runt sized to a
     * real appetite is consumed rather than left standing. See `Work.holdsBack`, where each
     * exemption is argued.
     */
    val shipsWholePackets: Boolean = false,
) {
    Hull("HULL", preventAirflow = true),
    Airlock("AIRLOCK", preventAirflow = true),
    Vent("VENT"),
    Storage("STORAGE", preventThoroughfare = true, gatesOutput = true),
    Sensor("SENSOR"),
    KeyInput("BUTTON"),
    // Whole packets, for an extractor's reason: a pump's store is a hopper filling at a rate,
    // not a pair of finished packets, so a runt lump shipped off the bottom of it would own a
    // tile for good. See `reference_oos_packets_never_merge`.
    Pump("PUMP", shipsWholePackets = true),
    /**
     * A rocket motor: two tiles end to end, and the only kind whose anchor is **not** the middle of
     * its own footprint — see `FootprintShape.Nose`.
     *
     * The tile it is stored at is the chamber, which is what you feed; the second tile is the bell,
     * which juts out into the exhaust direction. Solid to a rock, permeable to gas, because a bell
     * that held the air out could not exhaust into anything.
     */
    Thruster("THRUSTER", preventThoroughfare = true),
    /**
     * A bipropellant rocket: three tiles square, two doors at the back, a chamber in the middle and
     * a bell on the front face — see `Rocket`.
     *
     * Permeable to gas for the thruster's reason: the bell is a tile of the machine, and a nozzle
     * that held the air out could not exhaust into anything. ⚠️ **Not `gatesOutput`** — it has no
     * output port to gate. What it does with no signal is not fire, which is its throttle's business.
     */
    Rocket("ROCKET", preventThoroughfare = true),
    /**
     * Ore in, one species out pure, the rest out of the floor — see `Concentrator`.
     *
     * Whole packets, for the pump's reason rather than the one this machine used to have: its
     * concentrate store is an **accumulator** now, banking the machine's share of each charge until
     * it has a packet of pure metal, so left to dribble it would put 61 kg lumps on the track for
     * ever. It used to hand out one finished packet of each stream per charge and had nothing part
     * sized to hold back.
     */
    Concentrator("CONCENTRATOR", preventThoroughfare = true, gatesOutput = true, shipsWholePackets = true),
    /**
     * Water in, hydrogen out of one face and oxygen out of another — see `Electrolyzer`.
     *
     * Whole packets, for the pump's reason rather than the concentrator's: its two stores are
     * hoppers filling at a rate, not a pair of finished packets, and the 1:8 mass split means a
     * charge handed out whole would make a runt of the hydrogen every single time.
     */
    Electrolyzer("ELECTROLYZER", preventThoroughfare = true, gatesOutput = true, shipsWholePackets = true),
    Furnace("FURNACE", preventThoroughfare = true, gatesOutput = true),
    Extractor("EXTRACTOR", gatesOutput = true, shipsWholePackets = true),

    /**
     * Three tiles end to end, and the only kind whose footprint is a line rather than a square —
     * see `FootprintShape.Span`.
     *
     * Permeable, because a bridge is a gantry and not a block: it is mostly the air under it, so it
     * divides no room and displaces no gas. What it *does* claim is the floor, which is the whole
     * point of it being here — a bridge can no longer be stacked on another building or on another
     * bridge, so crossing a run costs three tiles of deck.
     */
    Bridge("BRIDGE"),

    /**
     * An instrument standing over a run, reading what goes past. Permeable: a belt with a gauge on
     * it is still a corridor.
     */
    Gauge("GAUGE"),

    /** Pushes charge onto the power run under it — see [SolarPanel]. */
    SolarPanel("SOLAR"),

    /**
     * An opening between the pipe under it and the room it stands in. Permeable, and that is not a
     * convenience — a valve that displaced the air out of its own tile would open onto the vacuum it
     * had just made. See [Valve].
     */
    Valve("VALVE"),

    /**
     * Three tiles square: the ship's mouth onto somebody else's economy — `PLAN_economy.md` §5.
     *
     * Solid, like every other installation of its size — you do not walk through a docking collar.
     * ⚠️ **Deliberately NOT `preventAirflow` yet.** Only the hull and the airlock hold air out, and
     * making a third kind do so changes the vessel's room topology — which is a real change, and it
     * has nothing to do with money. Whether a docking port is part of the hull boundary is a
     * question for the docking increment, which is the first one where the answer matters.
     */
    DockingPort("DOCK", preventThoroughfare = true, gatesOutput = true),
    ;

    companion object {
        val ALL: List<DeckMachineKind> = entries.toList()
    }
}
