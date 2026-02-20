package org.emerge.render.torus

object ShaderSources {
    fun vertexGles2(): String =
        """
        attribute vec2 aPos;
        void main() {
            gl_Position = vec4(aPos, 0.0, 1.0);
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
        uniform vec4 uBodies[MAX_BODIES]; // x,y,r,playerId

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
            
            vec3 col = vec3(0.0);
            float best = 1e30;
            vec2 guv = cover;

            bool occluded = false;
            for (int i = 0; i < uBodyCount; i++) {
                vec4 b = uBodies[i];
                float dx = wrapDelta(p.x - b.x, uWorld.x);
                float dy = wrapDelta(p.y - b.y, uWorld.y);
                float d2 = dx*dx + dy*dy;
                float r2 = b.z*b.z;
                if (d2 <= r2 && d2 < best) {
                    best = d2;
                    float w = b.w+3.0;
                    float a = (1.0 - d2/(r2*1.5));
                    col = mod(vec3(w/1.9, w/2.9, w/4.9),1.0)*a;
                    occluded = true;
                }
                guv -= vec2(dx, dy)*r2*b.z/(10.0*d2*d2);
            }
            guv = wrap2(guv, uWorld);
            
            if (!occluded) {
                float freq_maj = 1.0;
                float freq_min = 8.0;
                
                vec2 uv_maj = abs(mod(guv/uWorld*freq_maj,1.0)-0.5);
                float grid_maj = max(uv_maj.x, uv_maj.y)*2.0;
                vec2 uv_min = abs(mod(guv/uWorld*freq_min,1.0)-0.5);
                float grid_min = max(uv_min.x, uv_min.y)*2.0;
                
                float zoom = uView.y;
                float scale_maj = zoom*freq_maj/64.0;
                float scale_min = zoom*freq_min/64.0;
                
                float col_maj = (grid_maj-(1.0-1.0*scale_maj))/(scale_maj*max(zoom, 0.5));
                float col_min = (grid_min-(1.0-1.0*scale_min))/(scale_min*max(zoom, 0.25)*4.0);
                
                float grid = max(col_maj, col_min);
                
                col = vec3(grid);
            }
            gl_FragColor = vec4(col, 1.0);
        }
        """.trimIndent()
}