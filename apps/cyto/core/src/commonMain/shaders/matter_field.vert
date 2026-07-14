#version 330 core

// A single full-screen triangle for the matter density field. The fragment shader samples a rasterised
// density texture (one torus tile) with GL_REPEAT wrapping, so matter renders as a smooth, seamlessly
// wrapped cloud across the whole screen. Forwards clip-space xy so the fragment can reconstruct world xy.
layout(location = 0) in vec2 aPos;  // clip-space fullscreen triangle: (-1,-1), (3,-1), (-1,3)

out vec2 vClip;

void main() {
    vClip = aPos;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
