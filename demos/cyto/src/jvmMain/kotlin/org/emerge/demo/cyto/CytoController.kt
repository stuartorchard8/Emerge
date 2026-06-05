package org.emerge.demo.cyto

import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.physics.box2d.Body
import com.badlogic.gdx.physics.box2d.BodyDef.BodyType
import com.badlogic.gdx.physics.box2d.Box2D
import com.badlogic.gdx.physics.box2d.joints.DistanceJointDef
import com.badlogic.gdx.physics.box2d.joints.FrictionJointDef
import org.emerge.demo.cyto.cells.Cell
import org.emerge.demo.cyto.cells.CellConnectionData
import org.emerge.demo.cyto.cells.CellData
import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.environment.CellWorld
import org.emerge.demo.cyto.environment.WorldInterface
import ktx.box2d.body

/**
 * Local-only controller for the Cyto demo, mirroring
 * [org.emerge.demo.drockets.DrocketsController]. Owns the [CellWorld], steps it each
 * frame (real delta → fixed 1/64 substeps inside the world), and brokers pointer
 * interactions and save/restore for the host.
 */
class CytoController(
  initialCells: List<CellData> = defaultInitialCells(),
  initialConnections: List<CellConnectionData> = emptyList(),
) {
  init {
    // Load the gdx + Box2D native libraries before the first World is created.
    // Idempotent — the shared-library loader caches, so re-calling is harmless.
    Box2D.init()
  }

  private var world = CellWorld(initialCells, initialConnections)

  var tick: Long = 0L
    private set

  /** The live world, for the renderer and pointer hit-testing. */
  val cellWorld: CellWorld get() = world

  fun tick(deltaSeconds: Float): CytoFrame {
    world.update(deltaSeconds)
    tick++
    return CytoFrame(world, tick)
  }

  fun currentFrame(): CytoFrame = CytoFrame(world, tick)

  // ── Pointer interaction (host wires GLFW input to these) ────────────────────
  //
  // Box2D stays encapsulated here: the host passes plain world-space floats and gets
  // back an opaque [Grab] handle, so it never touches gdx types directly.

  /** Selectable pointer behaviour, ported from Cyto's TouchMode. */
  enum class TouchMode { Base, Sticky, Detach, Activate, Delete, Set }

  /** A held cell — a kinematic body spring-jointed to the cell, dragged by the host. */
  inner class Grab internal constructor(private val holdBody: Body) {
    fun moveTo(x: Float, y: Float) {
      holdBody.setTransform(x, y, 0f)
      holdBody.isAwake = true
    }

    fun release() {
      holdBody.jointList.forEach { (it.other.userData as? Cell)?.isSticky = false }
      world.world.destroyBody(holdBody)
    }
  }

  /**
   * Begin dragging the cell under ([x], [y]). Returns null if no cell is there (the
   * host treats that as a camera-pan start). Replicates Cyto's onTouchStart: a
   * kinematic hold body joined to the cell by a soft distance + friction joint, plus
   * the per-mode start effect (Detach disconnects, Sticky welds).
   */
  fun grabAt(x: Float, y: Float, mode: TouchMode): Grab? {
    val target = world.cells.firstOrNull { it.containsPoint(Vector2(x, y)) } ?: return null
    val holdBody = world.world.body(BodyType.KinematicBody) {
      position.set(x, y)
      userData = target
    }
    world.world.createJoint(DistanceJointDef().apply {
      frequencyHz = 5f
      initialize(holdBody, target.body, Vector2(x, y), target.body.position)
    })
    world.world.createJoint(FrictionJointDef().apply {
      maxForce = target.body.mass * 100f
      maxTorque = target.body.mass * 5f
      initialize(holdBody, target.body, target.body.position)
    })
    when (mode) {
      TouchMode.Detach -> target.disconnectSafely()
      TouchMode.Sticky -> target.isSticky = true
      else -> {}
    }
    return Grab(holdBody)
  }

  /**
   * Tap (press-release without drag). Replicates Cyto's onTapEnd: an empty tap spawns a
   * [type] cell with surplus energy; a tap on cells applies the active [mode].
   */
  fun tapAt(x: Float, y: Float, mode: TouchMode, type: CellType) {
    val point = Vector2(x, y)
    val hits = world.cells.filter { it.containsPoint(point) }
    if (hits.isEmpty()) {
      world.spawns.add(CellData(point, type = type, chemicals = mapOf("energy" to 2f)))
      return
    }
    hits.forEach {
      when (mode) {
        TouchMode.Activate -> it.activate(WorldInterface.TIME_STEP, null)
        TouchMode.Delete -> it.disposeSafely()
        TouchMode.Set -> {
          it.type = type
          it.genes = Cell.genesForType(type)
        }
        else -> {}
      }
    }
  }

  // ── Persistence ─────────────────────────────────────────────────────────────

  fun snapshotBytes(): ByteArray = CytoSaveCodec.encode(
    CytoSnapshot(
      cells = world.cells.map(Cell::data),
      connections = world.connectionData(),
    )
  )

  fun restoreSnapshot(bytes: ByteArray) {
    val snapshot = CytoSaveCodec.decode(bytes)
    world.dispose()
    world = CellWorld(snapshot.cells, snapshot.connections)
    tick = 0L
  }

  companion object {
    /**
     * Fresh-start seed: a single Stem cell with surplus energy. Cyto's original fresh
     * start was an empty canvas (you tap to spawn); seeding one dividing Stem cell gives
     * an immediately-alive colony to look at while keeping the world otherwise empty.
     */
    fun defaultInitialCells(): List<CellData> = listOf(
      CellData(
        position = Vector2(0f, 0f),
        type = CellType.Stem,
        chemicals = mapOf("energy" to 2f),
      ),
    )
  }
}
