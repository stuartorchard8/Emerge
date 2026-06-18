package org.emerge.demo.cyto.ui

import org.emerge.demo.cyto.CytoController
import org.emerge.render.torus.ui.Anchor
import org.emerge.render.torus.ui.PanelBuilder
import org.emerge.render.torus.ui.UiBuilder

/**
 * The in-game **last-held cell** info panel: type / size / biomass, then a **metabolism table** — one row
 * per metabolically-relevant species showing it side-by-side across `ENV` (the local reservoir), `CYT`
 * (cytoplasm) and `BIO` (locked biomass), with a flow arrow (`>>` drawing in, `<<` leaking out, `==` held)
 * between env and cytoplasm so the gradient + direction of travel are read at a glance. Shared across
 * hosts; draws nothing when no cell has been held. (Built on the monospace bitmap font, so the columns
 * align from a plain padded string; `|` has no glyph, hence `==` for "held".)
 */
fun UiBuilder.cellInfoPanel(info: CytoController.CellInfo?) {
    if (info == null) return
    panel(Anchor.TopRight) {
        title("CELL ${info.id}")
        keyValue("TYPE", info.type)
        keyValue("SIZE", info.radius)
        keyValue("BIOMASS", info.totalBiomass.toString())
        keyValue("LIGHT", info.light)
        metabolismTable(info)
        if (info.genes.isNotEmpty()) {
            gap(); row("GENES (dim = inactive)")
            for (g in info.genes) {
                row(g.desc, color = if (g.active) 0x88CC88FFL else 0x6E6E6EFFL)   // active green, inactive grey
                if (!g.active && g.blockers.isNotEmpty()) row("   - ${g.blockers.joinToString("   ")}", color = 0xC8963CFFL)
            }
        }
    }
}

/** Renders the env|cyto|bio metabolism table (shared by the info panel + the gene editor). */
fun PanelBuilder.metabolismTable(info: CytoController.CellInfo) {
    if (info.metabolism.isEmpty()) return
    gap()
    row(METAB_HEADER, color = 0x9A9A9AFFL)
    for (r in info.metabolism) {
        // Colour by the net story: any inflow/build → green, else any outflow → orange, else held.
        val color = when {
            r.dirEnvCyt == ">>" || r.dirCytBio == ">>" -> 0x66CC66FFL
            r.dirEnvCyt == "<<" || r.dirCytBio == "<<" -> 0xCC8855FFL
            else -> 0xC8C8C8FFL
        }
        row(metabRow(r), color)
    }
}

// Column layout (monospace): species[6] env[6] ' ' dirEC[2] ' ' cyto[6] ' ' dirCB[2] ' ' bio[6].
private val METAB_HEADER =
    "".padEnd(6) + "ENV".padStart(6) + "    " + "CYT".padStart(6) + "    " + "BIO".padStart(6)

private fun metabRow(r: CytoController.CellInfo.MetRow): String =
    r.species.padEnd(6) + r.env.toString().padStart(6) + " " + r.dirEnvCyt + " " +
        r.cyto.toString().padStart(6) + " " + r.dirCytBio + " " + r.bio.toString().padStart(6)
