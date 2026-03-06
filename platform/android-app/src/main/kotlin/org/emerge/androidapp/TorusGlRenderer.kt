package org.emerge.androidapp

import android.opengl.GLSurfaceView
import org.emerge.demo.physics.PhysicsFrame
import org.emerge.render.torus.ScreenRenderer
import org.emerge.sim.core.physics.PhysicsInput
import org.emerge.sim.core.physics.Vec2
import org.emerge.sim.core.physics.Frac2
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class TorusGlRenderer(
    private val getState: () -> PhysicsFrame,
    private val contentScale: Vec2,
) : GLSurfaceView.Renderer {
    private lateinit var screenRenderer: ScreenRenderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        screenRenderer = ScreenRenderer(contentScale)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenRenderer.setResolution(Frac2(width, height))
    }

    override fun onDrawFrame(gl: GL10?) {
        val frame = getState()
        screenRenderer.draw(frame.state, frame.myId)
    }

    fun rotateInputToWorld(input: PhysicsInput): PhysicsInput {
        if (!::screenRenderer.isInitialized) {
            return input
        }
        return screenRenderer.rotateInputToWorld(input)
    }

    fun applyCameraGesture(zoomFactor: Float, rotationDeltaRad: Float) {
        if (!::screenRenderer.isInitialized) {
            return
        }
        screenRenderer.zoomByFactor(zoomFactor)
        screenRenderer.rotateBy(rotationDeltaRad)
    }
}
