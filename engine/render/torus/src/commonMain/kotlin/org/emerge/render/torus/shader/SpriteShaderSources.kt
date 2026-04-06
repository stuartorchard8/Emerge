package org.emerge.render.torus.shader

import org.emerge.render.torus.GPU

object SpriteShaderSources {
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
        layout(location = 6) in float iUvX;
        layout(location = 7) in float iUvY;
        layout(location = 8) in float iBodyAlpha;

        out vec2 vUv;
        out float vPrimaryId;
        out float vAlpha;
        
        uniform vec2 uFrameSize;

        void main() {
            mat4 m = mat4(iCol0, iCol1, iCol2, iCol3);
            gl_Position = m * vec4(aPos, 0.0, 1.0);
            // Map local [-1,1] quad to UV space for the current animation frame
            vec2 localUv = aPos * 0.5 + 0.5;
            vUv = vec2(iUvX, iUvY) + localUv * uFrameSize;
            vPrimaryId = iPrimaryId;
            vAlpha = iBodyAlpha;
        }
        """.trimIndent()

    fun fragment(): String = fragment(GPU.shaderVersion)
    private fun fragment(version: String): String =
        """
        #version $version
        ${precisionBlock(version)}
        
        in vec2 vUv;
        in float vPrimaryId;
        in float vAlpha;
        out vec4 fragColor;
        
        uniform sampler2D uSpriteTexture;
        uniform vec3 uTintColor;
        uniform float uUseTint;

        void main() {
            vec4 texel = texture(uSpriteTexture, vUv);
            if (texel.a < 0.01) discard;
            
            // Optional team-based tinting: replace green channel with tint color
            if (uUseTint > 0.5) {
                float greenAmount = texel.g - max(texel.r, texel.b);
                if (greenAmount > 0.1) {
                    texel.rgb = mix(texel.rgb, uTintColor * texel.g, greenAmount);
                }
            }
            
            fragColor = vec4(texel.rgb, texel.a * vAlpha);
        }
        """.trimIndent()

    private fun precisionBlock(version: String): String =
        if (version.contains("es")) {
            "precision highp float;\nprecision highp int;"
        } else {
            ""
        }
}
