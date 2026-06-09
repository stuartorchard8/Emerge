package org.emerge.desktop

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Verification harness for [CreatureRenderer]: bake the default baseline genome across the preset moods,
 *  to confirm the consolidated (genome body + baked expression) path reads cute AND emotes. */
fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    val out = File(args.getOrElse(0) { "build/creature-render.png" })
    val yaw = args.getOrNull(1)?.toDoubleOrNull() ?: 0.0
    val pitch = args.getOrNull(2)?.toDoubleOrNull() ?: 0.0
    val genome = defaultNornGenome()
    val fur = Color(176, 142, 104)
    val moods = CreatureRenderer.Mood.PRESETS
    val tile = 280; val bgC = Color(236, 232, 224); val bg = bgC.rgb and 0xFFFFFF
    val img = BufferedImage(tile * moods.size, tile, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = bgC; g.fillRect(0, 0, img.width, img.height)
    for ((i, nm) in moods.withIndex()) {
        CreatureRenderer.render(CreatureRenderer.Baked(genome, nm.second), fur, img, i * tile, tile, bg, yawDeg = yaw, pitchDeg = pitch)
        g.color = Color(60, 50, 40); g.font = Font("SansSerif", Font.BOLD, 14); g.drawString(nm.first, i * tile + 10, 22)
    }
    g.dispose(); out.parentFile?.mkdirs(); ImageIO.write(img, "png", out); println("wrote ${out.absolutePath}")
}
