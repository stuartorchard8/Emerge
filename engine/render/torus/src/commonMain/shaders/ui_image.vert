#version 330 core

// A single textured quad in screen NDC — the counterpart to ui_rect's solid-colour fill, for UI content
// that comes from a texture (e.g. a pre-rendered density field sampled with hardware bilinear filtering)
// rather than being drawn primitive-by-primitive.
layout(location = 0) in vec2 aPos; // unit quad [-1, 1]: TL, BL, TR, BR

uniform vec2 uCenter;    // NDC centre
uniform vec2 uHalfSize;  // NDC half extents
uniform vec2 uUvMin;
uniform vec2 uUvMax;

out vec2 vUv;

void main() {
    vUv = mix(uUvMin, uUvMax, vec2((aPos.x + 1.0) * 0.5, (1.0 - aPos.y) * 0.5));
    gl_Position = vec4(uCenter + aPos * uHalfSize, 0.0, 1.0);
}
