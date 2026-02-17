package org.emerge.render.torus

object Gl330ShaderSources {
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

            vec3 col = vec3(0f);
            float best = 1e30;
            vec2 guv = cover;

            for (int i = 0; i < uBodyCount; i++) {
                vec4 b = uBodies[i];
                float dx = wrapDelta(p.x - b.x, uWorld.x);
                float dy = wrapDelta(p.y - b.y, uWorld.y);
                float d2 = dx*dx + dy*dy;
                float r2 = b.z*b.z;
                if (d2 <= r2 && d2 < best) {
                    best = d2;
                    float w = b.w+3.0;
                    float a = (1f-d2*100f);
                    col = mod(vec3(w/1.9, w/2.9, w/4.9),1.0)*a;
                }
                guv -= vec2(dx, dy)/(d2*d2*20000.0);
            }
            guv = wrap2(guv, uWorld);
            
            if (best == 1e30) {
                float freq_maj = 1.0;
                float freq_min = 8.0;
                
                vec2 uv_maj = abs(mod(guv/uWorld*freq_maj,1.0)-0.5);
                float grid_maj = max(uv_maj.x, uv_maj.y)*2.0;
                vec2 uv_min = abs(mod(guv/uWorld*freq_min,1.0)-0.5);
                float grid_min = max(uv_min.x, uv_min.y)*2.0;
                
                float scale_maj = freq_maj/32.0;
                float scale_min = freq_min/64.0;
                
                float grid = max((grid_maj-(1.0-1.0*scale_maj))/scale_maj, (grid_min-(1.0-1.0*scale_min))/(scale_min*2.0));
                
                col = vec3(grid);
            }
            FragColor = vec4(col, 1.0);
        }
        """.trimIndent()
}