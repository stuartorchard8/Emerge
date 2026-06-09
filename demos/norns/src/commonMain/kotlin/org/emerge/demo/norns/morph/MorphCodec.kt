package org.emerge.demo.norns.morph

/**
 * Text (de)serialization for a [MorphNode] genome — so the authoring tool can save/load a sculpted
 * baseline and the game can ship it as a readable asset. One node per line; **two spaces of indent per
 * depth level** encode the tree; each line is `name` then space-separated `key=value` pairs for any
 * non-default field (`ox oy scale sym mirX mirY`), and `@key=value` for [MorphNode.extra] entries.
 * Lines that are blank or start with `#` are ignored.
 *
 * Example:
 * ```
 * body scale=0.82
 *   head oy=1.25 scale=1.85
 *     eye ox=0.55 oy=0.02 scale=0.66 mirX=1.0
 * ```
 */
object MorphCodec {

    fun format(root: MorphNode): String {
        val sb = StringBuilder()
        emit(root, 0, sb)
        return sb.toString()
    }

    private fun emit(n: MorphNode, depth: Int, sb: StringBuilder) {
        repeat(depth) { sb.append("  ") }
        sb.append(n.name)
        if (n.ox != 0f) sb.append(" ox=").append(num(n.ox))
        if (n.oy != 0f) sb.append(" oy=").append(num(n.oy))
        if (n.scale != 1f) sb.append(" scale=").append(num(n.scale))
        if (n.sym != 0) sb.append(" sym=").append(n.sym)
        if (n.mirX != 0f) sb.append(" mirX=").append(num(n.mirX))
        if (n.mirY != 0f) sb.append(" mirY=").append(num(n.mirY))
        for ((k, v) in n.extra) sb.append(" @").append(k).append('=').append(num(v))
        sb.append('\n')
        for (c in n.children) emit(c, depth + 1, sb)
    }

    fun parse(text: String): MorphNode {
        val lines = text.lines().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        require(lines.isNotEmpty()) { "empty genome" }
        var root: MorphNode? = null
        val stack = ArrayDeque<Pair<Int, MorphNode>>()   // (depth, node) — ancestor chain
        for (raw in lines) {
            val depth = raw.takeWhile { it == ' ' }.length / 2
            val toks = raw.trim().split(Regex("\\s+"))
            val node = MorphNode(toks[0])
            for (i in 1 until toks.size) {
                val eq = toks[i].indexOf('='); if (eq < 0) continue
                val key = toks[i].substring(0, eq); val value = toks[i].substring(eq + 1)
                when {
                    key == "ox" -> node.ox = value.toFloat()
                    key == "oy" -> node.oy = value.toFloat()
                    key == "scale" -> node.scale = value.toFloat()
                    key == "sym" -> node.sym = value.toInt()
                    key == "mirX" -> node.mirX = value.toFloat()
                    key == "mirY" -> node.mirY = value.toFloat()
                    key.startsWith("@") -> node.extra[key.substring(1)] = value.toFloat()
                }
            }
            if (depth == 0) {
                require(root == null) { "more than one root node" }
                root = node; stack.clear(); stack.addLast(0 to node)
            } else {
                while (stack.isNotEmpty() && stack.last().first >= depth) stack.removeLast()
                require(stack.isNotEmpty()) { "node '${node.name}' has no parent at depth $depth" }
                stack.last().second.children.add(node)
                stack.addLast(depth to node)
            }
        }
        return root ?: error("no root node")
    }

    /** Compact float: drops a trailing `.0` so whole numbers read cleanly (`1.0` → `1`). */
    private fun num(v: Float): String {
        val s = v.toString()
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }
}
