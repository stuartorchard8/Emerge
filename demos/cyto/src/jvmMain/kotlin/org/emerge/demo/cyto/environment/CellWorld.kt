package org.emerge.demo.cyto.environment

import com.badlogic.gdx.math.MathUtils.PI
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.physics.box2d.joints.DistanceJoint
import com.badlogic.gdx.physics.box2d.joints.DistanceJointDef
import org.emerge.demo.cyto.MyContactListener
import org.emerge.demo.cyto.cells.Cell
import org.emerge.demo.cyto.cells.CellConnection
import org.emerge.demo.cyto.cells.CellConnectionData
import org.emerge.demo.cyto.cells.CellData
import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.cells.JointCreationData
import ktx.collections.GdxArray
import ktx.math.div
import ktx.math.minus
import ktx.math.plus
import ktx.math.times
import kotlin.math.*

/**
 * The cell simulation. Ported from Cyto's `CellWorld` with LibGDX rendering, camera,
 * input, and SQLDelight persistence removed:
 *  - the old `inputProcessor.spawns` queue is replaced by the public [spawns] list
 *    that the Emerge host fills from pointer input;
 *  - the SQLDelight delete/upsert bookkeeping is gone — [org.emerge.demo.cyto.CytoSaveCodec]
 *    snapshots the live [cells] instead, so this assigns stable ids itself via [nextId].
 * The chemistry, gene, division, sticky-weld, and connection-damage behaviour is verbatim.
 */
class CellWorld(
  initialCellData: List<CellData>,
  initialConnectionData: List<CellConnectionData>,
) : WorldInterface() {
  val cells = initialCellData.map { Cell(world, it) }.toMutableList()
  val resourceGrid = ResourceGrid(
    gridCells = mutableMapOf(*(-5..5).map { column ->
      column to mutableMapOf(*(-5..5).map { row ->
        row to mutableMapOf(*('A'..'Z').map { letter ->
          Pair(letter.toString(), 1f)
        }.toTypedArray())
      }.toTypedArray())
    }.toTypedArray())
  )

  /** Cells requested by the host's pointer input; drained each [fixedUpdate]. */
  val spawns = GdxArray<CellData>()

  private var nextId: Long = (initialCellData.mapNotNull { it.id }.maxOrNull() ?: 0L) + 1L

  private val cellsToRemove = GdxArray<Cell>()
  private val cellsToDivide = GdxArray<Cell>()
  private val newJointData = GdxArray<JointCreationData>()
  private val disconnects = GdxArray<CellConnection>()

  init {
    initialConnectionData.forEach { con ->
      val cell1 = cells.singleOrNull { it.id == con.id1 }
      val cell2 = cells.singleOrNull { it.id == con.id2 }
      if (cell1 == null || cell2 == null) {
        println("Bad connection: ${con.id1}, ${con.id2}")
      } else {
        cell1.collide(cell2, sticky=true)
      }
    }
    world.setContactListener(MyContactListener())
    assignIds()
    // Initial update ensures connections are set up correctly
    update(0f)
  }

  override fun fixedUpdate() {
    super.fixedUpdate()

    cellsToRemove.clear()
    cellsToDivide.clear()
    newJointData.clear()
    disconnects.clear()

    cells.forEach {
      it.chemistry(TIME_STEP)
    }
    cells.forEach {
      val response = it.update(TIME_STEP)
      if (response.destroy) {
        cellsToRemove.add(it)
      } else if (response.divide) {
        cellsToDivide.add(it)
      }
      newJointData.addAll(response.newJoints)
      if (response.disconnects != null) disconnects.addAll(response.disconnects)
    }


    disconnects.forEach {
      it.cellA.removeJoint(it.joint)
      if (it.cellB.removeJoint(it.joint)) {
        // Only destroy if it was not already removed
        world.destroyJoint(it.joint)
      }
    }

    cellsToRemove.forEach { world.destroyBody(it.body) }
    cells.removeAll(cellsToRemove.toList().toSet())

    newJointData.forEach {
      connectCells(it.a, it.b)
    }

    cellsToDivide.forEach { mother ->
      var averageNeighbourPosition = mother.body.position
      mother.connections.forEach { connection ->
        val neighbour = if(connection.cellA != mother) connection.cellA else connection.cellB
        averageNeighbourPosition += neighbour.body.position
      }
      averageNeighbourPosition /= (mother.connections.size + 1f)
      val neighbourVector = mother.body.position - averageNeighbourPosition
      val neighbourNormal = if (neighbourVector.x == 0f && neighbourVector.y == 0f) {
        Vector2(cos(mother.body.angle), sin(mother.body.angle))
      } else {
        neighbourVector.nor()
      }
      val offset = neighbourNormal*0.25f*mother.shape.radius

      val groupedConnections = mother.connections.groupBy { connection ->
        val neighbour = if(connection.cellA != mother) connection.cellA else connection.cellB
        val normal = (mother.body.position - neighbour.body.position).nor()
        val sign = normal.dot(neighbourNormal)
        val magnitude = sign.absoluteValue
        if (magnitude < 0.75f) 0f else sign.sign
      }

      val halfChemicals = mother.divide()

      val daughterData = CellData(
        position = mother.body.position+offset,
        linearVelocity = mother.body.linearVelocity,
        direction = mother.body.angle,
        spin = mother.body.angularVelocity,
        type = CellType.Stem,
        radius = sqrt(min(1f, halfChemicals["energy"] ?: 0f)),
        chemicals = halfChemicals,
      )
      val daughter = Cell(world, daughterData)

      val ahead = groupedConnections.getOrDefault(-1f, emptyList())
      val side = groupedConnections.getOrDefault(0f, emptyList())
      ahead.forEach { connection ->
        val neighbour = if(connection.cellA != mother) connection.cellA else connection.cellB
        connectCells(daughter, neighbour)
        connection.cellA.removeJoint(connection.joint)
        if (connection.cellB.removeJoint(connection.joint)) {
          // Only destroy if it was not already removed
          world.destroyJoint(connection.joint)
        }
      }
      side.forEach { connection ->
        val neighbour = if(connection.cellA != mother) connection.cellA else connection.cellB
        connectCells(daughter, neighbour)
      }

      cells.add(daughter)

      mother.body.setTransform(mother.body.position-offset, (mother.body.angle + PI /2)%(PI *2))

      connectCells(mother, daughter)
    }

    cells.addAll(spawns.map { Cell(world, it) })
    spawns.clear()

    assignIds()
  }

  private fun connectCells(cellA: Cell, cellB: Cell): CellConnection {
    val jointDef = DistanceJointDef().apply {
      bodyA = cellA.body
      bodyB = cellB.body
      length = cellA.shape.radius+cellB.shape.radius
      frequencyHz = 10f
      dampingRatio = 4f
    }
    val joint = world.createJoint(jointDef) as DistanceJoint
    val connection = CellConnection(cellA, cellB, joint)
    cellA.connect(connection)
    cellB.connect(connection)
    return connection
  }

  /** Assigns stable ids to any newly created cells (daughters, spawns). */
  private fun assignIds() {
    cells.forEach { if (it.id == null) it.id = nextId++ }
  }

  /** Current live connections as id pairs, for [org.emerge.demo.cyto.CytoSaveCodec]. */
  fun connectionData(): List<CellConnectionData> {
    val seen = HashSet<Long>()
    val out = ArrayList<CellConnectionData>()
    cells.forEach { cell ->
      cell.connections.forEach { con ->
        val a = con.cellA.id
        val b = con.cellB.id
        if (a != null && b != null) {
          val key = if (a < b) a * 1_000_003L + b else b * 1_000_003L + a
          if (seen.add(key)) out.add(CellConnectionData(a, b))
        }
      }
    }
    return out
  }
}
