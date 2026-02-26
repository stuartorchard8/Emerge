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
import kotlin.math.max
import kotlin.math.min

class WorldShader(val maxBodies: Int) {
    private val vSrc = WorldShaderSources.vertexGles2()
    private val fSrc = WorldShaderSources.fragmentGles2(maxBodies)
    private val program: Int = ShaderFactory.createProgram(vSrc, fSrc)
    val viewportVertices: Int = Renderer.getAttribLocation(program, "aPos")

    private val uResolution: Int = Renderer.getUniformLocation(program, "uResolution")
    private val uWorld: Int = Renderer.getUniformLocation(program, "uWorld")
    private val uZoom: Int = Renderer.getUniformLocation(program, "uZoom")
    private val uCenter: Int = Renderer.getUniformLocation(program, "uCenter")
    private val uBodyCount: Int = Renderer.getUniformLocation(program, "uBodyCount")
    private val uMyId: Int = Renderer.getUniformLocation(program, "uMyId")
    private val uBodies: Int = Renderer.getUniformLocation(program, "uBodies")

    private val bodiesFloats = FloatArray(4 * maxBodies)

    private var localResolution = Vec2i(1,1)
    private var focusOffset = Vec2(0f,0f)

    private var vertexBufferHandle: Int = -1
    private var vertexFloatBuffer: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    fun initVertexBuffer(vbo: Int) {
        vertexBufferHandle = vbo
        Renderer.bindBuffer(Renderer.ARRAY_BUFFER, vertexBufferHandle)
        Renderer.enableVertexAttribArray(viewportVertices)
        // Capture the VBO binding into the VAO's attrib state.
        Renderer.putVertexAttribPointer(viewportVertices, 2, Renderer.FLOAT, false, 2 * 4, 0)
        Renderer.bindBuffer(Renderer.ARRAY_BUFFER, 0)
    }

    fun useLayout(layout: ScreenLayout) {
        localResolution = layout.resolution
        setResolution(layout.resolution)
        // Offset focus based on viewport center
        val worldViewportCenter = layout.getWorldCenter()
        focusOffset = Vec2(
            -worldViewportCenter.x * min(layout.aspectRatio, 1f),
            worldViewportCenter.y / max(layout.aspectRatio, 1f),
        )

        val verts = layout.getWorldVerts()
        vertexFloatBuffer.put(verts).flip()
        Renderer.bindBuffer(Renderer.ARRAY_BUFFER, vertexBufferHandle)
        Renderer.bufferData(Renderer.ARRAY_BUFFER, verts.size, vertexFloatBuffer, Renderer.STATIC_DRAW)
        Renderer.bindBuffer(Renderer.ARRAY_BUFFER, 0)
    }

    fun setParameters(params: WorldShaderParams) {
        setResolution(localResolution)
        setWorld(params.worldSize)
        setZoom(params.zoom)
        setCenter(params.viewFocus + focusOffset/params.zoom)
        setMyId(params.myId?.value ?: -1)
        setBodies(params.bodies)
    }
    fun setResolution(resolution: Vec2i) = Renderer.putUniform2f(
        uResolution,
        resolution.x.toFloat(),
        resolution.y.toFloat(),
    )
    fun setWorld(size: Vec2) = Renderer.putUniform2f(uWorld, size.x, size.y)
    fun setZoom(value: Float) = Renderer.putUniform1f(uZoom, value)
    fun setCenter(center: Vec2) = Renderer.putUniform2f(uCenter, center.x, center.y)
    fun setMyId(value: Int) = Renderer.putUniform1i(uMyId, value)
    fun setBodies(bodies: List<CircleBody>) {
        val n = min(maxBodies, bodies.size)
        Renderer.putUniform1i(uBodyCount, n)
        packBodiesToFloatArray(bodies, maxBodies, out = bodiesFloats)
        Renderer.putUniform4fv(uBodies, bodiesFloats, maxBodies)
    }

    fun draw() {
        Renderer.useProgram(program)
        Renderer.enableVertexAttribArray(viewportVertices)
        Renderer.drawTriangles(0,3)
        Renderer.drawTriangles(1,3)
        Renderer.disableVertexAttribArray(viewportVertices)
    }

    fun deleteProgram() {
        Renderer.deleteProgram(program)
    }
}
