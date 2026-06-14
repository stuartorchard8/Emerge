package org.emerge.demo.cyto.sim.soa

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.ConnectionStateComponent
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoMatterGrid
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.GeneCodec
import org.emerge.demo.cyto.sim.TouchMode
import org.emerge.demo.cyto.sim.createCytoInitialState
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.sim.SimState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **The replacement for the AoS-oracle gate.** Instead of re-deriving correctness from a second
 * (array-of-structs) implementation tick-for-tick, this freezes the *current, known-good* behaviour of
 * the live [CytoSoaReducer] as committed golden digests and gates future optimisations against it: the
 * "pre-optimised source of truth". The sim is fixed-point deterministic, so a fixed seed + scripted
 * input yields a bit-stable trajectory across runs/machines, and a digest of the resulting state is a
 * stable regression key.
 *
 * Why this is *more* coverage than the old AoS gate, not less: the AoS equivalence gate was `@Ignore`d
 * under mutation (a never-resolved AoS↔SoA divergence), so it never covered the **mutation-on** config
 * the game actually ships. The golden does — [mutationOn] locks the live evolving trajectory down.
 *
 * The digest is split into dimensions (meta / physics / biology / topology / grid) so a failure says
 * *what* drifted. To intentionally re-baseline after a deliberate behaviour change, run the test, copy
 * the `actual` hashes from the assertion messages into the constants below, and justify it in the commit.
 *
 * Determinism of the *parallel* path and faithfulness of the toSimState boundary are gated separately
 * ([parallelMatchesSequential], [grownStateRoundTrips]) — no AoS implementation involved anywhere here.
 */
class CytoGoldenTest {

    // ── Golden digests: { dimension -> FNV-1a hex } per scenario. Captured from the live SoA reducer. ──
    // Re-baselined 2026-06-14: passive cell↔environment exchange (CytoBiologyCore.passiveEnvExchange) went
    // from a per-cell sequential draw — which let the lowest-EntityId cell skim a shared grid-cell first
    // every tick, so founders starved their own identical-genome daughters (selection on birth order, not
    // genome) — to a batched, order-independent fair split (all co-located cells draw against one snapshot;
    // over-subscribed absorbers share proportionally). All three trajectory goldens shifted; the SoA
    // determinism gates (parallelMatchesSequential, grownStateRoundTrips) held, confirming the new split is
    // deterministic. See demos/cyto/PRESSURE.md.
    // Re-baselined again same day: light became a SHARED per-cell quanta budget (CytoBiologyCore.runGene
    // now spends `work.quanta` across all light-sourced genes in genome order) instead of handing each
    // light gene its own full-power copy — removing the genome-bloat free lunch.
    // Re-baselined 2026-06-15: LIGHT SHADING (interference competition). Cells sharing a grid-cell now split
    // its incident light by capture weight (exposure × radius) in CytoSoaReducer.biology — a bigger cell
    // captures a larger share and shades its neighbours (PRESSURE.md proposal 4, applied to light). All
    // three trajectory goldens shifted (every scene divides into co-located cells); a lone cell is
    // unaffected (capture share 1 → quanta bit-identical). parallelMatchesSequential held (the per-grid-cell
    // capture sum is order-independent).
    // Re-baselined 2026-06-15: GENOME-BLOAT TAX (CytoBiologyCore.runGenes). Each active gene is now
    // throttled to a 1/N share of its energy source (N = genes active that tick), regardless of source —
    // replacing the shared light-pool spend-down with per-gene slices (the unclaimed share is lost). All
    // three trajectory goldens shifted; the determinism gates held.
    // growth, mutation off, 250 ticks from the default scene.
    private val GROWTH = mapOf(
        "meta" to "2cc2b311f53949fa",
        "physics" to "9591079fa368af8f",
        "biology" to "467fc7d419493385",
        "topology" to "9295f0306166ed66",
        "grid" to "7941cbbb9f02c4f1",
    )
    // mutation on (rateDenom 200), 250 ticks — the live evolving config the AoS gate never covered.
    // Re-baselined twice for deliberate gene-model extensions, both of which re-route point-mutation's
    // PRNG-driven choices (and thus every downstream dimension): (1) the Expand/Contract flex actions
    // grew ActionType 5→7, remapping `ActionType.entries[nextInt(size)]`; (2) the Touching gate condition
    // changed condition-type mutation from a deterministic ChemQty↔Biomass flip to an entries draw
    // (`ConditionType.entries[nextInt(size)]`), so it now consumes a PRNG int and can reach the new type.
    // The mutation-off GROWTH/INTERACT goldens below are unchanged (no flex/touch gene in the default
    // scene, no mutation), confirming the drift is isolated to the new mechanics.
    private val MUTATION = mapOf(
        "meta" to "9d780c5b9c30aa0",
        "physics" to "8d7401f392d11901",
        "biology" to "3ade895ca46e67ca",
        "topology" to "9ee6c7a21a877c87",
        "grid" to "1527457ef25605e7",
    )
    // grow then a scripted player-interaction sequence (delete / spawn / set / detach / grab).
    private val INTERACT = mapOf(
        "meta" to "cc3c08744084beb8",
        "physics" to "3fd3a84e48952a8e",
        "biology" to "d739bd01ef144f46",
        "topology" to "99818f584161b8b3",
        "grid" to "ff7dd79427ca1373",
    )

    @Test
    fun growthMutationOff() {
        val cfg = CytoConfig(mutationRateDenom = 0)
        val soa = CytoSoaReducer(cfg)
        var w = CytoWorld.fromSimState(createCytoInitialState())
        repeat(250) { w = soa.tick(w, CytoInput.EMPTY) }
        assertGolden("growth", GROWTH, w.toSimState())
    }

    @Test
    fun mutationOn() {
        val cfg = CytoConfig(mutationRateDenom = 200)
        val soa = CytoSoaReducer(cfg)
        var w = CytoWorld.fromSimState(createCytoInitialState())
        repeat(250) { w = soa.tick(w, CytoInput.EMPTY) }
        assertGolden("mutation", MUTATION, w.toSimState())
    }

    @Test
    fun scriptedInteractions() {
        val cfg = CytoConfig(mutationRateDenom = 0)
        val soa = CytoSoaReducer(cfg)
        var w = CytoWorld.fromSimState(createCytoInitialState())
        repeat(80) { w = soa.tick(w, CytoInput.EMPTY) }   // grow a connected colony
        // A fixed input script (positions in logical units), one tap per tick, deterministic.
        val collector = CellType.Collector
        w = soa.tick(w, CytoInput(taps = listOf(CytoInput.Tap(0f, 0f, TouchMode.Delete, collector))))
        w = soa.tick(w, CytoInput(taps = listOf(CytoInput.Tap(500f, 500f, TouchMode.Base, collector))))
        w = soa.tick(w, CytoInput(taps = listOf(CytoInput.Tap(500f, 500f, TouchMode.Set, CellType.Blank))))
        repeat(20) { w = soa.tick(w, CytoInput.EMPTY) }
        assertGolden("interact", INTERACT, w.toSimState())
    }

    // ── SoA-only determinism + boundary gates (no AoS) ─────────────────────────────────────────────

    @Test
    fun parallelMatchesSequential() {
        // The spring gather fans across cores via ColumnPartition.disjoint; that is bit-identical to its
        // sequential fallback only if each body writes solely its own delta from frozen state. Run a
        // parallel reducer (forced on at small N) lockstep with a sequential one and assert identical
        // state each tick — the determinism guarantee, gated SoA-vs-SoA.
        val cfg = CytoConfig(mutationRateDenom = 0)
        val executor = org.emerge.sim.core.ecs.ParallelExecutor()
        val seq = CytoSoaReducer(cfg)
        val par = CytoSoaReducer(cfg, executor = executor, springParallelThreshold = 2)
        var ws = CytoWorld.fromSimState(createCytoInitialState())
        var wp = CytoWorld.fromSimState(createCytoInitialState())
        for (t in 1..250) {
            ws = seq.tick(ws, CytoInput.EMPTY)
            wp = par.tick(wp, CytoInput.EMPTY)
            assertEquals(digest(ws.toSimState()), digest(wp.toSimState()), "parallel != sequential at tick=$t")
        }
    }

    @Test
    fun grownStateRoundTrips() {
        // toSimState/fromSimState is load-bearing (render + save read it). Grow a colony, round-trip the
        // SoA world through a SimState, and assert the digest is unchanged.
        val cfg = CytoConfig(mutationRateDenom = 0)
        val soa = CytoSoaReducer(cfg)
        var w = CytoWorld.fromSimState(createCytoInitialState())
        repeat(250) { w = soa.tick(w, CytoInput.EMPTY) }
        val before = w.toSimState()
        val round = CytoWorld.fromSimState(before).toSimState()
        assertEquals(digest(before), digest(round), "round-trip changed the state digest")
    }

    // ── digest ──────────────────────────────────────────────────────────────────────────────────

    private fun assertGolden(label: String, golden: Map<String, String>, state: SimState) {
        val actual = digest(state)
        if (actual != golden) {
            val dump = actual.entries.joinToString(",\n        ") { "\"${it.key}\" to \"${it.value}\"" }
            throw AssertionError("$label digest drift. Current digests (paste to re-baseline):\n        $dump")
        }
    }

    /** A canonical per-dimension FNV-1a digest of the persistent sim state (impulse excluded — transient,
     *  reset each tick). Entities are visited in ascending EntityId order and every map is key-sorted, so
     *  the string — and thus the hash — is stable across runs, JVMs, and component-table iteration order. */
    private fun digest(s: SimState): Map<String, String> {
        val cells = s.components.getTable<CytoCellComponent>().asMap()
        val transforms = s.components.getTable<TransformComponent>().asMap()
        val motions = s.components.getTable<MotionComponent>().asMap()
        val materials = s.components.getTable<MaterialComponent>().asMap()
        val colliders = s.components.getTable<ColliderComponent>().asMap()
        val springs = s.components.getTable<SpringConstraintComponent>().asMap()
        val conns = s.components.getTable<ConnectionStateComponent>().asMap()
        val ids = cells.keys.sortedBy { it.value }

        val meta = "seed=${s.randomSeed};tick=${s.tick};last=${s.world.lastEntityValue};n=${ids.size}"

        val physics = StringBuilder()
        for (id in ids) {
            val p = transforms[id]?.pos; val v = motions[id]?.vel
            val m = materials[id]?.mass ?: 0u; val r = colliders[id]?.radius?.raw ?: 0L
            physics.append(id.value).append(':')
                .append(p?.x?.raw).append(',').append(p?.y?.raw).append(',')
                .append(v?.x?.raw).append(',').append(v?.y?.raw).append(',')
                .append(m).append(',').append(r).append(';')
        }

        val biology = StringBuilder()
        for (id in ids) {
            val c = cells.getValue(id)
            biology.append(id.value).append(':').append(c.type.name).append('|')
                .append(c.logicalRadius.raw).append('|').append(c.wear).append('|')
                .append(GeneCodec.serialize(c.genome)).append('|')
                .append(mapStr(c.cytoplasm)).append('|').append(mapStr(c.biomass)).append(';')
        }

        val topology = StringBuilder()
        for (id in ids) {
            val sp = springs[id]?.springs.orEmpty()
                .sortedBy { it.other.value }
                .joinToString(",") { "${it.other.value}/${it.restLength.raw}/${it.stiffness.raw}/${it.damping.raw}" }
            // Connection damage with zero entries dropped (a fresh spring carries 0; readers treat
            // missing as 0 — only a genuine non-zero divergence should move the hash).
            val dmg = conns[id]?.damage.orEmpty().filterValues { it != 0f }
                .entries.sortedBy { it.key.value }.joinToString(",") { "${it.key.value}=${it.value}" }
            if (sp.isNotEmpty() || dmg.isNotEmpty()) topology.append(id.value).append(":[").append(sp).append("][").append(dmg).append(']').append(';')
        }

        val gridSb = StringBuilder()
        val grid = s.components.getTable<CytoMatterGridComponent>()[GRID_SINGLETON]?.grid
        if (grid != null) {
            for (idx in 0 until CytoMatterGrid.RES * CytoMatterGrid.RES) {
                val cell = grid.cellAt(idx)
                if (cell.isNotEmpty()) gridSb.append(idx).append(':').append(mapStr(cell)).append(';')
            }
        }

        return mapOf(
            "meta" to fnv(meta),
            "physics" to fnv(physics.toString()),
            "biology" to fnv(biology.toString()),
            "topology" to fnv(topology.toString()),
            "grid" to fnv(gridSb.toString()),
        )
    }

    private fun mapStr(m: Map<String, Int>): String =
        m.entries.filter { it.value != 0 }.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }

    private fun fnv(s: String): String {
        var h = -3750763034362895579L  // FNV-1a 64-bit offset basis (0xcbf29ce484222325)
        for (ch in s) { h = h xor ch.code.toLong(); h *= 1099511628211L }
        return h.toULong().toString(16)
    }
}
