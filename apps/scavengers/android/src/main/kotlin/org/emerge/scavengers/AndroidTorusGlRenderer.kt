package org.emerge.scavengers

import android.opengl.GLSurfaceView
import org.emerge.demo.scavengers.ScavengersFrame
import org.emerge.demo.scavengers.rendererFocus
import org.emerge.demo.scavengers.scavengersBodyTint
import org.emerge.demo.scavengers.scavengersEdgeIndicators
import org.emerge.demo.scavengers.ScavengersRenderer
import org.emerge.sim.core.physics.primitives.Vec2
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class AndroidTorusGlRenderer(
    private val getState: () -> ScavengersFrame,
    private val contentScale: Vec2,
) : GLSurfaceView.Renderer {
    private lateinit var screenRenderer: ScavengersRenderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        screenRenderer = ScavengersRenderer(contentScale)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenRenderer.setResolution(Vec2(width.toFloat(), height.toFloat()))
    }

    override fun onDrawFrame(gl: GL10?) {
        val frame = getState()
        screenRenderer.draw(
            state = frame.state.core,
            focus = frame.state.rendererFocus(frame.myId),
            primaryColorOf = { entityId -> frame.state.scavengersBodyTint(entityId) },
            edgeIndicators = frame.state.scavengersEdgeIndicators(frame.myId),
        )
    }

    fun applyCameraGesture(zoomFactor: Float, rotationDeltaRad: Float) {
        if (!::screenRenderer.isInitialized) {
            return
        }
        screenRenderer.zoomByFactor(zoomFactor)
        screenRenderer.rotateBy(rotationDeltaRad)
    }
}
