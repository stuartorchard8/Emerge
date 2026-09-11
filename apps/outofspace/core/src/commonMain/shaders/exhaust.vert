#version 330 core

// ── One triangle per plume ────────────────────────────────────────────────────
//
// The base mesh is three vertices in PLUME SPACE, which is the frame everything downstream reasons
// in and the reason this shader needs no trigonometry of its own:
//
//     aPos.x  — along the exhaust. 0 at the TIP (which points back at the motor), 1 at the MOUTH.
//     aPos.y  — across it. 0 on the axis, ±0.5 at the mouth's corners, so the two long edges are
//               exactly y = ±x/2 and the fragment stage can recover "how far across am I" by
//               division rather than by being told.
//
// The instance matrix carries plume space to clip space: the turn, the zoom, the camera, the view
// rotation and the anchor offset are all folded into it on the CPU (see ExhaustShader), so nothing
// here knows where the ship is or which way it is pointing.
layout(location = 0) in vec2 aPos;

// Plume space -> clip, as four columns.
layout(location = 1) in vec4 iCol0;
layout(location = 2) in vec4 iCol1;
layout(location = 3) in vec4 iCol2;
layout(location = 4) in vec4 iCol3;

// What is coming out, in units a person can reason about rather than normalised ones. Physical
// numbers on purpose: the mapping from "9 km/s" to a colour is the thing being tuned, and a shader
// handed a pre-normalised 0..1 has had that decision taken away from it upstream.
//   x — exhaust velocity, km/s      (cold nitrogen ~0.8, hot hydrogen ~9.3)
//   y — temperature, kilokelvin
//   z — mass flow, kg/tick
//   w — throttle, 0..1
layout(location = 5) in vec4 iFlow;

// What it is made of.
//   xyz — the dominant species' colour, 0..1
//   w   — mean molar mass, g/mol (hydrogen 2, water 18, forsterite 140)
layout(location = 6) in vec4 iStuff;

// The geometry the matrix already applied, restated so the fragment stage can use it as a number.
//   x — the triangle's length in tiles
//   y — the triangle's width at the mouth, in tiles
//   z — the plume-space x that sits on the nozzle: everything below this is inside the machine
//   w — a per-plume seed, so two identical motors are not the same flicker
layout(location = 7) in vec4 iShape;

out vec2 vPlume;
out vec4 vFlow;
out vec4 vStuff;
out vec4 vShape;

void main() {
    mat4 m = mat4(iCol0, iCol1, iCol2, iCol3);
    gl_Position = m * vec4(aPos, 0.0, 1.0);
    vPlume = aPos;
    vFlow = iFlow;
    vStuff = iStuff;
    vShape = iShape;
}
