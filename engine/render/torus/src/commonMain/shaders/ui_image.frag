#version 330 core

in vec2 vUv;
in vec2 vLocal;
out vec4 fragColor;

uniform sampler2D uImage;
uniform float uRound;   // >0.5 clips the quad to its inscribed ellipse

void main() {
    // `discard` rather than a zero alpha, so what is behind the quad is left exactly as it was —
    // the corners of a round instrument show the world, not a darkened copy of it.
    if (uRound > 0.5 && dot(vLocal, vLocal) > 1.0) discard;
    vec4 v = texture(uImage, vUv);
    fragColor = vec4(v.r*v.a, v.g*v.a, v.b*v.a, 1.0f);
}
