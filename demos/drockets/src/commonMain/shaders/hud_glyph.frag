#version 330 core

in vec2 vUv;
in float vAlpha;
out vec4 fragColor;

uniform sampler2D uHudTexture;
uniform vec4 uColor;

void main() {
    vec4 texel = texture(uHudTexture, vUv);
    if (texel.a < 0.01) discard;
    fragColor = vec4(uColor.rgb, texel.a * vAlpha);
}
