package org.emerge.demo.cyto

import com.badlogic.gdx.physics.box2d.Contact
import com.badlogic.gdx.physics.box2d.ContactImpulse
import com.badlogic.gdx.physics.box2d.ContactListener
import com.badlogic.gdx.physics.box2d.Manifold
import org.emerge.demo.cyto.cells.Cell
import ktx.math.minus
import kotlin.math.absoluteValue

class MyContactListener : ContactListener {
  override fun beginContact(p0: Contact) {}

  override fun endContact(p0: Contact) {}

  override fun preSolve(contact: Contact, p1: Manifold) {
    contact.apply {
      val dataA = fixtureA.body.userData
      val dataB = fixtureB.body.userData
      if (dataA is Cell && dataB is Cell) {
        val displacement = fixtureB.body.position - fixtureA.body.position
        val len2 = displacement.len2()
        if (len2 == 0f) return

        val maxDistance = dataA.shape.radius+dataB.shape.radius
        val threshold = maxDistance*3f/4f
        val threshold2 = threshold*threshold
        if (dataA.isSticky || dataB.isSticky) {
          return
        }

        if (len2 < threshold2) {
          isEnabled = false
          dataA.collide(dataB, sticky = true)
        }
      }
    }
  }

  override fun postSolve(contact: Contact, impulse: ContactImpulse) {
    contact.apply {
      val dataA = fixtureA.body.userData
      val dataB = fixtureB.body.userData
      if (dataA is Cell && dataB is Cell) {
        dataA.collide(dataB, pressure = impulse.normalImpulses.first().absoluteValue)
      }
    }
  }
}
