package org.emerge.sim.core.ecs

/**
 * Minimal append-only list of primitive `Int`. Used by hot-path data structures
 * (e.g. [SpatialGrid]) that would otherwise allocate an `Integer` object per
 * entry via `ArrayList<Int>`. Auto-grows by doubling its backing array.
 *
 * Not thread-safe. Not intended as a general-purpose collection.
 */
class IntList(initialCapacity: Int = 4) {
    private var data: IntArray = IntArray(initialCapacity.coerceAtLeast(1))
    var size: Int = 0
        private set

    fun add(value: Int) {
        if (size == data.size) {
            data = data.copyOf(data.size * 2)
        }
        data[size] = value
        size += 1
    }

    operator fun get(index: Int): Int {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("index=$index size=$size")
        return data[index]
    }

    fun clear() {
        size = 0
    }
}
