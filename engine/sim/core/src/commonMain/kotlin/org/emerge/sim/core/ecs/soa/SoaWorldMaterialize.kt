package org.emerge.sim.core.ecs.soa

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsWorld
import org.emerge.sim.core.sim.SimState
import kotlin.reflect.KClass

/**
 * Converts this [SoaWorld] into an engine [SimState] by gathering all columns into
 * `ComponentTable`s. This is the bridge that lets cold systems (which expect `SimState`)
 * operate on an SOA world — materialization happens once per isolated-phase barrier, not
 * on every tick.
 *
 * The resulting `SimState` has:
 *  - `EcsWorld` with live entity IDs and the same `lastEntityValue`
 *  - One `ComponentTable<T>` per registered column type, populated by gathering each alive slot
 *  - Empty contacts list, tick and randomSeed copied from this world
 *
 * **Ordering.** Tables are built by iterating columns in registration order, then alive slots
 * in insertion order — the same iteration order the AoS `ComponentTable` uses. Entity IDs in
 * each table are therefore bit-identical to what the AoS builder would produce.
 */
fun SoaWorld.materializeToSimState(): SimState {
    val tables = LinkedHashMap<KClass<*>, ComponentTable<*>>(columnsByType.size)
    for ((type, cols) in columnsByType) {
        val map = LinkedHashMap<EntityId, Any>(cols.count)
        cols.forEachAliveSlot { slot, id ->
            map[id] = cols.gatherAt(slot)
        }
        tables[type] = ComponentTable.fromMap(map)
    }
    return SimState(
        world = EcsWorld(liveIds.toMutableSet(), lastEntityValue),
        components = ComponentStore(tables),
        contacts = emptyList(),
        randomSeed = randomSeed,
        tick = tick,
    )
}
