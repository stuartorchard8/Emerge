#version 330 core

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uImage;

void main() {
    vec4 v = texture(uImage, vUv);
    fragColor = vec4(v.r*v.a, v.g*v.a, v.b*v.a, 1.0f);
}
