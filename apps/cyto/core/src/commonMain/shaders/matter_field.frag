#version 330 core

// Sample the matter density texture at this pixel's world position. One torus tile maps to [0,1]; the
// texture's GL_REPEAT wrap + linear filtering make matter a smooth, seamlessly wrapped density cloud.
in vec2 vClip;
out vec4 fragColor;

uniform sampler2D uTex;
uniform vec2 uCenter;    // camera centre, world
uniform vec2 uHalfView;  // half the view size, world units (clip [-1,1] -> +-uHalfView)
uniform float uHalf;     // torus half-extent
uniform float uSpan;     // torus span (2 * uHalf)

void main() {
    vec2 world = uCenter + vClip * uHalfView;
    vec2 uv = (world + uHalf) / uSpan;  // one tile -> [0,1]; GL_REPEAT wraps the rest
    fragColor = texture(uTex, uv);
}
