package org.emerge.demo.norns.world

import org.emerge.demo.norns.biology.LifeStage
import kotlin.math.roundToInt

/**
 * Renders a [NornsWorld] as a side-on text frame: stacked [floors] (Creatures' multi-floor
 * house), a camera window scrolling across the wider world, and a detail panel for the followed
 * creature. A deliberate placeholder — the point is legible *watching* of the mechanism; the GPU
 * sprite host is the collaborative visual pass (DESIGN.md G2). ASCII-only.
 */
object AsciiView {
    const val VIEW_WIDTH = 72

    /** Render the world through a camera at [cameraX], highlighting + detailing creature [followId]. */
    fun render(world: NornsWorld, cameraX: Int, followId: Int?): String {
        val cfg = world.cfg
        val vw = minOf(cfg.worldWidth, VIEW_WIDTH)
        val camX = cameraX.coerceIn(0, maxOf(0, cfg.worldWidth - vw))

        // Map (floor,x) -> creature in the window; the followed creature wins its cell.
        val byCell = HashMap<Int, WorldCreature>()
        for (c in world.creatures) {
            val cx = c.x.roundToInt()
            if (cx in camX until camX + vw && (byCell[c.floor * cfg.worldWidth + cx] == null || c.id == followId)) {
                byCell[c.floor * cfg.worldWidth + cx] = c
            }
        }

        val sb = StringBuilder()
        val border = "-".repeat(vw)
        sb.append('+').append(border).append("+  ")
        sb.append("[x ").append(camX).append("..").append(camX + vw).append(" / ").append(cfg.worldWidth).append("]\n")

        for (f in (cfg.floors - 1) downTo 0) {
            // air row with creatures + food
            sb.append('|')
            for (x in camX until camX + vw) {
                val c = byCell[f * cfg.worldWidth + x]
                sb.append(
                    when {
                        c != null -> glyph(c, followId)
                        world.food.contains(world.cell(f, x)) -> '*'
                        else -> ' '
                    },
                )
            }
            sb.append("|\n")
            // floor line with lift shafts
            sb.append('|')
            for (x in camX until camX + vw) sb.append(if (world.isLiftColumn(x)) 'H' else '=')
            sb.append("|\n")
        }
        sb.append('+').append(border).append("+\n")

        sb.append(hud(world))
        sb.append(panel(world, followId))
        return sb.toString()
    }

    /** Followed creature 'Y'; otherwise young 'o', fertile 'A', elderly '@'. */
    private fun glyph(c: WorldCreature, followId: Int?): Char {
        if (c.id == followId) return 'Y'
        return when (c.biology.lifeStage) {
            LifeStage.EMBRYO, LifeStage.BABY, LifeStage.CHILD -> 'o'
            LifeStage.ADOLESCENT, LifeStage.YOUTH, LifeStage.ADULT -> 'A'
            LifeStage.OLD, LifeStage.SENILE -> '@'
        }
    }

    private fun hud(world: NornsWorld): String {
        val stages = IntArray(LifeStage.entries.size)
        for (c in world.creatures) stages[c.biology.lifeStage.ordinal]++
        return buildString {
            append("tick="); append(world.ticks)
            append("  pop="); append(world.population)
            append("  food="); append(world.food.size)
            append("  births="); append(world.births)
            append("  deaths="); append(world.deaths)
            append("  meanMetab="); append(fmt4(world.meanMetabolism()))
            append('\n')
            append("legend: o young  A fertile  @ elderly  * food  H lift   |   ")
            append("young="); append(stages[0] + stages[1] + stages[2])
            append(" fertile="); append(stages[3] + stages[4] + stages[5])
            append(" elderly="); append(stages[6] + stages[7])
            append('\n')
        }
    }

    /** Detail panel for the followed creature (the "what's going on" your watch was missing). */
    private fun panel(world: NornsWorld, followId: Int?): String {
        val c = followId?.let { world.creatureById(it) } ?: return "following: (none — use 'follow <id>')\n"
        return buildString {
            append("follow #"); append(c.id)
            append("  stage="); append(c.biology.lifeStage.name.lowercase())
            append("  age="); append(c.biology.age)
            append("  floor="); append(c.floor)
            append("  x="); append(c.x.roundToInt())
            append("  facing="); append(if (c.facing >= 0) "right" else "left")
            if (c.held) append("  [HELD]")
            if (c.carryingFood) append("  [carrying food]")
            append('\n')
            append("  hunger "); append(bar(c.hunger))
            append("  urge "); append(bar(c.matingUrge))
            append("  health "); append(bar(c.biology.organHealth[0]))
            append("  metab="); append(fmt4(c.metabolism))
            append('\n')
            append("  doing: "); append(doing(c.activity)); append("  (goal: "); append(goal(c.goalAction)); append(')')
            append('\n')
        }
    }

    private fun doing(a: ActivityType): String = when (a) {
        ActivityType.IDLE -> "deciding"
        ActivityType.MOVING -> "moving"
        ActivityType.PICKING_UP -> "picking up food"
        ActivityType.EATING -> "eating"
        ActivityType.COURTING -> "courting"
        ActivityType.RESTING -> "resting"
    }

    private fun goal(action: Int): String = when (action) {
        CreatureMind.A_SEEK_FOOD -> "food"
        CreatureMind.A_SEEK_MATE -> "mate"
        else -> "rest"
    }

    private fun bar(v: Float): String {
        val filled = (v.coerceIn(0f, 1f) * 10).toInt()
        return "[" + "#".repeat(filled) + "-".repeat(10 - filled) + "]"
    }

    private fun fmt4(v: Float): String {
        val t = (v * 10000f).toInt()
        val s = t.toString().padStart(5, '0')
        return "0." + s.substring(s.length - 4)
    }
}
