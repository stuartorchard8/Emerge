package org.emerge.androidapp

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import org.emerge.demo.physics.PhysicsFrame
import org.emerge.render.torus.shader.GuiShaderSources
import org.emerge.render.torus.shader.ShaderFactory
import org.emerge.render.torus.TorusViewComputer
import org.emerge.render.torus.shader.WorldShader
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min

class TorusGlRenderer(
    private val getState: () -> PhysicsFrame,
) : GLSurfaceView.Renderer {
    private lateinit var worldShader: WorldShader

    private val maxBodies = 128

    private var guiShaderProgram: Int = 0
    private var uGuiResolution: Int = -1
    private var aGuiVerts: Int = -1


    private val view = TorusViewComputer()

    // zoom < 1 => zoom out (view larger than world)
    private var zoom: Float = 0.75f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        worldShader = WorldShader(maxBodies)
        prepareGuiShader()

        GLES20.glClearColor(0.07f, 0.07f, 0.07f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // resolution comes from viewport; GLES doesn't expose it, but we can query it
        val vp = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, vp, 0)
        val fbW = max(1, vp[2])
        val fbH = max(1, vp[3])
        val aspectRatio = (fbW.toFloat() / fbH.toFloat())
        val worldViewportMinY = if (aspectRatio < 1f) -0.9f else -1f
        val worldViewportMaxY =  1f
        val worldViewportMinX = -1f
        val worldViewportMaxX =  if (aspectRatio < 1f) 1f else 0.9f
        val worldViewportCenterX =  (worldViewportMinX+worldViewportMaxX)/2f
        val worldViewportCenterY = (worldViewportMinY+worldViewportMaxY)/2f
        val verts = floatArrayOf(
            worldViewportMinX, worldViewportMinY,
            worldViewportMaxX, worldViewportMinY,
            worldViewportMinX, worldViewportMaxY,
            worldViewportMaxX, worldViewportMaxY,
        )

        val vb = java.nio.ByteBuffer.allocateDirect(verts.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
        vb.put(verts).position(0)
        GLES20.glEnableVertexAttribArray(worldShader.aPos)
        // Capture the VBO binding into the VAO's attrib state.
        GLES20.glVertexAttribPointer(worldShader.aPos, 2, GLES20.GL_FLOAT, false, 0, vb)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val frame = getState()
        val state = frame.state
        val myId = frame.myId
        val params = view.compute(state = state, myId = myId, zoom = zoom)

        // uniforms
        worldShader.setResolution(fbW.toFloat(), fbH.toFloat())
        worldShader.setWorld(params.worldSizeX, params.worldSizeY)
        worldShader.setZoom(params.zoom)
        worldShader.setCenter(
            params.viewFocusX-worldViewportCenterX*params.zoom*min(aspectRatio, 1f),
            params.viewFocusY+worldViewportCenterY*params.zoom/max(aspectRatio, 1f),
        )
        worldShader.setMyId(myId?.value ?: -1)
        worldShader.setBodies(state.bodies.values.toList())

        worldShader.draw()
        GLES20.glDisableVertexAttribArray(worldShader.aPos)
    }

    fun prepareGuiShader() {
        val vSrc = GuiShaderSources.vertexGles2()
        val fSrc = GuiShaderSources.fragmentGles2()
        guiShaderProgram = ShaderFactory.createProgram(vSrc, fSrc)
        aGuiVerts = GLES20.glGetAttribLocation(guiShaderProgram, "aPos")

        uGuiResolution = GLES20.glGetUniformLocation(guiShaderProgram, "uResolution")
    }
}
