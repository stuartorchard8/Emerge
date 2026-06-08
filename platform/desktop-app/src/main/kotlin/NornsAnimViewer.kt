package org.emerge.desktop

import org.emerge.demo.norns.anim.CreatureAction
import java.awt.AlphaComposite
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GradientPaint
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
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JToggleButton
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * **Norn rig compositor + animation editor.** Builds a creature out of the real ripped C2 sprite
 * **parts** ([NornParts]) driven by a fully-editable, *procedural* rig ([NornRigDef] /
 * [NornCompositor]) — no baked frames. Pick a part, drag its numbers: where it anchors on its parent,
 * its pivot, rest angle, draw order, and — per action — how it rotates (bias + sine swing). Tune the
 * global body bob/lean/hop per action too. Pick an action, play/scrub the phase, drop a C2 reference
 * behind it, then **Save** the rig to a text file you can reload and keep iterating.
 *
 * Controls: Space play/pause · ←/→ scrub (paused) · `[`/`]` speed · Esc quit.
 */
object NornsAnimViewer {

    fun run() {
        val first = NornParts.firstAvailable()
        if (first == null) {
            JOptionPane.showMessageDialog(null, "No Norn sprite parts found under assets/norns/. Run via :platform:desktop-app:runNornsAnim.")
            return
        }
        var breed = first.first
        var age = first.second
        var sprites = first.third
        var def = NornRigDef.default(sprites)

        var action = CreatureAction.WALK
        var facing = 1
        var playing = true
        var phase = 0f
        var phaseSpeed = 0.18f
        var heightPx = 560f
        var bgMode = 0
        var onion = false
        var showRef = false
        var refImg: BufferedImage? = null
        var selected = def.parts.firstOrNull()?.id ?: ""
        var symmetry = false      // mirror edits to the paired (L↔R) part
        var symPhase = 3.14f      // extra phase offset on the mirrored side (π → anti-phase walk)
        var suppress = false      // guard programmatic control updates
        var building = false      // guard combo rebuilds

        lateinit var canvas: JComponent
        val syncList = ArrayList<() -> Unit>()
        fun syncAll() { syncList.forEach { it() } }

        // ---- canvas ----
        canvas = object : JComponent() {
            override fun paintComponent(g0: Graphics) {
                val g = g0 as Graphics2D
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val w = width; val h = height
                when (bgMode) {
                    0 -> g.paint = GradientPaint(0f, 0f, Color(232, 220, 188), 0f, h.toFloat(), Color(74, 58, 44))
                    1 -> g.paint = Color(38, 32, 26)
                    else -> g.paint = Color(244, 244, 240)
                }
                g.fillRect(0, 0, w, h)
                refImg?.takeIf { showRef }?.let { img ->
                    val s = minOf(w * 0.9f / img.width, h * 0.9f / img.height)
                    val iw = (img.width * s).roundToInt(); val ih = (img.height * s).roundToInt()
                    val old = g.composite
                    g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f)
                    g.drawImage(img, (w - iw) / 2, (h - ih) / 2, iw, ih, null)
                    g.composite = old
                }
                val sx = heightPx / 2.95f
                val originX = w / 2f; val originY = h * 0.86f
                if (onion) {
                    val old = g.composite
                    g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.22f)
                    for (d in listOf(-2f * PI.toFloat() / 3f, 2f * PI.toFloat() / 3f)) {
                        NornCompositor.draw(g, def, sprites, action, phase + d, facing, originX, originY, sx)
                    }
                    g.composite = old
                }
                NornCompositor.draw(g, def, sprites, action, phase, facing, originX, originY, sx, highlight = selected)
                g.color = if (bgMode == 2) Color(60, 50, 40) else Color(245, 240, 228)
                g.font = Font("SansSerif", Font.PLAIN, 13)
                g.drawString("$breed a$age   ${action.name}   facing ${if (facing > 0) "▶" else "◀"}   sel:$selected   phase ${"%.2f".format(phase % (2 * PI))}", 12, 20)
            }
        }
        canvas.preferredSize = Dimension(640, 720)
        canvas.isFocusable = true

        // selection-dependent accessors
        fun sel() = def.part(selected)
        fun selAnim() = sel()?.animFor(action)
        fun glob() = def.globalFor(action)

        // ---- symmetry: the L↔R body pairs (a side-view rig, so "same values", not x-mirrored) ----
        val mirror = mapOf(
            "thighL" to "thighR", "thighR" to "thighL", "shinL" to "shinR", "shinR" to "shinL",
            "footL" to "footR", "footR" to "footL", "uarmL" to "uarmR", "uarmR" to "uarmL",
            "farmL" to "farmR", "farmR" to "farmL",
        )
        // Copy the selected part's transform + this action's animation onto its mirror, with the
        // mirrored side's oscillation offset by [symPhase] (e.g. π = anti-phase stride).
        fun applySymmetry() {
            if (!symmetry) return
            val s = sel() ?: return
            val m = mirror[s.id]?.let { def.part(it) } ?: return
            m.anchorX = s.anchorX; m.anchorY = s.anchorY; m.pivotX = s.pivotX; m.pivotY = s.pivotY
            m.restAngle = s.restAngle; m.z = s.z
            val sa = s.animFor(action); val ma = m.animFor(action)
            ma.bias = sa.bias; ma.amp = sa.amp; ma.freq = sa.freq; ma.sign = sa.sign
            ma.phase = sa.phase + symPhase
        }

        // ---- a float slider bound to dynamic getter/setter ----
        fun fslider(name: String, lo: Float, hi: Float, get: () -> Float, set: (Float) -> Unit): JPanel {
            val valLabel = JLabel().apply { preferredSize = Dimension(52, 18); minimumSize = preferredSize }
            val nameLabel = JLabel(name).apply { preferredSize = Dimension(92, 18); minimumSize = preferredSize }
            val slider = JSlider(0, 1000)
            slider.addChangeListener {
                if (suppress) return@addChangeListener
                val v = lo + (slider.value / 1000f) * (hi - lo)
                set(v); applySymmetry(); valLabel.text = "%.2f".format(v); canvas.repaint()
            }
            syncList += {
                val v = get()
                slider.value = (((v - lo) / (hi - lo)) * 1000f).roundToInt().coerceIn(0, 1000)
                valLabel.text = "%.2f".format(v)
            }
            return JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS); alignmentX = JComponent.LEFT_ALIGNMENT
                add(nameLabel); add(slider); add(valLabel)
                maximumSize = Dimension(Int.MAX_VALUE, 22)
            }
        }
        fun header(t: String) = JLabel(t).apply {
            font = font.deriveFont(Font.BOLD, 12f); foreground = Color(120, 90, 60); alignmentX = JComponent.LEFT_ALIGNMENT
        }
        fun row(vararg c: JComponent) = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS); alignmentX = JComponent.LEFT_ALIGNMENT
            for (x in c) { add(x); add(Box.createHorizontalStrut(5)) }
            maximumSize = Dimension(Int.MAX_VALUE, 26)
        }

        // ---- top controls ----
        val breedBox = JComboBox(NornParts.BREEDS.toTypedArray()).apply { selectedItem = breed }
        val ageBox = JComboBox(arrayOf(0, 1, 2, 3)).apply { selectedItem = age }
        val actionBox = JComboBox(CreatureAction.entries.toTypedArray()).apply { selectedItem = action }
        val partBox = JComboBox<String>()
        val facingBtn = JToggleButton("Facing ▶").apply {
            addActionListener { facing = if (isSelected) -1 else 1; text = if (isSelected) "Facing ◀" else "Facing ▶"; canvas.repaint() }
        }
        val playBtn = JToggleButton("⏸ Pause", true).apply {
            addActionListener { playing = isSelected; text = if (playing) "⏸ Pause" else "▶ Play" }
        }
        val phaseSlider = JSlider(0, 628, 0).apply { addChangeListener { if (!suppress) { phase = value / 100f; canvas.repaint() } } }
        val speedSlider = JSlider(0, 100, 18).apply { addChangeListener { phaseSpeed = value / 100f } }
        val sizeSlider = JSlider(200, 1000, 560).apply { addChangeListener { heightPx = value.toFloat(); canvas.repaint() } }
        val bgBox = JComboBox(arrayOf("Albia sky", "Flat dark", "White")).apply { addActionListener { bgMode = selectedIndex; canvas.repaint() } }
        val onionChk = JCheckBox("Onion").apply { addActionListener { onion = isSelected; canvas.repaint() } }
        val refChk = JCheckBox("Reference").apply { addActionListener { showRef = isSelected; canvas.repaint() } }
        val symChk = JCheckBox("Symmetry").apply { addActionListener { symmetry = isSelected; applySymmetry(); canvas.repaint() } }
        val symPhaseSlider = JSlider(0, 628, 314).apply {
            toolTipText = "Phase offset on the mirrored side (π = anti-phase)"
            addChangeListener { if (!suppress) { symPhase = value / 100f; applySymmetry(); canvas.repaint() } }
        }

        fun rebuildPartCombo() {
            building = true
            partBox.removeAllItems()
            for (p in def.parts) partBox.addItem(p.id)
            selected = def.parts.firstOrNull()?.id ?: ""
            partBox.selectedItem = selected
            building = false
        }
        fun reloadBreed() {
            NornParts.load(breed, age)?.let { sprites = it; def = NornRigDef.default(sprites); rebuildPartCombo(); syncAll(); canvas.repaint() }
                ?: JOptionPane.showMessageDialog(canvas, "No art for $breed a$age.")
        }

        breedBox.addActionListener { if (!building) { breed = breedBox.selectedItem as String; reloadBreed() } }
        ageBox.addActionListener { if (!building) { age = ageBox.selectedItem as Int; reloadBreed() } }
        actionBox.addActionListener { action = actionBox.selectedItem as CreatureAction; syncAll(); canvas.repaint() }
        partBox.addActionListener { if (!building) { (partBox.selectedItem as? String)?.let { selected = it }; syncAll(); canvas.repaint() } }

        val loadRefBtn = JButton("Reference…").apply {
            addActionListener {
                val fc = JFileChooser(File("demos/norns/reference").takeIf { it.isDirectory } ?: File("."))
                if (fc.showOpenDialog(canvas) == JFileChooser.APPROVE_OPTION) {
                    refImg = runCatching { ImageIO.read(fc.selectedFile) }.getOrNull()
                    if (refImg != null) { showRef = true; refChk.isSelected = true; canvas.repaint() }
                }
            }
        }
        val saveBtn = JButton("Save rig…").apply {
            addActionListener {
                val fc = JFileChooser(File(".")); fc.selectedFile = File("norn-rig-$breed.txt")
                if (fc.showSaveDialog(canvas) == JFileChooser.APPROVE_OPTION) runCatching { fc.selectedFile.writeText(def.toText()) }
            }
        }
        val loadBtn = JButton("Load rig…").apply {
            addActionListener {
                val fc = JFileChooser(File("."))
                if (fc.showOpenDialog(canvas) == JFileChooser.APPROVE_OPTION) runCatching {
                    def = NornRigDef.parse(fc.selectedFile.readText(), sprites); rebuildPartCombo(); syncAll(); canvas.repaint()
                }
            }
        }
        val exportBtn = JButton("Export").apply {
            addActionListener {
                val txt = def.toText(); println(txt)
                runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(txt), null) }
                runCatching { File("norn-rig.txt").writeText(txt) }
                println("(rig copied to clipboard + written to norn-rig.txt)")
            }
        }
        val resetBtn = JButton("Reset").apply { addActionListener { def = NornRigDef.default(sprites); rebuildPartCombo(); syncAll(); canvas.repaint() } }

        // ---- the editable-dial panels ----
        val controls = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(row(JLabel("breed"), breedBox, JLabel("age"), ageBox))
            add(row(JLabel("action"), actionBox, facingBtn))
            add(row(playBtn, JLabel("spd"), speedSlider))
            add(row(JLabel("phase"), phaseSlider))
            add(row(JLabel("size"), sizeSlider, JLabel("bg"), bgBox))
            add(row(onionChk, refChk, loadRefBtn))
            add(row(saveBtn, loadBtn, exportBtn, resetBtn))
            add(Box.createVerticalStrut(6))
            add(row(JLabel("PART"), partBox))
            add(row(symChk, JLabel("symΦ"), symPhaseSlider))
        }

        val dials = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); border = BorderFactory.createEmptyBorder(4, 8, 8, 8)
            add(header("ANCHOR (on parent)"))
            add(fslider("anchor x", -60f, 80f, { sel()?.anchorX ?: 0f }, { sel()?.anchorX = it }))
            add(fslider("anchor y", -60f, 80f, { sel()?.anchorY ?: 0f }, { sel()?.anchorY = it }))
            add(header("PIVOT (own)"))
            add(fslider("pivot x", -60f, 80f, { sel()?.pivotX ?: 0f }, { sel()?.pivotX = it }))
            add(fslider("pivot y", -60f, 80f, { sel()?.pivotY ?: 0f }, { sel()?.pivotY = it }))
            add(header("PART"))
            add(fslider("rest∠", -3.14f, 3.14f, { sel()?.restAngle ?: 0f }, { sel()?.restAngle = it }))
            add(fslider("z-order", 0f, 15f, { (sel()?.z ?: 0).toFloat() }, { sel()?.z = it.roundToInt() }))
            add(header("ANIM (this action)"))
            add(fslider("bias∠", -1.5f, 1.5f, { selAnim()?.bias ?: 0f }, { selAnim()?.bias = it }))
            add(fslider("amp", 0f, 1.2f, { selAnim()?.amp ?: 0f }, { selAnim()?.amp = it }))
            add(fslider("freq", 0f, 6f, { selAnim()?.freq ?: 1f }, { selAnim()?.freq = it }))
            add(fslider("phase", 0f, 6.28f, { selAnim()?.phase ?: 0f }, { selAnim()?.phase = it }))
            add(fslider("sign", -1f, 1f, { selAnim()?.sign ?: 1f }, { selAnim()?.sign = if (it < 0f) -1f else 1f }))
            add(header("BODY (this action)"))
            add(fslider("bob", 0f, 1f, { glob().bobAmp }, { glob().bobAmp = it }))
            add(fslider("bobFreq", 0f, 6f, { glob().bobFreq }, { glob().bobFreq = it }))
            add(fslider("lean∠", -1f, 1f, { glob().lean }, { glob().lean = it }))
            add(fslider("hop", 0f, 1f, { glob().hopAmp }, { glob().hopAmp = it }))
            add(fslider("hopFreq", 0f, 6f, { glob().hopFreq }, { glob().hopFreq = it }))
        }

        val east = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(430, 720)
            add(controls, BorderLayout.NORTH)
            add(JScrollPane(dials).apply {
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                verticalScrollBar.unitIncrement = 16
            }, BorderLayout.CENTER)
        }

        canvas.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_SPACE -> { playing = !playing; playBtn.isSelected = playing; playBtn.text = if (playing) "⏸ Pause" else "▶ Play" }
                    KeyEvent.VK_LEFT -> if (!playing) { phase -= 0.1f; canvas.repaint() }
                    KeyEvent.VK_RIGHT -> if (!playing) { phase += 0.1f; canvas.repaint() }
                    KeyEvent.VK_OPEN_BRACKET -> speedSlider.value = (speedSlider.value - 3).coerceAtLeast(0)
                    KeyEvent.VK_CLOSE_BRACKET -> speedSlider.value = (speedSlider.value + 3).coerceAtMost(100)
                    KeyEvent.VK_ESCAPE -> System.exit(0)
                }
            }
        })

        Timer(33) {
            if (playing) {
                phase += phaseSpeed
                suppress = true
                phaseSlider.value = ((phase % (2 * PI).toFloat()) * 100).roundToInt().coerceIn(0, 628)
                suppress = false
                canvas.repaint()
            }
        }.start()

        rebuildPartCombo(); syncAll()

        val frame = JFrame("Norns — rig compositor + animation editor")
        frame.defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        frame.layout = BorderLayout()
        frame.add(canvas, BorderLayout.CENTER)
        frame.add(east, BorderLayout.EAST)
        frame.pack(); frame.setLocationRelativeTo(null); frame.isVisible = true
        canvas.requestFocusInWindow()
        println("Norns rig editor: pick a part, tweak its anchor/pivot/rotation + per-action animation; Save/Load the rig as text. Space play/pause · ←/→ scrub · [ / ] speed · Esc quit")
    }
}

/** Headless: render a contact sheet of every action at two phases to a PNG (no display needed). */
private fun renderContactSheet(out: File) {
    System.setProperty("java.awt.headless", "true")
    val first = NornParts.firstAvailable() ?: run { println("no Norn parts found"); return }
    val sprites = first.third
    val def = NornRigDef.default(sprites)
    val actions = CreatureAction.entries
    val phases = listOf(0.6f, (PI / 2).toFloat() + 0.6f)
    val tileW = 300; val tileH = 360
    val img = BufferedImage(tileW * actions.size, tileH * phases.size, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    for ((rowi, ph) in phases.withIndex()) for ((coli, a) in actions.withIndex()) {
        val ox = coli * tileW; val oy = rowi * tileH
        g.paint = GradientPaint(0f, oy.toFloat(), Color(232, 220, 188), 0f, (oy + tileH).toFloat(), Color(74, 58, 44))
        g.fillRect(ox, oy, tileW, tileH)
        NornCompositor.draw(g, def, sprites, a, ph, 1, ox + tileW / 2f, oy + tileH * 0.86f, (tileH * 0.62f) / 2.95f)
        g.color = Color(40, 30, 20); g.font = Font("SansSerif", Font.BOLD, 14); g.drawString(a.name, ox + 10, oy + 22)
    }
    g.dispose(); out.parentFile?.mkdirs(); ImageIO.write(img, "png", out); println("wrote ${out.absolutePath}")
}

fun main(args: Array<String>) {
    if (args.isNotEmpty() && args[0] == "--render") {
        renderContactSheet(File(args.getOrElse(1) { "build/norn-anim-sheet.png" }))
        return
    }
    SwingUtilities.invokeLater { NornsAnimViewer.run() }
}
