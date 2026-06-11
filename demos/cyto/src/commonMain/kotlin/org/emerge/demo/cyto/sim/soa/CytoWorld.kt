package org.emerge.demo.cyto.sim.soa

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.ConnectionStateComponent
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsWorld
import org.emerge.sim.core.ecs.soa.ColliderColumnStore
import org.emerge.sim.core.ecs.soa.ComponentColumns
import org.emerge.sim.core.ecs.soa.ImpulseColumnStore
import org.emerge.sim.core.ecs.soa.MaterialColumnStore
import org.emerge.sim.core.ecs.soa.MotionColumnStore
import org.emerge.sim.core.ecs.soa.SoaWorld
import org.emerge.sim.core.ecs.soa.SpringCsr
import org.emerge.sim.core.ecs.soa.TransformColumnStore
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.SpringConstraint
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.sim.SimState

/**
 * Cyto's struct-of-arrays world, now built on the **generic** [SoaWorld] framework rather than
 * a bespoke flat store: engine physics components live in their stock column stores, per-cell
 * biology in [CytoCellColumnStore], and the spring topology in a framework [SpringCsr]. The
 * [CytoSoaReducer] mutates the column field arrays in place each tick (no `SimState` rebuild)
 * — the structural win — reading them by a dense slot index.
 *
 * Every cell carries all six component types, added together at spawn in ascending-EntityId
 * order, so a given slot indexes the same entity across *all* columns and the CSR. That slot
 * alignment is what lets the hot loops cross-reference columns by a single index, and it's
 * preserved by append-spawn + tombstone + a single coherent [SoaWorld.compact] (identical
 * membership ⇒ identical remap for every column).
 *
 * Field accessors below alias the underlying column arrays / CSR so the reducer (and the
 * proven spike logic it carries) reads `posX[i]` etc. directly. Indices `0 until count` are
 * live; backing arrays may be longer (capacity).
 */
class CytoWorld private constructor(
    val world: SoaWorld,
    val cells: ComponentColumns<CytoCellComponent>,
    val transform: TransformColumnStore,
    val motion: MotionColumnStore,
    val impulse: ImpulseColumnStore,
    val collider: ColliderColumnStore,
    val material: MaterialColumnStore,
    val cell: CytoCellColumnStore,
    val csr: SpringCsr,
    /**
     * Multi-species chemistry side-tables, keyed by EntityId.value. Energy — the dominant,
     * always-present chemical — stays in the dense [CytoCellColumnStore] columns; only the
     * *extra* (non-energy) chemicals / pending transfers / suppression live here, so the
     * energy-only common case (benchmark + growing colony) carries no map at all. Empty unless
     * a cell seeds extra chemicals or a gene mints a new species (the plan's object side-table
     * fallback for unbounded chemistry). See [CytoBiologyCore].
     */
    val extraChem: HashMap<Int, LinkedHashMap<String, Frac>>,
    val extraPending: HashMap<Int, LinkedHashMap<String, Frac>>,
    val suppression: HashMap<Int, Map<String, Frac>>,
    /**
     * The world's depletable energy reservoir — the SoA home of the [CytoEnergyGridComponent]
     * singleton. Mutated in place each tick by the biology path (Collectors draw, every cell
     * deposits its respiration/overflow waste), so unlike the cell columns it is *not* rebuilt;
     * [toSimState]/[toComparison] re-emit it on [GRID_SINGLETON] at the materialize boundary.
     */
    val energyGrid: org.emerge.demo.cyto.sim.CytoEnergyGrid,
) {
    val count: Int get() = cells.count

    // physics columns (raw fixed-point: Coord = Int raw, Frac = Long raw)
    val posX: IntArray get() = transform.posX
    val posY: IntArray get() = transform.posY
    val ang: IntArray get() = transform.ang
    val velX: IntArray get() = motion.velX
    val velY: IntArray get() = motion.velY
    /** Per-cell accumulated velocity impulse (the only impulse channels cyto uses). */
    val impX: LongArray get() = impulse.velX
    val impY: LongArray get() = impulse.velY
    val radiusRaw: LongArray get() = collider.radius
    val mass: IntArray get() = material.mass

    // biology columns (Frac raw Long / ordinal)
    val energy: LongArray get() = cell.energy
    val energyPending: LongArray get() = cell.energyPending
    val logicalRadius: LongArray get() = cell.logicalRadius
    val divideCharge: LongArray get() = cell.divideCharge
    val touch: LongArray get() = cell.touch
    val type: IntArray get() = cell.type
    val sticky: BooleanArray get() = cell.sticky
    val stickyTemp: BooleanArray get() = cell.stickyTemp
    val genome: Array<List<org.emerge.demo.cyto.sim.Gene>?> get() = cell.genome

    /** Dense EntityId.value per slot (`0 until count`). */
    val entityId: IntArray get() = cells.denseIds()

    fun slotOf(idValue: Int): Int = cells.slotOfValue(idValue)

    companion object {
        /**
         * Builds a CytoWorld from an engine [SimState], preserving ascending-EntityId order so
         * iteration matches the engine's insertion order. Runs ONCE (loader), never per tick.
         */
        fun fromSimState(state: SimState): CytoWorld {
            val cellsTable = state.components.getTable<CytoCellComponent>().asMap()
            val transforms = state.components.getTable<TransformComponent>()
            val motions = state.components.getTable<MotionComponent>()
            val colliders = state.components.getTable<ColliderComponent>()
            val materials = state.components.getTable<MaterialComponent>()
            val springMap = state.components.getTable<SpringConstraintComponent>().asMap()
            val damageMap = state.components.getTable<ConnectionStateComponent>().asMap()

            val ids = cellsTable.keys.sortedBy { it.value }

            val world = SoaWorld(randomSeed = state.randomSeed)
            val transform = TransformColumnStore()
            val motion = MotionColumnStore()
            val impulse = ImpulseColumnStore()
            val collider = ColliderColumnStore()
            val material = MaterialColumnStore()
            val cellStore = CytoCellColumnStore()
            world.register(TransformComponent::class, transform)
            world.register(MotionComponent::class, motion)
            world.register(ImpulseComponent::class, impulse)
            world.register(ColliderComponent::class, collider)
            world.register(MaterialComponent::class, material)
            val cellCols = world.register(CytoCellComponent::class, cellStore)

            val extraChem = HashMap<Int, LinkedHashMap<String, Frac>>()
            val extraPending = HashMap<Int, LinkedHashMap<String, Frac>>()
            val suppression = HashMap<Int, Map<String, Frac>>()
            for (id in ids) {
                world.add(id, TransformComponent::class, transforms[id]!!)
                world.add(id, MotionComponent::class, motions[id]!!)
                world.add(id, ImpulseComponent::class, ImpulseComponent())
                world.add(id, ColliderComponent::class, colliders[id]!!)
                world.add(
                    id, MaterialComponent::class,
                    materials[id] ?: MaterialComponent(mass = 1u, bounce = Frac(0), rough = Frac(0)),
                )
                val cellComp = cellsTable.getValue(id)
                world.add(id, CytoCellComponent::class, cellComp)
                // Energy lives in the dense column; capture any extra species in the side-table.
                extraOf(cellComp.chemicals)?.let { extraChem[id.value] = it }
                extraOf(cellComp.pendingTransfers)?.let { extraPending[id.value] = it }
                if (cellComp.suppression.isNotEmpty()) suppression[id.value] = cellComp.suppression
            }
            world.seedLastEntityValue(maxOf(state.world.lastEntityValue, ids.lastOrNull()?.value ?: 0))

            // Spring CSR over the cell ordering, preserving each cell's spring-list order.
            val csr = SpringCsr.build(
                count = ids.size,
                entityIdAt = { cellCols.denseIds()[it] },
                slotOf = { cellCols.slotOfValue(it) },
                springsAt = { slot -> springMap[cellCols.entityAt(slot)]?.springs ?: emptyList() },
                edgeAuxAt = { slot, other -> damageMap[cellCols.entityAt(slot)]?.damage?.get(other) ?: 0f },
            )

            // The depletable reservoir (singleton component); default to a fresh seeded grid for
            // states that predate it (old saves / fixtures). Cloned so in-place tick mutation never
            // reaches back into the source SimState's component.
            val energyGrid = state.components.getTable<org.emerge.demo.cyto.sim.CytoEnergyGridComponent>()[org.emerge.demo.cyto.sim.GRID_SINGLETON]
                ?.grid?.let { org.emerge.demo.cyto.sim.CytoEnergyGrid.fromRaw(it.rawColumn()) }
                ?: org.emerge.demo.cyto.sim.CytoEnergyGrid.seeded()

            return CytoWorld(
                world, cellCols, transform, motion, impulse, collider, material, cellStore, csr,
                extraChem, extraPending, suppression, energyGrid,
            )
        }

        /** Non-energy entries of [chem] as a fresh map, or null if there are none. */
        private fun extraOf(chem: Map<String, Frac>): LinkedHashMap<String, Frac>? {
            if (chem.size == 1 && chem.containsKey(CytoCellColumnStore.ENERGY)) return null
            var out: LinkedHashMap<String, Frac>? = null
            for ((k, v) in chem) if (k != CytoCellColumnStore.ENERGY) {
                (out ?: LinkedHashMap<String, Frac>().also { out = it })[k] = v
            }
            return out
        }
    }

    /** Full chemical map for a slot: energy column + side-table extras. */
    fun chemicalsAt(slot: Int): LinkedHashMap<String, Frac> {
        val out = LinkedHashMap<String, Frac>()
        out[CytoCellColumnStore.ENERGY] = Frac(cell.energy[slot])
        extraChem[entityId[slot]]?.let { out.putAll(it) }
        return out
    }

    /** Full pending-transfer map for a slot: energy column + side-table extras. */
    fun pendingAt(slot: Int): LinkedHashMap<String, Frac> {
        val out = LinkedHashMap<String, Frac>()
        out[CytoCellColumnStore.ENERGY] = Frac(cell.energyPending[slot])
        extraPending[entityId[slot]]?.let { out.putAll(it) }
        return out
    }

    /**
     * Exports the current store back to a storage-agnostic projection for the bit-identity
     * comparison. Loader-only (never per tick).
     */
    fun toComparison(): SoaComparison {
        val out = HashMap<Int, ComparisonCell>(count * 2)
        for (slot in 0 until count) {
            val springs = HashMap<Int, SpringTriple>()
            val damage = HashMap<Int, Float>()
            for (k in csr.offset[slot] until csr.offset[slot + 1]) {
                springs[csr.otherId[k]] = SpringTriple(csr.restRaw[k], csr.stiffRaw[k], csr.dampRaw[k])
                damage[csr.otherId[k]] = csr.edgeAux[k]
            }
            out[entityId[slot]] = ComparisonCell(
                posX = posX[slot], posY = posY[slot], ang = ang[slot],
                velX = velX[slot], velY = velY[slot],
                radiusRaw = radiusRaw[slot],
                chemicals = chemicalsAt(slot), pendingTransfers = pendingAt(slot),
                logicalRadius = Frac(logicalRadius[slot]), divideCharge = Frac(divideCharge[slot]),
                touch = Frac(touch[slot]), type = type[slot],
                sticky = sticky[slot], stickyTemp = stickyTemp[slot],
                springs = springs, damage = damage,
            )
        }
        return SoaComparison(out, world.lastEntityValue)
    }

    /**
     * Materializes the live store back into an engine [SimState] — the inverse of
     * [fromSimState], faithful for every component the world tracks. This is the SoA→AoS
     * boundary the live runtime renders, saves, and inspects through: it runs **once per
     * frame** (not per tick), so the per-tick win is untouched.
     *
     * Engine physics components round-trip via their column stores' `gather`; the cell is
     * rebuilt from the dense biology columns plus the multi-species side-tables; springs and
     * their accumulated damage come from the CSR (in ascending-EntityId, then spring-list
     * order — matching [fromSimState]'s import order). [RenderShapeComponent] is not
     * reconstructed: the world never stores it and the cyto renderer doesn't read it (all
     * cells are circles). The [EcsWorld] is rebuilt with the exact live id set + allocator
     * cursor so a save's id sequence continues correctly.
     */
    fun toSimState(): SimState {
        val n = count
        val transforms = LinkedHashMap<EntityId, TransformComponent>(n)
        val motions = LinkedHashMap<EntityId, MotionComponent>(n)
        val impulses = LinkedHashMap<EntityId, ImpulseComponent>(n)
        val colliders = LinkedHashMap<EntityId, ColliderComponent>(n)
        val materials = LinkedHashMap<EntityId, MaterialComponent>(n)
        val cellsOut = LinkedHashMap<EntityId, CytoCellComponent>(n)
        val springsOut = LinkedHashMap<EntityId, SpringConstraintComponent>(n)
        val damagesOut = LinkedHashMap<EntityId, ConnectionStateComponent>(n)

        for (slot in 0 until n) {
            val id = EntityId(entityId[slot])
            transforms[id] = transform.gather(slot)
            motions[id] = motion.gather(slot)
            impulses[id] = impulse.gather(slot)
            colliders[id] = collider.gather(slot)
            materials[id] = material.gather(slot)
            cellsOut[id] = gatherCell(slot)

            val lo = csr.offset[slot]
            val hi = csr.offset[slot + 1]
            if (hi > lo) {
                val springList = ArrayList<SpringConstraint>(hi - lo)
                val damageMap = LinkedHashMap<EntityId, Float>(hi - lo)
                for (k in lo until hi) {
                    val other = EntityId(csr.otherId[k])
                    springList.add(SpringConstraint(other, Frac(csr.restRaw[k]), Frac(csr.stiffRaw[k]), Frac(csr.dampRaw[k])))
                    damageMap[other] = csr.edgeAux[k]
                }
                springsOut[id] = SpringConstraintComponent(springList)
                damagesOut[id] = ConnectionStateComponent(damageMap)
            }
        }

        val components = ComponentStore(
            mapOf(
                TransformComponent::class to ComponentTable.fromMap(transforms),
                MotionComponent::class to ComponentTable.fromMap(motions),
                ImpulseComponent::class to ComponentTable.fromMap(impulses),
                ColliderComponent::class to ComponentTable.fromMap(colliders),
                MaterialComponent::class to ComponentTable.fromMap(materials),
                CytoCellComponent::class to ComponentTable.fromMap(cellsOut),
                SpringConstraintComponent::class to ComponentTable.fromMap(springsOut),
                ConnectionStateComponent::class to ComponentTable.fromMap(damagesOut),
                // The reservoir singleton: re-emit the live grid on its reserved id so the
                // materialized snapshot the renderer/save/AoS-oracle read carries it.
                org.emerge.demo.cyto.sim.CytoEnergyGridComponent::class to ComponentTable.fromMap(
                    linkedMapOf(org.emerge.demo.cyto.sim.GRID_SINGLETON to org.emerge.demo.cyto.sim.CytoEnergyGridComponent(energyGrid)),
                ),
            )
        )
        return SimState(
            world = EcsWorld(world.liveIds.toMutableSet(), world.lastEntityValue),
            components = components,
            contacts = emptyList(),
            randomSeed = world.randomSeed,
            tick = world.tick,
        )
    }

    /** The full [CytoCellComponent] for a slot: dense biology columns + side-table extras. */
    private fun gatherCell(slot: Int): CytoCellComponent = CytoCellComponent(
        type = CellType.entries[type[slot]],
        chemicals = chemicalsAt(slot),
        logicalRadius = Frac(logicalRadius[slot]),
        divideCharge = Frac(divideCharge[slot]),
        sticky = sticky[slot],
        pendingTransfers = pendingAt(slot),
        suppression = suppression[entityId[slot]] ?: emptyMap(),
        touch = Frac(touch[slot]),
        stickyTemp = stickyTemp[slot],
    )
}

/** Canonical, storage-agnostic projection of one cell for bit-identity comparison. */
class ComparisonCell(
    val posX: Int, val posY: Int, val ang: Int,
    val velX: Int, val velY: Int,
    val radiusRaw: Long,
    val chemicals: Map<String, Frac>, val pendingTransfers: Map<String, Frac>,
    val logicalRadius: Frac, val divideCharge: Frac,
    val touch: Frac, val type: Int,
    val sticky: Boolean, val stickyTemp: Boolean,
    val springs: Map<Int, SpringTriple>,
    val damage: Map<Int, Float>,
)

class SpringTriple(val restRaw: Long, val stiffRaw: Long, val dampRaw: Long) {
    override fun equals(other: Any?): Boolean =
        other is SpringTriple && restRaw == other.restRaw && stiffRaw == other.stiffRaw && dampRaw == other.dampRaw
    override fun hashCode(): Int = (restRaw * 31 + stiffRaw).hashCode() * 31 + dampRaw.hashCode()
}

class SoaComparison(val cells: Map<Int, ComparisonCell>, val nextEntityValue: Int)
