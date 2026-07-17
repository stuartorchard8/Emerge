#version 330 core

// Evaluate the light level at this pixel from the moving daylight band (mirrors the moving-band branch of
// CytoLightField.sampleAt): a single torus-wrapped gaussian in x, y-independent. The normalised level
// t = exp(-dx^2 / falloff^2) (peak STRENGTH cancels), then a perceptual sqrt ramp.
//
// This is drawn as a WHITE MULTIPLY over the finished scene (GL_DST_COLOR, GL_ZERO), not as a coloured layer
// of its own: daylight is white light falling on the world, and what you see is the pigments of the ground
// and the cells absorbing and reflecting it. So the band makes everything under it more vibrant without
// tinting it or hiding the nutrient topology underneath — and the same pass dims the cells at night, because
// they are lit by the same light.
in float vClipX;
out vec4 fragColor;

uniform float uCenterX;    // camera centre, world x
uniform float uHalfViewX;  // half the view width, world units (clipX in [-1,1] -> +-uHalfViewX)
uniform float uBandX;      // daylight band centre, world x (CytoLightField.bandCenterX(tick))
uniform float uFalloff;    // gaussian falloff radius, world units
uniform float uHalf;       // torus half-extent
uniform float uSpan;       // torus span (2 * uHalf)
uniform float uNight;      // scene multiplier at full night; 1.0 at the band's peak (player-tunable)

void main() {
    float worldX = uCenterX + vClipX * uHalfViewX;
    // Shortest signed distance to the band on the torus (period uSpan), in [-uHalf, uHalf).
    float d = mod(worldX - uBandX + uHalf, uSpan) - uHalf;
    float t = exp(-(d * d) / (uFalloff * uFalloff));
    float s = sqrt(t);
    float k = uNight + (1.0 - uNight) * s;
    fragColor = vec4(k, k, k, 1.0);
}
