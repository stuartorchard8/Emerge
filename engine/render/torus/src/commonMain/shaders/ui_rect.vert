#version 330 core

// Instanced solid-colour rectangles in screen NDC, for the on-screen control buttons.
layout(location = 0) in vec2 aPos;       // unit quad [-1, 1]
layout(location = 1) in vec2 iCenter;    // NDC centre
layout(location = 2) in vec2 iHalfSize;  // NDC half extents
layout(location = 3) in vec4 iColor;     // rgba

out vec4 vColor;

void main() {
    vColor = iColor;
    gl_Position = vec4(iCenter + aPos * iHalfSize, 0.0, 1.0);
}
