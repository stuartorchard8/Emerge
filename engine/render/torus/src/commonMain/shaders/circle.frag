#version 330 core

in vec2 vLocal; // local [-1,1] coords for circle test
in float vPrimaryId;
in float vSecondaryId;
in float vBodyShape;
in float vBodyAlpha;
in float vBodyRadius;
in vec3 vTintColor;
in float vInstanceId;
out vec4 fragColor;

uniform sampler2D uNoiseTexture;

float sampleNoise(vec2 uv) {
    return texture(uNoiseTexture, uv).r * 2.0 - 1.0;
}

void main() {
    float colorSeed = vPrimaryId;
    float c = colorSeed + 1.0;
    vec3 seededColor = mod(vec3(c/1.9, c/2.9, c/4.9),1.0);
    bool hasCustomTint = max(vTintColor.r, max(vTintColor.g, vTintColor.b)) > 0.0;
    vec3 vColor = hasCustomTint ? vTintColor : seededColor;
    if (vBodyShape > 0.5) {
        if (vBodyAlpha < 1.0) {
            fragColor = vec4(vColor, vBodyAlpha);
        } else {
            vec2 cone = vec2(vLocal.x/3.0+0.5, vLocal.y/1.25);
            if (dot(cone, cone) > 1.0) {
                discard;
            }
            vec2 bell = vec2((vLocal.x+1.0)*1.5, vLocal.y*1.25);
            if (dot(bell, bell) < 1.0) {
                discard;
            }
            vec2 window = vec2(vLocal.x*1.33-0.8, vLocal.y*2.25);
            float window_scalar = dot(window, window);
            float body_color = min(1.0, max(0.0, window_scalar));
            float window_color = max(0.0, 1.0-window_scalar);
            float wColorSeed = vSecondaryId;
            float w = -wColorSeed - 1.0;
            vec3 wColor = mod(vec3(w/1.9, w/2.9, w/4.9),1.0);
            vec3 rocketColor = vColor * window_color + wColor * body_color;
            fragColor = vec4(rocketColor, 1.0);
        }
    } else {
        float a = min(0.75, (1.0 - dot(vLocal, vLocal)/(1.5)));
        if (vBodyAlpha < 1.0) {
            if (dot(vLocal, vLocal) <= 1.0) {
                fragColor = vec4(vColor * a, vBodyAlpha);
            } else {
                discard;
            }
        } else {
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
            fragColor = vec4(vColor*(n-1.1), vBodyAlpha);
        }
    }
}
