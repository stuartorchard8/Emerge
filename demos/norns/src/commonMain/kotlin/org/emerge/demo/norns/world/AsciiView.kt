package org.emerge.demo.norns.world

import org.emerge.demo.norns.biology.LifeStage

/**
 * Renders a [NornsWorld] as a plain-text frame: a grid plus a HUD. Deliberately a placeholder —
 * the point of this first render host is to *watch the mechanism* (life, death, heredity,
 * evolution) reliably, not to look like Creatures. The GPU/styling host is a later pass done with
 * a human (DESIGN.md subsystem 8 / G2). ASCII-only so it renders in any terminal.
 */
object AsciiView {
    fun render(world: NornsWorld): String {
        val cfg = world.cfg
        val grid = CharArray(cfg.width * cfg.height) { '.' }
        for (cell in world.food) grid[cell] = '#'
        for (c in world.creatures) grid[world.cellIndex(c.x, c.y)] = glyph(c)

        val sb = StringBuilder()
        val border = "-".repeat(cfg.width)
        sb.append('+').append(border).append("+\n")
        for (y in 0 until cfg.height) {
            sb.append('|')
            for (x in 0 until cfg.width) sb.append(grid[y * cfg.width + x])
            sb.append("|\n")
        }
        sb.append('+').append(border).append("+\n")
        sb.append(hud(world))
        return sb.toString()
    }

    /** Glyph by life stage: young 'o', fertile (adolescent–adult) 'O', elderly '@'. */
    private fun glyph(c: WorldCreature): Char = when (c.biology.lifeStage) {
        LifeStage.EMBRYO, LifeStage.BABY, LifeStage.CHILD -> 'o'
        LifeStage.ADOLESCENT, LifeStage.YOUTH, LifeStage.ADULT -> 'O'
        LifeStage.OLD, LifeStage.SENILE -> '@'
    }

    fun hud(world: NornsWorld): String {
        val stages = IntArray(LifeStage.entries.size)
        for (c in world.creatures) stages[c.biology.lifeStage.ordinal]++
        val stageStr = buildString {
            append("young="); append(stages[0] + stages[1] + stages[2])
            append(" fertile="); append(stages[3] + stages[4] + stages[5])
            append(" elderly="); append(stages[6] + stages[7])
        }
        return buildString {
            append("tick="); append(world.ticks)
            append("  pop="); append(world.population)
            append("  food="); append(world.food.size)
            append("  births="); append(world.births)
            append("  deaths="); append(world.deaths)
            append('\n')
            append("meanAge="); append(fmt1(world.meanAge()))
            append("  meanMetabolism="); append(fmt4(world.meanMetabolism()))
            append("  ["); append(stageStr); append(']')
            append('\n')
        }
    }

    private fun fmt1(v: Float): String {
        val t = (v * 10f).toInt()
        return "${t / 10}.${t % 10}"
    }

    private fun fmt4(v: Float): String {
        val t = (v * 10000f).toInt()
        val s = t.toString().padStart(5, '0')
        return "0.${s.substring(s.length - 4)}"
    }
}
