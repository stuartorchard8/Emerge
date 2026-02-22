package org.emerge.androidapp

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import org.emerge.demo.physics.PhysicsFrame
import org.emerge.render.torus.GuiShaderSources
import org.emerge.render.torus.ShaderFactory
import org.emerge.render.torus.WorldShaderSources
import org.emerge.render.torus.TorusViewComputer
import org.emerge.render.torus.packBodiesToFloatArray
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class TorusGlRenderer(
    private val getState: () -> PhysicsFrame,
) : GLSurfaceView.Renderer {
    private var worldShaderProgram: Int = 0
    private var aPos: Int = -1

    private var uResolution: Int = -1
    private var uWorld: Int = -1
    private var uView: Int = -1
    private var uCenter: Int = -1
    private var uBodyCount: Int = -1
    private var uMyId: Int = -1
    private var uBodies0: Int = -1

    private val maxBodies = 128
    private val bodiesFloats = FloatArray(4 * maxBodies)


    private var guiShaderProgram: Int = 0
    private var uGuiResolution: Int = -1
    private var aGuiVerts: Int = -1


    private val view = TorusViewComputer()

    // zoom < 1 => zoom out (view larger than world)
    private var zoom: Float = 0.75f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        prepareWorldShader()
        prepareGuiShader()

        GLES20.glClearColor(0.07f, 0.07f, 0.07f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val frame = getState()
        val st = frame.state
        val myId = frame.myId
        val scale = Int.MAX_VALUE.toFloat()
        val params = view.compute(state = st, myId = myId, zoom = zoom)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(worldShaderProgram)

        // Full-screen triangle in clip space
        // (x,y): (-1,-1), (3,-1), (-1,3)
        val verts = floatArrayOf(
            -1f, -1f,
            3f, -1f,
            -1f, 3f,
        )
        val vb = java.nio.ByteBuffer.allocateDirect(verts.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
        vb.put(verts).position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, vb)

        // uniforms
        // resolution comes from viewport; GLES doesn't expose it, but we can query it
        val vp = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, vp, 0)
        GLES20.glUniform2f(uResolution, vp[2].toFloat(), vp[3].toFloat())
        GLES20.glUniform2f(uWorld, params.worldW, params.worldH)
        GLES20.glUniform2f(uView, params.viewW, params.viewH)
        GLES20.glUniform2f(uCenter, params.topLeftCoverX, params.topLeftCoverY)
        GLES20.glUniform1i(uMyId, myId?.value ?: -1)

        val n = minOf(maxBodies, st.bodies.size)
        packBodiesToFloatArray(state = st, maxBodies = maxBodies, out = bodiesFloats)
        GLES20.glUniform1i(uBodyCount, n)
        GLES20.glUniform4fv(uBodies0, maxBodies, bodiesFloats, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    fun prepareWorldShader() {
        val vSrc = WorldShaderSources.vertexGles2()
        val fSrc = WorldShaderSources.fragmentGles2(maxBodies)
        worldShaderProgram = ShaderFactory.createProgramGles2(vSrc, fSrc)
        aPos = GLES20.glGetAttribLocation(worldShaderProgram, "aPos")

        uResolution = GLES20.glGetUniformLocation(worldShaderProgram, "uResolution")
        uWorld = GLES20.glGetUniformLocation(worldShaderProgram, "uWorld")
        uView = GLES20.glGetUniformLocation(worldShaderProgram, "uView")
        uCenter = GLES20.glGetUniformLocation(worldShaderProgram, "uCenter")
        uBodyCount = GLES20.glGetUniformLocation(worldShaderProgram, "uBodyCount")
        uMyId = GLES20.glGetUniformLocation(worldShaderProgram, "uMyId")
        uBodies0 = GLES20.glGetUniformLocation(worldShaderProgram, "uBodies[0]")
    }

    fun prepareGuiShader() {
        val vSrc = GuiShaderSources.vertexGles2()
        val fSrc = GuiShaderSources.fragmentGles2()
        guiShaderProgram = ShaderFactory.createProgramGles2(vSrc, fSrc)
        aGuiVerts = GLES20.glGetAttribLocation(guiShaderProgram, "aPos")

        uGuiResolution = GLES20.glGetUniformLocation(guiShaderProgram, "uResolution")
    }
}
