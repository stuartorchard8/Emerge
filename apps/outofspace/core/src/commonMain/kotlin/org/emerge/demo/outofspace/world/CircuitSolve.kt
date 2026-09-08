package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.scaledRatio

/**
 * **Kirchhoff over the contact graph**, solved fresh every tick.
 *
 * Increment 2 of `PLAN_power_network.md`. [circuitOf] says what is wired to what; this says what the
 * wiring is doing.
 *
 * ### ⛔ The wire holds nothing
 *
 * The self-capacitance of a metre-scale conductor is about 111 pF — some 1e9 electrons at 1.23 V,
 * against 6.2e18 a second for one amp. **The entire stored charge of a tile of wire is a tenth of a
 * nanosecond of its own current**, and the RC time constant of a copper run is femtoseconds. Against
 * a tick the charge distribution is instantaneous, so there is no state on the network and nothing
 * to relax: the potentials are whatever satisfies Kirchhoff *now*.
 *
 * ⚠️ That is the whole reason `SETTLING_TICKS` is gone rather than retuned. It was never modelling a
 * quantity; it was a stabiliser for an explicit solve, and its diffusive `L² × SETTLING_TICKS`
 * settling put a sixty-tile hull some 29,000 ticks from equilibrium.
 *
 * ### ⛔ Bounded sweeps, and the residual is a term rather than a rounding error
 *
 * ⚠️ **Convergence is not a perf knob here; it decides whether the ledger is an identity.** A fully
 * converged solve satisfies KCL exactly and closes its energy ledger by **Tellegen's theorem**. An
 * unconverged one does neither, and its KCL imbalance *is* charge appearing and vanishing.
 *
 * So [SWEEPS] is bounded and the imbalance is **accounted** — [Solution.residual] — rather than
 * swept up. ⭐ This codebase has done exactly this once already: `reconciledMass` is why the mass
 * balance has four terms instead of three, and it exists so drift is *visible*. ⛔ A residual nobody
 * watches is a leak with a name on it, which is why `CircuitSolveTest` puts a bound on it.
 *
 * ### ⭐ Why no component ever needs a current balance imposed on it
 *
 * Every source is a **two-terminal** device: what it pushes in at one end it takes out at the other.
 * So the injected current of any component sums to zero by construction, the pinned reference is a
 * pure gauge choice, and a nonzero residual at the pin is a real signal rather than an artefact of
 * pinning. That is a dividend of the two-terminal decision and it is worth knowing it was not free.
 */
object CircuitSolve {

    /**
     * **Potentials are microvolts**, and the scale is stated rather than inherited.
     *
     * ⛔ Not millivolts, which is what the chemistry speaks. A node's update floors a division, so
     * the quantisation of the potential is the floor of the KCL residual: at millivolt resolution
     * the flooring error reaches some 2% of a cell's own current, and at microvolt resolution it is
     * under two parts in ten thousand. See `NUMERIC_LIMITS.md` §13. ⚠️ `Frac` is no use here — it
     * holds about [-2, 2].
     */
    const val MICROVOLTS_PER_MILLIVOLT = 1_000L

    /**
     * ⛔ **The bound a potential is clamped to** — a thousand volts, which is generous for a vessel
     * whose only stated potential is water's 1.23.
     *
     * **Derived from the overflow, in `HEATER_POWER`'s idiom.** The largest product formed here is a
     * node's `Σ G·V`: the most conductive thing the table can build is a silver hull plate at
     * `electricalConductanceOf` 3.52e6 (measured, `NUMERIC_LIMITS.md` §13), a node reaches degree
     * seven where four layers meet a terminal, and `7 × 3.52e6 × 1e9` is 2.5e16 against a `Long`'s
     * 9.2e18 — a margin of 375.
     *
     * ⚠️ **A power is never formed as `I × ΔV` directly.** That product is 1.4e25 at these bounds
     * and would wrap; it goes through [scaledRatio], which is the same hazard `seriesConductance`
     * documents one level down.
     */
    const val MAX_MICROVOLTS = 1_000_000_000L

    /**
     * **How far news travels in one tick**, and the only number here that is chosen.
     *
     * A Jacobi sweep moves a change one node along, so this is a propagation speed: four tiles a
     * tick crosses any vessel in well under a second, which is below what a player can see. ⚠️ It is
     * *not* a convergence guarantee and is not meant to be — what happens when four sweeps are not
     * enough is that [Solution.residual] rises and the tripwire notices, which is the whole point of
     * accounting it.
     *
     * ⭐ **Seeding from the previous tick is what makes this cheap.** A steady state is already the
     * answer, so the sweeps confirm it rather than finding it, and only a *change* costs anything.
     *
     * ⚠️ **Four is enough only because the graph is reduced first** — see [reduceToJunctions]. On the
     * raw graph it is nowhere near enough, and measuring that is what found the reduction: a
     * thirty-tile run was still 53% out after two hundred warm ticks, because Jacobi on a chain
     * needs sweeps in proportion to its length *squared*. That is the same `L²` wall the capacitive
     * model had, and solving per tick does not on its own escape it.
     */
    const val SWEEPS = 4

    /**
     * **The resistance scale the chain reduction sums in.**
     *
     * A series combination adds *resistances*, so a reduction has to leave conductance for as long
     * as it takes to add them up. Sized against both ends of the measured conductance range
     * (`NUMERIC_LIMITS.md` §13): the worst metal a cable can be drawn from conducts 2185, so its
     * resistance is 4.6e11 and ten thousand tiles of it still sum inside a `Long`; the best is a
     * silver hull plate at 3.5e6, whose resistance is 2.9e8 and still has nine figures of room to
     * distinguish it from its neighbours.
     */
    const val RESISTANCE_UNIT = 1_000_000_000_000_000L

    /**
     * **What one unit of `current × microvolt` is worth in the game's energy unit.**
     *
     * ⛔ **Not anchored to a joule, and stated as such.** Conductance comes off `electricalConductanceOf`,
     * whose scale is siemens-per-metre times a fill fraction, and nothing yet says what one of those
     * times one microvolt is worth in `Budget`'s centijoules. What increment 2 needs is that the
     * energy ledger *closes* — that every unit the sources put in comes out as heat somewhere — and
     * that is true in any consistent unit.
     *
     * ⚠️ **`PLAN_power_network.md` increment 5 is where this becomes a real number**, against
     * `HEATER_POWER`, which is the one machine whose draw is derived from a physical climb rate.
     * This constant is the lever, exactly as `CHARGE_PER_FACE` was meant to be.
     */
    const val POWER_PER_UNIT = 1_000_000L
}

/**
 * A two-terminal source: it drives [emfMicrovolts] from [fromNode] toward [toNode], through its own
 * [conductance].
 *
 * ⚠️ **The internal conductance is not optional.** A source with none is a voltage source that can
 * deliver unbounded current into a short, which is both unphysical and a division by zero waiting to
 * happen. A panel's is set by the light; a cell's is its electrolyte.
 */
class Source(
    val fromNode: Int,
    val toNode: Int,
    val emfMicrovolts: Long,
    val conductance: Long,
)

/**
 * What one solve produced. Arrays are indexed as [Circuit]'s are — [potential] by node,
 * [edgeCurrent] by circuit edge, [sourceCurrent] by the source list handed in.
 */
class Solution(
    /** Microvolts at each node. */
    val potential: LongArray,
    /** Current on each resistive edge, positive from `edgeA` to `edgeB`. */
    val edgeCurrent: LongArray,
    /** Current through each source, positive out of its `fromNode`. */
    val sourceCurrent: LongArray,
    /**
     * ⛔ **The named term.** Summed absolute KCL imbalance over every node — the charge this solve
     * could not account for, in current units. Zero is the converged answer; a bound on it is what
     * `CircuitSolveTest` asserts, because drift that nobody prints is drift that nobody finds.
     */
    val residual: Long,
    /** What each resistive edge dissipated, in the units [CircuitSolve.POWER_PER_UNIT] names. */
    val edgePower: LongArray,
    /** What each source's internal resistance dissipated, same units. */
    val sourcePower: LongArray,
    /** What each source's EMF did, same units — the work that went in. */
    val sourceWork: LongArray,
) {
    val dissipated: Long get() = edgePower.sum() + sourcePower.sum()
    val supplied: Long get() = sourceWork.sum()
}

/**
 * Solve [circuit] with [sources] on it, warm-started from [seed] where one is given.
 *
 * ⚠️ **Decided from the whole state and applied at once** — this reads a world and produces an
 * answer, which is the *decide* half of `PLAN_one_tick_causality.md`'s rule and not an update.
 */
fun solveCircuit(
    circuit: Circuit,
    sources: List<Source> = emptyList(),
    seed: LongArray? = null,
): Solution {
    val n = circuit.nodeCount
    val v = LongArray(n)
    if (seed != null) {
        for (i in 0 until minOf(n, seed.size)) v[i] = clampPotential(seed[i])
    }

    val chains = reduceToJunctions(circuit, sources)

    // ── The gauge. One node per *supply group* held at zero: a floating resistive network determines
    // potentials only up to a constant, so something has to name the constant. ⚠️ Nothing physical
    // rests on which node — every device reads a difference across itself. ⚠️ Pinned among the
    // junctions, because the interior of a chain is interpolated rather than solved and pinning one
    // would bend the line it sits on.
    //
    // ⛔ **A supply group, not a component, and the difference is a bug this had.** [Circuit]'s
    // components are joined by *conductor*; a source is not one. Two stubs with a panel across them
    // and nothing else joining them are two components, and pinning each of them separately holds
    // both ends of the panel at zero — so an open-circuit panel drove **nothing** instead of sitting
    // at its stall. Found by `SolarPanelTest :: an open circuit panel sits at its open circuit
    // voltage`, which is exactly the case a load would have hidden.
    val group = IntArray(circuit.componentCount) { it }
    fun rootOf(x: Int): Int {
        var i = x
        while (group[i] != i) { group[i] = group[group[i]]; i = group[i] }
        return i
    }
    for (s in sources) {
        val ra = rootOf(circuit.circuitOfNode(s.fromNode))
        val rb = rootOf(circuit.circuitOfNode(s.toNode))
        if (ra != rb) group[rb] = ra
    }
    val pinned = BooleanArray(n)
    val pinnedOf = IntArray(circuit.componentCount) { -1 }
    for (node in 0 until n) {
        if (!chains.isJunction[node]) continue
        val g = rootOf(circuit.circuitOfNode(node))
        if (pinnedOf[g] == -1) {
            pinnedOf[g] = node
            pinned[node] = true
        }
    }
    for (node in 0 until n) if (pinned[node]) v[node] = 0L

    // ── Denominators are fixed for the whole solve: the conductance meeting each junction, over the
    // *reduced* edges rather than the laid ones.
    val totalG = LongArray(n)
    for (e in chains.edges.indices) {
        val edge = chains.edges[e]
        totalG[edge.a] += edge.g
        totalG[edge.b] += edge.g
    }
    for (s in sources) {
        totalG[s.fromNode] += s.conductance
        totalG[s.toNode] += s.conductance
    }

    val next = LongArray(n)
    repeat(CircuitSolve.SWEEPS) {
        // ⚠️ **Jacobi, from a snapshot** — every node updated against the same previous answer, so
        // the result cannot depend on which node the sweep reached first. Gauss-Seidel would
        // converge about twice as fast and would make the answer a fact about node ordering, which
        // is the bias `stepSolidHeat` refuses for the same reason.
        for (node in 0 until n) next[node] = 0L
        for (e in chains.edges.indices) {
            val edge = chains.edges[e]
            next[edge.a] += edge.g * v[edge.b]
            next[edge.b] += edge.g * v[edge.a]
        }
        for (s in sources) {
            // A branch of EMF E from `from` to `to` carries `G(V_from − V_to − E)` out of `from`.
            next[s.fromNode] += s.conductance * (v[s.toNode] + s.emfMicrovolts)
            next[s.toNode] += s.conductance * (v[s.fromNode] - s.emfMicrovolts)
        }
        for (node in 0 until n) {
            if (pinned[node] || !chains.isJunction[node]) continue
            val d = totalG[node]
            v[node] = if (d <= 0L) 0L else clampPotential(next[node] / d)
        }
    }

    chains.interpolate(v)

    // ── Currents, and the imbalance the sweeps left behind.
    val edgeCurrent = LongArray(circuit.edgeCount)
    val edgePower = LongArray(circuit.edgeCount)
    val balance = LongArray(n)
    for (e in 0 until circuit.edgeCount) {
        val a = circuit.edgeA[e]
        val b = circuit.edgeB[e]
        val drop = v[a] - v[b]
        val i = circuit.edgeG[e] * drop
        edgeCurrent[e] = i
        balance[a] -= i
        balance[b] += i
        // ⚠️ Through [scaledRatio]: `I × ΔV` is 1.4e25 at the stated bounds and would wrap.
        edgePower[e] = scaledRatio(if (i < 0L) -i else i, CircuitSolve.POWER_PER_UNIT, if (drop < 0L) -drop else drop)
    }
    val sourceCurrent = LongArray(sources.size)
    val sourcePower = LongArray(sources.size)
    val sourceWork = LongArray(sources.size)
    for (k in sources.indices) {
        val s = sources[k]
        val drop = v[s.fromNode] - v[s.toNode] - s.emfMicrovolts
        // Positive out of `fromNode` when the EMF is winning, which is the sign a source expects.
        val i = -(s.conductance * drop)
        sourceCurrent[k] = i
        balance[s.fromNode] += i
        balance[s.toNode] -= i
        val mag = if (i < 0L) -i else i
        val loss = if (drop < 0L) -drop else drop
        sourcePower[k] = scaledRatio(mag, CircuitSolve.POWER_PER_UNIT, loss)
        sourceWork[k] = scaledRatio(mag, CircuitSolve.POWER_PER_UNIT, s.emfMicrovolts)
    }
    var residual = 0L
    for (node in 0 until n) residual += if (balance[node] < 0L) -balance[node] else balance[node]

    return Solution(v, edgeCurrent, sourceCurrent, residual, edgePower, sourcePower, sourceWork)
}

private fun clampPotential(v: Long): Long =
    if (v > CircuitSolve.MAX_MICROVOLTS) CircuitSolve.MAX_MICROVOLTS
    else if (v < -CircuitSolve.MAX_MICROVOLTS) -CircuitSolve.MAX_MICROVOLTS
    else v

/**
 * ⭐ **A run of wire with nothing attached to it is one resistor**, and reducing it to one is what
 * makes a bounded number of sweeps enough.
 *
 * ### ⛔ Why this is not an optimisation
 *
 * Jacobi on a chain of `N` nodes needs sweeps in proportion to `N²`, because news travels one node
 * per sweep and has to arrive from both ends. **Measured before this existed**: a thirty-tile run
 * was still 53% out on its current balance after two hundred warm-started ticks, and its energy
 * ledger was 8.5% short. So *"solve per tick"* does not on its own escape the `L²` wall the
 * capacitive model had — it escapes it only once the chain stops being `N` unknowns.
 *
 * ⭐ **And a power network is almost all chain.** Every tile of a run between two junctions has
 * degree two and nothing injecting into it, which is the exact condition under which a series
 * reduction is *exact*: the potential along it falls linearly in accumulated resistance, so the
 * interior is [interpolate]d back rather than solved. What is left to iterate on is the junctions —
 * where runs meet, where a stub ends, and where a source is attached — and there are few of those.
 *
 * ⚠️ **The interior is recovered exactly, not approximately.** A node of degree two with no source
 * carries the same current in as out by definition, so it has no equation of its own to satisfy;
 * its potential is a consequence of its neighbours' and of where along the chain it sits.
 *
 * ### ⚠️ What this does *not* fix
 *
 * Settling still costs sweeps in proportion to the **number of junctions** between two ends, squared
 * — it is the same iteration, on a far smaller graph. A run of any length is now free, because it is
 * one edge; a bus with a dozen machines hung off it is a dozen junctions and takes a second or so of
 * ticks to settle after a change.
 *
 * ⭐ **That is a better place for the cost to live**, because it scales with what the *player* built
 * rather than with how far apart they built it, and because it is bounded by the vessel rather than
 * by the grid. ⚠️ It is also why a cold solve of a branched network is visibly unconverged — a spur
 * trails the node it hangs off by one sweep — and why the residual is accounted rather than assumed
 * away.
 */
internal class Chains(
    val isJunction: BooleanArray,
    val edges: List<ReducedEdge>,
    private val interior: List<ChainRun>,
) {
    /** Fill in every node the reduction removed, by where it sits along its own chain. */
    fun interpolate(v: LongArray) {
        for (run in interior) {
            val from = v[run.from]
            val span = v[run.to] - from
            for (k in run.nodes.indices) {
                // `from + span · r/R`, formed as a ratio first so a long chain cannot wrap.
                v[run.nodes[k]] = clampPotential(from + scaledRatio(run.upTo[k], run.total, span))
            }
        }
    }
}

/** One edge of the reduced graph: two junctions and what the whole run between them conducts. */
internal class ReducedEdge(val a: Int, val b: Int, val g: Long)

/** The nodes a chain swallowed, with the resistance accumulated to each of them from [from]. */
internal class ChainRun(
    val from: Int,
    val to: Int,
    val nodes: IntArray,
    val upTo: LongArray,
    val total: Long,
)

/**
 * Collapse every maximal run of degree-two nodes into a single edge.
 *
 * A node is a **junction** — kept, and iterated on — when it has anything but exactly two edges, or
 * when a source is attached to it. Everything else is interior and gets interpolated.
 */
internal fun reduceToJunctions(circuit: Circuit, sources: List<Source>): Chains {
    val n = circuit.nodeCount
    val degree = IntArray(n)
    for (e in 0 until circuit.edgeCount) {
        degree[circuit.edgeA[e]]++
        degree[circuit.edgeB[e]]++
    }
    val isJunction = BooleanArray(n) { degree[it] != 2 }
    for (s in sources) {
        isJunction[s.fromNode] = true
        isJunction[s.toNode] = true
    }

    // Adjacency, so a chain can be walked without re-scanning the edge list.
    val head = IntArray(n) { -1 }
    val nextOf = IntArray(circuit.edgeCount * 2)
    val other = IntArray(circuit.edgeCount * 2)
    val gOf = LongArray(circuit.edgeCount * 2)
    for (e in 0 until circuit.edgeCount) {
        val a = circuit.edgeA[e]
        val b = circuit.edgeB[e]
        other[2 * e] = b; gOf[2 * e] = circuit.edgeG[e]; nextOf[2 * e] = head[a]; head[a] = 2 * e
        other[2 * e + 1] = a; gOf[2 * e + 1] = circuit.edgeG[e]; nextOf[2 * e + 1] = head[b]; head[b] = 2 * e + 1
    }

    val edges = ArrayList<ReducedEdge>()
    val interior = ArrayList<ChainRun>()
    val walked = BooleanArray(circuit.edgeCount * 2)

    for (start in 0 until n) {
        if (!isJunction[start]) continue
        var arc = head[start]
        while (arc != -1) {
            val thisArc = arc
            arc = nextOf[arc]
            if (walked[thisArc]) continue
            walked[thisArc] = true

            // Walk until the far end is a junction, adding resistance as we go.
            var here = other[thisArc]
            var resistance = resistanceOf(gOf[thisArc])
            val swallowed = ArrayList<Int>()
            val upTo = ArrayList<Long>()
            var arrivedBy = thisArc
            while (!isJunction[here]) {
                swallowed.add(here)
                upTo.add(resistance)
                // A degree-two node has exactly one arc that is not the one we arrived by.
                var chosen = -1
                var scan = head[here]
                while (scan != -1) {
                    if (!walked[scan] && scan != (arrivedBy xor 1)) { chosen = scan; break }
                    scan = nextOf[scan]
                }
                if (chosen == -1) break
                walked[chosen] = true
                walked[chosen xor 1] = true
                resistance += resistanceOf(gOf[chosen])
                arrivedBy = chosen
                here = other[chosen]
            }
            walked[thisArc xor 1] = true

            // ⚠️ A chain that comes back to where it started is a loop hanging off one point. It
            // carries no current whatever is done to it, so it is dropped rather than solved.
            if (here == start) continue
            edges.add(ReducedEdge(start, here, conductanceOf(resistance)))
            if (swallowed.isNotEmpty()) {
                interior.add(
                    ChainRun(
                        from = start,
                        to = here,
                        nodes = IntArray(swallowed.size) { swallowed[it] },
                        upTo = LongArray(upTo.size) { upTo[it] },
                        total = resistance,
                    )
                )
            }
        }
    }
    return Chains(isJunction, edges, interior)
}

/** ⚠️ A zero conductance would be an infinite resistance; the graph never contains one. */
private fun resistanceOf(g: Long): Long =
    if (g <= 0L) CircuitSolve.RESISTANCE_UNIT else CircuitSolve.RESISTANCE_UNIT / g

private fun conductanceOf(r: Long): Long =
    if (r <= 0L) 0L else (CircuitSolve.RESISTANCE_UNIT / r).coerceAtLeast(1L)
