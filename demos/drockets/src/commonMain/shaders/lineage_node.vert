#version 330 core
// Instanced filled-disc shader for the lineage overlay.
//
// Each instance places a unit-quad at `iCenter` (NDC) scaled by `iRadius` (NDC),
// tinted by `iColor` (RGBA). The fragment shader anti-aliases the disc edge so
// the boundary is smooth at any radius. `iRingFrac` widens an opaque outline ring
// when > 0 — used for selection / hover highlights without a separate draw call.
layout(location = 0) in vec2 aPos;        // quad corner in [-1, 1]
layout(location = 1) in vec2 iCenter;     // NDC center
layout(location = 2) in float iRadius;    // NDC radius
layout(location = 3) in vec4 iColor;      // RGBA fill
layout(location = 4) in float iRingFrac;  // 0 = no ring; >0 = ring width as a fraction of radius

out vec2 vLocal;
out vec4 vColor;
out float vRingFrac;

void main() {
    gl_Position = vec4(iCenter + aPos * iRadius, 0.0, 1.0);
    vLocal = aPos;
    vColor = iColor;
    vRingFrac = iRingFrac;
}
