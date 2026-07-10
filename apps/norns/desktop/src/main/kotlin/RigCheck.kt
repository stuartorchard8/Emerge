package org.emerge.desktop

import org.emerge.demo.norns.anim.CreatureAction
import org.emerge.demo.norns.morph.MorphCodec
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Verify the part-bake → NornRig pipeline: bake a genome's parts into the denali rig and composite a
 *  REST pose + a WALK cycle. `--args="<png> <morph>"` (defaults: build/rig-check.png, m8.morph). */
fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    val out = File(args.getOrElse(0) { "build/rig-check.png" })
    val genome = (args.getOrNull(1) ?: "m8.morph").let { File(it).takeIf(File::exists)?.let { f -> MorphCodec.parse(f.readText()) } } ?: defaultNornGenome()
    val rb = CreatureBaker.bakeRig(genome, Color(176, 142, 104))
    println("rig parts: ${rb.parts.keys}")

    val tau = (2.0 * Math.PI).toFloat()
    val frames = listOf("rest" to (CreatureAction.REST to 0f)) + (0..5).map { "walk" to (CreatureAction.WALK to it * tau / 6f) }
    val tile = 300; val bgC = Color(236, 232, 224)
    val img = BufferedImage(tile * frames.size, tile, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = bgC; g.fillRect(0, 0, img.width, img.height)
    for ((i, fr) in frames.withIndex()) {
        val (label, ap) = fr
        NornCompositor.draw(g, rb.rig, rb.parts, ap.first, ap.second, 1, i * tile + tile / 2f, tile * 0.86f, 95f, 2.2f, rb.rig.groundOffset)
        g.color = Color(60, 50, 40); g.font = Font("SansSerif", Font.BOLD, 13); g.drawString("$label ${i}", i * tile + 8, 18)
    }
    g.dispose(); out.parentFile?.mkdirs(); ImageIO.write(img, "png", out); println("wrote ${out.absolutePath}")
}
