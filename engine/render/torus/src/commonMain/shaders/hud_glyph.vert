#version 330 core
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 iCenter;
layout(location = 2) in vec2 iHalfSize;
layout(location = 3) in vec4 iUvRect;

out vec2 vUv;

void main() {
    vec2 localUv = vec2(
        aPos.x * 0.5 + 0.5,
        1.0 - (aPos.y * 0.5 + 0.5)
    );
    vUv = iUvRect.xy + localUv * iUvRect.zw;
    gl_Position = vec4(iCenter + aPos * iHalfSize, 0.0, 1.0);
}
