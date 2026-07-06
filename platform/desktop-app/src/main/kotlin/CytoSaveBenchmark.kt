package org.emerge.desktop

import org.emerge.demo.cyto.CytoSaveCodec
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.CytoSimParamsComponent
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.GeneCodec
import org.emerge.demo.cyto.sim.createCytoInitialState
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.sim.SimState
import java.io.File

/**
 * Benchmark for **Cyto save/encode/decode**. Loads a save (or grows a fresh world),
 * warms the JIT, then runs N measured encode + decode cycles with per-phase timing
 * and GC allocation tracking.
 *
 * --args="<savePath|fresh> [warmupIters] [measureIters]"
 *
 * Phase breakdown for encode:
 *   header   — version, randomSeed, tick, mutation rate
 *   cells    — iterate cells + transforms + motions + write each cell's data
 *   springs  — collect unique spring pairs into LinkedHashSet + write
 *   matter   — encode quad-tree (walk all 256 tile roots)
 *   flush    — ByteWriter.toByteArray() final copy
 *
 * Decode breaks down into: header parse, cells spawn, spring rebuild, matter decode, build().
 */
fun main(args: Array<String>) {
    val path = args.getOrNull(0) ?: "platform/desktop-app/cyto-save.bin"
    val warmup = args.getOrNull(1)?.toIntOrNull() ?: 50
    val measure = args.getOrNull(2)?.toIntOrNull() ?: 500

    // Load or create
    val initial: SimState = if (path == "fresh") createCytoInitialState()
        else CytoSaveCodec.decode(File(path).readBytes())

    val cellMap = initial.components.getTable<CytoCellComponent>().asMap()
    val cells = cellMap.size
    val springs = initial.components.getTable<SpringConstraintComponent>().asMap().values.sumOf { it.springs.size } / 2
    val geneSizes = cellMap.values.map { it.genome.size }.sorted()
    val cytoSizes = cellMap.values.map { it.cytoplasm.size }.sorted()
    val bioSizes = cellMap.values.map { it.biomass.size }.sorted()

    // Quick size summary
    val testBytes = CytoSaveCodec.encode(initial)
    println("=== Cyto Save Benchmark ===")
    println("source: $path")
    println("cells=$cells springs=$springs encoded=${testBytes.size / 1024} KB")
    println("genome: min=${geneSizes.firstOrNull()} med=${geneSizes.getOrNull(geneSizes.size / 2)} max=${geneSizes.lastOrNull()} total=${geneSizes.sum()}")
    println("cyto species: min=${cytoSizes.firstOrNull()} med=${cytoSizes.getOrNull(cytoSizes.size / 2)} max=${cytoSizes.lastOrNull()}")
    println("biomass species: min=${bioSizes.firstOrNull()} med=${bioSizes.getOrNull(bioSizes.size / 2)} max=${bioSizes.lastOrNull()}")
    println("warmup=$warmup measure=$measure\n")

    // Benchmark encode (phase-broken)
    benchEncode("ENCODE", initial, warmup, measure)

    // Benchmark decode
    benchDecode(initial, warmup, measure)

    // Benchmark round-trip (encode -> decode)
    benchRoundTrip(initial, warmup, measure)
}

// ─────────────────────────────────────────────────────────────────────────────
// ENCODE BENCH - phase-by-phase
// ─────────────────────────────────────────────────────────────────────────────

private fun benchEncode(label: String, initial: SimState, warmup: Int, measure: Int) {
    // Warmup
    repeat(warmup) { CytoSaveCodec.encode(initial) }

    var allocStart = allocatedBytes()

    // Header phase
    val headerTimes = LongArray(measure)
    val headerParams = initial.components.getTable<CytoSimParamsComponent>().asMap()[GRID_SINGLETON]
        ?.mutationRateDenom ?: -1
    for (i in 0 until measure) {
        val t = System.nanoTime()
        val w = org.emerge.net.codec.ByteWriter()
        w.writeInt(9)
        w.writeLong(initial.randomSeed)
        w.writeLong(initial.tick)
        w.writeInt(headerParams)
        headerTimes[i] = System.nanoTime() - t
    }

    // Cell phase
    val cells = initial.components.getTable<CytoCellComponent>().asMap()
    val transforms = initial.components.getTable<org.emerge.sim.core.physics.components.TransformComponent>()
    val motions = initial.components.getTable<org.emerge.sim.core.physics.components.MotionComponent>()

    val cellTimes = LongArray(measure)
    for (i in 0 until measure) {
        val t = System.nanoTime()
        val w = org.emerge.net.codec.ByteWriter()
        w.writeInt(cells.size)
        for ((id, cell) in cells) {
            val pos = transforms[id]?.pos ?: org.emerge.sim.core.physics.primitives.Coord2.zero
            val vel = motions[id]?.vel ?: org.emerge.sim.core.physics.primitives.Coord2.zero
            w.writeInt(id.value)
            w.writeInt(pos.x.raw); w.writeInt(pos.y.raw)
            w.writeInt(vel.x.raw); w.writeInt(vel.y.raw)
            w.writeLong(cell.type.dbIndex)
            w.writeLong(cell.logicalRadius.raw)
            w.writeInt(cell.wear)
            w.writeByte(if (cell.sticky) 1 else 0)
            writeCounts(w, cell.cytoplasm)
            writeCounts(w, cell.biomass)
            w.writeString(GeneCodec.serialize(cell.genome))
        }
        cellTimes[i] = System.nanoTime() - t
    }

    // Spring phase
    val springTable = initial.components.getTable<SpringConstraintComponent>().asMap()
    val springTimes = LongArray(measure)
    for (i in 0 until measure) {
        val t = System.nanoTime()
        val w = org.emerge.net.codec.ByteWriter()
        val pairs = LinkedHashSet<Long>()
        for ((id, comp) in springTable) {
            for (spring in comp.springs) {
                val lo = minOf(id.value, spring.other.value)
                val hi = maxOf(id.value, spring.other.value)
                pairs.add((lo.toLong() shl 32) or (hi.toLong() and 0xFFFFFFFFL))
            }
        }
        w.writeInt(pairs.size)
        for (packed in pairs) {
            w.writeInt((packed ushr 32).toInt())
            w.writeInt(packed.toInt())
        }
        springTimes[i] = System.nanoTime() - t
    }

    // Matter phase
    val grid = initial.components.getTable<CytoMatterGridComponent>()[GRID_SINGLETON]?.grid
        ?: org.emerge.demo.cyto.sim.CytoMatterField.empty()
    val matterTimes = LongArray(measure)
    for (i in 0 until measure) {
        val t = System.nanoTime()
        val w = org.emerge.net.codec.ByteWriter()
        grid.encodeTree(
            { w.writeByte(it.toByte()) },
            { writeCounts(w, it) },
            { w.writeInt(it) },
        )
        matterTimes[i] = System.nanoTime() - t
    }

    // Flush (toByteArray) phase
    val flushTimes = LongArray(measure)
    val sampleBytes = CytoSaveCodec.encode(initial)
    for (i in 0 until measure) {
        val t = System.nanoTime()
        sampleBytes.copyOf(sampleBytes.size)
        flushTimes[i] = System.nanoTime() - t
    }

    // Full encode for allocation measurement
    var totalNanos = 0L
    var minNanos = Long.MAX_VALUE
    var maxNanos = Long.MIN_VALUE
    for (i in 0 until measure) {
        val t = System.nanoTime()
        CytoSaveCodec.encode(initial)
        val d = System.nanoTime() - t
        totalNanos += d
        minNanos = minOf(minNanos, d)
        maxNanos = maxOf(maxNanos, d)
    }

    val allocDelta = allocatedBytes() - allocStart
    val avgUs = totalNanos / measure / 1000

    println("-- $label --")
    println("  total: avg=${avgUs}us min=${minNanos / 1000}ms max=${maxNanos / 1000}ms")
    println("  alloc: ${allocDelta / 1024} KB total, ${allocDelta / measure / 1024} KB/iter")
    println()
    println("  %-12s %10s %10s %8s %10s".format("phase", "avg us", "max us", "share%", "KB/iter"))
    println("  " + "-".repeat(56))

    val totalUs = totalNanos / measure
    val phaseData = listOf("header" to headerTimes, "cells" to cellTimes, "springs" to springTimes,
                           "matter" to matterTimes, "flush" to flushTimes)
    for ((name, times) in phaseData) {
        val avg = times.average().toLong()
        val mx = times.maxOrNull()!!
        println("  %-12s %10d %10d %7.1f%% %10d".format(name, avg, mx / 1000, avg.toDouble() / totalUs * 100, 0))
    }
    println()
}

// ─────────────────────────────────────────────────────────────────────────────
// DECODE BENCH
// ─────────────────────────────────────────────────────────────────────────────

private fun benchDecode(initial: SimState, warmup: Int, measure: Int) {
    // Encode once, then benchmark decode only
    val sampleBytes = CytoSaveCodec.encode(initial)

    // Warmup
    repeat(warmup) { CytoSaveCodec.decode(sampleBytes) }

    var allocStart = allocatedBytes()

    var totalNanos = 0L
    var minNanos = Long.MAX_VALUE
    var maxNanos = Long.MIN_VALUE
    for (i in 0 until measure) {
        val t = System.nanoTime()
        CytoSaveCodec.decode(sampleBytes)
        val d = System.nanoTime() - t
        totalNanos += d
        minNanos = minOf(minNanos, d)
        maxNanos = maxOf(maxNanos, d)
    }

    val allocDelta = allocatedBytes() - allocStart
    val avgUs = totalNanos / measure / 1000

    println("-- DECODE --")
    println("  input: ${sampleBytes.size / 1024} KB")
    println("  total: avg=${avgUs}us min=${minNanos / 1000}ms max=${maxNanos / 1000}ms")
    println("  alloc: ${allocDelta / 1024} KB total, ${allocDelta / measure / 1024} KB/iter")
    println()
}

// ─────────────────────────────────────────────────────────────────────────────
// ROUND-TRIP BENCH (encode + decode)
// ─────────────────────────────────────────────────────────────────────────────

private fun benchRoundTrip(initial: SimState, warmup: Int, measure: Int) {
    // Warmup
    repeat(warmup) {
        val bytes = CytoSaveCodec.encode(initial)
        CytoSaveCodec.decode(bytes)
    }

    var allocStart = allocatedBytes()

    // Measure encode separately
    val encTimes = LongArray(measure)
    for (i in 0 until measure) {
        val t = System.nanoTime()
        CytoSaveCodec.encode(initial)
        encTimes[i] = System.nanoTime() - t
    }

    // Measure decode separately (pre-encode to avoid measuring encode time)
    val decTimes = LongArray(measure)
    val preEncoded = Array(measure) { CytoSaveCodec.encode(initial) }
    for (i in 0 until measure) {
        val t = System.nanoTime()
        CytoSaveCodec.decode(preEncoded[i])
        decTimes[i] = System.nanoTime() - t
    }

    val allocDelta = allocatedBytes() - allocStart
    val encAvg = encTimes.average() / 1000
    val decAvg = decTimes.average() / 1000
    val rtAvg = (encAvg + decAvg).toLong()

    println("-- ROUND-TRIP (encode + decode) --")
    println("  total: avg=${rtAvg}us  enc=${encAvg.toLong()}us  dec=${decAvg.toLong()}us")
    println("  alloc: ${allocDelta / 1024} KB total, ${allocDelta / measure / 1024} KB/iter")
    println()
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun writeCounts(w: org.emerge.net.codec.ByteWriter, counts: Map<String, Int>) {
    w.writeInt(counts.size)
    for ((species, count) in counts) { w.writeString(species); w.writeInt(count) }
}

private fun org.emerge.net.codec.ByteWriter.writeString(s: String) {
    val b = s.encodeToByteArray()
    writeInt(b.size); writeBytes(b)
}

private fun allocatedBytes(): Long {
    val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    return bean.getThreadAllocatedBytes(Thread.currentThread().id)
}
