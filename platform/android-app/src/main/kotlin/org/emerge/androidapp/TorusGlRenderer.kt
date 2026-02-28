package org.emerge.androidapp

import android.opengl.GLSurfaceView
import org.emerge.demo.physics.PhysicsFrame
import org.emerge.render.torus.ScreenRenderer
import org.emerge.sim.core.physics.Vec2i
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class TorusGlRenderer(
    private val getState: () -> PhysicsFrame,
) : GLSurfaceView.Renderer {
    private lateinit var screenRenderer: ScreenRenderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        screenRenderer = ScreenRenderer()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenRenderer.setResolution(Vec2i(width, height))
    }

    override fun onDrawFrame(gl: GL10?) {
        val frame = getState()
        screenRenderer.draw(frame.state, frame.myId)
    }
}
