package org.emerge.render.torus

import org.emerge.render.torus.shader.CircleShader
import org.emerge.render.torus.shader.GuiShader
import org.emerge.render.torus.shader.WorldShader
import org.emerge.render.torus.shader.WorldShaderParams
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Vec2
import org.emerge.sim.core.physics.Vec2i
import kotlin.math.max
import kotlin.math.min

class ScreenRenderer(val contentScale: Vec2) {
    private var zoom: Float = 1.0f // <1 => zoom out (see multiple tiles)

    private val vao = GPU.genAndBindVertexArrays()
    private var vbo: Int = GPU.genBuffers()

    private val worldShader = WorldShader(MAX_BODIES)
    private val guiShader = GuiShader()
    private val circleShader = CircleShader()
    private var layout: ScreenLayout = ScreenLayout.compute(Vec2i(1,1), contentScale)

    fun setResolution(resolution: Vec2i) {
        GPU.setViewport(0, 0, resolution.x, resolution.y)
        layout = ScreenLayout.compute(resolution, contentScale)
        layout.putVerts(vbo)
        worldShader.useLayout(layout)
        guiShader.useLayout(layout)
    }

    fun zoomOut() {
        zoom = max(1.0f, zoom * 0.98f)
    }
    fun zoomIn() {
        zoom = min(20f, zoom * 1.02f)
    }

    fun draw(state: PhysicsState, myId: PlayerId?) {
        val params = WorldShaderParams.compute(state, myId, zoom)
        worldShader.draw(params, segmentation=layout.worldSegmentation)
        guiShader.draw(vOffset=layout.guiVertexOffset)
        circleShader.draw(vOffset = layout.circleVertexOffset)
    }

    fun cleanup() {
        worldShader.deleteProgram()
        guiShader.deleteProgram()
        circleShader.deleteProgram()
        GPU.deleteBuffers(vbo)
        if (vao != null) {
            GPU.deleteVertexArrays(vao)
        }
    }
    companion object {
        const val MAX_BODIES: Int = 128
    }
}
