package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Species
import kotlin.jvm.JvmInline

@JvmInline
value class TileIndex(val index: Int) {
    companion object {
        val NONE: TileIndex = TileIndex(-1)
    }
}
@JvmInline
value class TileArray(val data: IntArray) {
    operator fun get(key: TileIndex): TileIndex {
        return TileIndex(data[key.index])
    }

    operator fun set(key: TileIndex, value: TileIndex) {
        data[key.index] = value.index
    }

    inline fun contentEquals(other: TileArray) : Boolean = data.contentEquals(other.data)
    inline fun contentHashCode() : Int = data.contentHashCode()
    inline fun copyOf() : TileArray = TileArray(data.copyOf())
    inline val size : Int get() = data.size
    inline fun extendedBy(other: TileArray) : TileArray {
        val newArray = IntArray(data.size+other.data.size)
        data.copyInto(newArray)
        other.data.copyInto(newArray, data.size)
        return TileArray(newArray)
    }
}
fun TileArray(size: Int, init: (Int) -> TileIndex = { TileIndex.NONE}): TileArray {
    return TileArray(IntArray(size, { init(it).index }))
}

@JvmInline
value class MassIndex(val index: Int)
fun MassIndex(i: TileIndex, s: Species) =
    MassIndex(i.index*Species.COUNT + s.ordinal)
@JvmInline
value class MassArray(val data: LongArray) {
    operator fun get(key: MassIndex): Long {
        return data[key.index]
    }

    operator fun set(key: MassIndex, value: Long) {
        data[key.index] = value
    }

    fun copyOf() : MassArray = MassArray(data.copyOf())
    fun contentEquals(other: MassArray) : Boolean = data.contentEquals(other.data)
    fun contentHashCode() : Int = data.contentHashCode()
    val size : Int get() = data.size
    fun extendedBy(other: MassArray) : MassArray {
        // TODO: no extension, just fixed-size internal multi-layer
        val newArray = LongArray(data.size+other.data.size)
        data.copyInto(newArray)
        other.data.copyInto(newArray, data.size)
        return MassArray(newArray)
    }
    inline fun forEach(action: (Long) -> Unit) {
        for (i in data.indices) {
            action(data[i])
        }
    }
}
fun MassArray(size: Int, init: (TileIndex, Species) -> Long = { _,_ -> 0}): MassArray {
    return MassArray(LongArray(size*Species.COUNT, {init(TileIndex(it/Species.COUNT), Species.ALL[it%Species.COUNT])}))
}

@JvmInline
value class EnergyArray(val data: LongArray) {
    operator fun get(key: TileIndex): Long {
        return data[key.index]
    }

    operator fun set(key: TileIndex, value: Long) {
        data[key.index] = value
    }

    fun copyOf() : EnergyArray = EnergyArray(data.copyOf())
    fun contentEquals(other: EnergyArray) : Boolean = data.contentEquals(other.data)
    fun contentHashCode() : Int = data.contentHashCode()
    val size : Int get() = data.size
    inline fun forEach(action: (Long) -> Unit) {
        for (i in data.indices) {
            action(data[i])
        }
    }
}
fun EnergyArray(size: Int, init: (Int) -> Long = {0}): EnergyArray {
    return EnergyArray(LongArray(size, init))
}
