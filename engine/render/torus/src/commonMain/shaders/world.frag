#version 330 core

uniform vec2 uVpMin;
uniform vec2 uVpMax;
uniform vec2 uWorld;
uniform float uZoom;
uniform vec2 uCenter;
uniform float uRotation;

out vec4 fragColor;

vec2 wrap2(vec2 p, vec2 size) {
    vec2 q = mod(p, size);
    if (q.x < 0.0) q.x += size.x;
    if (q.y < 0.0) q.y += size.y;
    return q;
}

float wrapDelta(float d, float size) {
    float halfSize = 0.5 * size;
    float x = mod(d + halfSize, size) - halfSize;
    return x;
}

void main() {
    vec2 resolution = uVpMax - uVpMin;
    float aspect = resolution.x / resolution.y;
    vec2 uv = (gl_FragCoord.xy- uVpMin) / resolution;
    vec2 d = (uv - 0.5);
    vec2 dWorld = d * vec2(uWorld.x*min(aspect, 1.0), -uWorld.y/max(aspect, 1.0)) * uZoom;
    float c = cos(-uRotation), s = sin(-uRotation);
    vec2 dWorldRot = vec2(dWorld.x*c - dWorld.y*s, dWorld.x*s + dWorld.y*c);
    vec2 cover = uCenter + dWorldRot;
    vec2 guv = wrap2(cover, uWorld);

    float freq_maj = 1.0;
    float freq_min = 8.0;

    vec2 uv_maj = abs(mod(guv/uWorld*freq_maj,1.0)-0.5);
    float grid_maj = max(uv_maj.x, uv_maj.y)*2.0;
    vec2 uv_min = abs(mod(guv/uWorld*freq_min,1.0)-0.5);
    float grid_min = max(uv_min.x, uv_min.y)*2.0;

    float scale_maj = uZoom*freq_maj/64.0;
    float scale_min = uZoom*freq_min/64.0;

    float col_maj = (grid_maj-(1.0-1.0*scale_maj))/(scale_maj*max(uZoom, 0.5));
    float col_min = (grid_min-(1.0-1.0*scale_min))/(scale_min*max(uZoom, 0.25)*4.0);

    float grid = max(col_maj, col_min);

    fragColor = vec4(vec3(grid), 1.0);
}
