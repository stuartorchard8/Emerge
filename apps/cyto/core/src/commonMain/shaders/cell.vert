#version 330 core

// Per-cell quad, one instance per cell. The quad is a unit square in [-1, 1], scaled to the cell's world
// footprint (half-extent = 2 * cell radius, matching Cyto's original SpriteBatch draw) and placed at the
// cell centre. Texture coords run [0, 1] across the quad — the fragment shader works in
// (texCoords - 0.5) space, exactly as the original cell.frag did.
//
// The whole cell pass is one draw call. Everything that used to be a per-cell uniform is now a per-instance
// attribute, which is what makes that possible: with per-cell uniforms the renderer had to issue a draw
// plus several uniform uploads each time round, and at a few thousand cells that submission cost WAS the
// cell pass.
//
// Only the PROJECTION is still a uniform, since every cell shares it. Each instance carries its centre and
// size rather than a full model-view-projection matrix — that keeps the per-instance payload down and, more
// importantly, keeps the attribute count inside the 16 that GL 3.3 and GLES 3 guarantee.

layout(location = 0) in vec2 aPos;

layout(location = 1) in vec4 iCenterSize;   // xy = centre in view coords, z = half-extent (= 2 * radius)
layout(location = 2) in vec4 iColor;
layout(location = 3) in float iNeighbourCount;
// Welded-neighbour data: xy = world-space delta to the neighbour, z = its radius. Declared as eight
// separate inputs rather than an array because vertex input arrays are not portable to GLSL ES 3.00.
layout(location = 4) in vec4 iNeighbour0;
layout(location = 5) in vec4 iNeighbour1;
layout(location = 6) in vec4 iNeighbour2;
layout(location = 7) in vec4 iNeighbour3;
layout(location = 8) in vec4 iNeighbour4;
layout(location = 9) in vec4 iNeighbour5;
layout(location = 10) in vec4 iNeighbour6;
layout(location = 11) in vec4 iNeighbour7;

// The shared projection, as four column vectors: the engine's GPU abstraction exposes only vec4 uniform
// uploads, so a column-major matrix goes up as a vec4[4] in a single call.
uniform vec4 uProj[4];

out vec2 v_texCoords;
// Flat: these are per-cell constants, not per-vertex quantities to interpolate. Handing the neighbour data
// through unchanged is what lets the fragment stage keep the original membrane code verbatim.
flat out vec4 v_color;
flat out float v_radius;
flat out float v_neighbourCount;
flat out vec4 v_neighbour[8];

void main() {
    mat4 proj = mat4(uProj[0], uProj[1], uProj[2], uProj[3]);

    // v flipped vs. world-y to match Cyto's original SpriteBatch UV orientation, so the fragment shader's
    // membrane "necks" point toward the right neighbours.
    v_texCoords = vec2((aPos.x + 1.0) * 0.5, (1.0 - aPos.y) * 0.5);

    v_color = iColor;
    // The half-extent IS the old u_radius uniform: both were 2 * the cell radius.
    v_radius = iCenterSize.z;
    v_neighbourCount = iNeighbourCount;
    v_neighbour[0] = iNeighbour0;
    v_neighbour[1] = iNeighbour1;
    v_neighbour[2] = iNeighbour2;
    v_neighbour[3] = iNeighbour3;
    v_neighbour[4] = iNeighbour4;
    v_neighbour[5] = iNeighbour5;
    v_neighbour[6] = iNeighbour6;
    v_neighbour[7] = iNeighbour7;

    vec2 world = iCenterSize.xy + aPos * iCenterSize.z;
    gl_Position = proj * vec4(world, 0.0, 1.0);
}
