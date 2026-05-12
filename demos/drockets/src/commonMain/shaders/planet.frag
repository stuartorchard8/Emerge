#version 330 core

in vec2 vLocal;
in float vPrimaryId;
in float vAlpha;
out vec4 fragColor;

// Procedural planet rendering: surface with angular segments + depth-based brightness,
// fading to an atmospheric halo in the outer ring [planetFrac, 1.0].
void main() {
    float lenSquared = dot(vLocal, vLocal);

    // Atmosphere occupies the outer ring from planetFrac to 1.0
    float planetFrac = 0.871;
    float planetFracSq = planetFrac * planetFrac;
    float surfaceDepthFrac = 0.04;

    if (lenSquared >= 1.0) {
        discard;
    }

    if (lenSquared < planetFracSq) {
        // Surface region
        float r = sqrt(lenSquared);
        float baseColor = 1.0 - (planetFrac - r) / surfaceDepthFrac;
        baseColor = clamp(baseColor, 0.1, 1.0);

        float angle = atan(vLocal.x, vLocal.y);
        float segments = 3.0;
        float segColor = (sin(angle * segments) + 1.0) * 0.2;

        vec3 surfaceColor = vec3(segColor + 0.4, 0.5, 0.18) * baseColor;
        fragColor = vec4(surfaceColor, vAlpha);
    } else {
        // Atmosphere region
        vec4 skyColor = vec4(0.7, 0.65, 1.4, 1.1);
        float r = sqrt(lenSquared);
        float atmosphereFrac = 1.0 - planetFrac;
        float distToSpace = (1.0 - r) / atmosphereFrac;
        fragColor = skyColor * distToSpace * vAlpha;
    }
}
