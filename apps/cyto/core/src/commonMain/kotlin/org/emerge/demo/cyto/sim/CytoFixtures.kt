package org.emerge.demo.cyto.sim

/**
 * **Named world states**, built with [CytoTestWorld] and shared between the tests and the agent harness.
 *
 * A fixture is a condition worth returning to: a state that took thought to reach, or that a live world
 * can't be steered into at all. Naming it means a test can assert on it *and* a human can look at it —
 * `cytoAgent --args="fixture divide-contention; shot"` puts the same world on screen that
 * `CytoTestWorldTest` makes its assertions against, which is the whole point of these living in commonMain
 * rather than in a test source set.
 *
 * Add one whenever you catch yourself growing a world in the hope of reaching a state.
 */
object CytoFixtures {

    /**
     * **Two DIVIDE genes, both gated open, contending for one tick's fuel.**
     *
     * The case the strict 1/n energy split exists for, and the reason it was previously pinned only by a
     * unit test on the arithmetic: no live world could be steered into the window that discriminates it.
     *
     * Division is all-or-nothing — it needs `biomass/4` energy units in a single tick and energy can't be
     * banked — and the division phase splits the cell's means by the number of gated-open DIVIDE genes. So
     * with 4000 atoms of biomass (cost 1000) and 1500 each of `r` and `g`:
     *
     *  - **one** open DIVIDE gene draws `min(1500, 1500) = 1500` ≥ 1000 → funded, and it divides;
     *  - **two** open DIVIDE genes each draw `min(1500/2, 1500/2) = 750` < 1000 → **neither** is funded,
     *    and the cell sits there reading perfectly active while never once dividing.
     *
     * That second state is the least legible thing in the game, which is why the gene card flags it. The
     * numbers are chosen to sit clear of the boundary on both sides (1.5× funded, 0.75× unfunded) so the
     * fixture doesn't turn into a rounding test.
     *
     * `funded` is the same cell with one of the two genes removed — the control.
     */
    fun divideContention(genes: Int): CytoTestWorld.Fixture {
        require(genes in 1..2) { "divide-contention is about 1 vs 2 contending genes, not $genes" }
        return CytoTestWorld.empty()
            .cell(
                "divider",
                genome = List(genes) { divideOnBond() },
                cytoplasm = mapOf("r" to 1500, "g" to 1500),
                biomass = mapOf("rg" to 2000),          // 2 atoms x 2000 = 4000 total, so cost = 1000
                light = CytoTestWorld.Light.None,       // no light: the bond reaction is the only fuel
            )
            .matter(level = 0)                          // and nothing to passively absorb
            .build()
    }

    /** An unconditional DIVIDE powered by bonding `r`+`g`. Unconditional so the gate is never what's
     *  blocking it — the fixture is about *funding*, and a failed clause would mask that. */
    private fun divideOnBond() = Gene(
        source = EnergySource.FormBond("r", "g"),
        condition = GeneCondition(emptyList()),
        action = GeneAction(ActionType.Mitosis, rejectMother = true),
    )

    /** Fixtures reachable by name — what the harness's `fixture <name>` command offers. */
    val BY_NAME: Map<String, () -> CytoTestWorld.Fixture> = linkedMapOf(
        "divide-contention" to { divideContention(genes = 2) },
        "divide-funded" to { divideContention(genes = 1) },
    )
}
