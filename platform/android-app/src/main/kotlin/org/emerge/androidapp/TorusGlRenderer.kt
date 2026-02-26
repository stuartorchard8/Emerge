package org.emerge.androidapp

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import org.emerge.demo.physics.PhysicsFrame
import org.emerge.render.torus.Renderer
import org.emerge.render.torus.ScreenLayout
import org.emerge.render.torus.shader.GuiShaderSources
import org.emerge.render.torus.shader.ShaderFactory
import org.emerge.render.torus.shader.WorldShader
import org.emerge.render.torus.shader.WorldShaderParams
import org.emerge.sim.core.physics.Vec2i
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class TorusGlRenderer(
    private val getState: () -> PhysicsFrame,
) : GLSurfaceView.Renderer {
    private lateinit var worldShader: WorldShader

    private val maxBodies = 128

    private var guiShaderProgram: Int = 0
    private var uGuiResolution: Int = -1
    private var aGuiVerts: Int = -1
    private var layout: ScreenLayout = ScreenLayout.compute(Vec2i(1,1))

    // zoom < 1 => zoom out (view larger than world)
    private var zoom: Float = 0.75f

    private var vbo: Int = -1

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.07f, 0.07f, 0.07f, 1f)

        val vbo = Renderer.genBuffers()
        worldShader = WorldShader(maxBodies)
        worldShader.initVertexBuffer(vbo)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        layout = ScreenLayout.compute(Vec2i(width, height))
        worldShader.useLayout(layout)
    }

    override fun onDrawFrame(gl: GL10?) {
        val frame = getState()

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val params = WorldShaderParams.compute(frame.state, frame.myId, zoom)
        worldShader.setParameters(params)

        worldShader.draw()
    }
}
