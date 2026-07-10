#version 330 core

// Per-cell quad. The quad is a unit square in [-1, 1]; the model-view-projection
// matrix scales it to the cell's world footprint (half-extent = 2 * cell radius,
// matching Cyto's original SpriteBatch draw) and places it at the cell centre.
// Texture coords run [0, 1] across the quad — the fragment shader works in
// (texCoords - 0.5) space, exactly as the original cell.frag did.
//
// The MVP arrives as four column vectors in uMvp[0..3] rather than a mat4 uniform:
// the engine's GPU abstraction exposes only vec4 uniform uploads, so the renderer
// uploads the column-major matrix as a vec4[4] in a single call.
layout(location = 0) in vec2 aPos;

uniform vec4 uMvp[4];

out vec2 v_texCoords;

void main() {
    mat4 mvp = mat4(uMvp[0], uMvp[1], uMvp[2], uMvp[3]);
    // v flipped vs. world-y to match Cyto's original SpriteBatch UV orientation, so the
    // fragment shader's membrane "necks" point toward the right neighbours.
    v_texCoords = vec2((aPos.x + 1.0) * 0.5, (1.0 - aPos.y) * 0.5);
    gl_Position = mvp * vec4(aPos, 0.0, 1.0);
}
