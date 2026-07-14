#version 330 core

// A single full-screen triangle. The fragment shader evaluates the light field analytically per pixel,
// so the daylight band renders as the continuous field it is (no mesh, no baking). Only the clip-space
// x is forwarded — the moving band is y-independent (it lights whole columns).
layout(location = 0) in vec2 aPos;  // clip-space fullscreen triangle: (-1,-1), (3,-1), (-1,3)

out float vClipX;

void main() {
    vClipX = aPos.x;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
