#version 330 core

// Ported verbatim from Cyto's cell.frag (LibGDX GLSL ES) to GL 3.3 core:
//   varying -> in, gl_FragColor -> out fragColor, texture2D -> texture.
// The membrane-blend logic (drawConnection) is unchanged — it draws each cell as a
// soft disc and carves smooth "necks" toward connected neighbour cells so a cluster
// reads as one membrane. Neighbour positions/radii arrive as world-space deltas
// divided by (u_radius * 2) in this shader, exactly as the original.

in vec2 v_texCoords;
out vec4 fragColor;

uniform sampler2D u_texture;
uniform float u_radius;
uniform vec4 u_color;
uniform float u_charge;
// Membrane thickness in logical world units. <= 0 fills the body solid.
uniform float u_border;

const float FAR = 1e9;

const int MAX_NEIGHBOURS = 8;
uniform int u_neighbourCount;
// xy = relative neighbour position (world delta), z = neighbour radius. Packed into a
// vec4 array so the renderer can upload it with a single vec4 uniform call.
uniform vec4 u_neighbour[MAX_NEIGHBOURS];

// Inside-depth (uv units) of the neck toward one neighbour: the distance from uv to the
// nearer of the two outer tangent lines, or 0.0 when uv is outside the neck. `owned` is
// cleared when uv sits past the mid-plane, i.e. when the neighbour draws this fragment.
// The tangent lines are shared geometry between the two cells, so the depths — and hence
// the membrane bands — line up across that seam.
//
// `seam` returns the distance to the mid-plane where this cell hands over to the
// neighbour, or FAR when that plane doesn't bound uv. It is halved, so this cell and the
// neighbour each lay down half a membrane and their bands sum to u_border across the join.
float drawConnection(vec2 uv, float r1, float r2, vec2 c1, out bool owned, out float seam) {
    owned = true;
    seam = FAR;
    if (c1.x == 0.0 && c1.y == 0.0) {
        return 0.0;
    }

    float a = atan(c1.y, c1.x);
    float rm = length(c1) / 2.0;
    float r12 = r1 - r2;
    float t = acos(r12 / (2.0 * rm));
    vec2 pNorm = vec2(cos(a + t), sin(a + t));
    vec2 pNorm_ = vec2(cos(a - t), sin(a - t));

    vec2 c1Edge = pNorm * r1;
    vec2 c2Edge = pNorm * r2 + c1;
    vec2 c1Edge_ = pNorm_ * r1;
    vec2 c2Edge_ = pNorm_ * r2 + c1;

    vec2 tline = c2Edge - c1Edge;
    vec2 tlinePerp = vec2(-tline.y, tline.x);
    float dotPerp = dot(uv - c1Edge, tlinePerp);

    vec2 tline_ = c2Edge_ - c1Edge_;
    vec2 tlinePerp_ = vec2(-tline_.y, tline_.x);
    float dotPerp_ = dot(uv - c1Edge_, tlinePerp_);

    vec2 c1EdgeCenter = (c1Edge + c1Edge_) / 2.0;
    float dot_uv = dot(uv - c1EdgeCenter, c1);

    if (dotPerp < 0.0
    && dotPerp_ > 0.0
    && dot_uv > 0.0
    ) {
        vec2 tmid = (c1Edge + c2Edge) / 2.0;
        vec2 tmid_ = (c1Edge_ + c2Edge_) / 2.0;
        vec2 c12Center = (tmid + tmid_) / 2.0;
        float dot_uv2 = dot(uv - c12Center, c1);
        if (dot_uv2 > 0.0) {
            owned = false;
            return 0.0;
        }
        seam = (-dot_uv2 / length(c1)) * 2.0;
        return min(-dotPerp / length(tlinePerp), dotPerp_ / length(tlinePerp_));
    }
    return 0.0;
}

void main() {
    vec2 uv = v_texCoords - 0.5;
    float len = length(uv);
    float r1 = 0.5 / 2.0;

    fragColor = min(u_color, texture(u_texture, v_texCoords));
    fragColor.rgb *= (2.0 - len - (1.0 / (u_charge / 32.0 + 1.0)));
    fragColor.a = 1.0;

    // Depth of uv inside the body, as a union (max) of the disc and every neck. The disc
    // arc under a neck goes interior, so no membrane is drawn along it.
    float depth = r1 - len;
    // Distance to the nearest seam with a neighbour — an edge of this cell's own shape,
    // clipping the body below, so welded cells stay individually outlined.
    float seams = FAR;

    float divisor = u_radius * 2.0;
    for (int i = 0; i < u_neighbourCount; ++i) {
        float rad = u_neighbour[i].z / divisor;
        vec2 pos = u_neighbour[i].xy / divisor;
        bool owned;
        float seam;
        float neck = drawConnection(uv, r1, rad, pos, owned, seam);
        if (!owned) {
            // Point is closer to the other cell than this one, so the other cell renders it.
            fragColor.a = 0.0;
            return;
        }
        depth = max(depth, neck);
        seams = min(seams, seam);
    }
    depth = min(depth, seams);

    if (depth <= 0.0) {
        fragColor.a = 0.0;
        return;
    }

    float border = u_border / divisor;
    if (border > 0.0 && depth > border) {
        // Interior of the membrane — left transparent for details to be drawn into later.
        fragColor.a = 0.0;
    }
}
