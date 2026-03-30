package org.emerge.sim.sync.lockstep

import org.emerge.net.api.Pipe
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimReducer
import org.emerge.sim.sync.Codec
import org.emerge.sim.sync.StateCodec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Deterministic lockstep client.
 *
 * Connects to a [LockstepHost], sends its own input each tick, and advances the local simulation
 * whenever a TickInputs bundle arrives from the host. The simulation is never replaced wholesale
 * except on the initial Welcome or an explicit Resync (player join/leave).
 */
class ThinLockstepClient<C, S, I>(
    cfg: C,
    initialState: S,
    reducer: SimReducer<C, S, I>,
    pipe: Pipe,
    inputCodec: Codec<I>,
    stateCodec: StateCodec<S>,
    handshakeTimeout: Duration = 5.seconds,
    inactivityTimeout: Duration = 5.seconds,
    onDisconnected: ((reason: String) -> Unit)? = null,
    val semiThinStateCodec: StateCodec<S>,
) : LockstepClient<C, S, I> (
    cfg,
    initialState,
    reducer,
    pipe,
    inputCodec,
    stateCodec,
    handshakeTimeout,
    inactivityTimeout,
    onDisconnected
) {
    override val mode = ClientMode.SEMI_THIN

    override fun processMessage(msg: Any) {
        when (msg) {
            is Pair<*, *> -> handleSemiThinResync(
                msg.first as LockstepProtocol.SemiThinResync,
                msg.second as LockstepProtocol.TickInputs
            )
            else -> super.processMessage(msg)
        }
    }

    private fun handleSemiThinResync(resync: LockstepProtocol.SemiThinResync, inputs: LockstepProtocol.TickInputs) {
        val decoded = try {
            semiThinStateCodec.decode(resync.stateBytes)
        } catch (t: Throwable) {
            disconnect("invalid semi-thin resync state: ${t::class.simpleName}")
            return
        }
        stepper.patch(decoded)
        handleTickInputs(inputs)
    }
}
