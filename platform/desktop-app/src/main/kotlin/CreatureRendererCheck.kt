package org.emerge.desktop

import org.emerge.demo.norns.morph.MorphNode
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Verification harness for [CreatureRenderer]: bake the default baseline genome across the preset moods,
 *  to confirm the consolidated (genome body + baked expression) path reads cute AND emotes. */
private fun defaultGenome(): MorphNode {
    val body = MorphNode("body", scale = 0.82f)
    val head = MorphNode("head", ox = 0f, oy = 1.25f, scale = 1.85f).apply {
        children.add(MorphNode("crown", ox = 0f, oy = 0.42f, scale = 0.82f))
        children.add(MorphNode("muzzle", ox = 0.86f, oy = -0.22f, scale = 0.5f).apply {
            children.add(MorphNode("nose", ox = 0.5f, oy = 0.02f, scale = 0.34f))
        })
        children.add(MorphNode("eye", ox = 0.55f, oy = 0.02f, scale = 0.66f, mirX = 1f))
        children.add(MorphNode("ear", ox = -0.34f, oy = 0.66f, scale = 0.5f, mirX = 1f))
    }
    body.children.add(head)
    body.children.add(MorphNode("arm", ox = 0.62f, oy = -0.35f, scale = 0.4f, mirX = 1f))
    body.children.add(MorphNode("leg", ox = 0.16f, oy = -0.95f, scale = 0.58f, mirX = 1f))
    return body
}

fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    val out = File(args.getOrElse(0) { "build/creature-render.png" })
    val baked = CreatureRenderer.Baked(defaultGenome())
    val fur = Color(176, 142, 104)
    val moods = CreatureRenderer.Mood.PRESETS
    val tile = 280; val bgC = Color(236, 232, 224); val bg = bgC.rgb and 0xFFFFFF
    val img = BufferedImage(tile * moods.size, tile, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = bgC; g.fillRect(0, 0, img.width, img.height)
    for ((i, nm) in moods.withIndex()) {
        CreatureRenderer.render(baked, nm.second, fur, img, i * tile, tile, bg)
        g.color = Color(60, 50, 40); g.font = Font("SansSerif", Font.BOLD, 14); g.drawString(nm.first, i * tile + 10, 22)
    }
    g.dispose(); out.parentFile?.mkdirs(); ImageIO.write(img, "png", out); println("wrote ${out.absolutePath}")
}
