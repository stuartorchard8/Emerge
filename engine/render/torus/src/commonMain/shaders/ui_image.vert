#version 330 core

// A single textured quad in screen NDC — the counterpart to ui_rect's solid-colour fill, for UI content
// that comes from a texture (e.g. a pre-rendered density field sampled with hardware bilinear filtering)
// rather than being drawn primitive-by-primitive.
layout(location = 0) in vec2 aPos; // unit quad [-1, 1]: TL, BL, TR, BR

uniform vec2 uCenter;    // NDC centre
uniform vec2 uHalfSize;  // NDC half extents
uniform vec2 uUvMin;
uniform vec2 uUvMax;
uniform vec2 uUvRot;     // (cos, sin) of a turn applied to the UVs about the middle of the UV rect

out vec2 vUv;
out vec2 vLocal;         // the unit quad, for a round mask in the fragment stage

void main() {
    // Turned about the middle of the sampled rect, in the rect's own normalised space, so a caller
    // that wants a rotating map does not have to rebuild its UVs every frame.
    //
    // NOTE: the turn is applied *before* the rect's extents are restored, so a non-square uUvMin/Max
    // shears rather than rotates. Every caller today samples a square window; give this its own
    // aspect correction the first time one does not.
    vec2 t = vec2((aPos.x + 1.0) * 0.5, (1.0 - aPos.y) * 0.5) - vec2(0.5);
    vec2 r = vec2(t.x * uUvRot.x - t.y * uUvRot.y, t.x * uUvRot.y + t.y * uUvRot.x);
    vUv = mix(uUvMin, uUvMax, r + vec2(0.5));
    vLocal = aPos;
    gl_Position = vec4(uCenter + aPos * uHalfSize, 0.0, 1.0);
}
