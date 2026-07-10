package org.emerge.demo.norns.world

/** Mutable playback / camera state the player controls. */
class ViewState(
    var followId: Int? = null,
    var paused: Boolean = false,
    var delayMs: Long = 140L,
    var quit: Boolean = false,
)

/**
 * Parses and applies a player command line to the [NornsWorld] / [ViewState] — the interaction
 * surface (drop food, hand-feed, pick up & place a creature, follow/zoom controls). Pure and
 * testable; the render host just pipes stdin lines into [apply]. Returns a short status message.
 */
object NornsCommands {
    const val HELP = "commands: tickle <id> | slap <id> | feed <id> | pick <id> | place <id> <fl> <x> | " +
        "lift <n> up|down|<fl> | follow <id> | speed <ms> | pause | go | quit"

    fun apply(world: NornsWorld, view: ViewState, line: String): String {
        val p = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (p.isEmpty()) return ""
        fun int(i: Int) = p.getOrNull(i)?.toIntOrNull()
        return when (p[0].lowercase()) {
            "tickle", "t" -> int(1)?.let { world.tickle(it); "tickled #$it" } ?: "usage: tickle <id>"
            "slap", "s" -> int(1)?.let { world.slap(it); "slapped #$it" } ?: "usage: slap <id>"
            "feed" -> int(1)?.let { world.feed(it); "hand-fed #$it" } ?: "usage: feed <id>"
            "pick" -> int(1)?.let { world.pickUp(it); "picked up #$it" } ?: "usage: pick <id>"
            "place" -> {
                val id = int(1); val fl = int(2); val x = int(3)
                if (id != null && fl != null && x != null) { world.place(id, fl, x); "placed #$id at floor $fl, x $x" }
                else "usage: place <id> <floor> <x>"
            }
            "lift" -> {
                val lift = int(1)?.let { world.lifts.getOrNull(it) }
                val arg = p.getOrNull(2)?.lowercase()
                if (lift == null) "usage: lift <n> up|down|<floor>"
                else when (arg) {
                    "up" -> { world.liftUp(lift); "lift ${int(1)} sent up" }
                    "down" -> { world.liftDown(lift); "lift ${int(1)} sent down" }
                    else -> arg?.toIntOrNull()?.let { world.callLift(lift, it); "lift ${int(1)} called to floor $it" }
                        ?: "usage: lift <n> up|down|<floor>"
                }
            }
            "follow" -> { view.followId = int(1); "following ${int(1)?.let { "#$it" } ?: "(cleared)"}" }
            "speed" -> int(1)?.let { view.delayMs = it.toLong().coerceIn(0, 2000); "speed ${view.delayMs}ms/tick" } ?: "usage: speed <ms>"
            "pause" -> { view.paused = true; "paused" }
            "go", "resume" -> { view.paused = false; "resumed" }
            "quit", "q" -> { view.quit = true; "quitting" }
            "help", "?" -> HELP
            else -> "unknown command '${p[0]}' — $HELP"
        }
    }
}
