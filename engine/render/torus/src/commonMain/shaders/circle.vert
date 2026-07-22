#version 330 core
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec4 iCol0;
layout(location = 2) in vec4 iCol1;
layout(location = 3) in vec4 iCol2;
layout(location = 4) in vec4 iCol3;
layout(location = 5) in float iShapeParam;
layout(location = 6) in float iShape;
layout(location = 7) in float iAlpha;
layout(location = 8) in vec3 iTintColor;

out vec2 vLocal;
out float vShapeParam;
out float vShape;
out float vAlpha;
out vec3 vTintColor;
void main() {
    mat4 m = mat4(iCol0, iCol1, iCol2, iCol3);
    gl_Position = m * vec4(aPos, 0.0, 1.0);
    vLocal = aPos;
    vShapeParam = iShapeParam;
    vShape = iShape;
    vAlpha = iAlpha;
    vTintColor = iTintColor;
}
