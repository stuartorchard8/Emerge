#version 330 core
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec4 iCol0;
layout(location = 2) in vec4 iCol1;
layout(location = 3) in vec4 iCol2;
layout(location = 4) in vec4 iCol3;
layout(location = 5) in float iPrimaryId;
layout(location = 6) in float iUvX;
layout(location = 7) in float iUvY;
layout(location = 8) in float iUvW;
layout(location = 9) in float iUvH;
layout(location = 10) in float iBodyAlpha;
layout(location = 11) in float iSquash;
layout(location = 12) in vec3 iTintColor;

out vec2 vUv;
out float vPrimaryId;
out float vAlpha;
out vec3 vTintColor;

uniform vec2 uFrameSize;

void main() {
    mat4 m = mat4(iCol0, iCol1, iCol2, iCol3);
    vec2 phasedPos = aPos;
    if (phasedPos.x > 0) {
        phasedPos.x -= 2.0*iSquash;
    }
    gl_Position = m * vec4(phasedPos*vec2(iUvW, iUvH), 0.0, 1.0);
    // Map local [-1,1] quad to UV space for the current animation frame
    // Rotate aPos 90 degrees cw for up-as-forward
    vec2 aPos90 = vec2(aPos.y, -aPos.x);
    vec2 localUv = aPos90 * 0.5 + 0.5;
    vUv = vec2(iUvX, iUvY) + localUv * uFrameSize;
    vPrimaryId = iPrimaryId;
    vAlpha = iBodyAlpha;
    vTintColor = iTintColor;
}
