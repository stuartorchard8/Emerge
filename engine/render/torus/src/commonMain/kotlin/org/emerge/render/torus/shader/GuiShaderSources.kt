package org.emerge.render.torus.shader

import org.emerge.render.torus.GPU

object GuiShaderSources {
    fun vertex(): String = vertex(GPU.shaderVersion)
    private fun vertex(version: String): String =
        """
        #version $version
        layout(location = 0) in vec2 aPos;
        void main() {
            gl_Position = vec4(aPos, 0.0, 1.0);
        }
        """.trimIndent()

    fun fragment(): String = fragment(GPU.shaderVersion)
    private fun fragment(version: String): String =
        """
        #version $version
        precision mediump float;
        precision mediump int;

        uniform vec2 uVpMin;
        uniform vec2 uVpMax;
        
        out vec4 fragColor;

        void main() {
            vec2 resolution = uVpMax-uVpMin;
            vec2 uv = (gl_FragCoord.xy-uVpMin) / min(resolution.x, resolution.y);
            
            fragColor = vec4(mod(uv, 1.0), 0.0, 1.0);
        }
        """.trimIndent()
}
