#version 330 core

in vec2 vLocal; // local [-1,1] coords for circle test
in float vShape; // 0 = rect, 1 = disc, 2 = semi-disc
in vec4 vColor;

out vec4 fragColor;

void main() {
    if (vShape < 0.5) {
        fragColor = vColor;
    } else if (vShape < 1.5) {
        // Soft tinted disc (particles, force fields).
        if (dot(vLocal, vLocal) > 1.0) {
            discard;
        }
        float a = min(0.75, 1.0 - dot(vLocal, vLocal) / 1.5);
        fragColor = vec4(vColor.r * a, vColor.g * a, vColor.b * a, vColor.a);
    } else {
        vec2 offsetLocal = vec2(vLocal.x/2.0-0.5, vLocal.y);
        if (dot(offsetLocal, offsetLocal) > 1.0) {
            discard;
        }
        float a = min(0.75, 1.0 - dot(offsetLocal, offsetLocal) / 1.5);
        fragColor = vec4(vColor.r * a, vColor.g * a, vColor.b * a, vColor.a);
    }
}
