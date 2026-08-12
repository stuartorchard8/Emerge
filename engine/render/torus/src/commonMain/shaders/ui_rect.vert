#version 330 core

// Instanced solid-colour rectangles in screen NDC, for the on-screen control buttons.
layout(location = 0) in vec2 aPos;       // unit quad [-1, 1]
layout(location = 1) in vec2 iCenter;    // NDC centre
layout(location = 2) in vec2 iHalfSize;  // NDC half extents
layout(location = 3) in vec4 iColor;     // rgba

// A 2x2 view transform about the screen centre, row-major: (m00, m01, m10, m11).
// Identity for the UI, which is drawn in the frame the player is looking at. A world drawn in some
// other frame passes its rotation here, so the *quads* turn with the scene and not just their
// centres — an axis-aligned rect whose centre had been swirled would read as a bug, not a bank.
uniform vec4 uView;

out vec4 vColor;

void main() {
    vColor = iColor;
    vec2 p = iCenter + aPos * iHalfSize;
    gl_Position = vec4(dot(uView.xy, p), dot(uView.zw, p), 0.0, 1.0);
}
