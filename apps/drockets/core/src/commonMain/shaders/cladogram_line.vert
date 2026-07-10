#version 330 core
// Attribute locations match CladogramLineShader.ATTR_WHICH / ATTR_ENDPOINTS / ATTR_COLOR.
// Keep these in sync with that companion object if the layout ever changes.
layout(location = 0) in float aWhich;
layout(location = 1) in vec4 iEndpoints;
layout(location = 2) in vec4 iColor;
flat out vec4 vColor;
void main() {
    vColor = iColor;
    vec2 p = aWhich < 0.5 ? iEndpoints.xy : iEndpoints.zw;
    gl_Position = vec4(p, 0.0, 1.0);
}
