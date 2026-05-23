package org.emerge.demo.scavengers



abstract class ScavengersController {
    abstract fun tick(localInput: ScavengersInput): ScavengersFrame
}
