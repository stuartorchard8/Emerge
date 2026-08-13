#version 330 core

// Instanced solid-colour rectangles in screen NDC, for the on-screen control buttons.
layout(location = 0) in vec2 aPos;       // unit quad [-1, 1]
layout(location = 1) in vec4 iColor;     // rgba
// Instanced transformation matrix columns
layout(location = 2) in vec4 iCol0;
layout(location = 3) in vec4 iCol1;
layout(location = 4) in vec4 iCol2;
layout(location = 5) in vec4 iCol3;

out vec4 vColor;

void main() {
    vColor = iColor;
    mat4 m = mat4(iCol0, iCol1, iCol2, iCol3);
    gl_Position = m * vec4(aPos, 0.0, 1.0);
}
