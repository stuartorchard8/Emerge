package org.emerge.desktop

import org.emerge.demo.cyto.ui.CytoTextRenderer
import org.emerge.demo.norns.anim.CreatureAnimation
import org.emerge.demo.norns.anim.CreatureAction
import org.emerge.demo.norns.world.ActivityType
import org.emerge.demo.norns.world.NornsView
import org.emerge.demo.norns.world.NornsWorld
import org.emerge.demo.norns.world.WorldCreature
import org.emerge.render.torus.GPU
import org.emerge.render.torus.GpuFloatBuffer
import org.emerge.render.torus.Mat4
import org.emerge.render.torus.shader.CircleShader

/**
 * GPU renderer for the Norns side-scroll world: every creature is a cluster of soft blobs (its
 * [CreatureAnimation] pose) drawn via the engine's instanced [CircleShader], plus food, floor
 * bars, and a text HUD (reusing cyto's procedural-font [CytoTextRenderer] — could be extracted to
 * the engine later). Camera geometry is the shared, tested [NornsView].
 *
 * NOTE: the GL path is unverified in the authoring environment (no display); the world, animation,
 * and camera math underneath are unit-tested. Pixel issues are fixed together on a real run.
 */
class NornsGlRenderer(private val cfg: NornsRenderConfig = NornsRenderConfig()) {
    private val vao = GPU.genAndBindVertexArrays()
    private val vbo = GPU.genBuffers()
    private val circleShader = CircleShader()
    private val text = CytoTextRenderer()

    private val matView = Mat4.scratch()
    private val matT = Mat4.scratch()
    private val matS = Mat4.scratch()
    private val matModel = Mat4.scratch()
    private val matInst = Mat4.scratch()

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

    fun draw(world: NornsWorld, cameraCenterX: Float, fbW: Float, fbH: Float, followId: Int?) {
        // Our instanced attributes live on our VAO; the text renderer binds its own, so rebind ours.
        GPU.bindVertexArray(vao)
        count = 0

        val view = NornsView(world.cfg.worldWidth, world.cfg.floors)
        val aspect = fbW / fbH
        val sx = view.sx(aspect)
        val sy = view.sy
        val left = view.cameraLeft(cameraCenterX, aspect)
        val horiz = view.horizontalUnits(aspect)
        matView.setIdentity()
        matView.m[0] = sx; matView.m[5] = sy
        matView.m[12] = -left * sx - 1f; matView.m[13] = -1f

        val right = left + horiz
        for (f in 0 until world.cfg.floors) {
            addBlob(left + horiz / 2f, view.floorY(f) - view.groundOffset, horiz, 0.12f, 0.30f, 0.30f, 0.36f, 1f)
        }
        // lift shafts (faint) + cars
        val shaftMidY = (view.floorY(0) + view.floorY(world.cfg.floors - 1)) / 2f
        val shaftHalfH = (view.floorY(world.cfg.floors - 1) - view.floorY(0)) / 2f + 0.5f
        for (lift in world.lifts) {
            if (lift.column < left - 1 || lift.column > right + 1) continue
            addBlob(lift.column.toFloat(), shaftMidY, 0.12f, shaftHalfH, 0.22f, 0.22f, 0.28f, 1f) // shaft
            addBlob(lift.column.toFloat(), view.floorYf(lift.carPos), 0.95f, 0.16f, 0.42f, 0.43f, 0.55f, 1f) // platform car
        }
        for (foodCell in world.food) {
            val fx = world.foodX(foodCell)
            if (fx < left - 1 || fx > right + 1) continue
            addBlob(fx.toFloat(), view.floorY(world.foodFloor(foodCell)) - 0.7f, 0.35f, 0.35f, 0.95f, 0.85f, 0.25f, 1f)
        }
        for (c in world.creatures) {
            if (c.x < left - 2 || c.x > right + 2) continue
            val cy = if (c.ridingY >= 0f) view.floorYf(c.ridingY) else view.floorY(c.floor)
            drawCreature(c, c.x, cy, c.id == followId)
        }
        if (count > 0) circleShader.drawInstanced(0, count, matrices, primaryIds, shapes, alphas, tints)

        drawHud(world, followId, fbW, fbH)
    }

    private fun drawCreature(c: WorldCreature, worldX: Float, worldY: Float, followed: Boolean) {
        val action = actionOf(c.activity)
        val phase = c.ticksLived * 0.35f
        // warm, earthy fur, gene-tinted: efficient = mossy/green, inefficient = rusty/red
        val frac = ((c.metabolism - cfg.metabMin) / (cfg.metabMax - cfg.metabMin)).coerceIn(0f, 1f)
        val r = 0.55f + 0.30f * frac
        val g = 0.62f - 0.16f * frac
        val b = 0.40f - 0.06f * frac

        // ground shadow (mirrors the verified PNG renderer)
        addBlob(worldX, worldY - 0.82f * cfg.creatureScale, 0.5f * cfg.creatureScale, 0.12f * cfg.creatureScale, 0f, 0f, 0f, 0.45f)
        for (p in CreatureAnimation.pose(action, phase, c.facing, r, g, b)) {
            addBlob(worldX + p.x * cfg.creatureScale, worldY + p.y * cfg.creatureScale,
                p.radius * cfg.creatureScale, p.radius * cfg.creatureScale, p.r, p.g, p.b, 1f)
        }
        if (c.carryingFood) {
            addBlob(worldX + c.facing * 0.5f * cfg.creatureScale, worldY + 0.05f * cfg.creatureScale, 0.22f, 0.22f, 0.95f, 0.85f, 0.25f, 1f)
        }
        val top = worldY + 1.3f * cfg.creatureScale
        val (ar, ag, ab) = when (action) {
            CreatureAction.WALK -> Triple(0.35f, 0.9f, 0.4f)
            CreatureAction.EAT -> Triple(0.95f, 0.9f, 0.3f)
            CreatureAction.COURT -> Triple(0.95f, 0.45f, 0.75f)
            CreatureAction.REST -> Triple(0.6f, 0.6f, 0.6f)
        }
        addBlob(worldX, top, 0.22f, 0.22f, ar, ag, ab, 1f)
        if (followed) addBlob(worldX, top + 0.5f, 0.18f, 0.18f, 1f, 1f, 1f, 1f)
    }

    private fun actionOf(a: ActivityType) = when (a) {
        ActivityType.EATING, ActivityType.PICKING_UP -> CreatureAction.EAT
        ActivityType.COURTING -> CreatureAction.COURT
        ActivityType.RESTING, ActivityType.IDLE -> CreatureAction.REST
        ActivityType.MOVING -> CreatureAction.WALK
    }

    private fun drawHud(world: NornsWorld, followId: Int?, fbW: Float, fbH: Float) {
        val stats = "POP ${world.population}  FOOD ${world.food.size}  BORN ${world.births}  " +
            "DIED ${world.deaths}  METAB ${(world.meanMetabolism() * 10000).toInt()}"
        text.drawCentered(stats, fbW * 0.5f, 16f, 15f, 0.9f, 0.9f, 0.95f, fbW, fbH)

        val c = followId?.let { world.creatureById(it) } ?: return
        val doing = when (c.activity) {
            ActivityType.IDLE -> "DECIDING"; ActivityType.MOVING -> "MOVING"
            ActivityType.PICKING_UP -> "PICKING UP"; ActivityType.EATING -> "EATING"
            ActivityType.COURTING -> "COURTING"; ActivityType.RESTING -> "RESTING"
        }
        val hud = buildString {
            append("NORN ").append(c.id).append("  ").append(c.biology.lifeStage.name).append('\n')
            append("AGE ").append(c.biology.age).append("  METAB ").append((c.metabolism * 10000).toInt()).append('\n')
            append(doing); if (c.carryingFood) append(" +FOOD"); append('\n')
            append("HUNGER ").append((c.hunger * 100).toInt()).append("  URGE ").append((c.matingUrge * 100).toInt()).append('\n')
            append("LIFE ").append((c.biology.organHealth[0] * 100).toInt())
        }
        text.drawCentered(hud, 150f, 90f, 14f, 0.8f, 0.95f, 0.85f, fbW, fbH)
    }

    private fun addBlob(wx: Float, wy: Float, rx: Float, ry: Float, r: Float, g: Float, b: Float, alpha: Float) {
        if (count >= CircleShader.MAX_INSTANCES) return
        matS.setScale(rx, ry)
        matT.setTranslation(wx, wy)
        matModel.setProduct(matT, matS)
        matInst.setProduct(matView, matModel)
        matInst.copyInto(matrices, count * Mat4.FLOATS)
        primaryIds[count] = (count + 1).toFloat()
        shapes[count] = 0f // disc
        alphas[count] = alpha
        tints[count * 3] = r; tints[count * 3 + 1] = g; tints[count * 3 + 2] = b
        count++
    }

    private fun uploadBaseTriangle() {
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
        text.cleanup()
        GPU.deleteBuffers(vbo)
        vao?.let { GPU.deleteVertexArrays(it) }
    }
}

/** Render tuning. metab range mirrors NornsConfig defaults (for the genetic colour map). */
class NornsRenderConfig(
    val creatureScale: Float = 1.1f,
    val metabMin: Float = 0.003f,
    val metabMax: Float = 0.012f,
)
