package org.emerge.demo.cyto.ui

import org.emerge.demo.cyto.CytoController
import org.emerge.render.torus.ui.Anchor
import org.emerge.render.torus.ui.UiBuilder

/**
 * The in-game **last-held cell** info panel (replaces the makeshift floating readout): a top-right
 * panel showing the held cell's type, size, total biomass, and its cytoplasm (mobile) + structural
 * biomass (locked) molecule counts under section headers, plus a genome summary. Shared across hosts so
 * the panel's contents stay cross-platform. Draws nothing when no cell has been held (or it has died).
 * (Section headers, not a `*` prefix — the bitmap font has no `*` glyph, and a species can appear in
 * both pools.)
 */
fun UiBuilder.cellInfoPanel(info: CytoController.CellInfo?) {
    if (info == null) return
    panel(Anchor.TopRight) {
        title("CELL ${info.id}")
        keyValue("TYPE", info.type)
        keyValue("SIZE", info.radius)
        keyValue("BIOMASS", info.totalBiomass.toString())
        if (info.cytoplasm.isNotEmpty()) {
            gap(); row("CYTOPLASM")
            for ((species, count) in info.cytoplasm) keyValue(species, count.toString())
        }
        if (info.biomass.isNotEmpty()) {
            gap(); row("STRUCTURE")
            for ((species, count) in info.biomass) keyValue(species, count.toString())
        }
        if (info.reservoir.isNotEmpty()) {
            gap(); row("RESERVOIR", color = 0xC8A050FFL)   // shared matter available in this cell's grid-cell
            for ((species, count) in info.reservoir) keyValue(species, count.toString())
        }
        if (info.genes.isNotEmpty()) {
            gap(); row("GENES")
            for (gene in info.genes) row(gene, color = 0x88CC88FFL)
        }
    }
}
