package org.emerge.render.torus.shader

object WorldShaderSources {
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
        layout(location = 0) in vec2 aPos;
        void main() {
            gl_Position = vec4(aPos, 0.0, 1.0);
        }
        """.trimIndent()

    fun fragmentGles2(maxBodies: Int): String =
        """
        precision mediump float;
        precision mediump int;
        #define MAX_BODIES $maxBodies

        uniform vec2 uVpMin;
        uniform vec2 uVpMax;
        uniform vec2 uWorld;
        uniform float uZoom;
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
            vec2 resolution = uVpMax - uVpMin;
            float aspect = resolution.x / resolution.y;
            vec2 uv = (gl_FragCoord.xy- uVpMin) / resolution;
            vec2 cover = uCenter + (uv - 0.5) * vec2(uWorld.x*min(aspect, 1.0), -uWorld.y/max(aspect, 1.0)) * uZoom;
            vec2 p = wrap2(cover, uWorld);
            
            bool occluded = false;
            float bestId = 0.0;
            float bestR2 = 0.0;
            float bestD2 = 0.0;
            
            float best = 1e30;
            vec2 guv = cover;

            for (int i = 0; i < uBodyCount; i++) {
                vec4 b = uBodies[i];
                float dx = wrapDelta(p.x - b.x, uWorld.x);
                float dy = wrapDelta(p.y - b.y, uWorld.y);
                float d2 = dx*dx + dy*dy;
                float r2 = b.z*b.z;
                bool isBest = d2 <= r2 && d2 < best;
                occluded = occluded || isBest;
                if (isBest) {
                    bestId = b.w;
                    bestR2 = r2;
                    bestD2 = d2;
                }
                
                guv -= vec2(dx, dy)*r2*b.z/(10.0*d2*d2);
            }
            guv = wrap2(guv, uWorld);
            
            vec3 col;
            if (occluded) {
                float a = (1.0 - bestD2/(bestR2*1.5));
                float c = bestId+3.0;
                col = mod(vec3(c/1.9, c/2.9, c/4.9),1.0)*a;
            } else {
                float freq_maj = 1.0;
                float freq_min = 8.0;
                
                vec2 uv_maj = abs(mod(guv/uWorld*freq_maj,1.0)-0.5);
                float grid_maj = max(uv_maj.x, uv_maj.y)*2.0;
                vec2 uv_min = abs(mod(guv/uWorld*freq_min,1.0)-0.5);
                float grid_min = max(uv_min.x, uv_min.y)*2.0;
                
                float scale_maj = uZoom*freq_maj/64.0;
                float scale_min = uZoom*freq_min/64.0;
                
                float col_maj = (grid_maj-(1.0-1.0*scale_maj))/(scale_maj*max(uZoom, 0.5));
                float col_min = (grid_min-(1.0-1.0*scale_min))/(scale_min*max(uZoom, 0.25)*4.0);
                
                float grid = max(col_maj, col_min);
                
                col = vec3(grid);
            }
            gl_FragColor = vec4(col, 1.0);
        }
        """.trimIndent()
}