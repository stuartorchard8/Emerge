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
        layout(location = 8) in float iUvW;
        layout(location = 9) in float iUvH;
        layout(location = 10) in float iBodyAlpha;
        layout(location = 11) in float iSquash;
        layout(location = 12) in vec3 iTintColor;

        out vec2 vUv;
        out float vPrimaryId;
        out float vAlpha;
        out vec3 vTintColor;
        
        uniform vec2 uFrameSize;

        void main() {
            mat4 m = mat4(iCol0, iCol1, iCol2, iCol3);
            vec2 phasedPos = aPos;
            if (phasedPos.x > 0) {
                phasedPos.x -= 2f*iSquash;
            }
            gl_Position = m * vec4(phasedPos*vec2(iUvW, iUvH), 0.0, 1.0);
            // Map local [-1,1] quad to UV space for the current animation frame
            // Rotate aPos 90 degrees cw for up-as-forward
            vec2 aPos90 = vec2(aPos.y, -aPos.x);
            vec2 localUv = aPos90 * 0.5 + 0.5;
            vUv = vec2(iUvX, iUvY) + localUv * uFrameSize;
            vPrimaryId = iPrimaryId;
            vAlpha = iBodyAlpha;
            vTintColor = iTintColor;
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
        in vec3 vTintColor;
        out vec4 fragColor;
        
        uniform sampler2D uSpriteTexture;
        uniform vec3 uTintColor;
        uniform float uUseTint;

        void main() {
            vec4 texel = texture(uSpriteTexture, vUv);
            if (texel.a < 0.01) discard;
            
            // Primary id tinting: replace green channel with tint color
            float colorSeed = vPrimaryId;
            float c = colorSeed + 1.0;
            vec3 vColor = mod(vec3(c/1.9, c/2.9, c/4.9),1.0);
            float greenAmount = texel.g - max(texel.r, texel.b);
            if (greenAmount > 0.1) {
                bool hasCustomTint = max(vTintColor.r, max(vTintColor.g, vTintColor.b)) > 0.0;
                texel.rgb = hasCustomTint ? vTintColor : vColor;
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
