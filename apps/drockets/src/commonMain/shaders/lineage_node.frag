#version 330 core

in vec2 vLocal;
in vec4 vColor;
in float vRingFrac;
out vec4 fragColor;

// Anti-aliased filled disc with optional ring outline.
//
// `d` is distance from quad centre in unit-quad coords. The disc occupies d <= 1.
// `aaWidth` smooths the outer edge across roughly 2 pixels of an instance whose
// radius covers ~50px on screen — for the lineage overlay this scales loosely
// with the camera zoom but the constant works well across the practical range.
//
// When `vRingFrac > 0`, the inner region [0, 1 - vRingFrac] is rendered as the
// fill colour at full alpha, and the outer ring [1 - vRingFrac, 1] is brightened
// (multiplied by 1.4 and clamped) — produces a subtle highlight outline without
// needing a second shader pass.
void main() {
    float d = length(vLocal);
    if (d > 1.0) discard;
    float aaWidth = 0.04;
    float edgeAlpha = 1.0 - smoothstep(1.0 - aaWidth, 1.0, d);
    vec3 fillRgb = vColor.rgb;
    if (vRingFrac > 0.0 && d > 1.0 - vRingFrac) {
        fillRgb = clamp(vColor.rgb * 1.4 + vec3(0.15), 0.0, 1.0);
    }
    fragColor = vec4(fillRgb, vColor.a * edgeAlpha);
}
