#version 330 core

in vec2 vUv;
in float vPrimaryId;
in float vAlpha;
in vec3 vTintColor;
out vec4 fragColor;

uniform sampler2D uSpriteTexture;
uniform vec3 uTintColor;
uniform float uUseTint;

void main() {
    vec4 texel = texture(uSpriteTexture, vUv);
    if (texel.a < 0.01) discard;

    // Primary id tinting: replace green channel with tint color
    float colorSeed = vPrimaryId;
    float c = colorSeed + 1.0;
    vec3 vColor = mod(vec3(c/1.9, c/2.9, c/4.9),1.0);
    float greenAmount = texel.g - max(texel.r, texel.b);
    if (greenAmount > 0.1) {
        bool hasCustomTint = max(vTintColor.r, max(vTintColor.g, vTintColor.b)) > 0.0;
        texel.rgb = hasCustomTint ? vTintColor : vColor;
    }

    fragColor = vec4(texel.rgb, texel.a * vAlpha);
}
