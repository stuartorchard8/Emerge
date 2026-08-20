package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Conduit

/**
 * Which left-click means what.
 *
 * Tools rather than modifier keys, because each of these is a mode you stay in for a while and a
 * held key is a poor way to express that — and there is no modifier key on a phone. It is also what
 * frees the right mouse button for panning: deleting used to be right-click, which meant the one
 * button every other game pans with was spent on a gesture that now has a tool of its own.
 */
enum class Tool(val label: String) {
    /**
     * Reads the world and changes nothing — the default, and the one tool that is always safe.
     *
     * ⛔ **It replaced WIRE, it is not a rename of it.** Wiring was a tool because a wiring editor
     * needed a selected machine to edit, and selection was the wire tool's private business. Once
     * every machine panel wanted a selection that reasoning collapsed: wiring is one of the several
     * things a *building* has, alongside its thermostat, its lock and everything in its buffers, and
     * all of them belong in one place. That place is the DECK layer of the inspector — see
     * [InspectLayer].
     *
     * A click picks a tile and its first readable layer; clicking the same tile again steps to the
     * next layer that has anything to say. See [OutofspaceController.inspect].
     */
    Inspect("INSPECT"),
    Build("BUILD"),
    Delete("DELETE"),

    /**
     * Calls off a deconstruction — see [Edit.Cancel].
     *
     * Its own tool rather than a modifier on DELETE, because it is the only way to undo a mark and a
     * player who has condemned the wrong building needs to be able to reach for it deliberately.
     */
    Cancel("CANCEL"),

    /**
     * Severs joins without taking anything up — drag across a belt and the belt is in two pieces.
     *
     * The exact opposite of the [Build] gesture, which is what makes it worth its own tool. Conduit
     * connects by being *drawn*, never by touching, so a run is only a run where the player dragged;
     * this is the other half of that idea, and until it existed the only way to break a line was to
     * delete a tile of it and lay it again, which meant paying for the tile twice and, outside
     * creative, waiting for a ghost to build itself back.
     *
     * **Every join of every tile the drag passes over**, not merely the joins along the drag. A
     * stroke *across* a belt is the gesture this exists for, and one that only cut the edges running
     * parallel to the stroke would do nothing at all in exactly the case it is reached for.
     *
     * ⚠️ One conduit at a time — see [OutofspaceController.cutConduit]. A wire is the one fitting
     * that still shares its tile with a belt, and a tool that cut both would take down a signal
     * network as a side effect of tidying the track. Same reasoning as [DeleteLayer.Wire].
     */
    Cut("CUT"),

    /**
     * The atmosphere injector: hold over a permeable tile and gas appears there, a kilogram a tick.
     *
     * A **debug** tool and labelled as one, alongside the debug engine and the rock drop. It mints
     * matter, which is the one thing this game's ledgers exist to forbid, so what it actually does is
     * mint it *and admit to it* — see [Edit.Inject] and [org.emerge.demo.outofspace.world.VesselState.injectedAirMass].
     */
    Inject("INJECT"),

    /**
     * The water injector: hold over a permeable tile and liquid water appears there.
     *
     * The same debug tool as [Inject] and booked the same way, but it exists for a different reason
     * — there is no other way to get a liquid into the world, and the liquid is the whole question
     * the equation of state was built to answer. Without it the phase machinery can only be exercised
     * from tests.
     *
     * ⚠️ The water arrives at [Edit.WATER_INJECT_KELVIN], well below room temperature, because this
     * model boils water near −33 °C. See that constant.
     */
    InjectWater("WATER"),
    ;

    companion object {
        /**
         * What [Cut] will sever, and in the order the key cycles them.
         *
         * ⚠️ **`Conduit.Power` is deliberately absent**, exactly as it is from [DeleteLayer]: nothing
         * lays it yet, and a key that quietly reaches a fourth network is how a player loses a run
         * of cable without ever learning what did it.
         */
        val CUTTABLE: List<Conduit> = listOf(Conduit.Rail, Conduit.Pipe, Conduit.Signal)
    }
}

/**
 * Which layer the delete tool takes off — the ONI answer to "a tile is not one thing".
 *
 * A tile can hold a bridge, a pipe, a rail and a machine at once, and until now the only way to
 * reach the track threaded under a smelter was to click repeatedly and hope: [Top] peels one layer
 * per click in a fixed order, which is safe and blind. Naming the layer makes it aimed instead —
 * "take the pipes out of this room and leave everything else" is one drag rather than an audit.
 */
enum class DeleteLayer(val label: String) {
    /** One layer per click, topmost first — bridge, then conduit, then the machine underneath. */
    Top("TOP"),
    Bridge("BRIDGE"),
    Rail("RAIL"),
    Pipe("PIPE"),

    /**
     * The signal network — what the WIRE brush lays, and the layer most likely to be buried, since
     * a wire is the only fitting that still shares its tile with a belt.
     *
     * Named WIRE rather than SIGNAL because that is the brush that lays it. `Conduit.Power` is not
     * in this and has no tool of its own: nothing lays it yet, and one key taking down two networks
     * is the sort of thing a player discovers by losing a run of cable.
     */
    Wire("WIRE"),

    /** The building, straight through whatever is threaded over it. */
    Deck("DECK"),

    /** Everything on the tile, in one click. */
    All("ALL"),
}
