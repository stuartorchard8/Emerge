#version 330 core

in vec2 vLocal; // local [-1,1] coords
in float vPrimaryId;
in vec3 vSecondaryColor; // body tone, supplied by the caller
in vec3 vTintColor;      // window tone, supplied by the caller
out vec4 fragColor;

void main() {
    // Both tones are supplied by the caller; the shader no longer hashes ids.
    vec3 vColor = vTintColor;

    // Carve the rocket silhouette out of the unit triangle: cone hull minus bell base.
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
    vec3 rocketColor = vColor * window_color + vSecondaryColor * body_color;
    fragColor = vec4(rocketColor, 1.0);
}
