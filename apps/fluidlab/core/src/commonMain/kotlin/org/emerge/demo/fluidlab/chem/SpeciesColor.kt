package org.emerge.demo.fluidlab.chem

/**
 * The colour a species is drawn in.
 *
 * In Out of Space this lived on the renderer, so that a lump on a belt and its name in the inspector
 * were unmistakably the same stuff. Fluidlab has no belts and no inspector, so the palette is inlined
 * here instead — it is the one thing `chem` needed from the game, and copying thirteen constants is a
 * cheaper boundary than carrying a thousand-line renderer across.
 */
fun speciesColor(dominant: Species?): Long = when (dominant) {
    Species.Iron -> 0xB07A5AFFL
    Species.Aluminum -> 0xB8BCC4FFL
    Species.Copper -> 0xE08A3AFFL
    Species.Titanium -> 0xC8CCD4FFL
    Species.Silica -> 0xD8D0A8FFL
    Species.Carbon -> 0x484848FFL
    Species.RareEarth -> 0x6ED09AFFL
    Species.Uranium -> 0xA8E04AFFL
    Species.Oxygen -> 0x7AB8FFFFL
    Species.Nitrogen -> 0x9A9AD0FFL
    Species.CarbonDioxide -> 0x8A8A8AFFL
    Species.Water -> 0x4A8AD0FFL
    null -> 0x707070FFL
}
