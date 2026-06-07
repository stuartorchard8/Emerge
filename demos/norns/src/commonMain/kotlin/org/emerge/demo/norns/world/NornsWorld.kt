package org.emerge.demo.norns.world

import org.emerge.demo.norns.biology.Biology
import org.emerge.demo.norns.biology.BiologyConfig
import org.emerge.demo.norns.biology.LifeStage
import org.emerge.demo.norns.gene.EmitterGene
import org.emerge.demo.norns.gene.GeneRng
import org.emerge.demo.norns.gene.Genome
import kotlin.math.abs

/**
 * A spatial, watchable artificial-life world — the first thing you can actually run and observe.
 * Creatures forage for food on a grid, eat, age through [LifeStage]s, starve or die of old age
 * (the verified [Biology]), and breed when fertile (genome [crossover][Genome]+mutation), so a
 * colony lives, turns over, and **evolves** a heritable metabolism trait under implicit selection
 * (efficient creatures survive and breed more).
 *
 * Honest scope for this first watch: **survival behaviour is hardwired** (seek nearest food, eat
 * when on it) rather than driven by the learned brain — wiring the neural-net brain into spatial
 * action/navigation is a design+tuning decision best made with a human watching (DESIGN.md G10).
 * The brain/biochemistry subsystems are proven separately; this view exists to make the
 * population dynamics — life, death, heredity, evolution — visible.
 *
 * Deterministic given the seed: creatures step in id order, food spawns on a fixed cadence,
 * all randomness flows through one [GeneRng].
 */
class NornsWorld(val cfg: NornsConfig = NornsConfig(), seed: Long = 1L) {
    private val rng = GeneRng(seed)

    /** Occupied food cells, as `y * width + x`. */
    val food = HashSet<Int>()
    val creatures = ArrayList<WorldCreature>()

    var ticks: Int = 0; private set
    var births: Int = 0; private set
    var deaths: Int = 0; private set
    private var nextId = 0

    init {
        repeat(cfg.foodSeed) { trySpawnFood() }
        repeat(cfg.initialPopulation) {
            val g = Genome(1, 1, listOf(EmitterGene(locus = 0, chemical = 0, gain = rng.nextFloat(), threshold = 0f)))
            spawnCreature(rng.nextInt().mod(cfg.width), rng.nextInt().mod(cfg.height), g)
        }
    }

    val population: Int get() = creatures.size
    fun cellIndex(x: Int, y: Int) = y * cfg.width + x

    fun step() {
        ticks++
        repeat(cfg.foodSpawnPerTick) { if (food.size < cfg.maxFood) trySpawnFood() }
        for (c in creatures) if (c.alive) stepCreature(c)
        reproduce()
        val before = creatures.size
        creatures.removeAll { !it.alive }
        deaths += before - creatures.size
    }

    private fun stepCreature(c: WorldCreature) {
        c.hunger = (c.hunger + c.metabolism).coerceAtMost(1f)

        // Hardwired foraging: step one cell toward the nearest food, else wander.
        val target = nearestFood(c.x, c.y)
        if (target != null) {
            c.x += stepToward(c.x, target % cfg.width)
            c.y += stepToward(c.y, target / cfg.width)
        } else {
            c.x = (c.x + rng.nextInt().mod(3) - 1).coerceIn(0, cfg.width - 1)
            c.y = (c.y + rng.nextInt().mod(3) - 1).coerceIn(0, cfg.height - 1)
        }

        // Eat what's underfoot.
        val here = cellIndex(c.x, c.y)
        if (food.remove(here)) c.hunger = (c.hunger - cfg.eatAmount).coerceAtLeast(0f)

        // Sustained hunger injures organs; otherwise they slowly repair. Then age + apply death.
        val injury = if (c.hunger > cfg.starvationThreshold) (c.hunger - cfg.starvationThreshold) * cfg.starvationDamage else 0f
        c.loci[c.biology.cfg.injuryLocus] = injury
        c.loci[c.biology.cfg.repairLocus] = cfg.baseRepair
        c.biology.tick(c.loci)

        c.ticksLived++
        if (c.reproCooldown > 0) c.reproCooldown--
    }

    private fun reproduce() {
        if (creatures.size >= cfg.maxPopulation) return
        val fertile = creatures.filter { it.alive && it.isFertile(cfg) }
        for (i in fertile.indices) {
            val a = fertile[i]
            if (a.reproCooldown > 0) continue
            for (j in i + 1 until fertile.size) {
                val b = fertile[j]
                if (b.reproCooldown > 0) continue
                if (abs(a.x - b.x) <= 1 && abs(a.y - b.y) <= 1) {
                    val childGenome = a.genome.reproduceWith(b.genome, cfg.mutationRate, rng)
                    spawnCreature(a.x, a.y, childGenome)
                    a.reproCooldown = cfg.reproduceCooldown
                    b.reproCooldown = cfg.reproduceCooldown
                    births++
                    if (creatures.size >= cfg.maxPopulation) return
                    break // a bred this tick
                }
            }
        }
    }

    private fun spawnCreature(x: Int, y: Int, genome: Genome) {
        val biology = Biology(
            BiologyConfig(
                stageStartAge = cfg.stageStartAge, maxAge = cfg.maxAge,
                organCount = 1, vital = booleanArrayOf(true),
                injuryLocus = 0, repairLocus = 1, ageLocus = 2, lifeStageLocus = 3,
            ),
        )
        creatures.add(
            WorldCreature(
                id = nextId++,
                x = x.coerceIn(0, cfg.width - 1), y = y.coerceIn(0, cfg.height - 1),
                genome = genome, biology = biology,
                metabolism = cfg.metabolismOf(genome),
                loci = FloatArray(4),
            ),
        )
    }

    private fun nearestFood(x: Int, y: Int): Int? {
        var best: Int? = null
        var bestDist = Int.MAX_VALUE
        for (cell in food) {
            val fx = cell % cfg.width; val fy = cell / cfg.width
            val dist = abs(fx - x) + abs(fy - y)
            // tie-break on lowest cell index for determinism
            if (dist < bestDist || (dist == bestDist && (best == null || cell < best!!))) {
                bestDist = dist; best = cell
            }
        }
        return best
    }

    private fun trySpawnFood() {
        val x = rng.nextInt().mod(cfg.width)
        val y = rng.nextInt().mod(cfg.height)
        food.add(cellIndex(x, y))
    }

    private fun stepToward(from: Int, to: Int): Int = when {
        to > from -> 1
        to < from -> -1
        else -> 0
    }

    /** Mean metabolism across the living population (the trait selection acts on; lower = fitter). */
    fun meanMetabolism(): Float {
        if (creatures.isEmpty()) return 0f
        var s = 0f; for (c in creatures) s += c.metabolism; return s / creatures.size
    }

    fun meanAge(): Float {
        if (creatures.isEmpty()) return 0f
        var s = 0; for (c in creatures) s += c.biology.age; return s.toFloat() / creatures.size
    }
}

/** One creature living in the [NornsWorld]: position, heritable genome, biology, and hunger. */
class WorldCreature(
    val id: Int,
    var x: Int,
    var y: Int,
    val genome: Genome,
    val biology: Biology,
    val metabolism: Float,
    val loci: FloatArray,
) {
    var hunger: Float = 0f
    var ticksLived: Int = 0
    var reproCooldown: Int = 0

    val alive: Boolean get() = biology.alive

    fun isFertile(cfg: NornsConfig): Boolean {
        val stage = biology.lifeStage.ordinal
        return alive && stage >= cfg.fertileFrom.ordinal && stage <= cfg.fertileTo.ordinal && hunger < cfg.fertileMaxHunger
    }
}

/** Tuning for the world. All placeholders (DESIGN.md G1) — the watch-and-tune surface. */
class NornsConfig(
    val width: Int = 56,
    val height: Int = 22,
    val initialPopulation: Int = 14,
    val maxPopulation: Int = 70,
    val foodSeed: Int = 60,
    val foodSpawnPerTick: Int = 3,
    val maxFood: Int = 90,
    val eatAmount: Float = 0.6f,
    val starvationThreshold: Float = 0.85f,
    val starvationDamage: Float = 0.12f,
    val baseRepair: Float = 0.05f,
    val maxAge: Int = 900,
    val stageStartAge: IntArray = intArrayOf(0, 40, 110, 220, 330, 460, 680, 820),
    val fertileFrom: LifeStage = LifeStage.ADOLESCENT,
    val fertileTo: LifeStage = LifeStage.ADULT,
    val fertileMaxHunger: Float = 0.55f,
    val reproduceCooldown: Int = 140,
    val mutationRate: Float = 0.6f,
    val minMetabolism: Float = 0.004f,
    val maxMetabolism: Float = 0.016f,
) {
    /** Maps a genome's trait gene (an [EmitterGene] gain in [0,1]) to a hunger-rise rate. Lower is
     *  more efficient, so implicit selection drives the population's mean metabolism down. */
    fun metabolismOf(genome: Genome): Float {
        val gain = (genome.genes.firstOrNull() as? EmitterGene)?.gain?.coerceIn(0f, 1f) ?: 0.5f
        return minMetabolism + gain * (maxMetabolism - minMetabolism)
    }
}
