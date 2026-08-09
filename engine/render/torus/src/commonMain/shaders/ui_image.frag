#version 330 core

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uImage;

void main() {
    float v = texture(uImage, vUv).r;
    fragColor = vec4(0.0f, v, 0.0f, 1.0f);
}
