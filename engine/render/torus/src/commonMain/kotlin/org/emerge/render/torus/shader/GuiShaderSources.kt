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

        uniform vec2 uVpMin;
        uniform vec2 uVpMax;

        void main() {
            vec2 resolution = uVpMax-uVpMin;
            vec2 uv = (gl_FragCoord.xy-uVpMin) / min(resolution.x, resolution.y);
            
            gl_FragColor = vec4(mod(uv, 1.0), 0.0, 1.0);
        }
        """.trimIndent()
}
