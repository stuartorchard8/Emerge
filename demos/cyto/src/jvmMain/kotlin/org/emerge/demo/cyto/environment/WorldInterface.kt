package org.emerge.demo.cyto.environment

import com.badlogic.gdx.physics.box2d.World
import org.emerge.demo.cyto.MyContactListener
import ktx.box2d.createWorld

/**
 * Owns the Box2D world and the fixed-timestep accumulator. Rendering, camera, and
 * input — all LibGDX `Viewport`/`SpriteBatch` concerns in the original Cyto — now
 * live in the Emerge host ([org.emerge.demo.cyto.CytoSceneView]) and renderer, so
 * this is a pure simulation surface.
 */
abstract class WorldInterface {
  val world: World = createWorld()

  private var time = 0f

  init {
    world.setContactListener(MyContactListener())
  }

  fun update(rawDeltaTime: Float) {
    time += rawDeltaTime
    val updateCount = (time / TIME_STEP).toInt()
    time -= TIME_STEP*updateCount

    for (i in 1..updateCount) {
      fixedUpdate()
    }
  }

  internal open fun fixedUpdate() {
    world.step(TIME_STEP, 6, 2)
  }

  fun dispose() {
    world.dispose()
  }

  companion object {
    const val TIME_STEP = 1/64f
  }
}
