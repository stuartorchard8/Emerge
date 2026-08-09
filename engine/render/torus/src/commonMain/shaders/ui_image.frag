#version 330 core

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uImage;
uniform vec4 uTintLow;   // colour at texel value 0.0
uniform vec4 uTintHigh;  // colour at texel value 1.0

void main() {
    float v = texture(uImage, vUv).r;
    fragColor = mix(uTintLow, uTintHigh, v);
}
