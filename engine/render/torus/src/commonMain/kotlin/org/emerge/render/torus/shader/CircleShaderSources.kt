package org.emerge.render.torus.shader

import org.emerge.render.torus.GPU

object CircleShaderSources {
    fun vertex(): String = vertex(GPU.shaderVersion)
    private fun vertex(version: String): String =
        """
        #version $version
        layout(location = 0) in vec2 aPos;
        layout(location = 1) in vec4 iCol0;
        layout(location = 2) in vec4 iCol1;
        layout(location = 3) in vec4 iCol2;
        layout(location = 4) in vec4 iCol3;
        layout(location = 5) in float iPrimaryId;
        layout(location = 6) in float iSecondaryId;
        layout(location = 7) in float iBodyShape;
        layout(location = 8) in float iBodyAlpha;

        out vec2 vLocal;
        out float vPrimaryId;
        out float vSecondaryId;
        out float vBodyShape;
        out float vBodyAlpha;
        out float vInstanceId;
        void main() {
            mat4 m = mat4(iCol0, iCol1, iCol2, iCol3);
            gl_Position = m * vec4(aPos, 0.0, 1.0);
            vLocal = aPos;
            vPrimaryId = iPrimaryId;
            vSecondaryId = iSecondaryId;
            vBodyShape = iBodyShape;
            vBodyAlpha = iBodyAlpha;
            vInstanceId = gl_InstanceID;
        }
        """.trimIndent()

    fun fragment(): String = fragment(GPU.shaderVersion)
    private fun fragment(version: String): String =
        """
        #version $version
        ${precisionBlock(version)}

        in vec2 vLocal; // local [-1,1] coords for circle test
        in float vPrimaryId;
        in float vSecondaryId;
        in float vBodyShape;
        in float vBodyAlpha;
        in float vInstanceId;
        out vec4 fragColor;
        
        vec3 mod289(vec3 v) {
            return v - floor(v / 289.0) * 289.0;
        }
        
        vec4 mod289(vec4 v) {
            return v - floor(v / 289.0) * 289.0;
        }
        
        vec3 permute(vec3 v) {
            return mod289((v * 34.0 + 1.0) * v);
        }
        
        vec4 permute(vec4 v) {
            return mod289((v * 34.0 + 1.0) * v);
        }
        
        vec4 taylorInvSqrt(vec4 v) {
            return 1.79284291400159 - v * 0.85373472095314;
        }
        
        float snoise(vec2 v2) {
            vec3 v = vec3(v2, 1.0);
            const vec2 C = vec2(1.0/6.0, 1.0/3.0);
            
            vec3 i = floor(v + dot(v, C.yyy));
            vec3 x0 = v - i + dot(i, C.xxx);
            
            vec3 g = step(x0.yzx, x0.xyz);
            vec3 l = 1.0 - g;
            vec3 i1 = min(g.xyz, l.zxy);
            vec3 i2 = max(g.xyz, l.zxy);

            vec3 x1 = x0 - i1 + C.xxx;
            vec3 x2 = x0 - i2 + C.yyy;
            vec3 x3 = x0 - 0.5;
            
            i = mod289(i);
            vec4 p = permute(permute(permute(i.z + vec4(0.0, i1.z, i2.z, 1.0))
                                            + i.y + vec4(0.0, i1.y, i2.y, 1.0))
                                            + i.x + vec4(0.0, i1.x, i2.x, 1.0));
            
            vec4 j = p - 49.0 * floor(p / 49.0);
            
            vec4 x_ = floor(j / 7.0);
            vec4 y_ = floor(j - 7.0 * x_);
            vec4 x = (x_ * 2.0 + 0.5) / 7.0 - 1.0;
            vec4 y = (y_ * 2.0 + 0.5) / 7.0 - 1.0;
            
            vec4 h = 1.0 - abs(x) - abs(y);
            
            vec4 b0 = vec4(x.xy, y.xy);
            vec4 b1 = vec4(x.zw, y.zw);
            
            vec4 s0 = floor(b0) * 2.0 + 1.0;
            vec4 s1 = floor(b1) * 2.0 + 1.0;
            vec4 sh = -step(h, vec4(0.0));
            
            vec4 a0 = b0.xzyw + s0.xzyw * sh.xxyy;
            vec4 a1 = b1.xzyw + s1.xzyw * sh.zzww;
            
            vec3 g0 = vec3(a0.xy, h.x);
            vec3 g1 = vec3(a0.zw, h.y);
            vec3 g2 = vec3(a1.xy, h.z);
            vec3 g3 = vec3(a1.zw, h.w);
            
            vec4 norm = taylorInvSqrt(vec4(dot(g0, g0), dot(g1, g1), dot(g2, g2), dot(g3, g3)));
            g0 *= norm.x;
            g1 *= norm.y;
            g2 *= norm.z;
            g3 *= norm.w;
            
            vec4 m = max(0.6- vec4(dot(x0, x0), dot(x1, x1), dot(x2, x2), dot(x3, x3)), 0.0);
            m = m*m;
            m = m*m;
            
            vec4 px = vec4(dot(x0, g0), dot(x1, g1), dot(x2, g2), dot(x3, g3));
            
            return 42.0 * dot(m, px);
        }

        void main() {
            float colorSeed = vPrimaryId;
            float c = colorSeed + 1.0;
            vec3 vColor = mod(vec3(c/1.9, c/2.9, c/4.9),1.0);
            if (vBodyShape > 0.5) {
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
                float body_color = max(0.0, window_scalar);
                float window_color = max(0.0, 1.0-window_scalar);
                float wColorSeed = vSecondaryId;
                float w = -wColorSeed - 1.0;
                vec3 wColor = mod(vec3(w/1.9, w/2.9, w/4.9),1.0);
                vec3 rocketColor = vColor * body_color + wColor * window_color;
                fragColor = vec4(rocketColor, vBodyAlpha);
            } else {
                float a = min(0.75, (1.0 - dot(vLocal, vLocal)/(1.5)));
                if (vBodyAlpha < 1.0) {
                    if (dot(vLocal, vLocal) <= 1.0) {
                        fragColor = vec4(vColor * a, vBodyAlpha);
                    } else {
                        discard;
                    }
                } else {
                    float n = (snoise((vLocal + vInstanceId)) + snoise((vLocal + vInstanceId)*4.0)+3.0)*a;
                    if (n < 1.0) {
                        discard;
                    }
                    fragColor = vec4(vColor*(n-1.1), vBodyAlpha);
                }
            }
        }
        """.trimIndent()

    private fun precisionBlock(version: String): String =
        if (version.contains("es")) {
            "precision mediump float;"
        } else {
            ""
        }
}
