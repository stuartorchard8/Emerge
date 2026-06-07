package org.emerge.demo.norns.world

import org.emerge.demo.norns.biology.Biology
import org.emerge.demo.norns.biology.BiologyConfig
import org.emerge.demo.norns.biology.LifeStage
import org.emerge.demo.norns.brain.Brain
import org.emerge.demo.norns.gene.EmitterGene
import org.emerge.demo.norns.gene.GeneRng
import org.emerge.demo.norns.gene.Genome
import kotlin.math.abs

/**
 * A **side-scrolling** artificial-life world, in the spirit of Creatures' multi-floor house:
 * creatures live on horizontal [floors] connected by lifts, walk left/right foraging for food,
 * eat, age through [LifeStage]s (the verified [Biology]), starve or die of old age, and breed
 * (genome [crossover][Genome] + mutation) — so a colony lives, turns over, and **evolves** a
 * heritable metabolism trait under implicit selection. The world is wider than the screen; a
 * camera scrolls.
 *
 * Player [interaction] is first-class: drop food, hand-feed, or pick up and place a creature.
 *
 * Honest scope (DESIGN.md G10): foraging is hardwired (seek nearest food, ride lifts between
 * floors), NOT driven by the learned brain — wiring the neural-net brain into spatial behaviour
 * is a design call for the visual/tuning pass. This view makes the population dynamics visible.
 * Deterministic given the seed.
 */
class NornsWorld(val cfg: NornsConfig = NornsConfig(), seed: Long = 1L) {
    private val rng = GeneRng(seed)

    /** Food positions encoded as `floor * worldWidth + x`. */
    val food = HashSet<Int>()
    val creatures = ArrayList<WorldCreature>()

    var ticks: Int = 0; private set
    var births: Int = 0; private set
    var deaths: Int = 0; private set
    private var nextId = 0

    init {
        repeat(cfg.foodSeed) { trySpawnFood() }
        repeat(cfg.initialPopulation) {
            // genome = a metabolism trait gene + the (heritable, evolvable) brain instinct genes
            val g = Genome(
                1, 1,
                listOf(EmitterGene(locus = 0, chemical = 0, gain = rng.nextFloat(), threshold = 0f)) +
                    CreatureMind.defaultInstinctGenes(),
            )
            spawnCreature(rng.nextInt().mod(cfg.worldWidth), rng.nextInt().mod(cfg.floors), g)
        }
    }

    val population: Int get() = creatures.size
    fun cell(floor: Int, x: Int) = floor * cfg.worldWidth + x
    fun foodFloor(cell: Int) = cell / cfg.worldWidth
    fun foodX(cell: Int) = cell % cfg.worldWidth
    fun creatureById(id: Int): WorldCreature? = creatures.firstOrNull { it.id == id }

    fun step() {
        ticks++
        repeat(cfg.foodSpawnPerTick) { if (food.size < cfg.maxFood) trySpawnFood() }
        // Two phases so a creature's reward reflects mating (resolved in reproduce, after movement)
        // as well as the eating it did this tick.
        for (c in creatures) if (c.alive && !c.held) decideAndAct(c)
        reproduce()
        for (c in creatures) if (c.alive && !c.held) learnAndAge(c)
        val before = creatures.size
        creatures.removeAll { !it.alive }
        deaths += before - creatures.size
    }

    /** Phase 1: drives rise, the brain perceives + chooses a goal verb, the creature acts on it. */
    private fun decideAndAct(c: WorldCreature) {
        c.hunger = (c.hunger + c.metabolism).coerceAtMost(1f)
        if (c.biology.lifeStage.ordinal >= cfg.fertileFrom.ordinal) {
            c.matingUrge = (c.matingUrge + cfg.matingRate).coerceAtMost(1f)
        }

        val foodCell = nearestFood(c)
        val mate = nearestMate(c)

        val percept = FloatArray(CreatureMind.PERCEPTION)
        percept[CreatureMind.P_HUNGER] = c.hunger
        percept[CreatureMind.P_URGE] = c.matingUrge
        percept[CreatureMind.P_FOOD] = proximity(if (foodCell != null) foodCost(c, foodFloor(foodCell), foodX(foodCell)) else -1)
        percept[CreatureMind.P_MATE] = proximity(if (mate != null) foodCost(c, mate.floor, mate.x) else -1)
        percept[CreatureMind.P_BIAS] = 1f
        c.brain.lobes[0].set(percept)

        c.lastPrevDiscomfort = c.hunger + c.matingUrge
        c.brain.propagate()
        val greedy = c.brain.lobes[1].argmax()
        c.lastAction =
            if (cfg.brainExplore > 0f && rng.nextFloat() < cfg.brainExplore) rng.nextInt().mod(CreatureMind.ACTIONS)
            else greedy

        when (c.lastAction) {
            CreatureMind.A_SEEK_FOOD -> if (foodCell != null) walkToward(c, foodFloor(foodCell), foodX(foodCell)) else wander(c)
            CreatureMind.A_SEEK_MATE -> if (mate != null) walkToward(c, mate.floor, mate.x) else wander(c)
            else -> wander(c)
        }
        if (food.remove(cell(c.floor, c.x))) c.hunger = (c.hunger - cfg.eatAmount).coerceAtLeast(0f)
    }

    /** Phase 3: reinforce the chosen action by the discomfort it relieved, then age + apply death. */
    private fun learnAndAge(c: WorldCreature) {
        val reward = c.lastPrevDiscomfort - (c.hunger + c.matingUrge)
        c.brain.lobes[1].set(oneHot(c.lastAction, CreatureMind.ACTIONS))
        c.brain.learn(reward)

        val injury = if (c.hunger > cfg.starvationThreshold) (c.hunger - cfg.starvationThreshold) * cfg.starvationDamage else 0f
        c.loci[c.biology.cfg.injuryLocus] = injury
        c.loci[c.biology.cfg.repairLocus] = cfg.baseRepair
        c.biology.tick(c.loci)

        c.ticksLived++
        if (c.reproCooldown > 0) c.reproCooldown--
    }

    private fun proximity(cost: Int): Float =
        if (cost < 0) 0f else (1f - cost.toFloat() / cfg.senseRange).coerceIn(0f, 1f)

    private fun wander(c: WorldCreature) {
        val dir = rng.nextInt().mod(3) - 1
        if (dir != 0) { c.x = (c.x + dir).coerceIn(0, cfg.worldWidth - 1); c.facing = dir }
    }

    private fun oneHot(k: Int, n: Int) = FloatArray(n) { if (it == k) 1f else 0f }

    /** Walk one step toward food on (targetFloor, targetX): change floor at a lift, else walk x. */
    private fun walkToward(c: WorldCreature, targetFloor: Int, targetX: Int) {
        if (targetFloor != c.floor) {
            val lift = nearestLift(c.x)
            if (c.x != lift) { val d = sign(lift - c.x); c.x += d; c.facing = d }
            else c.floor += sign(targetFloor - c.floor) // ride the lift one floor
        } else if (c.x != targetX) {
            val d = sign(targetX - c.x); c.x += d; c.facing = d
        }
    }

    private fun reproduce() {
        if (creatures.size >= cfg.maxPopulation) return
        val fertile = creatures.filter { it.alive && !it.held && it.isFertile(cfg) }
        for (i in fertile.indices) {
            val a = fertile[i]
            if (a.reproCooldown > 0) continue
            for (j in i + 1 until fertile.size) {
                val b = fertile[j]
                if (b.reproCooldown > 0) continue
                if (a.floor == b.floor && abs(a.x - b.x) <= cfg.mateRange) {
                    val child = a.genome.reproduceWith(b.genome, cfg.mutationRate, rng)
                    spawnCreature(a.x, a.floor, child)
                    a.reproCooldown = cfg.reproduceCooldown
                    b.reproCooldown = cfg.reproduceCooldown
                    a.matingUrge = 0f // satisfied — the drop rewards a SEEK_MATE decision in phase 3
                    b.matingUrge = 0f
                    births++
                    if (creatures.size >= cfg.maxPopulation) return
                    break
                }
            }
        }
    }

    private fun spawnCreature(x: Int, floor: Int, genome: Genome) {
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
                x = x.coerceIn(0, cfg.worldWidth - 1), floor = floor.coerceIn(0, cfg.floors - 1),
                genome = genome, biology = biology, metabolism = cfg.metabolismOf(genome),
                brain = CreatureMind.build(genome, cfg.brainLearnRate),
                loci = FloatArray(4),
            ),
        )
    }

    /** Nearest other fertile, ready creature — the mate a fertile creature walks toward. */
    private fun nearestMate(self: WorldCreature): WorldCreature? {
        var best: WorldCreature? = null
        var bestCost = Int.MAX_VALUE
        for (o in creatures) {
            if (o.id == self.id || !o.alive || o.held || o.reproCooldown > 0 || !o.isFertile(cfg)) continue
            val cost = foodCost(self, o.floor, o.x)
            if (cost < bestCost || (cost == bestCost && (best == null || o.id < best!!.id))) {
                bestCost = cost; best = o
            }
        }
        return best
    }

    private fun nearestFood(c: WorldCreature): Int? {
        var best: Int? = null
        var bestCost = Int.MAX_VALUE
        for (cellId in food) {
            val cost = foodCost(c, foodFloor(cellId), foodX(cellId))
            if (cost < bestCost || (cost == bestCost && (best == null || cellId < best!!))) {
                bestCost = cost; best = cellId
            }
        }
        return best
    }

    /** Travel cost to food: same floor → horizontal distance; else via the nearest lift. */
    private fun foodCost(c: WorldCreature, floor: Int, x: Int): Int =
        if (floor == c.floor) abs(c.x - x)
        else { val lift = nearestLift(c.x); abs(c.x - lift) + abs(floor - c.floor) * cfg.liftSpacing + abs(lift - x) }

    private fun nearestLift(x: Int): Int {
        val snapped = ((x + cfg.liftSpacing / 2) / cfg.liftSpacing) * cfg.liftSpacing
        return snapped.coerceIn(0, (cfg.worldWidth - 1) / cfg.liftSpacing * cfg.liftSpacing)
    }

    fun isLiftColumn(x: Int): Boolean = x % cfg.liftSpacing == 0

    private fun trySpawnFood() {
        food.add(cell(rng.nextInt().mod(cfg.floors), rng.nextInt().mod(cfg.worldWidth)))
    }

    private fun sign(d: Int) = if (d > 0) 1 else if (d < 0) -1 else 0

    // ── player interaction ───────────────────────────────────────────────────────

    /** Drop food at a spot (clamped into the world). */
    fun dropFood(floor: Int, x: Int) {
        food.add(cell(floor.coerceIn(0, cfg.floors - 1), x.coerceIn(0, cfg.worldWidth - 1)))
    }

    /** Hand-feed a creature: relieve its hunger directly. */
    fun feed(id: Int) {
        creatureById(id)?.let { it.hunger = (it.hunger - cfg.eatAmount).coerceAtLeast(0f) }
    }

    /** Pick a creature up (it pauses, foraging suspended) so it can be placed elsewhere. */
    fun pickUp(id: Int) { creatureById(id)?.held = true }

    /** Place a held (or any) creature at a new spot and release it. */
    fun place(id: Int, floor: Int, x: Int) {
        creatureById(id)?.let {
            it.floor = floor.coerceIn(0, cfg.floors - 1)
            it.x = x.coerceIn(0, cfg.worldWidth - 1)
            it.held = false
        }
    }

    // ── stats ─────────────────────────────────────────────────────────────────────

    fun meanMetabolism(): Float {
        if (creatures.isEmpty()) return 0f
        var s = 0f; for (c in creatures) s += c.metabolism; return s / creatures.size
    }

    fun meanAge(): Float {
        if (creatures.isEmpty()) return 0f
        var s = 0; for (c in creatures) s += c.biology.age; return s.toFloat() / creatures.size
    }
}

/** One creature in the side-scroll world: position (x, [floor]), heritable genome, biology, hunger. */
class WorldCreature(
    val id: Int,
    var x: Int,
    var floor: Int,
    val genome: Genome,
    val biology: Biology,
    val metabolism: Float,
    val brain: Brain,
    val loci: FloatArray,
) {
    var hunger: Float = 0f
    var matingUrge: Float = 0f
    var ticksLived: Int = 0
    var reproCooldown: Int = 0
    var facing: Int = 1
    var held: Boolean = false // picked up by the player

    // scratch for the two-phase brain tick (decide/act → reproduce → learn)
    var lastAction: Int = CreatureMind.A_REST
    var lastPrevDiscomfort: Float = 0f

    val alive: Boolean get() = biology.alive

    fun isFertile(cfg: NornsConfig): Boolean {
        val stage = biology.lifeStage.ordinal
        return alive && stage >= cfg.fertileFrom.ordinal && stage <= cfg.fertileTo.ordinal && hunger < cfg.fertileMaxHunger
    }
}

/** Tuning for the side-scroll world. All placeholders (DESIGN.md G1) — the watch-and-tune surface. */
class NornsConfig(
    val worldWidth: Int = 120,
    val floors: Int = 3,
    val liftSpacing: Int = 30,
    val initialPopulation: Int = 16,
    val maxPopulation: Int = 70,
    val foodSeed: Int = 60,
    val foodSpawnPerTick: Int = 3,
    val maxFood: Int = 120,
    val eatAmount: Float = 0.6f,
    val starvationThreshold: Float = 0.85f,
    val starvationDamage: Float = 0.12f,
    val baseRepair: Float = 0.05f,
    val maxAge: Int = 900,
    val stageStartAge: IntArray = intArrayOf(0, 40, 110, 220, 330, 460, 680, 820),
    val fertileFrom: LifeStage = LifeStage.ADOLESCENT,
    val fertileTo: LifeStage = LifeStage.ADULT,
    val fertileMaxHunger: Float = 0.6f,
    val reproduceCooldown: Int = 90,
    val mateRange: Int = 2,
    val senseRange: Int = 30,
    val matingRate: Float = 0.01f,
    val brainLearnRate: Float = 0.05f,
    val brainExplore: Float = 0.05f,
    val mutationRate: Float = 0.6f,
    val minMetabolism: Float = 0.004f,
    val maxMetabolism: Float = 0.016f,
) {
    fun metabolismOf(genome: Genome): Float {
        val gain = genome.genes.filterIsInstance<EmitterGene>().firstOrNull()?.gain?.coerceIn(0f, 1f) ?: 0.5f
        return minMetabolism + gain * (maxMetabolism - minMetabolism)
    }
}
