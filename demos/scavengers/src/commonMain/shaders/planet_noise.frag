#version 330 core

in vec2 vLocal; // local [-1,1] coords
in float vPrimaryId;
in float vSecondaryId;
in float vBodyRadius;
in vec3 vTintColor;
out vec4 fragColor;

uniform sampler2D uNoiseTexture;

float sampleNoise(vec2 uv) {
    return texture(uNoiseTexture, uv).r * 2.0 - 1.0;
}

void main() {
    // Surface color comes from the caller-supplied tint.
    vec3 vColor = vTintColor;

    float a = min(0.75, (1.0 - dot(vLocal, vLocal)/(1.5)));
    vec2 noiseOffset = vec2(vSecondaryId * 0.06711056, vSecondaryId * 0.00583715);
    vec2 noiseUv = vLocal * vBodyRadius + noiseOffset;
    float n =
        (
            sampleNoise(noiseUv * 4.0) +
            sampleNoise(noiseUv * 16.0 + vec2(0.37, 0.61)) * 0.5 +
            3.0
        ) * a;
    if (n < 1.0) {
        discard;
    }
    fragColor = vec4(vColor*(n-1.1), 1.0);
}
