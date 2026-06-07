package org.emerge.desktop

import org.emerge.demo.norns.anim.CreatureAnimation
import org.emerge.demo.norns.anim.CreatureAction
import org.emerge.demo.norns.world.ActivityType
import org.emerge.demo.norns.world.NornsWorld
import org.emerge.demo.norns.world.WorldCreature
import org.emerge.render.torus.GPU
import org.emerge.render.torus.GpuFloatBuffer
import org.emerge.render.torus.Mat4
import org.emerge.render.torus.shader.CircleShader

/**
 * GPU renderer for the Norns side-scroll world: every creature is drawn as a cluster of soft
 * blobs (its [CreatureAnimation] pose), plus food and floor bars, via the engine's instanced
 * [CircleShader]. Built by faithfully reusing the proven WorldRenderer/CircleShader pattern.
 *
 * NOTE: this GL path could not be run/verified in the authoring environment (no display). The
 * animation + world underneath are unit-tested; if the window is blank or errors on first run,
 * that's a GL-wiring detail to fix together — the logic feeding it is sound.
 */
class NornsGlRenderer(private val cfg: NornsRenderConfig = NornsRenderConfig()) {
    private val vao = GPU.genAndBindVertexArrays()
    private val vbo = GPU.genBuffers()
    private val circleShader = CircleShader()

    private val matView = Mat4.scratch()
    private val matT = Mat4.scratch()
    private val matS = Mat4.scratch()
    private val matModel = Mat4.scratch()

    private val matrices = FloatArray(CircleShader.MAX_INSTANCES * Mat4.FLOATS)
    private val primaryIds = FloatArray(CircleShader.MAX_INSTANCES)
    private val shapes = FloatArray(CircleShader.MAX_INSTANCES)
    private val alphas = FloatArray(CircleShader.MAX_INSTANCES)
    private val tints = FloatArray(CircleShader.MAX_INSTANCES * 3)
    private var count = 0

    init {
        GPU.bindVertexArray(vao)
        uploadBaseTriangle()
        GPU.enableBlend()
        GPU.setBlendFuncSrcAlphaOneMinusSrcAlpha()
    }

    /** World→view scale: each grid cell is 1 unit; floors stack [FLOOR_SPACING] units apart. */
    private fun floorY(floor: Int) = floor * FLOOR_SPACING + 1.2f

    /**
     * Draws one frame. Vertical extent shows all floors; the horizontal extent is derived from the
     * window [aspect] (fbWidth/fbHeight) so a world unit is the same size in pixels on both axes —
     * blobs stay round and the world doesn't stretch with the window. Camera centres on
     * [cameraCenterX].
     */
    fun draw(world: NornsWorld, cameraCenterX: Float, aspect: Float, followId: Int?) {
        count = 0
        val worldTop = world.cfg.floors * FLOOR_SPACING
        val verticalUnits = worldTop + 1.5f                 // a little headroom above the top floor
        val sy = 2f / verticalUnits
        val sx = sy / aspect.coerceAtLeast(0.01f)           // square units → undistorted
        val horizontalUnits = 2f / sx
        val left = (cameraCenterX - horizontalUnits / 2f)
            .coerceIn(0f, maxOf(0f, world.cfg.worldWidth - horizontalUnits))

        matView.setIdentity()
        matView.m[0] = sx
        matView.m[5] = sy
        matView.m[12] = -left * sx - 1f
        matView.m[13] = -1f

        val right = left + horizontalUnits
        // floor bars (dim, wide flat blobs)
        for (f in 0 until world.cfg.floors) {
            addBlob(left + horizontalUnits / 2f, floorY(f) - 1.2f, horizontalUnits, 0.12f, 0.30f, 0.30f, 0.36f, 1f)
        }
        // food
        for (foodCell in world.food) {
            val fx = world.foodX(foodCell)
            if (fx < left - 1 || fx > right + 1) continue
            addBlob(fx.toFloat(), floorY(world.foodFloor(foodCell)) - 0.7f, 0.35f, 0.35f, 0.95f, 0.85f, 0.25f, 1f)
        }
        // creatures as posed blob clusters
        for (c in world.creatures) {
            if (c.x < left - 2 || c.x > right + 2) continue
            drawCreature(c, c.x, floorY(c.floor), c.id == followId)
        }

        if (count > 0) {
            circleShader.drawInstanced(0, count, matrices, primaryIds, shapes, alphas, tints)
        }
    }

    private fun drawCreature(c: WorldCreature, worldX: Float, worldY: Float, followed: Boolean) {
        val action = when (c.activity) {
            ActivityType.EATING, ActivityType.PICKING_UP -> CreatureAction.EAT
            ActivityType.COURTING -> CreatureAction.COURT
            ActivityType.RESTING, ActivityType.IDLE -> CreatureAction.REST
            ActivityType.MOVING -> CreatureAction.WALK
        }
        val phase = c.ticksLived * 0.35f
        // body colour from the metabolism trait: efficient = green, inefficient = red (watch evolution).
        val frac = ((c.metabolism - cfg.metabMin) / (cfg.metabMax - cfg.metabMin)).coerceIn(0f, 1f)
        val r = 0.25f + 0.6f * frac
        val g = 0.25f + 0.6f * (1f - frac)
        val b = 0.35f

        for (p in CreatureAnimation.pose(action, phase, c.facing, r, g, b)) {
            addBlob(
                worldX + p.x * cfg.creatureScale,
                worldY + p.y * cfg.creatureScale,
                p.radius * cfg.creatureScale, p.radius * cfg.creatureScale,
                p.r, p.g, p.b, 1f,
            )
        }

        // food being carried: a small yellow blob in front of the creature
        if (c.carryingFood) {
            addBlob(worldX + c.facing * 0.5f * cfg.creatureScale, worldY + 0.05f * cfg.creatureScale, 0.22f, 0.22f, 0.95f, 0.85f, 0.25f, 1f)
        }

        // No-text state cue: a coloured dot above the head shows what the creature is doing.
        val top = worldY + 1.3f * cfg.creatureScale
        val (ar, ag, ab) = when (action) {
            CreatureAction.WALK -> Triple(0.35f, 0.9f, 0.4f)   // seeking food (green)
            CreatureAction.EAT -> Triple(0.95f, 0.9f, 0.3f)    // eating (yellow)
            CreatureAction.COURT -> Triple(0.95f, 0.45f, 0.75f) // courting (pink)
            CreatureAction.REST -> Triple(0.6f, 0.6f, 0.6f)    // resting (grey)
        }
        addBlob(worldX, top, 0.22f, 0.22f, ar, ag, ab, 1f)
        // a white marker above the followed creature
        if (followed) addBlob(worldX, top + 0.5f, 0.18f, 0.18f, 1f, 1f, 1f, 1f)
    }

    private fun addBlob(wx: Float, wy: Float, rx: Float, ry: Float, r: Float, g: Float, b: Float, alpha: Float) {
        if (count >= CircleShader.MAX_INSTANCES) return
        matS.setScale(rx, ry)
        matT.setTranslation(wx, wy)
        matModel.setProduct(matT, matS)
        val inst = Mat4.scratch().setProduct(matView, matModel)
        inst.copyInto(matrices, count * Mat4.FLOATS)
        primaryIds[count] = (count + 1).toFloat()
        shapes[count] = 0f // disc
        alphas[count] = alpha
        tints[count * 3] = r; tints[count * 3 + 1] = g; tints[count * 3 + 2] = b
        count++
    }

    private fun uploadBaseTriangle() {
        // Oversized triangle containing the unit disc (the circle fragment shader clips it round).
        val verts = floatArrayOf(-1f, 1.7320508f, 2f, 0f, -1f, -1.7320508f)
        val buf = GpuFloatBuffer(verts.size)
        buf.put(verts, 0, verts.size).flip()
        GPU.bindBuffer(GPU.ARRAY_BUFFER, vbo)
        GPU.enableVertexAttribArray(0)
        GPU.putVertexAttribPointer(0, 2, GPU.FLOAT, false, 2 * 4, 0)
        GPU.bufferData(GPU.ARRAY_BUFFER, verts.size, buf, GPU.STATIC_DRAW)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
    }

    fun cleanup() {
        circleShader.deleteProgram()
        GPU.deleteBuffers(vbo)
        vao?.let { GPU.deleteVertexArrays(it) }
    }

    companion object {
        const val FLOOR_SPACING = 3.2f
    }
}

/** Render tuning. metab range mirrors NornsConfig defaults (for the genetic colour map). */
class NornsRenderConfig(
    val viewWidth: Float = 48f,
    val creatureScale: Float = 1.1f,
    val metabMin: Float = 0.004f,
    val metabMax: Float = 0.016f,
)
