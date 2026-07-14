#version 330 core

// Evaluate the light level at this pixel from the moving daylight band (mirrors the moving-band branch of
// CytoLightField.sampleAt): a single torus-wrapped gaussian in x, y-independent. The normalised level
// t = exp(-dx^2 / falloff^2) (peak STRENGTH cancels), then a perceptual sqrt ramp up to the peak yellow.
in float vClipX;
out vec4 fragColor;

uniform float uCenterX;    // camera centre, world x
uniform float uHalfViewX;  // half the view width, world units (clipX in [-1,1] -> +-uHalfViewX)
uniform float uBandX;      // daylight band centre, world x (CytoLightField.bandCenterX(tick))
uniform float uFalloff;    // gaussian falloff radius, world units
uniform float uHalf;       // torus half-extent
uniform float uSpan;       // torus span (2 * uHalf)

void main() {
    float worldX = uCenterX + vClipX * uHalfViewX;
    // Shortest signed distance to the band on the torus (period uSpan), in [-uHalf, uHalf).
    float d = mod(worldX - uBandX + uHalf, uSpan) - uHalf;
    float t = exp(-(d * d) / (uFalloff * uFalloff));
    float s = sqrt(t);
    fragColor = vec4(s, s * 0.90, s * 0.43, 1.0);
}
