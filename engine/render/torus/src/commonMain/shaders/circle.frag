#version 330 core

in vec2 vLocal; // local [-1,1] coords for circle test
in float vShapeParam; // per-instance shape parameter (the `primaryIds` slot); used by shape 2 only
in float vShape; // 0 = soft disc, 1 = flat triangle, 2 = soft annulus
in float vAlpha;
in vec3 vTintColor;
out vec4 fragColor;

void main() {
    // Color is supplied by the caller; the shader no longer hashes ids into hues.
    vec3 vColor = vTintColor;

    if (vShape > 1.5) {
        // Soft tinted annulus (haloes that radiate from a body's rim rather than from its centre).
        // The hole's radius comes in via vShapeParam, in the same local [0,1] units as the outer edge.
        float d = length(vLocal);
        float inner = clamp(vShapeParam, 0.0, 0.999);
        if (d > 1.0 || d < inner) {
            discard;
        }
        // Brightest against the inner edge, fading to nothing at the outer one — so the glow reads as
        // leaving the rim. Matches the disc's 0.75 peak so the two shapes sit at the same brightness.
        float t = (d - inner) / (1.0 - inner);
        fragColor = vec4(vColor * 0.75 * (1.0 - t * t), vAlpha);
    } else if (vShape > 0.5) {
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
