package org.emerge.desktop

import org.emerge.demo.norns.world.NornsConfig
import org.emerge.demo.norns.world.NornsView
import org.emerge.demo.norns.world.NornsWorld
import java.awt.Dimension
import java.awt.Graphics
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Live host for Norns using the **Java2D** [NornsImageRenderer]. The camera either follows a
 * creature (the oldest, or one you left-click) or you can roam freely: arrow keys / A–D pan the
 * camera left/right (entering free mode); F re-attaches to follow. Right-click drops food; P pauses;
 * [ / ] change speed.
 */
object NornsSwingView {
    fun run(seed: Long = 7L) {
        val world = NornsWorld(NornsConfig(), seed)
        val view = NornsView(world.cfg.worldWidth, world.cfg.floors)
        var lockedFollowId: Int? = null
        var paused = false
        var stepsPerFrame = 1
        var cameraCenterX = 0f
        var freeCam = false           // true = roaming freely (camX), false = following a creature
        var camX = 0f
        val maxX = (world.cfg.worldWidth - 1).toFloat()
        val panStep = 3f

        val panel = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                val follow = if (freeCam) null else
                    (lockedFollowId?.let { world.creatureById(it) }?.takeIf { it.alive }
                        ?: world.creatures.maxByOrNull { it.biology.age })
                cameraCenterX = if (freeCam) camX else (follow?.x ?: 0f).also { camX = it }
                g.drawImage(NornsImageRenderer.renderFrame(world, cameraCenterX, follow?.id, width, height), 0, 0, null)
            }
        }
        panel.preferredSize = Dimension(1000, 620)
        panel.isFocusable = true

        panel.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val aspect = panel.width.toFloat() / panel.height
                val spot = view.screenToWorld(e.x.toFloat(), e.y.toFloat(), panel.width.toFloat(), panel.height.toFloat(), cameraCenterX, aspect)
                if (SwingUtilities.isLeftMouseButton(e)) {
                    val hit = world.creatureNear(spot.floor, spot.x, 1.8f)
                    if (hit != null) { lockedFollowId = hit.id; freeCam = false } // click a creature → follow it
                } else {
                    world.dropFood(spot.floor, spot.x.roundToInt())
                }
            }
        })
        panel.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_LEFT, KeyEvent.VK_A -> { freeCam = true; camX = (camX - panStep).coerceIn(0f, maxX) }
                    KeyEvent.VK_RIGHT, KeyEvent.VK_D -> { freeCam = true; camX = (camX + panStep).coerceIn(0f, maxX) }
                    KeyEvent.VK_F -> freeCam = false                 // re-attach to follow
                    KeyEvent.VK_P -> paused = !paused
                    KeyEvent.VK_OPEN_BRACKET -> stepsPerFrame = max(1, stepsPerFrame - 1)
                    KeyEvent.VK_CLOSE_BRACKET -> stepsPerFrame += 1
                    KeyEvent.VK_ESCAPE -> System.exit(0)
                }
            }
        })

        Timer(33) {
            if (!paused) repeat(stepsPerFrame) { world.step() }
            panel.repaint()
        }.start()

        val frame = JFrame("Norns")
        frame.defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        frame.contentPane = panel
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        panel.requestFocusInWindow()
        println("Norns controls: ←/→ or A/D pan camera (free look) · F follow · left-click a Norn to follow · right-click drop food · P pause · [ / ] speed · Esc quit")
    }
}

fun main(args: Array<String>) {
    val seed = args.getOrNull(0)?.toLongOrNull() ?: 7L
    SwingUtilities.invokeLater { NornsSwingView.run(seed) }
}
