package org.emerge.render.torus.shader

import org.emerge.render.torus.GPU

object CircleShaderSources {
    fun vertex(): String = vertex(GPU.shaderVersion)
    private fun vertex(version: String): String =
        """
        #version $version
        layout(location = 0) in vec2 aPos;
        out vec2 uv;
        void main() {
            gl_Position = vec4(aPos, 0.0, 1.0);
            uv = aPos;
        }
        """.trimIndent()

    fun fragment(): String = fragment(GPU.shaderVersion)
    private fun fragment(version: String): String =
        """
        #version $version
        
        in vec2 uv; // Input from the vertex shader (automatically interpolated)
        out vec4 fragColor;

        void main() {
            if (dot(uv, uv) <= 1) {
                fragColor = vec4(1.0);
            } else {
                discard;
            }
        }
        """.trimIndent()
}
