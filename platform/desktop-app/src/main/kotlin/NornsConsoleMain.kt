package org.emerge.desktop

import org.emerge.demo.norns.world.AsciiView
import org.emerge.demo.norns.world.NornsConfig
import org.emerge.demo.norns.world.NornsWorld

/**
 * The first Norns render host: a live ASCII view of the artificial-life colony in the terminal.
 * Deliberately a placeholder (the GPU/styling host is a later pass done with a human) — its job
 * is to let you *watch the mechanism*: creatures foraging, eating, aging through life stages,
 * starving or dying of old age, and breeding, with population/heredity stats in the HUD.
 *
 * Run: `./gradlew :platform:desktop-app:runNorns`
 *   optional `--args="<steps> <delayMs> <seed>"` — e.g. `--args="500 60 7"`.
 */
fun main(args: Array<String>) {
    val steps = args.getOrNull(0)?.toIntOrNull() ?: Int.MAX_VALUE
    val delayMs = args.getOrNull(1)?.toLongOrNull() ?: 80L
    val seed = args.getOrNull(2)?.toLongOrNull() ?: 1L

    val world = NornsWorld(NornsConfig(), seed)
    val esc = Char(27).toString()
    val clearScreen = esc + "[H" + esc + "[2J" // ANSI: cursor home + clear screen

    var i = 0
    while (i < steps) {
        world.step()
        print(clearScreen)
        print(AsciiView.render(world))
        print("\n(Ctrl-C to stop)\n")
        System.out.flush()

        if (world.population == 0) {
            println("The colony went extinct at tick ${world.ticks}.")
            break
        }
        if (delayMs > 0) Thread.sleep(delayMs)
        i++
    }
}
