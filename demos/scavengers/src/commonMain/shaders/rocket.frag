#version 330 core

in vec2 vLocal; // local [-1,1] coords
in float vPrimaryId;
in float vSecondaryId;
in vec3 vTintColor;
out vec4 fragColor;

void main() {
    // Body color: caller-supplied tint, else a stable hash of the primary id.
    float c = vPrimaryId + 1.0;
    vec3 seededColor = mod(vec3(c/1.9, c/2.9, c/4.9), 1.0);
    bool hasCustomTint = max(vTintColor.r, max(vTintColor.g, vTintColor.b)) > 0.0;
    vec3 vColor = hasCustomTint ? vTintColor : seededColor;

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
    float w = -vSecondaryId - 1.0;
    vec3 wColor = mod(vec3(w/1.9, w/2.9, w/4.9), 1.0);
    vec3 rocketColor = vColor * window_color + wColor * body_color;
    fragColor = vec4(rocketColor, 1.0);
}
