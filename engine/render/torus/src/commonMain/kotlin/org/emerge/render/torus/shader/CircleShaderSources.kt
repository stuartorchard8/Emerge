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
        layout(location = 9) in float iBodyRadius;

        out vec2 vLocal;
        out float vPrimaryId;
        out float vSecondaryId;
        out float vBodyShape;
        out float vBodyAlpha;
        out float vBodyRadius;
        void main() {
            mat4 m = mat4(iCol0, iCol1, iCol2, iCol3);
            gl_Position = m * vec4(aPos, 0.0, 1.0);
            vLocal = aPos;
            vPrimaryId = iPrimaryId;
            vSecondaryId = iSecondaryId;
            vBodyShape = iBodyShape;
            vBodyAlpha = iBodyAlpha;
            vBodyRadius = iBodyRadius;
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
        in float vBodyRadius;
        in float vInstanceId;
        out vec4 fragColor;

        uniform sampler2D uNoiseTexture;

        float sampleNoise(vec2 uv) {
            return texture(uNoiseTexture, uv).r * 2.0 - 1.0;
        }

        void main() {
            float colorSeed = vPrimaryId;
            float c = colorSeed + 1.0;
            vec3 vColor = mod(vec3(c/1.9, c/2.9, c/4.9),1.0);
            if (vBodyShape > 0.5) {
                if (vBodyAlpha < 1.0) {
                    fragColor = vec4(vColor, vBodyAlpha);
                } else {
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
                    float wColorSeed = vSecondaryId;
                    float w = -wColorSeed - 1.0;
                    vec3 wColor = mod(vec3(w/1.9, w/2.9, w/4.9),1.0);
                    vec3 rocketColor = vColor * window_color + wColor * body_color;
                    fragColor = vec4(rocketColor, 1.0);
                }
            } else {
                float a = min(0.75, (1.0 - dot(vLocal, vLocal)/(1.5)));
                if (vBodyAlpha < 1.0) {
                    if (dot(vLocal, vLocal) <= 1.0) {
                        fragColor = vec4(vColor * a, vBodyAlpha);
                    } else {
                        discard;
                    }
                } else {
                    vec2 noiseOffset = vec2(vSecondaryId * 0.06711056, vSecondaryId * 0.00583715);
                    vec2 noiseUv = vLocal * vBodyRadius + noiseOffset;
                    float n =
                        (
                            sampleNoise(noiseUv * 4.0) +
                            sampleNoise(noiseUv * 16.0 + vec2(0.37, 0.61)) * 0.5 +
                            3.0
                        ) * a;
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
            "precision highp float;\nprecision highp int;"
        } else {
            ""
        }
}
