package org.emerge.demo.outofspace.chem

/**
 * **What a species conducts electricity at, derived from what it conducts heat at.**
 *
 * Increment 0 of `PLAN_power_network.md`, and the third of this package's oracles — [MINERALS]
 * derives molar mass from a formula, [FORMATION_ENTHALPY] derives every reaction's energy from one
 * number per species, and this derives every wire's resistance from a column the table already has.
 *
 * ### Why this is a derivation and not a new column
 *
 * `Material.kt` already makes the argument, for grip:
 *
 * > *metallic bonding is what gives a solid free electrons to carry heat **and** the ductile,
 * > smoothly shearing surface that makes it slide … conduction is not a proxy for grip here — the
 * > two are consequences of the same bond.*
 *
 * ⭐ For **electrical** conductivity that is not an argument by analogy, it is the mechanism itself:
 * the same free electrons carry the heat and the charge. That relation is the **Wiedemann–Franz
 * law**, `κ/σ = L·T`, and it means a hand-typed conductivity column would be a hundred and seventy
 * chances to contradict a number already in the table.
 *
 * Scored against measured figures for every metal the game has — see `ConductivityTest`. The ten
 * a wire would actually be made of land within 15%; the poor metals reach 60%, which is
 * Wiedemann–Franz being honest about where it holds rather than an error in the arithmetic.
 *
 * ### ⛔ Why the metal test is not a conductivity threshold
 *
 * The obvious gate is `Material.kt`'s `METALLIC_CONDUCTION_MILLIWATTS`, which calls a solid a metal
 * above 10 W/m/K. **It cannot be used here, because it is no longer true.** That constant's doc
 * claims *"the table has a factor of four of clear air on either side of it"*; the table now has
 * fourteen species inside that gap and it misclassifies in both directions —
 *
 *  - **hematite (11.3), cassiterite (12), pyrite (20) and thorianite (10)** sit above the line and
 *    are minerals. Deriving σ from that would let a player draw wire out of iron ore.
 *  - **mercury (8.3), manganese (8) and bismuth (8)** sit below it and are metals.
 *
 * So what is stated here is what a metal *is* — an element that is not one of the twenty non-metals
 * — which is a fact about chemistry rather than a threshold that drifts as the table grows. ⚠️ A
 * compound is a non-conductor by default, which is right for every mineral and wrong for an alloy;
 * [METALLIC_ALLOYS] is where that exception lives and it is one entry long.
 */

/**
 * `L·T` in nano-units: the Lorenz number, 2.44e-8 W·Ω/K², at 300 K — the temperature
 * [Species.milliWattsPerMetreKelvin] is quoted at. 2.44e-8 × 300 = 7.32e-6.
 */
private const val LORENZ_TIMES_KELVIN_NANO = 7_320L

/**
 * **The elements with no free electrons**, and therefore the ones Wiedemann–Franz says nothing about.
 *
 * ⚠️ **Carbon is here despite conducting heat better than iron.** Graphite is 130 W/m/K, which would
 * derive to 1.8e7 S/m — sixty times what graphite actually manages. Its heat rides on *phonons*, not
 * on free electrons, so the law's premise fails and the number it gives is nonsense. That is the
 * whole reason this is a stated list rather than a conductivity threshold: the species that break
 * the law break it by mechanism, not by magnitude.
 *
 * Silicon and germanium are semiconductors, whose conductivity is a fact about doping rather than
 * about the element. Selenium, tellurium and boron likewise. The halogens and noble gases are
 * insulators by any reading.
 */
private val NON_METALS: Set<Species> = setOf(
    // Non-metals and semiconductors.
    Species.Carbon, Species.Sulfur, Species.Phosphorus, Species.Boron,
    Species.Silicon, Species.Germanium, Species.Selenium, Species.Tellurium,
    // Halogens.
    Species.Fluorine, Species.Chlorine, Species.Bromine, Species.Iodine,
    // The permanent gases.
    Species.Hydrogen, Species.Nitrogen, Species.Oxygen,
    Species.Helium, Species.Neon, Species.Argon, Species.Krypton, Species.Xenon,
)

/**
 * Compounds that conduct anyway — a metal wearing a formula.
 *
 * Steel is a solid solution of iron and carbon, not a ceramic, and it is what most of the ship is
 * made of. ⚠️ It is the *only* entry and the list exists so that adding an alloy is one line rather
 * than a new rule.
 */
private val METALLIC_ALLOYS: Set<Species> = setOf(Species.Steel)

/**
 * Whether a current can pass through this at all.
 *
 * An element that is not a stated non-metal, or a stated alloy. ⛔ **A compound is an insulator by
 * default**, which is the correct answer for all ninety of them bar the alloy above, and the
 * conservative one for any mineral added later.
 */
fun conductsElectrically(species: Species): Boolean =
    species in METALLIC_ALLOYS || (MINERALS[species] == null && species !in NON_METALS)

/**
 * **Electrical conductivity in siemens per metre**, or zero for anything a current cannot cross.
 *
 * ⚠️ Zero is a real answer here and not a missing one, which is the opposite of
 * [FORMATION_ENTHALPY]'s convention and is right for the opposite reason: an insulator's
 * conductivity is *known* to be negligible, whereas an unsourced formation enthalpy is unknown.
 */
fun electricalConductivityOf(species: Species): Long =
    if (!conductsElectrically(species)) 0L
    else species.milliWattsPerMetreKelvin.toLong() * 1_000_000L / LORENZ_TIMES_KELVIN_NANO
