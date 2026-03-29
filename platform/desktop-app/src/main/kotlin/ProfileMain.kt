package org.emerge.desktop

import org.emerge.demo.physics.GameMode
import org.emerge.demo.physics.createDefaultInitialState
import org.emerge.demo.physics.defaultJoinPolicy
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimReducer
import org.emerge.sim.core.TickStepper
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.PhysicsConfig
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.core.physics.systems.*

private const val PLAYER_COUNT = 8
private const val WARMUP_TICKS = 120
private const val PROFILE_TICKS = 1800 // ~30 seconds of game time at 60fps

private val SYSTEMS: List<Pair<String, EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput>>> = listOf(
    "ShipThrust" to ShipThrustSystem,
    "Gravity" to GravitySystem,
    "Integration" to IntegrationSystem,
    "ForceField" to ForceFieldSystem,
    "Collision" to CollisionSystem,
    "Attachment" to AttachmentSystem,
    "Particle" to ParticleSystem,
    "Respawn" to RespawnSystem,
)

class ProfilingReducer : SimReducer<PhysicsConfig, PhysicsState, PhysicsInput> {
    private val accumulatedNanos = LongArray(SYSTEMS.size)
    private val peakNanos = LongArray(SYSTEMS.size)
    private var tickCount = 0

    override fun reduce(cfg: PhysicsConfig, state: PhysicsState, inputs: Map<PlayerId, PhysicsInput>) {
        for (i in SYSTEMS.indices) {
            val start = System.nanoTime()
            SYSTEMS[i].second.update(cfg, state, inputs)
            val elapsed = System.nanoTime() - start
            accumulatedNanos[i] += elapsed
            if (elapsed > peakNanos[i]) peakNanos[i] = elapsed
        }
        tickCount++
    }

    fun reset() {
        accumulatedNanos.fill(0)
        peakNanos.fill(0)
        tickCount = 0
    }

    fun printSummary(wallNanos: Long) {
        val totalSystemNanos = accumulatedNanos.sum()
        val avgTickUs = totalSystemNanos / tickCount / 1000
        val wallTickUs = wallNanos / tickCount / 1000

        println()
        println("=== Simulation Profile ($tickCount ticks, $PLAYER_COUNT players) ===")
        println()
        println("  Wall time:   ${wallNanos / 1_000_000} ms  (${wallTickUs} us/tick)")
        println("  System time: ${totalSystemNanos / 1_000_000} ms  (${avgTickUs} us/tick)")
        println("  Budget:      16,667 us/tick (60 fps)")
        println()
        println("  %-14s  %8s  %8s  %8s  %6s".format("System", "Total ms", "Avg us", "Peak us", "Share"))
        println("  " + "-".repeat(56))
        for (i in SYSTEMS.indices) {
            val name = SYSTEMS[i].first
            val totalMs = accumulatedNanos[i] / 1_000_000
            val avgUs = accumulatedNanos[i] / tickCount / 1000
            val peakUs = peakNanos[i] / 1000
            val share = if (totalSystemNanos > 0) accumulatedNanos[i] * 100 / totalSystemNanos else 0
            println("  %-14s  %8d  %8d  %8d  %5d%%".format(name, totalMs, avgUs, peakUs, share))
        }
        println()
    }
}

fun main() {
    val gameMode = GameMode.CO_OP
    val cfg = PhysicsConfig()
    val state = createDefaultInitialState(gameMode, spawnHostPlayer = true)

    val joinPolicy = defaultJoinPolicy(gameMode)
    for (i in 1 until PLAYER_COUNT) {
        joinPolicy(state, PlayerId(i))
    }

    val reducer = ProfilingReducer()
    val stepper = TickStepper(cfg = cfg, initialState = state, reducer = reducer)

    val inputs = (0 until PLAYER_COUNT).associate { PlayerId(it) to PhysicsInput(thrust = Int.MAX_VALUE, turn = 1000) }

    println("Warming up ($WARMUP_TICKS ticks)...")
    for (i in 0 until WARMUP_TICKS) {
        stepper.step(inputs)
    }
    reducer.reset()

    val entityCount = stepper.state.raw.transforms.keys().size
    println("Profiling ($PROFILE_TICKS ticks, $entityCount entities)...")

    val wallStart = System.nanoTime()
    for (i in 0 until PROFILE_TICKS) {
        stepper.step(inputs)
    }
    val wallNanos = System.nanoTime() - wallStart

    val finalEntityCount = stepper.state.raw.transforms.keys().size
    reducer.printSummary(wallNanos)
    println("  Entity count: $entityCount -> $finalEntityCount")
    println()
}
