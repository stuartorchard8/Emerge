package org.emerge.render.torus.shader

object GuiShaderSources {
    fun vertexGles2(): String =
        """
        attribute vec2 aPos;
        void main() {
            gl_Position = vec4(aPos, 0.0, 1.0);
        }
        """.trimIndent()

    fun fragmentGles2(): String =
        """
        precision mediump float;
        precision mediump int;

        uniform vec2 uResolution;

        void main() {
            float aspect = uResolution.x / uResolution.y;
            vec2 uv = gl_FragCoord.xy / uResolution;
            
            gl_FragColor = vec4(uv, 0.0, 1.0);
        }
        """.trimIndent()
}
