#version 330 core
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec4 iCol0;
layout(location = 2) in vec4 iCol1;
layout(location = 3) in vec4 iCol2;
layout(location = 4) in vec4 iCol3;
layout(location = 5) in float iPrimaryId;
layout(location = 6) in vec3 iSecondaryColor;
layout(location = 7) in vec3 iTintColor;

out vec2 vLocal;
out float vPrimaryId;
out vec3 vSecondaryColor;
out vec3 vTintColor;
void main() {
    mat4 m = mat4(iCol0, iCol1, iCol2, iCol3);
    gl_Position = m * vec4(aPos, 0.0, 1.0);
    vLocal = aPos;
    vPrimaryId = iPrimaryId;
    vSecondaryColor = iSecondaryColor;
    vTintColor = iTintColor;
}
