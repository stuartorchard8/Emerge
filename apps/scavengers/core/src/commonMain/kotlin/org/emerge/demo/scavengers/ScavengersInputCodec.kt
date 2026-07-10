package org.emerge.demo.scavengers

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.sync.Codec

/**
 * Wire codec for Scavengers' [ScavengersInput] — two ints (thrust, turn).
 *
 * Used by lockstep transport to ship inputs between peers each tick.
 */
internal val scavengersInputCodec: Codec<ScavengersInput> =
    object : Codec<ScavengersInput> {
        override fun encode(value: ScavengersInput): ByteArray {
            val w = ByteWriter()
            w.writeInt(value.thrust)
            w.writeInt(value.turn)
            return w.toByteArray()
        }

        override fun decode(bytes: ByteArray): ScavengersInput {
            val c = ByteCursor(bytes)
            val thrust = c.readInt()
            val turn = c.readInt()
            return ScavengersInput(thrust, turn)
        }
    }
