#version 330 core

in vec2 vLocal; // local [-1,1] coords for circle test
in float vPrimaryId;
in float vShape; // 0 = soft disc, 1 = flat triangle
in float vAlpha;
in vec3 vTintColor;
out vec4 fragColor;

void main() {
    // Fallback color when no tint is supplied: hash the primary id into a stable hue.
    float c = vPrimaryId + 1.0;
    vec3 seededColor = mod(vec3(c/1.9, c/2.9, c/4.9), 1.0);
    bool hasCustomTint = max(vTintColor.r, max(vTintColor.g, vTintColor.b)) > 0.0;
    vec3 vColor = hasCustomTint ? vTintColor : seededColor;

    if (vShape > 0.5) {
        // Flat tinted triangle (e.g. off-screen edge-indicator arrows).
        fragColor = vec4(vColor, vAlpha);
    } else {
        // Soft tinted disc (particles, force fields).
        if (dot(vLocal, vLocal) > 1.0) {
            discard;
        }
        float a = min(0.75, 1.0 - dot(vLocal, vLocal) / 1.5);
        fragColor = vec4(vColor * a, vAlpha);
    }
}
