package org.emerge.androidapp

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import org.emerge.demo.physics.PhysicsFrame
import org.emerge.render.torus.ScreenLayout
import org.emerge.render.torus.shader.GuiShaderSources
import org.emerge.render.torus.shader.ShaderFactory
import org.emerge.render.torus.shader.WorldShader
import org.emerge.render.torus.shader.WorldShaderParams
import org.emerge.sim.core.physics.Vec2i
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
    private var resolution: Vec2i = Vec2i(1,1)

    // zoom < 1 => zoom out (view larger than world)
    private var zoom: Float = 0.75f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        worldShader = WorldShader(maxBodies)
        prepareGuiShader()

        GLES20.glClearColor(0.07f, 0.07f, 0.07f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        resolution = Vec2i(width, height)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val frame = getState()

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val layout = ScreenLayout.compute(resolution)
        val params = WorldShaderParams.compute(frame.state, frame.myId, zoom, layout)
        worldShader.setParameters(params)

        val verts = layout.getWorldVerts()
        val vb = java.nio.ByteBuffer.allocateDirect(verts.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
        vb.put(verts).position(0)
        GLES20.glEnableVertexAttribArray(worldShader.aPos)
        // Capture the VBO binding into the VAO's attrib state.
        GLES20.glVertexAttribPointer(worldShader.aPos, 2, GLES20.GL_FLOAT, false, 0, vb)

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
