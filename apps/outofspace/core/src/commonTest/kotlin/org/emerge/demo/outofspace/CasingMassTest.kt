package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.StuffLayer
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.heatCapacityOf
import org.emerge.demo.outofspace.world.tileBillOfMaterials
import kotlin.test.Test
import kotlin.test.assertEquals
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.world.materialBefore

/**
 * A deck machine's casing is **real matter in the deck layer**, and this is the invariant that made
 * moving it there safe: the species it is made of weigh exactly what the kind says a tile of it
 * weighs.
 *
 * ⚠️ Exactly, not nearly. The deck's contribution to [org.emerge.demo.outofspace.world.vesselMass]
 * used to be the constant `fixtureMassPerTile(kind)` and is now the sum of these species. If the two ever
 * disagree, every ship in the game silently changes weight — and since mass is what thrust is
 * divided by, it changes how it flies. [org.emerge.demo.outofspace.chem.Mixture.scaledTo]
 * apportions cumulatively, which is what makes the equality exact rather than approximate.
 */
class CasingMassTest {

    @Test
    fun `a tile's bill of materials weighs exactly what a tile of that kind weighs`() {
        for (kind in DeckMachineKind.ALL) {
            val bill = tileBillOfMaterials(kind, materialBefore(kind))
            val summed = Species.ALL.sumOf { bill[it] }
            assertEquals(fixtureMassPerTile(kind), summed, "$kind: bill of materials does not sum to massPerTile")
        }
    }

    @Test
    fun `the two heat-capacity formulas agree`() {
        // [heatCapacityOf] walks a Mixture; [StuffLayer.heatCapacityAt] walks a layer's row without
        // allocating one. They are the same physics written twice for performance, and if they ever
        // disagree a machine's built temperature and its running temperature come from different
        // models — which is precisely the drift that moving the casing into the layer removed.
        val layer = StuffLayer.empty(4)
        val tile = TileIndex(1)
        for (kind in DeckMachineKind.ALL) {
            val bill = tileBillOfMaterials(kind, materialBefore(kind))
            layer.release(tile)
            for (s in Species.ALL) layer[tile, s] = bill[s]
            assertEquals(heatCapacityOf(bill), layer.heatCapacityAt(tile), "$kind: capacity formulas disagree")
        }
    }

    @Test
    fun `a casing is made of something`() {
        // Guards the degenerate pass: an empty bill would sum to zero and equal a zero massPerTile.
        for (kind in DeckMachineKind.ALL) {
            val bill = tileBillOfMaterials(kind, materialBefore(kind))
            assertEquals(true, Species.ALL.any { bill[it] > 0L }, "$kind has an empty bill of materials")
        }
    }
}
