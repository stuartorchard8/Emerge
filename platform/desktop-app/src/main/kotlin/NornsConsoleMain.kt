package org.emerge.desktop

import org.emerge.demo.norns.world.AsciiView
import org.emerge.demo.norns.world.NornsCommands
import org.emerge.demo.norns.world.NornsConfig
import org.emerge.demo.norns.world.NornsWorld
import org.emerge.demo.norns.world.ViewState
import org.emerge.demo.norns.world.WorldCreature
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The Norns render host: a live, **interactive, side-scrolling** ASCII view of the artificial-life
 * colony in the terminal. A deliberate placeholder for the GPU/styling host (the collaborative
 * visual pass) — its job is legible watching + hands-on interaction with the mechanism.
 *
 * Type commands at any time (they're read on a background thread so the animation never blocks):
 *   food <fl> <x> | feed <id> | pick <id> | place <id> <fl> <x> | follow <id> | speed <ms> | pause | go | quit
 *
 * Run: `./gradlew :platform:desktop-app:runNorns -q --console=plain`
 *   optional `--args="<steps> <seed> <delayMs>"`.
 */
fun main(args: Array<String>) {
    val steps = args.getOrNull(0)?.toIntOrNull() ?: Int.MAX_VALUE
    val seed = args.getOrNull(1)?.toLongOrNull() ?: 1L
    val world = NornsWorld(NornsConfig(), seed)
    val view = ViewState(delayMs = args.getOrNull(2)?.toLongOrNull() ?: 140L)

    val commands = ConcurrentLinkedQueue<String>()
    Thread {
        val reader = System.`in`.bufferedReader()
        while (true) commands.add(reader.readLine() ?: break)
    }.apply { isDaemon = true; start() }

    val esc = Char(27).toString()
    val clearScreen = esc + "[H" + esc + "[2J"
    var status = NornsCommands.HELP

    var i = 0
    while (i < steps && !view.quit) {
        if (!view.paused) world.step()
        while (true) status = NornsCommands.apply(world, view, commands.poll() ?: break)

        val follow = resolveFollow(world, view)
        val vw = minOf(world.cfg.worldWidth, AsciiView.VIEW_WIDTH)
        val cameraX = (follow?.x ?: 0) - vw / 2

        print(clearScreen)
        print(AsciiView.render(world, cameraX, view.followId))
        print(if (view.paused) "[PAUSED] > " else "> ")
        print(status)
        print("\n")
        System.out.flush()

        if (world.population == 0) { println("\nThe colony went extinct at tick ${world.ticks}."); break }
        Thread.sleep(view.delayMs.coerceAtLeast(1))
        if (!view.paused) i++
    }
}

/** The followed creature, re-pinned to the eldest living creature if none is set or it has died. */
private fun resolveFollow(world: NornsWorld, view: ViewState): WorldCreature? {
    val current = view.followId?.let { world.creatureById(it) }
    if (current != null && current.alive) return current
    val oldest = world.creatures.maxByOrNull { it.biology.age }
    view.followId = oldest?.id
    return oldest
}
