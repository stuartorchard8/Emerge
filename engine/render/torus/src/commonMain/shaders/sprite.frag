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

    // Recolor the green-keyed mask with the caller-supplied tint. Any id-based
    // color choice happens in Kotlin now, not here; an unset (zero) tint leaves
    // the texture untouched.
    float greenAmount = texel.g - max(texel.r, texel.b);
    bool hasTint = max(vTintColor.r, max(vTintColor.g, vTintColor.b)) > 0.0;
    if (greenAmount > 0.1 && hasTint) {
        texel.rgb = vTintColor;
    }

    fragColor = vec4(texel.rgb, texel.a * vAlpha);
}
