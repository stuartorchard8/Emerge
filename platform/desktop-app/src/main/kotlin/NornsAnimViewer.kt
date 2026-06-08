package org.emerge.desktop

import org.emerge.demo.norns.anim.AnimParams
import org.emerge.demo.norns.anim.CreatureAction
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GradientPaint
import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JToggleButton
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * **Norn animation viewer + tweaker.** Renders one big procedural Norn ([NornBodyRenderer], the same
 * draw the live world uses) and exposes *every* [AnimParams] dial — proportions, fur shading, and the
 * per-action swing/bob/dip motion — as live sliders. Pick an action, play/scrub the phase, drop in a
 * Creatures-2 reference image to match by eye, then **Export** the tuned values as Kotlin to paste
 * back into `AnimParams`. This is how we nail the look without re-rendering PNGs.
 *
 * Controls: Space play/pause · ←/→ scrub (paused) · `[`/`]` speed · Esc quit.
 */
object NornsAnimViewer {

    private val EYE_COLORS = listOf(
        "blue" to Color(96, 148, 206),
        "brown" to Color(120, 78, 46),
        "amber" to Color(196, 150, 60),
        "green" to Color(96, 150, 96),
    )

    fun run() {
        val params = AnimParams.tweakable()
        var action = CreatureAction.WALK
        var facing = 1
        var playing = true
        var phase = 0f
        var phaseSpeed = 0.35f          // phase advance per frame (game uses ticks·0.35)
        var renderScale = 1.15f         // matches the live world's creature scale
        val fur = floatArrayOf(0.70f, 0.54f, 0.37f)   // a representative mid-metabolism fur
        var eyeIdx = 0
        var bgMode = 0                  // 0 Albia gradient · 1 flat dark · 2 white
        var onion = false
        var showRef = false
        var refImg: BufferedImage? = null

        val sliderByKey = HashMap<String, JSlider>()
        val valueLabelByKey = HashMap<String, JLabel>()
        var suppress = false            // re-entrancy guard for programmatic slider moves

        fun sliderToValue(key: String, i: Int): Float {
            val r = AnimParams.rangeOf(key)
            return r.start + (i / 1000f) * (r.endInclusive - r.start)
        }
        fun valueToSlider(key: String, v: Float): Int {
            val r = AnimParams.rangeOf(key)
            return (((v - r.start) / (r.endInclusive - r.start)) * 1000f).roundToInt().coerceIn(0, 1000)
        }

        // ---- the drawing canvas ----
        val canvas = object : JComponent() {
            override fun paintComponent(g0: Graphics) {
                val g = g0 as Graphics2D
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val w = width; val h = height
                // background
                when (bgMode) {
                    0 -> g.paint = GradientPaint(0f, 0f, Color(232, 220, 188), 0f, h.toFloat(), Color(74, 58, 44))
                    1 -> g.paint = Color(38, 32, 26)
                    else -> g.paint = Color(244, 244, 240)
                }
                g.fillRect(0, 0, w, h)

                // reference image, scaled to fit, ghosted behind the Norn
                refImg?.takeIf { showRef }?.let { img ->
                    val s = minOf(w * 0.9f / img.width, h * 0.9f / img.height)
                    val iw = (img.width * s).roundToInt(); val ih = (img.height * s).roundToInt()
                    val old = g.composite
                    g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f)
                    g.drawImage(img, (w - iw) / 2, (h - ih) / 2, iw, ih, null)
                    g.composite = old
                }

                // fit the Norn (body spans ~2.4 units tall) to ~72% of the canvas height
                val sx = (h * 0.72f) / (2.4f * renderScale)
                val originX = w / 2f
                val originY = h * 0.60f
                val eye = EYE_COLORS[eyeIdx].second

                // onion skin: faint ghosts a third- and two-thirds-cycle behind, to read the whole loop
                if (onion) {
                    val old = g.composite
                    g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.22f)
                    for (d in listOf(-2f * PI.toFloat() / 3f, 2f * PI.toFloat() / 3f)) {
                        NornBodyRenderer.draw(g, action, phase + d, facing, fur[0], fur[1], fur[2], eye, originX, originY, renderScale, sx, params)
                    }
                    g.composite = old
                }
                NornBodyRenderer.draw(g, action, phase, facing, fur[0], fur[1], fur[2], eye, originX, originY, renderScale, sx, params)

                // little HUD
                g.color = if (bgMode == 2) Color(60, 50, 40) else Color(245, 240, 228)
                g.font = Font("SansSerif", Font.PLAIN, 13)
                g.drawString("${action.name}   facing ${if (facing > 0) "▶" else "◀"}   phase ${"%.2f".format(phase % (2 * PI))}   ${if (playing) "playing" else "paused"}", 12, 20)
            }
        }
        canvas.preferredSize = Dimension(640, 720)
        canvas.isFocusable = true

        // ---- top controls ----
        val actionBox = JComboBox(CreatureAction.entries.toTypedArray()).apply {
            selectedItem = action
            addActionListener { action = selectedItem as CreatureAction; canvas.repaint() }
        }
        val facingBtn = JToggleButton("Facing ▶").apply {
            addActionListener { facing = if (isSelected) -1 else 1; text = if (isSelected) "Facing ◀" else "Facing ▶"; canvas.repaint() }
        }
        val playBtn = JToggleButton("⏸ Pause", true).apply {
            addActionListener { playing = isSelected; text = if (playing) "⏸ Pause" else "▶ Play" }
        }
        val phaseSlider = JSlider(0, 628, 0).apply {
            toolTipText = "Phase (scrub when paused)"
            addChangeListener { if (!suppress) { phase = value / 100f; canvas.repaint() } }
        }
        val speedSlider = JSlider(0, 200, 35).apply {
            toolTipText = "Playback speed (phase/frame ×100)"
            addChangeListener { phaseSpeed = value / 100f }
        }
        val scaleSlider = JSlider(50, 300, 115).apply {
            toolTipText = "Render scale ×100"
            addChangeListener { renderScale = value / 100f; canvas.repaint() }
        }
        val furSwatch = JPanel().apply { preferredSize = Dimension(26, 18); isOpaque = true }
        fun refreshSwatch() { furSwatch.background = Color(fur[0], fur[1], fur[2]); furSwatch.repaint() }
        refreshSwatch()
        val furSliders = listOf("R", "G", "B").mapIndexed { i, lbl ->
            JSlider(0, 255, (fur[i] * 255).roundToInt()).apply {
                toolTipText = "Fur $lbl"
                addChangeListener { fur[i] = value / 255f; refreshSwatch(); canvas.repaint() }
            }
        }
        val eyeBox = JComboBox(EYE_COLORS.map { it.first }.toTypedArray()).apply {
            addActionListener { eyeIdx = selectedIndex; canvas.repaint() }
        }
        val bgBox = JComboBox(arrayOf("Albia sky", "Flat dark", "White")).apply {
            addActionListener { bgMode = selectedIndex; canvas.repaint() }
        }
        val onionChk = JCheckBox("Onion skin").apply { addActionListener { onion = isSelected; canvas.repaint() } }
        val refChk = JCheckBox("Show reference").apply { addActionListener { showRef = isSelected; canvas.repaint() } }
        val loadRefBtn = JButton("Load reference…").apply {
            addActionListener {
                val fc = JFileChooser(File("demos/norns/reference").takeIf { it.isDirectory } ?: File("."))
                if (fc.showOpenDialog(canvas) == JFileChooser.APPROVE_OPTION) {
                    refImg = runCatching { ImageIO.read(fc.selectedFile) }.getOrNull()
                    if (refImg != null) { showRef = true; refChk.isSelected = true; canvas.repaint() }
                }
            }
        }
        val resetBtn = JButton("Reset all").apply {
            addActionListener {
                params.reset()
                suppress = true
                for (k in params.keys) {
                    sliderByKey[k]?.value = valueToSlider(k, params[k])
                    valueLabelByKey[k]?.text = "%.3f".format(params[k])
                }
                suppress = false
                canvas.repaint()
            }
        }
        val exportBtn = JButton("Export Kotlin").apply {
            addActionListener {
                val all = StringBuilder("// Norn AnimParams — paste into AnimParams.BASELINE\n")
                val changed = StringBuilder("// changed from baseline:\n")
                for (k in params.keys) {
                    val line = "            \"$k\" to ${"%.4f".format(params[k])}f,\n"
                    all.append(line)
                    if (params[k] != AnimParams.defaultOf(k)) changed.append(line)
                }
                val out = all.toString() + "\n" + changed.toString()
                println(out)
                runCatching {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(out), null)
                }
                runCatching { File("norns-anim-params.txt").writeText(out) }
                println("(copied to clipboard + written to norns-anim-params.txt)")
            }
        }

        fun row(vararg comps: JComponent): JPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = JComponent.LEFT_ALIGNMENT
            for (c in comps) { add(c); add(Box.createHorizontalStrut(6)) }
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

        val controls = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(row(JLabel("Action"), actionBox, facingBtn))
            add(Box.createVerticalStrut(4))
            add(row(playBtn, JLabel("speed"), speedSlider))
            add(row(JLabel("phase"), phaseSlider))
            add(row(JLabel("scale"), scaleSlider))
            add(Box.createVerticalStrut(4))
            add(row(JLabel("fur"), furSwatch, furSliders[0], furSliders[1], furSliders[2]))
            add(row(JLabel("eyes"), eyeBox, JLabel("bg"), bgBox))
            add(row(onionChk, refChk, loadRefBtn))
            add(row(resetBtn, exportBtn))
        }

        // ---- one slider per AnimParams dial, grouped by prefix ----
        val paramsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(4, 8, 8, 8)
        }
        var lastGroup = ""
        for (key in params.keys) {
            val group = key.substringBefore('/')
            if (group != lastGroup) {
                lastGroup = group
                paramsPanel.add(Box.createVerticalStrut(8))
                paramsPanel.add(JLabel(group.uppercase()).apply {
                    font = font.deriveFont(Font.BOLD, 12f)
                    foreground = Color(120, 90, 60)
                    alignmentX = JComponent.LEFT_ALIGNMENT
                })
            }
            val name = key.substringAfter('/')
            val nameLabel = JLabel(name).apply { preferredSize = Dimension(110, 18); minimumSize = preferredSize }
            val valLabel = JLabel("%.3f".format(params[key])).apply { preferredSize = Dimension(52, 18); minimumSize = preferredSize }
            val slider = JSlider(0, 1000, valueToSlider(key, params[key])).apply {
                addChangeListener {
                    if (suppress) return@addChangeListener
                    val v = sliderToValue(key, value)
                    params[key] = v
                    valLabel.text = "%.3f".format(v)
                    canvas.repaint()
                }
            }
            sliderByKey[key] = slider
            valueLabelByKey[key] = valLabel
            paramsPanel.add(row(nameLabel, slider, valLabel))
        }

        val east = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(420, 720)
            add(controls, BorderLayout.NORTH)
            add(
                JScrollPane(paramsPanel).apply {
                    verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS
                    verticalScrollBar.unitIncrement = 16
                },
                BorderLayout.CENTER,
            )
        }

        // ---- keyboard shortcuts on the canvas ----
        canvas.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_SPACE -> { playing = !playing; playBtn.isSelected = playing; playBtn.text = if (playing) "⏸ Pause" else "▶ Play" }
                    KeyEvent.VK_LEFT -> if (!playing) { phase -= 0.1f; canvas.repaint() }
                    KeyEvent.VK_RIGHT -> if (!playing) { phase += 0.1f; canvas.repaint() }
                    KeyEvent.VK_OPEN_BRACKET -> { speedSlider.value = (speedSlider.value - 5).coerceAtLeast(0) }
                    KeyEvent.VK_CLOSE_BRACKET -> { speedSlider.value = (speedSlider.value + 5).coerceAtMost(200) }
                    KeyEvent.VK_ESCAPE -> System.exit(0)
                }
            }
        })

        // ---- animation timer ----
        Timer(33) {
            if (playing) {
                phase += phaseSpeed
                suppress = true
                phaseSlider.value = ((phase % (2 * PI).toFloat()) * 100).roundToInt().coerceIn(0, 628)
                suppress = false
                canvas.repaint()
            }
        }.start()

        val frame = JFrame("Norns — animation viewer")
        frame.defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        frame.layout = BorderLayout()
        frame.add(canvas, BorderLayout.CENTER)
        frame.add(east, BorderLayout.EAST)
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        canvas.requestFocusInWindow()
        println("Norns animation viewer: pick an action, slide the dials, Export Kotlin to keep tuned values. Space play/pause · ←/→ scrub · [ / ] speed · Esc quit")
    }
}

/** Headless: render a contact sheet of every action at two phases to a PNG (no display needed). */
private fun renderContactSheet(out: File) {
    val actions = CreatureAction.entries
    val phases = listOf(0.6f, (PI / 2).toFloat() + 0.6f)   // two points in the cycle
    val tileW = 300; val tileH = 360
    val img = BufferedImage(tileW * actions.size, tileH * phases.size, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    val fur = floatArrayOf(0.70f, 0.54f, 0.37f)
    for ((row, ph) in phases.withIndex()) for ((col, a) in actions.withIndex()) {
        val ox = col * tileW; val oy = row * tileH
        g.paint = GradientPaint(0f, oy.toFloat(), Color(232, 220, 188), 0f, (oy + tileH).toFloat(), Color(74, 58, 44))
        g.fillRect(ox, oy, tileW, tileH)
        val sx = (tileH * 0.72f) / (2.4f * 1.15f)
        NornBodyRenderer.draw(g, a, ph, 1, fur[0], fur[1], fur[2], Color(96, 148, 206), ox + tileW / 2f, oy + tileH * 0.60f, 1.15f, sx)
        g.color = Color(40, 30, 20); g.font = Font("SansSerif", Font.BOLD, 14)
        g.drawString(a.name, ox + 10, oy + 22)
    }
    g.dispose()
    out.parentFile?.mkdirs()
    ImageIO.write(img, "png", out)
    println("wrote ${out.absolutePath}")
}

fun main(args: Array<String>) {
    if (args.isNotEmpty() && args[0] == "--render") {
        System.setProperty("java.awt.headless", "true")
        renderContactSheet(File(args.getOrElse(1) { "build/norn-anim-sheet.png" }))
        return
    }
    SwingUtilities.invokeLater { NornsAnimViewer.run() }
}
