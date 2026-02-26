package org.emerge.render.torus.shader

import org.emerge.render.torus.Renderer
import org.emerge.render.torus.ScreenLayout
import org.emerge.render.torus.packBodiesToFloatArray
import org.emerge.sim.core.physics.CircleBody
import org.emerge.sim.core.physics.Vec2
import org.emerge.sim.core.physics.Vec2i
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.div
import kotlin.math.max
import kotlin.math.min

class GuiShader() {
    private val vSrc = GuiShaderSources.vertexGles2()
    private val fSrc = GuiShaderSources.fragmentGles2()
    private val program: Int = ShaderFactory.createProgram(vSrc, fSrc)
    val viewportVertices: Int = Renderer.getAttribLocation(program, "aPos")

    private val uResolution: Int = Renderer.getUniformLocation(program, "uResolution")

    private var localResolution = Vec2i(1,1)

    private var vertexBufferHandle: Int = -1
    private var vertexFloatBuffer: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()


    fun initVertexBuffer(vbo: Int) {
        vertexBufferHandle = vbo
        Renderer.bindBuffer(Renderer.ARRAY_BUFFER, vbo)
        Renderer.enableVertexAttribArray(viewportVertices)
        // Capture the VBO binding into the VAO's attrib state.
        Renderer.putVertexAttribPointer(viewportVertices, 2, Renderer.FLOAT, false, 2 * 4, 0)
        Renderer.bindBuffer(Renderer.ARRAY_BUFFER, 0)
    }

    fun useLayout(layout: ScreenLayout) {
        localResolution = layout.resolution

        val verts = layout.getGuiVerts()
        vertexFloatBuffer.put(verts).flip()
        Renderer.bindBuffer(Renderer.ARRAY_BUFFER, vertexBufferHandle)
        Renderer.bufferData(Renderer.ARRAY_BUFFER, verts.size, vertexFloatBuffer, Renderer.STATIC_DRAW)
        Renderer.bindBuffer(Renderer.ARRAY_BUFFER, 0)
    }

    private fun setUniforms() {
        setResolution(localResolution)
    }

    private fun setResolution(resolution: Vec2i) = Renderer.putUniform2f(
        uResolution,
        resolution.x.toFloat(),
        resolution.y.toFloat(),
    )

    fun draw() {
        Renderer.useProgram(program)
        setUniforms()
        Renderer.enableVertexAttribArray(viewportVertices)
        Renderer.drawTriangles(0,3)
        Renderer.drawTriangles(1,3)
        Renderer.disableVertexAttribArray(viewportVertices)
    }

    fun deleteProgram() {
        Renderer.deleteProgram(program)
    }
}
