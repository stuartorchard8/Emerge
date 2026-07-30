#version 330 core

// Tiles one period of a torus world across the screen.
//
// The world has already been drawn once, period-aligned, into uPeriodTex. Here each screen fragment is
// mapped to the world position it looks at, and that position is divided by the world period to get a
// texture coordinate. Coordinates outside [0, 1] are not clamped or discarded — the texture is GL_REPEAT,
// so they wrap, and the world simply recurs. Zooming out therefore shows arbitrarily many copies of the
// world for the cost of the one that was actually rendered.
//
// The seam is only invisible because uPeriodTex is genuinely periodic: whatever straddles the world
// boundary was drawn into both sides when the period was rendered. Linear filtering across the wrap then
// interpolates between matching content rather than between an edge and unrelated pixels.

uniform vec2 uVpMin;          // world viewport rect in framebuffer pixels
uniform vec2 uVpMax;
uniform vec2 uCenter;         // camera centre, world units
uniform vec2 uViewHalfExtent; // half the visible width/height, world units
uniform vec2 uPeriod;         // world period (size of one torus repeat), world units
uniform sampler2D uPeriodTex;

out vec4 fragColor;

void main() {
    vec2 resolution = uVpMax - uVpMin;
    vec2 uv = (gl_FragCoord.xy - uVpMin) / resolution;

    // Screen +y (gl_FragCoord, bottom-left origin) is world +y, matching the renderer's convention that
    // screen-down is world -y.
    vec2 worldPos = uCenter + (uv - 0.5) * 2.0 * uViewHalfExtent;

    // The period texture spans [-uPeriod/2, +uPeriod/2), so world 0 sits at texel 0.5.
    vec2 texUv = worldPos / uPeriod + 0.5;

    fragColor = texture(uPeriodTex, texUv);
}
