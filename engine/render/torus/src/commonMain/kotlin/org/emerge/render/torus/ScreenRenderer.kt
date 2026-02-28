package org.emerge.render.torus

import org.emerge.render.torus.shader.GuiShader
import org.emerge.render.torus.shader.WorldShader
import org.emerge.render.torus.shader.WorldShaderParams
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Vec2i
import kotlin.math.max
import kotlin.math.min
import kotlin.times

class ScreenRenderer {
    val maxBodies: Int = 128
    private var zoom: Float = 0.75f // <1 => zoom out (see multiple tiles)

    private val vao = GPU.genAndBindVertexArrays()
    private var vbo: Int = GPU.genBuffers()

    private val worldShader = WorldShader(maxBodies)
    private val guiShader = GuiShader()
    private var layout: ScreenLayout = ScreenLayout.compute(Vec2i(1,1))

    fun setResolution(resolution: Vec2i) {
        GPU.setViewport(0, 0, resolution.x, resolution.y)
        layout = ScreenLayout.compute(resolution)
        layout.putVerts(vbo)
        worldShader.useLayout(layout)
        guiShader.useLayout(layout)
    }

    fun zoomOut() {
        zoom = max(0.05f, zoom * 0.98f)
    }
    fun zoomIn() {
        zoom = min(20f, zoom * 1.02f)
    }

    fun draw(state: PhysicsState, myId: PlayerId?) {
        val params = WorldShaderParams.compute(state, myId, zoom)
        worldShader.draw(params)
        guiShader.draw(vOffset = 2)
    }

    fun cleanup() {
        worldShader.deleteProgram()
        guiShader.deleteProgram()
        GPU.deleteBuffers(vbo)
        if (vao != null) {
            GPU.deleteVertexArrays(vao)
        }
    }
}
