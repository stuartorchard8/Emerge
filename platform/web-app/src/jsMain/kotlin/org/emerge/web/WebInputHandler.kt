package org.emerge.web

import kotlinx.browser.document
import org.emerge.render.torus.ScreenRenderer
import org.emerge.demo.scavengers.ScavengersInput

class WebInputHandler {
    private val pressed = mutableSetOf<String>()

    init {
        document.addEventListener("keydown", { e ->
            pressed.add(e.asDynamic().code as String)
        })
        document.addEventListener("keyup", { e ->
            pressed.remove(e.asDynamic().code as String)
        })
    }

    fun poll(renderer: ScreenRenderer): ScavengersInput {
        if ("Minus" in pressed) renderer.zoomOut()
        if ("Equal" in pressed) renderer.zoomIn()
        if ("KeyQ" in pressed) renderer.rotateLeft()
        if ("KeyE" in pressed) renderer.rotateRight()

        val thrust = if ("KeyW" in pressed || "ArrowUp" in pressed) Int.MAX_VALUE else 0
        val negTurn = "KeyA" in pressed || "ArrowLeft" in pressed
        val posTurn = "KeyD" in pressed || "ArrowRight" in pressed
        val turn = when {
            negTurn && !posTurn -> -Int.MAX_VALUE
            posTurn && !negTurn -> Int.MAX_VALUE
            else -> 0
        }
        return ScavengersInput(thrust = thrust, turn = turn)
    }
}
