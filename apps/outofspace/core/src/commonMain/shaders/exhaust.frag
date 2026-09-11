#version 330 core

// ── What a jet of hot gas looks like ──────────────────────────────────────────
//
// ⚠️ **This half is meant to be rewritten.** Everything above it — the triangle, the plume-space
// frame, the parameters — is structure; this is one plausible flame drawn with those parameters, put
// here so the pipeline has something to show rather than because it is the right look. Tune freely:
// nothing outside this file reads anything it does.
//
// Drawn for ADDITIVE blending (`setBlendFuncSrcAlphaOne`), so the alpha out is the intensity and the
// colour is never darker than what it is drawn over. A plume is light.

in vec2 vPlume;   // plume space: x along (0 = tip, 1 = mouth), y across
in vec4 vFlow;    // km/s, kilokelvin, kg/tick, throttle
in vec4 vStuff;   // species rgb, molar mass g/mol
in vec4 vShape;   // length tiles, mouth width tiles, nozzle anchor, seed

out vec4 fragColor;

/** Seconds since the renderer started, wrapped — the only thing here that moves on its own. */
uniform float uTime;

// Cheap value noise: a hashed lattice, smoothed. Two octaves is enough for a flame at the zoom
// anybody plays at, and it costs no texture and no uniform.
float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

/**
 * Roughly what a black body of this temperature looks like, in the range a chamber lives in.
 * Deep orange at a thousand kelvin, straw at three, blue-white above five.
 */
vec3 heatColor(float kiloKelvin) {
    vec3 ember = vec3(1.0, 0.35, 0.08);
    vec3 straw = vec3(1.0, 0.82, 0.45);
    vec3 arc   = vec3(0.62, 0.78, 1.0);
    float a = smoothstep(0.4, 2.6, kiloKelvin);
    float b = smoothstep(2.6, 6.0, kiloKelvin);
    return mix(mix(ember, straw, a), arc, b);
}

void main() {
    float anchor = vShape.z;
    float seed = vShape.w;
    float speed = vFlow.x;
    float kiloKelvin = vFlow.y;
    float flow = vFlow.z;
    float throttle = vFlow.w;

    // Where this fragment is in the jet. `across` is exact rather than interpolated: the triangle's
    // edges are y = ±x/2 by construction, so the ratio is ±1 on them at every length.
    float along = vPlume.x;
    float across = vPlume.y / max(along * 0.5, 1e-5);

    // Nothing upstream of the nozzle: the tip of the triangle is inside the machine.
    if (along < anchor) discard;
    float t = clamp((along - anchor) / max(1.0 - anchor, 1e-5), 0.0, 1.0);

    // The jet narrows to a throat just past the bell and spreads downstream, so the useful width is
    // not the triangle's. Everything outside it is cut rather than faded — a plume has an edge.
    float spread = mix(0.35, 1.0, t);
    float r = abs(across) / spread;
    if (r > 1.0) discard;

    // Turbulence: a noise field scrolling downstream at the exhaust's own speed, so a fast jet
    // shimmers and a cold puff wallows. The lateral term is stretched, which is what makes it read
    // as streaks rather than as clouds. The seed decorrelates two motors of the same kind.
    float scroll = uTime * (1.5 + speed * 1.1) + seed * 37.0;
    float n = valueNoise(vec2(t * 9.0 - scroll, across * 2.5 + seed * 11.0));
    n = mix(n, valueNoise(vec2(t * 21.0 - scroll * 2.0, across * 5.0)), 0.4);

    // Bright core, soft flanks, and a tail that gives out. The core survives further down a fast
    // jet than a slow one.
    float core = pow(1.0 - r * r, 2.0);
    float reachFade = 1.0 - smoothstep(mix(0.35, 0.85, clamp(speed / 6.0, 0.0, 1.0)), 1.0, t);
    float turbulence = mix(0.65, 1.35, n);

    // A light molecule burns clean and pale; a heavy one is sooty and dulls the plume. The species
    // tint is what says "this is not hydrogen" at a glance.
    float heavy = clamp(vStuff.w / 60.0, 0.0, 1.0);
    vec3 colour = mix(heatColor(kiloKelvin), vStuff.rgb, 0.25 + 0.45 * heavy);
    // The very middle of the jet washes out towards white, which is what makes a hot plume read as
    // hot rather than as merely orange.
    colour = mix(colour, vec3(1.0), core * core * smoothstep(1.2, 4.0, kiloKelvin));

    float thick = clamp(flow * 2.0, 0.0, 1.0);
    float intensity = core * reachFade * turbulence * throttle * mix(0.35, 1.0, thick);
    if (intensity <= 0.002) discard;

    fragColor = vec4(colour, clamp(intensity, 0.0, 1.0));
}
