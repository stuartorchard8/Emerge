#version 330 core

in vec2 vLocal; // local [-1,1] coords for circle test
in float vPrimaryId;
in float vShape; // 0 = soft disc, 1 = flat triangle
in float vAlpha;
in vec3 vTintColor;
out vec4 fragColor;

void main() {
    // Color is supplied by the caller; the shader no longer hashes ids into hues.
    vec3 vColor = vTintColor;

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
