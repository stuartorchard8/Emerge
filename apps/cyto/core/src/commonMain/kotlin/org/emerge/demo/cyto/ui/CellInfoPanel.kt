package org.emerge.demo.cyto.ui

import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.sim.SpeciesNames
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
            gap(); row("GENES (orange = inactive)")
            for (g in info.genes) row(g.desc, color = if (g.active) 0x88CC88FFL else 0xC8963CFFL)   // active green, inactive orange
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
        row(metabRow(r, info.aliases), color)
    }
}

// Column layout (monospace): species[9] env[5] ' ' dirEC[2] ' ' cyto[5] ' ' dirCB[2] ' ' bio[5] = 32 chars,
// which fits the fixed-width cell dock (GeneEditor.CELL_PANEL_DP). The value columns are 5 wide and values
// are compacted ([fmt]) so they never overflow: since biomass counts atoms these run to 6+ digits on a
// hoarder (e.g. 292084), which overran the old 6-wide raw columns and clipped the BIO column off-screen.
// The species column is 9 wide to fit the built-in flavour names (e.g. GREENIUM); longer fallbacks are clipped.
private const val SP_COL = 9
private const val NUM_COL = 5
private val METAB_HEADER =
    "".padEnd(SP_COL) + "ENV".padStart(NUM_COL) + "    " + "CYT".padStart(NUM_COL) + "    " + "BIO".padStart(NUM_COL)

private fun spName(species: String, aliases: Map<String, String>): String =
    SpeciesNames.name(species, aliases).uppercase().take(SP_COL)

/** Compact a molecule count to at most [NUM_COL] chars: exact below 100k, then `k` (thousands) up to
 *  10M, then `M` (millions). Keeps the metabolism columns fixed-width whatever the hoard size. */
private fun fmt(n: Int): String = when {
    n < 100_000 -> n.toString()          // 0..99999 — up to 5 digits, exact
    n < 10_000_000 -> "${n / 1_000}k"    // 100k..9999k
    else -> "${n / 1_000_000}M"          // 10M..2147M (Int.MAX)
}

private fun metabRow(r: CytoController.CellInfo.MetRow, aliases: Map<String, String>): String =
    spName(r.species, aliases).padEnd(SP_COL) + fmt(r.env).padStart(NUM_COL) + " " + r.dirEnvCyt + " " +
        fmt(r.cyto).padStart(NUM_COL) + " " + r.dirCytBio + " " + fmt(r.bio).padStart(NUM_COL)
