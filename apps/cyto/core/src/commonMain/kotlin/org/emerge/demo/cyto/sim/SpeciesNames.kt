package org.emerge.demo.cyto.sim

/**
 * **Display names for molecules** — a readability aid, with no effect on the sim (it never sees these).
 * The raw species token (`r`, `rg`, `gr`, `bb`, `rgg`, …) stays the canonical identity everywhere in the
 * model, codec and save file; this object only decides what a *player* sees on a chip or a gene card.
 *
 * Two layers, in precedence order:
 *  1. a **per-genome alias** (`rg` → "fuel") — the curated campaign genomes carry these; see [name]'s
 *     `aliases` param. Layer 2, authored per genome.
 *  2. **built-in names** — the same for every genome, on by default in the sandbox too:
 *      - the three **atoms** get flavour names ([ATOMS]) that read as colour-based chemicals
 *        (`r` → Redogen, `g` → Greenum, `b` → Blueon);
 *      - the nine **duomers** get distinct portmanteaus ([DUOMERS]) — the point being that `rg` and `gr`
 *        (same atoms, indistinguishable by colour) read as *Redreen* vs *Greed*, and `b` vs `bb` as
 *        *Blueon* vs *Blub*, which the bare tokens and the pigment colour can't separate;
 *      - **3+ length** molecules fall back to their raw token unless a genome alias overrides them.
 *
 * These are deliberately playful placeholders — cheap to rename, since nothing keys off them.
 */
object SpeciesNames {

    /** Atom → flavour name. Keyed by the single-char monomer token. */
    val ATOMS: Map<String, String> = mapOf(
        "r" to "Redogen",
        "g" to "Greenum",
        "b" to "Blueon",
    )

    /** The nine ordered atom pairs → distinct portmanteaus. Order matters: `rg` ≠ `gr`. */
    val DUOMERS: Map<String, String> = mapOf(
        "rr" to "Ruddle", "rg" to "Redreen", "rb" to "Ruble",
        "gr" to "Greed", "gg" to "Greeble", "gb" to "Greblu",
        "br" to "Blured", "bg" to "Blugre", "bb" to "Blub",
    )

    /**
     * The display name for [species]. [aliases] (Layer 2, a genome's species→alias map) wins when it holds
     * [species]; otherwise the built-in atom/duomer name; otherwise the raw token unchanged. Empty token
     * (a no-op operand) reads as "(NONE)" so callers needn't special-case it.
     */
    fun name(species: String, aliases: Map<String, String>? = null): String {
        if (species.isEmpty()) return "(NONE)"
        aliases?.get(species)?.let { return it }
        ATOMS[species]?.let { return it }
        DUOMERS[species]?.let { return it }
        return species
    }

    /**
     * A UI tint for a molecule reference, packed `0xRRGGBBAA`, blended from its atom mix (r→red, g→green,
     * b→blue) and lifted toward white so it stays legible on the dark panels. A token with no colour atoms
     * (shouldn't happen for a real species) reads light grey.
     */
    fun color(species: String): Long {
        var r = 0; var g = 0; var b = 0
        for (ch in species) when (ch) { 'r' -> r++; 'g' -> g++; 'b' -> b++ }
        val peak = maxOf(r, maxOf(g, b))
        if (peak <= 0) return 0xC8C8C8FFL
        // Normalise to the peak channel, then lift each channel a fixed amount toward white so a pure hue
        // (e.g. green 0,255,0) doesn't read as a muddy dark chip against the panel.
        val lift = 90
        fun chan(c: Int): Long { val v = 255 * c / peak; return (lift + v * (255 - lift) / 255).toLong() }
        return (chan(r) shl 24) or (chan(g) shl 16) or (chan(b) shl 8) or 0xFF
    }
}
