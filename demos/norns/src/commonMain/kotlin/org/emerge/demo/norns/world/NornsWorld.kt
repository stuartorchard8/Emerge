package org.emerge.demo.norns.world

import org.emerge.demo.norns.biology.Biology
import org.emerge.demo.norns.biology.BiologyConfig
import org.emerge.demo.norns.biology.LifeStage
import org.emerge.demo.norns.brain.Brain
import org.emerge.demo.norns.gene.EmitterGene
import org.emerge.demo.norns.gene.GeneRng
import org.emerge.demo.norns.gene.Genome
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A **side-scrolling** artificial-life world (Creatures' multi-floor house): creatures glide along
 * continuous-position floors connected by lifts, age through [LifeStage]s (the verified
 * [Biology]), and breed (genome [crossover][Genome] + mutation) — so the colony lives, turns
 * over, and **evolves** heritable traits.
 *
 * Behaviour is **durative**: the brain ([CreatureMind]) chooses a goal when idle, and the creature
 * carries it out as a timed [ActivityType] sequence with a real cost — e.g. *move to food → pick
 * it up → carry it → eat it*, each taking ticks and driving an animation. Movement is continuous
 * (sub-cell glide), not grid hops. The brain learns on goal completion from the net drive
 * reduction the goal achieved.
 *
 * Player [interaction]: drop food, hand-feed, pick up & place a creature. Deterministic per seed.
 */
class NornsWorld(val cfg: NornsConfig = NornsConfig(), seed: Long = 1L) {
    private val rng = GeneRng(seed)

    /** Food positions encoded as `floor * worldWidth + x` (food sits on integer cells). */
    val food = HashSet<Int>()
    val creatures = ArrayList<WorldCreature>()

    /** Physical lift cars, one per lift column, that oscillate between floors over time. */
    val lifts: List<Lift> = (0 until cfg.worldWidth step cfg.liftSpacing).map { Lift(it) }
    private val liftByColumn = lifts.associateBy { it.column }

    var ticks: Int = 0; private set
    var births: Int = 0; private set
    var deaths: Int = 0; private set
    private var nextId = 0

    init {
        repeat(cfg.foodSeed) { trySpawnFood() }
        repeat(cfg.initialPopulation) {
            val g = Genome(
                1, 1,
                listOf(EmitterGene(locus = 0, chemical = 0, gain = rng.nextFloat(), threshold = 0f)) +
                    CreatureMind.defaultInstinctGenes(),
            )
            spawnCreature(rng.nextInt().mod(cfg.worldWidth).toFloat(), rng.nextInt().mod(cfg.floors), g, breed = rng.nextInt().mod(cfg.breedCount))
        }
    }

    val population: Int get() = creatures.size
    fun cell(floor: Int, x: Int) = floor * cfg.worldWidth + x
    fun foodFloor(cell: Int) = cell / cfg.worldWidth
    fun foodX(cell: Int) = cell % cfg.worldWidth
    fun creatureById(id: Int): WorldCreature? = creatures.firstOrNull { it.id == id }
    fun isLiftColumn(x: Int): Boolean = x % cfg.liftSpacing == 0

    fun step() {
        ticks++
        for (lift in lifts) lift.tick(cfg.liftSpeed)
        repeat(cfg.foodSpawnPerTick) { if (food.size < cfg.maxFood) trySpawnFood() }
        // Iterate a snapshot: courting can spawn a newborn into `creatures` mid-step (it's stepped
        // next tick), which would otherwise be a concurrent modification.
        for (c in creatures.toList()) if (c.alive && !c.held) stepCreature(c)
        val before = creatures.size
        creatures.removeAll { !it.alive }
        deaths += before - creatures.size
    }

    private fun stepCreature(c: WorldCreature) {
        // An EMBRYO is an egg: it just incubates (ages) where it was laid — no drives, no behaviour
        // — until it hatches into a BABY and starts living.
        if (c.biology.lifeStage == LifeStage.EMBRYO) {
            c.loci[c.biology.cfg.injuryLocus] = 0f
            c.loci[c.biology.cfg.repairLocus] = cfg.baseRepair
            c.biology.tick(c.loci)
            return
        }
        // Drives rise/decay/react via the creature's biochemistry (G8). Being busy builds fatigue.
        val exerting = c.activity == ActivityType.MOVING || c.activity == ActivityType.EATING ||
            c.activity == ActivityType.COURTING || c.activity == ActivityType.PICKING_UP
        c.chem.tick(fertileActive = c.biology.lifeStage.ordinal >= cfg.fertileFrom.ordinal, exerting = exerting)

        when (c.activity) {
            ActivityType.IDLE -> decide(c)
            ActivityType.MOVING -> {
                // Pursue a mate (it moves too); abandon if it's no longer an eligible partner.
                val partner = if (c.goalAction == CreatureMind.A_SEEK_MATE) creatureById(c.partnerId) else null
                if (c.goalAction == CreatureMind.A_SEEK_MATE &&
                    (partner == null || !partner.alive || partner.held || partner.reproCooldown > 0 || !partner.isFertile(cfg))
                ) {
                    completeGoal(c)
                } else {
                    if (partner != null) { c.targetX = partner.x; c.targetFloor = partner.floor }
                    if (moveToward(c, c.targetX, c.targetFloor)) onArrive(c)
                }
            }
            ActivityType.PICKING_UP -> if (--c.activityTimer <= 0) finishPickup(c)
            ActivityType.EATING -> if (--c.activityTimer <= 0) finishEating(c)
            ActivityType.COURTING -> if (--c.activityTimer <= 0) finishCourting(c)
            ActivityType.RESTING -> { c.chem.recover(cfg.restRecovery); if (--c.activityTimer <= 0) completeGoal(c) }
        }

        // Aging + death happen every tick regardless of activity.
        val injury = if (c.hunger > cfg.starvationThreshold) (c.hunger - cfg.starvationThreshold) * cfg.starvationDamage else 0f
        c.loci[c.biology.cfg.injuryLocus] = injury
        c.loci[c.biology.cfg.repairLocus] = cfg.baseRepair
        c.biology.tick(c.loci)
        c.ticksLived++
        if (c.reproCooldown > 0) c.reproCooldown--
    }

    // ── decision (brain picks a goal; snapshot for learning at completion) ───────
    private fun decide(c: WorldCreature) {
        val foodCell = nearestFood(c)
        val mate = nearestMate(c)

        val percept = FloatArray(CreatureMind.PERCEPTION)
        percept[CreatureMind.P_HUNGER] = c.hunger
        percept[CreatureMind.P_URGE] = c.matingUrge
        percept[CreatureMind.P_FOOD] = proximity(if (foodCell != null) travelCost(c, foodFloor(foodCell), foodX(foodCell).toFloat()) else -1f)
        percept[CreatureMind.P_MATE] = proximity(if (mate != null) travelCost(c, mate.floor, mate.x) else -1f)
        percept[CreatureMind.P_FATIGUE] = c.chem.fatigue
        percept[CreatureMind.P_BIAS] = 1f
        c.brain.lobes[0].set(percept)
        c.brain.propagate()

        val greedy = c.brain.lobes[1].argmax()
        c.goalAction =
            if (cfg.brainExplore > 0f && rng.nextFloat() < cfg.brainExplore) rng.nextInt().mod(CreatureMind.ACTIONS)
            else greedy
        percept.copyInto(c.decisionPerception)
        c.decisionDiscomfort = c.discomfort()

        when (c.goalAction) {
            CreatureMind.A_SEEK_FOOD ->
                if (c.carryingFood) startEating(c)
                else if (foodCell != null) startMoving(c, foodX(foodCell).toFloat(), foodFloor(foodCell), -1)
                else startResting(c, cfg.restTicks)
            CreatureMind.A_SEEK_MATE ->
                if (mate != null) startMoving(c, mate.x, mate.floor, mate.id)
                else startResting(c, cfg.restTicks)
            else -> startResting(c, cfg.restTicks)
        }
    }

    private fun onArrive(c: WorldCreature) = when (c.goalAction) {
        CreatureMind.A_SEEK_FOOD -> { c.activity = ActivityType.PICKING_UP; c.activityTimer = cfg.pickupTicks }
        CreatureMind.A_SEEK_MATE -> {
            val partner = creatureById(c.partnerId)
            if (partner != null && canCourt(c, partner)) {
                // Lock BOTH into courting so the pair stays put for the courtship, then breeds.
                beginCourting(c, partner.id)
                beginCourting(partner, c.id)
            } else completeGoal(c)
        }
        else -> completeGoal(c)
    }

    private fun beginCourting(c: WorldCreature, partnerId: Int) {
        c.activity = ActivityType.COURTING
        c.activityTimer = cfg.courtTicks
        c.partnerId = partnerId
        c.goalAction = CreatureMind.A_SEEK_MATE
        c.decisionDiscomfort = c.discomfort() // baseline for the courting reward
    }

    private fun finishPickup(c: WorldCreature) {
        val here = cell(c.floor, c.x.roundToInt().coerceIn(0, cfg.worldWidth - 1))
        if (food.remove(here)) { c.carryingFood = true; startEating(c) } else completeGoal(c) // food gone
    }

    private fun finishEating(c: WorldCreature) {
        if (c.carryingFood) { c.chem.eat(); c.carryingFood = false } // glucose pulse → hunger falls next tick
        completeGoal(c)
    }

    private fun finishCourting(c: WorldCreature) {
        val partner = creatureById(c.partnerId)
        if (partner != null && canCourt(c, partner) && creatures.size < cfg.maxPopulation) {
            val child = c.genome.reproduceWith(partner.genome, cfg.mutationRate, rng)
            // breed is heritable: the child takes a parent's breed, with a small chance of mutating
            val childBreed = if (rng.nextInt().mod(100) < (cfg.breedMutationPct))
                rng.nextInt().mod(cfg.breedCount)
            else if (rng.nextInt().mod(2) == 0) c.breed else partner.breed
            spawnCreature(c.x, c.floor, child, childBreed)
            partner.reproCooldown = cfg.reproduceCooldown
            partner.chem.resetUrge()
            births++
        }
        c.reproCooldown = cfg.reproduceCooldown
        c.chem.resetUrge()
        completeGoal(c)
    }

    /** Ends the current goal: reinforce it by the net drive reduction it achieved, then go idle. */
    private fun completeGoal(c: WorldCreature) {
        val reward = c.decisionDiscomfort - c.discomfort()
        c.brain.lobes[0].set(c.decisionPerception)
        c.brain.lobes[1].set(oneHot(c.goalAction, CreatureMind.ACTIONS))
        c.brain.learn(reward)
        c.activity = ActivityType.IDLE
    }

    private fun startMoving(c: WorldCreature, targetX: Float, targetFloor: Int, partnerId: Int) {
        c.activity = ActivityType.MOVING; c.targetX = targetX; c.targetFloor = targetFloor; c.partnerId = partnerId
    }
    private fun startEating(c: WorldCreature) { c.activity = ActivityType.EATING; c.activityTimer = cfg.eatTicks }
    private fun startResting(c: WorldCreature, ticks: Int) { c.activity = ActivityType.RESTING; c.activityTimer = ticks }

    private fun canCourt(c: WorldCreature, p: WorldCreature): Boolean =
        p.alive && !p.held && p.isFertile(cfg) && p.reproCooldown <= 0 &&
            p.floor == c.floor && abs(p.x - c.x) <= cfg.mateRange

    // ── continuous movement (with physical lifts) ───────────────────────────────
    /** Glide one step toward (targetX, targetFloor); returns true once arrived. Changing floor
     *  means walking to the lift shaft, pressing the call button, waiting for the car, riding it,
     *  pressing the destination button, and disembarking — the C2 lift is summoned, not perpetual. */
    private fun moveToward(c: WorldCreature, targetX: Float, targetFloor: Int): Boolean {
        if (c.onLift || c.floor != targetFloor) {
            val col = nearestLiftColumn(c.x)
            val lift = liftByColumn[col]
            if (lift == null) { c.onLift = false; c.ridingY = -1f; return true } // no lift; give up
            if (!c.onLift) {
                if (abs(c.x - col) > cfg.moveSpeed) { glide(c, col.toFloat()); return false }
                c.x = col.toFloat() // standing in the shaft
                if (abs(lift.carPos - c.floor) <= cfg.liftBoardEps) { c.onLift = true; c.ridingY = lift.carPos }
                else lift.call(c.floor) // press the call button and wait for the car to arrive
            } else {
                c.ridingY = lift.carPos // ride the car
                if (abs(lift.carPos - targetFloor) <= cfg.liftBoardEps) {
                    c.floor = targetFloor; c.onLift = false; c.ridingY = -1f
                } else lift.call(targetFloor) // hold the destination button until we arrive
            }
            return false
        }
        if (abs(c.x - targetX) > cfg.arriveEps) { glide(c, targetX); return false }
        return true
    }

    private fun nearestLiftColumn(x: Float): Int {
        val last = (cfg.worldWidth - 1) / cfg.liftSpacing * cfg.liftSpacing
        return ((x / cfg.liftSpacing).roundToInt() * cfg.liftSpacing).coerceIn(0, last)
    }

    private fun glide(c: WorldCreature, targetX: Float) {
        val d = targetX - c.x
        val step = d.coerceIn(-cfg.moveSpeed, cfg.moveSpeed)
        c.x = (c.x + step).coerceIn(0f, (cfg.worldWidth - 1).toFloat())
        c.facing = if (d >= 0f) 1 else -1
    }

    private fun nearestLiftF(x: Float): Float {
        val snapped = (((x / cfg.liftSpacing).roundToInt()) * cfg.liftSpacing)
        return snapped.toFloat().coerceIn(0f, ((cfg.worldWidth - 1) / cfg.liftSpacing * cfg.liftSpacing).toFloat())
    }

    private fun proximity(cost: Float): Float =
        if (cost < 0f) 0f else (1f - cost / cfg.senseRange).coerceIn(0f, 1f)

    private fun oneHot(k: Int, n: Int) = FloatArray(n) { if (it == k) 1f else 0f }

    private fun nearestMate(self: WorldCreature): WorldCreature? {
        var best: WorldCreature? = null
        var bestCost = Float.MAX_VALUE
        for (o in creatures) {
            if (o.id == self.id || !o.alive || o.held || o.reproCooldown > 0 || !o.isFertile(cfg)) continue
            val cost = travelCost(self, o.floor, o.x)
            if (cost < bestCost || (cost == bestCost && (best == null || o.id < best!!.id))) { bestCost = cost; best = o }
        }
        return best
    }

    private fun nearestFood(c: WorldCreature): Int? {
        var best: Int? = null
        var bestCost = Float.MAX_VALUE
        for (cellId in food) {
            val cost = travelCost(c, foodFloor(cellId), foodX(cellId).toFloat())
            if (cost < bestCost || (cost == bestCost && (best == null || cellId < best!!))) { bestCost = cost; best = cellId }
        }
        return best
    }

    private fun travelCost(c: WorldCreature, floor: Int, x: Float): Float =
        if (floor == c.floor) abs(c.x - x)
        else { val lift = nearestLiftF(c.x); abs(c.x - lift) + abs(floor - c.floor) * cfg.liftSpacing + abs(lift - x) }

    private fun trySpawnFood() {
        food.add(cell(rng.nextInt().mod(cfg.floors), rng.nextInt().mod(cfg.worldWidth)))
    }

    private fun spawnCreature(x: Float, floor: Int, genome: Genome, breed: Int = 0) {
        val biology = Biology(
            BiologyConfig(
                stageStartAge = cfg.stageStartAge, maxAge = cfg.maxAge,
                organCount = 1, vital = booleanArrayOf(true),
                injuryLocus = 0, repairLocus = 1, ageLocus = 2, lifeStageLocus = 3,
            ),
        )
        val metab = cfg.metabolismOf(genome)
        creatures.add(
            WorldCreature(
                id = nextId++,
                x = x.coerceIn(0f, (cfg.worldWidth - 1).toFloat()), floor = floor.coerceIn(0, cfg.floors - 1),
                genome = genome, biology = biology, metabolism = metab,
                brain = CreatureMind.build(genome, cfg.brainLearnRate),
                chem = CreatureChemistry(metab, cfg),
                loci = FloatArray(4),
            ).also { it.breed = breed.mod(cfg.breedCount) },
        )
    }

    // ── player interaction ───────────────────────────────────────────────────────

    fun dropFood(floor: Int, x: Int) {
        food.add(cell(floor.coerceIn(0, cfg.floors - 1), x.coerceIn(0, cfg.worldWidth - 1)))
    }

    fun feed(id: Int) {
        creatureById(id)?.let { it.chem.setHunger(it.hunger - cfg.eatAmount) }
    }

    /** The living creature nearest to (floor, x) within [radius], for click-to-pick. */
    fun creatureNear(floor: Int, x: Float, radius: Float): WorldCreature? =
        creatures.filter { it.alive && it.floor == floor && abs(it.x - x) <= radius }.minByOrNull { abs(it.x - x) }

    fun pickUp(id: Int) { creatureById(id)?.held = true }

    fun place(id: Int, floor: Int, x: Int) {
        creatureById(id)?.let {
            it.floor = floor.coerceIn(0, cfg.floors - 1)
            it.x = x.coerceIn(0, cfg.worldWidth - 1).toFloat()
            it.held = false
            it.activity = ActivityType.IDLE // re-decide from the new spot
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

/**
 * A **called** lift car at a fixed [column] (Creatures-2 style): it idles at whatever floor it last
 * stopped at until a creature — or the player — presses a call button ([call]); then it travels to
 * serve each requested floor in turn, stopping there with its doors open. It never moves on its own.
 * [carPos] is the continuous floor it sits at (an integer when idle); a creature boards when the car
 * reaches its floor and rides until the car reaches the floor it asked for — so levels take time.
 */
class Lift(val column: Int, var carPos: Float = 0f) {
    /** Floors with a pending call: a creature waiting at that floor, or a rider's chosen stop. */
    val calls = HashSet<Int>()

    /** True when the car is parked at a floor with nothing requested. */
    val idle: Boolean get() = calls.isEmpty()

    /** Press a call button (at a floor) or a movement button (inside the car): summon it to [floor]. */
    fun call(floor: Int) { calls.add(floor) }

    /** Advance toward the nearest requested floor; on arrival, stop there and clear that call. */
    fun tick(speed: Float) {
        if (calls.isEmpty()) return                                    // idle — the car stays put
        val target = calls.minWith(compareBy({ abs(it - carPos) }, { it }))!!
        val d = target - carPos
        if (abs(d) <= speed) { carPos = target.toFloat(); calls.remove(target) } // doors open here
        else carPos += if (d > 0f) speed else -speed
    }
}

/** A creature's current durative activity. */
enum class ActivityType { IDLE, MOVING, PICKING_UP, EATING, COURTING, RESTING }

/** One creature: continuous position (x, [floor]), heritable genome, biology, drives, and the
 *  durative activity it's carrying out. */
class WorldCreature(
    val id: Int,
    var x: Float,
    var floor: Int,
    val genome: Genome,
    val biology: Biology,
    val metabolism: Float,
    val brain: Brain,
    val chem: CreatureChemistry,
    val loci: FloatArray,
) {
    // Drives are now biochemistry (G8): hunger/urge/fatigue are chemical concentrations.
    val hunger: Float get() = chem.hunger
    val matingUrge: Float get() = chem.urge
    val fatigue: Float get() = chem.fatigue
    /** Total drive discomfort the creature is driven to minimise (the reinforcement signal). */
    fun discomfort(): Float = hunger + matingUrge + fatigue
    var ticksLived: Int = 0
    var reproCooldown: Int = 0
    var facing: Int = 1
    var breed: Int = 0               // heritable visual breed (sprite palette); inherited from a parent
    var held: Boolean = false        // picked up by the player (frozen)
    var carryingFood: Boolean = false // holding a food item to eat
    var onLift: Boolean = false       // riding a lift car between floors
    var ridingY: Float = -1f          // continuous floor while riding (for rendering); -1 = not riding

    // durative activity state
    var activity: ActivityType = ActivityType.IDLE
    var activityTimer: Int = 0
    var targetX: Float = 0f
    var targetFloor: Int = 0
    var partnerId: Int = -1

    // brain decision snapshot (for learning on goal completion)
    var goalAction: Int = CreatureMind.A_REST
    var decisionDiscomfort: Float = 0f
    val decisionPerception: FloatArray = FloatArray(CreatureMind.PERCEPTION)

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
    // a small, slow colony that sits around ~20 (start small and let it grow)
    val initialPopulation: Int = 8,
    val maxPopulation: Int = 24,
    // ~quarter the food
    val foodSeed: Int = 15,
    val foodSpawnPerTick: Int = 1,
    val maxFood: Int = 24,
    val eatAmount: Float = 0.85f,
    val starvationThreshold: Float = 0.85f,
    // time-dilation ÷4: rates that damage per tick are slowed so starvation tracks the longer life
    val starvationDamage: Float = 0.025f,
    val baseRepair: Float = 0.0125f,
    // time-dilation ×4: lifespan + life-stage ages stretched so the colony lives ~4x slower
    val maxAge: Int = 5200,
    // EMBRYO(egg) BABY CHILD ADOLESCENT YOUTH ADULT OLD SENILE — baby+child stretched so the
    // colony visibly has crawling young most of the time
    val stageStartAge: IntArray = intArrayOf(0, 120, 500, 1100, 1600, 2200, 3800, 4600),
    val fertileFrom: LifeStage = LifeStage.ADOLESCENT,
    val fertileTo: LifeStage = LifeStage.OLD,
    val fertileMaxHunger: Float = 0.6f,
    val reproduceCooldown: Int = 280,
    val mateRange: Float = 1.5f,
    val senseRange: Float = 30f,
    val matingRate: Float = 0.00375f,   // urge rise per tick (÷4)
    val brainLearnRate: Float = 0.05f,
    val brainExplore: Float = 0.05f,
    val mutationRate: Float = 0.6f,
    val minMetabolism: Float = 0.00075f, // hunger rise per tick (÷4)
    val maxMetabolism: Float = 0.003f,
    // durative action costs (×4 ticks, ÷4 speed → 4x slower, smooth)
    val moveSpeed: Float = 0.09f,
    val arriveEps: Float = 0.5f,
    val pickupTicks: Int = 20,
    val eatTicks: Int = 56,
    val courtTicks: Int = 56,
    val restTicks: Int = 56,
    // physical lifts
    val liftSpeed: Float = 0.0125f,   // floors per tick (÷4)
    val liftBoardEps: Float = 0.25f,  // how close the car must be to board / disembark
    // fatigue (G7: makes REST a real, learned behaviour)
    val fatigueRate: Float = 0.0025f, // fatigue built per tick of exertion (÷4)
    val restRecovery: Float = 0.015f, // fatigue recovered per tick of resting (÷4)
    // heritable visual breeds (sprite palettes); offspring inherit a parent's, rarely mutate
    val breedCount: Int = 9,
    val breedMutationPct: Int = 4,
) {
    fun metabolismOf(genome: Genome): Float {
        val gain = genome.genes.filterIsInstance<EmitterGene>().firstOrNull()?.gain?.coerceIn(0f, 1f) ?: 0.5f
        return minMetabolism + gain * (maxMetabolism - minMetabolism)
    }
}
