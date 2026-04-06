package org.emerge.demo.drockets.shader

import org.emerge.render.torus.GPU

/**
 * Volumetric fractal starfield shader ported from Godot's starscape.gdshader.
 *
 * Renders a nebula/starfield background using raymarched fractal space-folding.
 * Adapted from the Godot canvas_item shader to standard GLSL fullscreen quad.
 *
 * Uniforms:
 * - uBearing: camera rotation angle synced from ScreenRenderer
 * - uResolution: screen resolution for aspect ratio correction
 */
object StarscapeShaderSources {
    fun vertex(): String = vertex(GPU.shaderVersion)
    private fun vertex(version: String): String =
        """
        #version $version
        layout(location = 0) in vec2 aPos;
        out vec2 vUv;

        void main() {
            gl_Position = vec4(aPos, 0.0, 1.0);
            vUv = aPos * 0.5 + 0.5;
        }
        """.trimIndent()

    fun fragment(): String = fragment(GPU.shaderVersion)
    private fun fragment(version: String): String =
        """
        #version $version
        ${precisionBlock(version)}

        in vec2 vUv;
        out vec4 fragColor;

        uniform float uBearing;
        uniform vec2 uResolution;

        void main() {
            float theta = uBearing;
            vec2 uv = vUv - 0.5;

            // Aspect ratio correction
            float aspectRatio = uResolution.y / uResolution.x;
            if (aspectRatio > 1.0) {
                uv.y *= aspectRatio;
            } else {
                uv.x /= aspectRatio;
            }

            // Apply camera rotation
            float ct = cos(theta);
            float st = sin(theta);
            uv = vec2(ct * uv.x + st * uv.y, ct * uv.y - st * uv.x);

            vec3 dir = vec3(uv * 10.0, 1.0);
            float time = 0.4;
            vec3 from = vec3(1.0, 0.5, 0.5) + vec3(time * 2.0, time, -2.0);

            vec3 v = vec3(0.0);
            for (int r = 0; r < 18; r++) {
                float depth = 0.13 + float(r) * 0.1;
                float fade = 0.16;
                vec3 p = from + depth * dir * 0.5;
                p = abs(vec3(3.0) - mod(p, vec3(6.0)));
                float pa = 0.0;
                float a = 0.0;
                for (int i = 0; i < 9; i++) {
                    p = abs(p) / dot(p, p) - 0.51;
                    a += abs(length(p) - pa);
                    pa = length(p);
                }
                float dm = max(0.0, 0.1 - a * a * 0.001);
                a *= a * a;
                if (r > 6) fade *= 1.0 - dm;
                v += vec3(dm, dm * 0.5, 0.0);
                v += fade;
                v += vec3(depth, depth * depth, depth * depth * depth) * a * 0.00015 * fade;
            }
            v = mix(vec3(length(v)), v, 0.8);
            fragColor = vec4(v * 0.01, 1.0);
        }
        """.trimIndent()

    private fun precisionBlock(version: String): String =
        if (version.contains("es")) {
            "precision highp float;\nprecision highp int;"
        } else {
            ""
        }
}
