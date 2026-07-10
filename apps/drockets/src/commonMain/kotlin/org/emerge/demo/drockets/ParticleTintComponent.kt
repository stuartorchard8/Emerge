package org.emerge.demo.drockets

/**
 * Render-only per-particle tint, seeded at spawn from the emitting drocket's fire-color
 * genes ([Phenotype.fireColor]). Engine particles carry no color of their own; the renderer
 * reads this and passes it to the circle shader, so exhaust and destruction sparks take on
 * each drocket's evolved fire hue instead of a hash of the particle's entity id.
 *
 * Stored as decoded HSV (h in [0,360], s/v in [0,1000]); the renderer converts to RGB,
 * matching how drocket bodies are tinted.
 */
data class ParticleTintComponent(val color: HsvColor)
