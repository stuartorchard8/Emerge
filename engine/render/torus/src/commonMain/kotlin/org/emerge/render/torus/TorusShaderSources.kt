package org.emerge.render.torus

object TorusShaderSources {
    fun vertexGles2(): String =
        """
        attribute vec2 aPos;
        void main() {
            gl_Position = vec4(aPos, 0.0, 1.0);
        }
        """.trimIndent()

    /**
     * @param maxBodies must be a compile-time constant in GLSL ES (used in `#define MAX_BODIES`).
     */
    fun fragmentGles2(maxBodies: Int): String =
        """
        precision mediump float;
        precision mediump int;
        #define MAX_BODIES $maxBodies

        uniform vec2 uResolution;
        uniform vec2 uWorld;
        uniform vec2 uView;
        uniform vec2 uCenter;
        uniform int uBodyCount;
        uniform int uMyId;
        uniform vec4 uBodies[MAX_BODIES];

        vec2 wrap2(vec2 p, vec2 size) {
            vec2 q = mod(p, size);
            if (q.x < 0.0) q.x += size.x;
            if (q.y < 0.0) q.y += size.y;
            return q;
        }

        float wrapDelta(float d, float size) {
            float halfSize = 0.5 * size;
            float x = mod(d + halfSize, size) - halfSize;
            return x;
        }

        void main() {
            float aspect = uResolution.x / uResolution.y;
            vec2 uv = gl_FragCoord.xy / uResolution;
            vec2 cover = uCenter + (uv - 0.5) * vec2(uView.x*aspect, -uView.y);
            vec2 p = wrap2(cover, uWorld);

            vec3 col = vec3(mod(p/100.0,1.0), 0.0);
            float best = 1e30;

            for (int i = 0; i < MAX_BODIES; i++) {
                if (i >= uBodyCount) break;
                vec4 b = uBodies[i];
                float dx = wrapDelta(p.x - b.x, uWorld.x);
                float dy = wrapDelta(p.y - b.y, uWorld.y);
                float d2 = dx*dx + dy*dy;
                float r2 = b.z*b.z;
                if (d2 <= r2 && d2 < best) {
                    best = d2;
                    int pid = int(b.w + 0.5);
                    if (pid == uMyId) col = vec3(0.18, 0.53, 0.67);
                    else col = vec3(0.80, 0.80, 0.80);
                }
            }
            gl_FragColor = vec4(col, 1.0);
        }
        """.trimIndent()

    fun vertexGl330(): String =
        """
        #version 330 core
        void main() {
            // fullscreen triangle
            vec2 p;
            if (gl_VertexID == 0) p = vec2(-1.0, -1.0);
            else if (gl_VertexID == 1) p = vec2(3.0, -1.0);
            else p = vec2(-1.0, 3.0);
            gl_Position = vec4(p, 0.0, 1.0);
        }
        """.trimIndent()

    /**
     * Desktop GL uses `#version 330 core` and can use `MAX_BODIES` as a literal in the uniform array.
     */
    fun fragmentGl330(maxBodies: Int): String =
        """
        #version 330 core
        out vec4 FragColor;

        uniform vec2 uResolution;
        uniform vec2 uWorld;
        uniform vec2 uView;
        uniform vec2 uCenter;

        uniform int uBodyCount;
        uniform int uMyId;
        uniform vec4 uBodies[$maxBodies]; // x,y,r,playerId

        vec2 wrap2(vec2 p, vec2 size) {
            vec2 q = mod(p, size);
            if (q.x < 0.0) q.x += size.x;
            if (q.y < 0.0) q.y += size.y;
            return q;
        }

        float wrapDelta(float d, float size) {
            float halfSize = 0.5 * size;
            float x = mod(d + halfSize, size) - halfSize;
            return x;
        }

        void main() {
            float aspect = uResolution.x / uResolution.y;
            vec2 uv = gl_FragCoord.xy / uResolution;
            vec2 cover = uCenter + (uv - 0.5) * vec2(uView.x*aspect, -uView.y);
            vec2 p = wrap2(cover, uWorld);

            vec3 col = vec3(mod(p/100.0,1.0), 0.0);
            float best = 1e30;

            for (int i = 0; i < uBodyCount; i++) {
                vec4 b = uBodies[i];
                float dx = wrapDelta(p.x - b.x, uWorld.x);
                float dy = wrapDelta(p.y - b.y, uWorld.y);
                float d2 = dx*dx + dy*dy;
                float r2 = b.z*b.z;
                if (d2 <= r2 && d2 < best) {
                    best = d2;
                    int pid = int(b.w + 0.5);
                    if (pid == uMyId) col = vec3(0.18, 0.53, 0.67);
                    else col = vec3(0.80, 0.80, 0.80);
                }
            }

            FragColor = vec4(col, 1.0);
        }
        """.trimIndent()
}
