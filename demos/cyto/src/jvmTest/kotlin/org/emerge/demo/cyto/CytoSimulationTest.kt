package org.emerge.demo.cyto

import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.physics.box2d.Box2D
import org.emerge.demo.cyto.cells.CellConnectionData
import org.emerge.demo.cyto.cells.CellData
import org.emerge.demo.cyto.cells.CellType
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Headless behavioural checks for the ported Cyto simulation. Box2D runs without a
 * display, so these exercise the real physics + chemistry + division pipeline as well
 * as the save-codec round-trip — the parity-critical paths that can't be eyeballed in
 * a GUI-less environment.
 */
class CytoSimulationTest {
  @BeforeTest
  fun loadNatives() {
    Box2D.init()
  }

  @Test
  fun stemCellDividesIntoAColony() {
    val controller = CytoController()
    assertEquals(1, controller.cellWorld.cells.size, "seed should be a single stem cell")

    // ~12 simulated seconds of fixed 1/64 steps. A seeded Stem cell with surplus energy
    // should pass its divide cooldown and grow a multi-cell colony.
    repeat(60) { controller.tick(0.2f) }

    assertTrue(
      controller.cellWorld.cells.size > 1,
      "stem cell should have divided (got ${controller.cellWorld.cells.size} cells)",
    )
  }

  @Test
  fun saveCodecRoundTrips() {
    val cells = listOf(
      CellData(
        position = Vector2(1.5f, -2.25f),
        linearVelocity = Vector2(0.1f, -0.2f),
        chemicals = linkedMapOf("energy" to 1.5f, "en" to 0.25f),
        direction = 0.5f,
        spin = -0.3f,
        radius = 0.75f,
        type = CellType.Muscle,
        id = 7L,
      ),
      CellData(
        position = Vector2(0f, 0f),
        chemicals = linkedMapOf("energy" to 2f),
        type = CellType.Stem,
        id = 8L,
      ),
    )
    val connections = listOf(CellConnectionData(7L, 8L))

    val decoded = CytoSaveCodec.decode(CytoSaveCodec.encode(CytoSnapshot(cells, connections)))

    assertEquals(2, decoded.cells.size)
    val first = decoded.cells.first { it.id == 7L }
    assertEquals(1.5f, first.position.x)
    assertEquals(-2.25f, first.position.y)
    assertEquals(CellType.Muscle, first.type)
    assertEquals(0.75f, first.radius)
    assertEquals(1.5f, first.chemicals["energy"])
    assertEquals(0.25f, first.chemicals["en"])
    assertEquals(1, decoded.connections.size)
    assertEquals(7L, decoded.connections.first().id1)
    assertEquals(8L, decoded.connections.first().id2)
  }
}
