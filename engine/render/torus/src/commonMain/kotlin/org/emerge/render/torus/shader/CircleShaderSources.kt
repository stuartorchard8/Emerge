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
        layout(location = 5) in float id;

        out vec2 vLocal;
        out vec3 vColor;
        void main() {
            mat4 m = mat4(iCol0, iCol1, iCol2, iCol3);
            gl_Position = m * vec4(aPos, 0.0, 1.0);
            float c = float(id)+3.0;
            vColor = mod(vec3(c/1.9, c/2.9, c/4.9),1.0);
            vLocal = aPos;
        }
        """.trimIndent()

    fun fragment(): String = fragment(GPU.shaderVersion)
    private fun fragment(version: String): String =
        """
        #version $version
        ${precisionBlock(version)}

        in vec2 vLocal; // local [-1,1] coords for circle test
        in vec3 vColor;
        out vec4 fragColor;

        void main() {
            if (dot(vLocal, vLocal) <= 1.0) {
                float a = (1.0 - dot(vLocal, vLocal)/(1.5));
                fragColor = vec4(vColor*a, 1.0);
            } else {
                discard;
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
