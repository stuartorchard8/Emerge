#version 330 core

// Sample the matter density texture at this pixel's world position, with an animated multi-octave domain
// warp so the piecewise-constant quad-tree boundaries roil like gas/smoke instead of reading as flat blocks.
// One torus tile maps to [0,1]; GL_REPEAT wrap + linear filtering + the warp make matter a smooth, seamless,
// churning density cloud.
in vec2 vClip;
out vec4 fragColor;

uniform sampler2D uTex;      // matter density (repeat, linear)
uniform sampler2D uNoise;    // tileable value noise (repeat, linear)
uniform vec2 uCenter;        // camera centre, world
uniform vec2 uHalfView;      // half the view size, world units
uniform float uHalf;         // torus half-extent
uniform float uSpan;         // torus span (2 * uHalf)
uniform float uTime;         // animation phase (scrolls the noise)
uniform float uAmp;          // warp amplitude, uv units

// Domain-warp offset: two scrolling octaves of the tileable noise, each read twice (decorrelated) for an
// (x,y) vector. Integer base frequencies (3, 7) keep it periodic in uv, so the warp is seamless across the
// torus wrap (the scroll + constant lookup offsets are shared between uv and uv+1).
vec2 warpOffset(vec2 uv) {
    vec2 t1 = vec2(0.00006, 0.00010) * uTime;
    vec2 t2 = vec2(-0.00011, 0.00005) * uTime;
    vec2 o1 = vec2(
        texture(uNoise, uv * 3.0 + t1).r,
        texture(uNoise, uv * 3.0 + t1 + vec2(0.37, 0.11)).r) - 0.5;
    vec2 o2 = vec2(
        texture(uNoise, uv * 7.0 + t2).r,
        texture(uNoise, uv * 7.0 + t2 + vec2(0.19, 0.53)).r) - 0.5;
    return o1 + 0.5 * o2;
}

void main() {
    vec2 world = uCenter + vClip * uHalfView;
    vec2 uv = (world + uHalf) / uSpan;
    vec2 duv = uv + warpOffset(uv) * uAmp;
    fragColor = texture(uTex, duv);
}
