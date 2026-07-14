#version 330 core

// The light-field heatmap as a smooth, gouraud-interpolated mesh. Each vertex carries a
// pre-baked heat colour sampled from the (continuous) light field; the GPU interpolates it
// across every triangle, so the field renders as the continuous energy landscape it is,
// with no visible grid cells. Vertices arrive already projected to screen NDC.
layout(location = 0) in vec2 aPos;    // screen NDC
layout(location = 1) in vec4 aColor;  // pre-baked heat rgba

out vec4 vColor;

void main() {
    vColor = aColor;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
