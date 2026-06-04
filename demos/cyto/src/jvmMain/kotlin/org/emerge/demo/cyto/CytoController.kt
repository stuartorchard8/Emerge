package org.emerge.demo.cyto

import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.physics.box2d.Box2D
import org.emerge.demo.cyto.cells.Cell
import org.emerge.demo.cyto.cells.CellConnectionData
import org.emerge.demo.cyto.cells.CellData
import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.environment.CellWorld

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

  /** First cell whose disc contains [worldPoint], or null. */
  fun cellAt(worldPoint: Vector2): Cell? = world.cells.firstOrNull { it.containsPoint(worldPoint) }

  /** Queue a new cell to be spawned on the next fixed step. */
  fun spawnCell(worldPoint: Vector2, type: CellType) {
    world.spawns.add(CellData(Vector2(worldPoint), type = type, chemicals = mapOf("energy" to 2f)))
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
