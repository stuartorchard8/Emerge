package org.emerge.demo.norns.world

import org.emerge.demo.norns.gene.BrainGene
import org.emerge.demo.norns.gene.EmitterGene
import org.emerge.demo.norns.gene.Gene
import org.emerge.demo.norns.gene.GeneHeader
import org.emerge.demo.norns.gene.Genome
import org.emerge.demo.norns.gene.HalfLifeGene
import org.emerge.demo.norns.gene.ReactionGene
import org.emerge.demo.norns.gene.ReceptorGene
import org.emerge.demo.norns.morph.MorphCodec
import org.emerge.demo.norns.morph.MorphNode

/**
 * Text (de)serialization of a whole Norns colony — a **full-state snapshot** so a player can keep
 * their creatures across sessions. Captures everything the world can't re-derive: each creature's
 * inherited genome (biochem + brain genes) and morphology, its **learned** brain weights, life-stage/
 * age, drive chemistry, and position/activity, plus world food, counters, and the RNG state (so a
 * loaded world continues deterministically). Line-based and human-readable; [NornsWorld] maps live
 * creatures ↔ [Rec]s and owns the build, this owns the bytes. Versioned (`norns-save 1`).
 */
object NornsSave {

    const val VERSION = 1

    /** World-level state (everything not on a creature). */
    class Header(
        val worldWidth: Int, val floors: Int, val ticks: Int, val births: Int, val deaths: Int,
        val nextId: Int, val rng: Long, val food: List<Int>, val lifts: List<LiftRec>,
    )

    /** A lift car's saved state (matched back to a lift by [column]). */
    class LiftRec(val column: Int, val carPos: Float, val target: Int, val dwell: Int, val calls: List<Int>)

    /** One creature's full state. `brain` is the per-tract weight matrices, flattened in tract order. */
    class Rec(
        val id: Int, val x: Float, val floor: Int, val breed: Int, val ticksLived: Int,
        val reproCooldown: Int, val facing: Int, val held: Boolean, val carryingFood: Boolean,
        val onLift: Boolean, val ridingY: Float, val activity: Int, val activityTimer: Int,
        val targetX: Float, val targetFloor: Int, val partnerId: Int, val goalAction: Int,
        val decisionDiscomfort: Float, val loci: FloatArray, val perception: FloatArray,
        val chem: FloatArray, val age: Int, val stage: Int, val alive: Boolean, val organHealth: FloatArray,
        val genome: Genome, val brain: List<FloatArray>, val morph: MorphNode,
    )

    // ---- write ----
    fun write(h: Header, recs: List<Rec>): String {
        val sb = StringBuilder()
        sb.append("norns-save $VERSION\n")
        sb.append("world ${h.worldWidth} ${h.floors} ${h.ticks} ${h.births} ${h.deaths} ${h.nextId} ${h.rng}\n")
        sb.append("food ${h.food.joinToString(",")}\n")
        for (l in h.lifts) sb.append("lift ${l.column} ${l.carPos} ${l.target} ${l.dwell} ${l.calls.joinToString(",")}\n")
        for (r in recs) writeCreature(r, sb)
        return sb.toString()
    }

    private fun writeCreature(r: Rec, sb: StringBuilder) {
        sb.append("creature\n")
        sb.append("c ${r.id} ${r.x} ${r.floor} ${r.breed} ${r.ticksLived} ${r.reproCooldown} ${r.facing} " +
            "${r.held} ${r.carryingFood} ${r.onLift} ${r.ridingY} ${r.activity} ${r.activityTimer} " +
            "${r.targetX} ${r.targetFloor} ${r.partnerId} ${r.goalAction} ${r.decisionDiscomfort}\n")
        sb.append("loci ${floats(r.loci)}\n")
        sb.append("perc ${floats(r.perception)}\n")
        sb.append("chem ${floats(r.chem)}\n")
        sb.append("bio ${r.age} ${r.stage} ${r.alive} ${floats(r.organHealth)}\n")
        writeGenome(r.genome, sb)
        for (t in r.brain) sb.append("tract ${floats(t)}\n")
        sb.append("morph ${MorphCodec.format(r.morph).trimEnd('\n').replace("\n", "¦")}\n")
        sb.append("end\n")
    }

    private fun writeGenome(g: Genome, sb: StringBuilder) {
        sb.append("genome ${g.chemicalCount} ${g.locusCount} ${g.genes.size}\n")
        for (gene in g.genes) sb.append("gene ").append(
            when (gene) {
                is EmitterGene -> "E ${gene.locus} ${gene.chemical} ${gene.gain} ${gene.threshold} ${gene.header.mutable}"
                is ReceptorGene -> "R ${gene.chemical} ${gene.locus} ${gene.gain} ${gene.threshold} ${gene.nominal} ${gene.header.mutable}"
                is ReactionGene -> "X ${pairs(gene.reactants)} ${pairs(gene.products)} ${gene.rate} ${gene.header.mutable}"
                is HalfLifeGene -> "H ${gene.chemical} ${gene.halfLife} ${gene.header.mutable}"
                is BrainGene -> "B ${gene.action} ${gene.sense} ${gene.weight} ${gene.header.mutable}"
            },
        ).append('\n')
    }

    private fun floats(a: FloatArray) = a.joinToString(",")
    private fun pairs(ps: List<Pair<Int, Float>>) = if (ps.isEmpty()) "-" else ps.joinToString(";") { "${it.first}:${it.second}" }

    // ---- read ----
    fun read(text: String): Pair<Header, List<Rec>> {
        val lines = text.lines().filter { it.isNotBlank() }
        require(lines.firstOrNull()?.startsWith("norns-save") == true) { "not a norns save" }
        var i = 1
        val w = lines[i++].split(" ")
        require(w[0] == "world") { "expected world header" }
        val foodLine = lines[i++]
        val food = foodLine.removePrefix("food ").split(",").mapNotNull { it.toIntOrNull() }
        val lifts = ArrayList<LiftRec>()
        while (i < lines.size && lines[i].startsWith("lift ")) {
            val t = lines[i++].split(" ")
            lifts.add(LiftRec(t[1].toInt(), t[2].toFloat(), t[3].toInt(), t[4].toInt(), t.getOrElse(5) { "" }.split(",").mapNotNull { it.toIntOrNull() }))
        }
        val header = Header(w[1].toInt(), w[2].toInt(), w[3].toInt(), w[4].toInt(), w[5].toInt(), w[6].toInt(), w[7].toLong(), food, lifts)
        val recs = ArrayList<Rec>()
        while (i < lines.size) {
            require(lines[i] == "creature") { "expected creature at line $i: ${lines[i]}" }
            i++
            val end = lines.indexOf("end").let { lines.subList(i, lines.size).indexOf("end") + i }
            recs.add(readCreature(lines.subList(i, end)))
            i = end + 1
        }
        return header to recs
    }

    private fun readCreature(b: List<String>): Rec {
        val m = HashMap<String, String>()                                  // single-line tagged fields
        var genomeAt = -1; val tracts = ArrayList<FloatArray>(); var morph: MorphNode? = null
        for ((idx, line) in b.withIndex()) {
            val tag = line.substringBefore(' ')
            when (tag) {
                "c", "loci", "perc", "chem", "bio" -> m[tag] = line.substringAfter(' ')
                "genome" -> genomeAt = idx
                "tract" -> tracts.add(parseFloats(line.substringAfter(' ')))
                "morph" -> morph = MorphCodec.parse(line.substringAfter(' ').replace("¦", "\n"))
            }
        }
        val c = m.getValue("c").split(" ")
        val genome = readGenome(b, genomeAt)
        val bio = m.getValue("bio").split(" ")
        return Rec(
            id = c[0].toInt(), x = c[1].toFloat(), floor = c[2].toInt(), breed = c[3].toInt(), ticksLived = c[4].toInt(),
            reproCooldown = c[5].toInt(), facing = c[6].toInt(), held = c[7].toBoolean(), carryingFood = c[8].toBoolean(),
            onLift = c[9].toBoolean(), ridingY = c[10].toFloat(), activity = c[11].toInt(), activityTimer = c[12].toInt(),
            targetX = c[13].toFloat(), targetFloor = c[14].toInt(), partnerId = c[15].toInt(), goalAction = c[16].toInt(),
            decisionDiscomfort = c[17].toFloat(), loci = parseFloats(m.getValue("loci")), perception = parseFloats(m.getValue("perc")),
            chem = parseFloats(m.getValue("chem")), age = bio[0].toInt(), stage = bio[1].toInt(), alive = bio[2].toBoolean(),
            organHealth = parseFloats(bio.drop(3).joinToString(" ")), genome = genome, brain = tracts, morph = morph!!,
        )
    }

    private fun readGenome(b: List<String>, at: Int): Genome {
        val head = b[at].split(" ")                                        // genome <chem> <loci> <count>
        val count = head[3].toInt()
        val genes = ArrayList<Gene>(count)
        for (j in at + 1..at + count) {
            val t = b[j].split(" ")                                        // gene <type> ...
            genes.add(
                when (t[1]) {
                    "E" -> EmitterGene(t[2].toInt(), t[3].toInt(), t[4].toFloat(), t[5].toFloat(), GeneHeader(t[6].toBoolean()))
                    "R" -> ReceptorGene(t[2].toInt(), t[3].toInt(), t[4].toFloat(), t[5].toFloat(), t[6].toFloat(), GeneHeader(t[7].toBoolean()))
                    "X" -> ReactionGene(unpairs(t[2]), unpairs(t[3]), t[4].toFloat(), GeneHeader(t[5].toBoolean()))
                    "H" -> HalfLifeGene(t[2].toInt(), t[3].toFloat(), GeneHeader(t[4].toBoolean()))
                    "B" -> BrainGene(t[2].toInt(), t[3].toInt(), t[4].toFloat(), GeneHeader(t[5].toBoolean()))
                    else -> error("unknown gene ${t[1]}")
                },
            )
        }
        return Genome(head[1].toInt(), head[2].toInt(), genes)
    }

    private fun parseFloats(s: String) = s.split(",").filter { it.isNotEmpty() }.map { it.toFloat() }.toFloatArray()
    private fun unpairs(s: String): List<Pair<Int, Float>> =
        if (s == "-") emptyList() else s.split(";").map { val kv = it.split(":"); kv[0].toInt() to kv[1].toFloat() }
}
