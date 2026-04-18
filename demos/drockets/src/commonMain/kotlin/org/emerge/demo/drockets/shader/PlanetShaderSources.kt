package org.emerge.demo.drockets.shader

import org.emerge.render.torus.GPU

/**
 * Procedural planet shader ported from Godot's planet.gdshader.
 *
 * Renders a circle with:
 * - Colored surface with angular segments and depth-based brightness
 * - Atmospheric gradient halo fading to transparent
 *
 * Used as a per-instance fragment shader on circle-shaped planet entities.
 * The existing CircleShader passes vLocal ([-1,1] local coords) which maps
 * to Godot's centredUV * 2.
 */
object PlanetShaderSources {
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
        layout(location = 8) in float iBodyAlpha;

        out vec2 vLocal;
        out float vPrimaryId;
        out float vAlpha;

        void main() {
            mat4 m = mat4(iCol0, iCol1, iCol2, iCol3);
            gl_Position = m * vec4(aPos, 0.0, 1.0);
            vLocal = aPos;
            vPrimaryId = iPrimaryId;
            vAlpha = iBodyAlpha;
        }
        """.trimIndent()

    fun fragment(): String = fragment(GPU.shaderVersion)
    private fun fragment(version: String): String =
        """
        #version $version
        ${precisionBlock(version)}

        in vec2 vLocal;
        in float vPrimaryId;
        in float vAlpha;
        out vec4 fragColor;

        void main() {
            float lenSquared = dot(vLocal, vLocal);

            // Atmosphere occupies the outer ring from planetFrac to 1.0
            float planetFrac = 0.871;
            float planetFracSq = planetFrac * planetFrac;
            float surfaceDepthFrac = 0.04;

            if (lenSquared >= 1.0) {
                discard;
            }

            if (lenSquared < planetFracSq) {
                // Surface region
                float r = sqrt(lenSquared);
                float baseColor = 1.0 - (planetFrac - r) / surfaceDepthFrac;
                baseColor = clamp(baseColor, 0.1, 1.0);

                float angle = atan(vLocal.x, vLocal.y);
                float segments = 3.0;
                float segColor = (sin(angle * segments) + 1.0) * 0.2;

                vec3 surfaceColor = vec3(segColor + 0.4, 0.5, 0.18) * baseColor;
                fragColor = vec4(surfaceColor, vAlpha);
            } else {
                // Atmosphere region
                vec4 skyColor = vec4(0.7, 0.65, 1.4, 1.1);
                float r = sqrt(lenSquared);
                float atmosphereFrac = 1.0 - planetFrac;
                float distToSpace = (1.0 - r) / atmosphereFrac;
                fragColor = skyColor * distToSpace * vAlpha;
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
