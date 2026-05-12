#version 330 core

uniform vec2 uVpMin;
uniform vec2 uVpMax;

out vec4 fragColor;

void main() {
    vec2 resolution = uVpMax-uVpMin;
    vec2 uv = (gl_FragCoord.xy-uVpMin) / min(resolution.x, resolution.y);

    fragColor = vec4(mod(uv, 1.0), 0.0, 1.0);
}
