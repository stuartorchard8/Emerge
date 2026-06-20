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

        // rigs are per (breed, age); the file lives in assets/norns so it's the same source the game
        // loads. A session cache keeps each (breed,age)'s edits so switching age/breed never resets.
        fun assetsDir(): File? = assetsNornsDir()
        fun rigFile(b: String, a: Int): File = assetsDir()?.resolve("rig-$b-a$a.txt") ?: File("rig-$b-a$a.txt")
        fun rigKey(b: String, a: Int) = "$b:$a"
        fun loadRig(b: String, a: Int, spr: Map<String, NornParts.Part>): NornRigDef {
            val f = rigFile(b, a)
            return if (f.isFile) runCatching { NornRigDef.parse(f.readText(), spr) }.getOrDefault(NornRigDef.default(spr))
            else NornRigDef.default(spr)
        }
        val rigCache = HashMap<String, NornRigDef>()
        var def = rigCache.getOrPut(rigKey(breed, age)) { loadRig(breed, age, sprites) }

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
        var showFloor = true
        var floorScroll = 0f      // px the ground has scrolled (advances during WALK at the stride rate)
        var selected = def.parts.firstOrNull()?.id ?: ""
        var symmetry = false      // mirror edits to the paired (L↔R) part
        var symPhase = PI.toFloat()   // extra phase offset on the mirrored side (0.5 turn → anti-phase walk)
        var suppress = false      // guard programmatic control updates
        var building = false      // guard combo rebuilds
        val tau = (2 * PI).toFloat()   // angles are edited in TURNS (1 turn = 2π rad); model stays rad

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
                val hAge = NornRigStore.heightForAge(age)
                val sx = heightPx / hAge                       // px per world-unit (age's true world height)
                val originX = w / 2f; val originY = h * 0.86f   // the floor line (where feet should sit)
                // floor: a band of scrolling alternating TILES, so the ground speed reads clearly
                if (showFloor) {
                    val oy = originY.roundToInt(); val bandH = h - oy
                    val stepPx = (0.5f * sx).coerceAtLeast(28f)
                    val shift = floorScroll / stepPx
                    val startTile = shift.toInt(); val frac = shift - startTile
                    var k = 0
                    while (true) {
                        val x = (k - frac) * stepPx
                        if (x > w) break
                        g.color = if (((startTile + k) and 1) == 0) Color(99, 72, 47) else Color(72, 52, 34)
                        g.fillRect(x.roundToInt(), oy, stepPx.roundToInt() + 1, bandH)
                        k++
                    }
                    g.color = Color(156, 124, 86); g.fillRect(0, oy, w, 3)                 // lit top edge
                    g.color = Color(150, 188, 92); g.fillRect(0, (oy - 2), w, 2)            // grassy line
                }
                if (onion) {
                    val old = g.composite
                    g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.22f)
                    for (d in listOf(-2f * PI.toFloat() / 3f, 2f * PI.toFloat() / 3f)) {
                        NornCompositor.draw(g, def, sprites, action, phase + d, facing, originX, originY, sx, hAge, def.groundOffset)
                    }
                    g.composite = old
                }
                val foodMode = when (action) {
                    CreatureAction.EAT -> NornCompositor.FoodMode.HAND
                    CreatureAction.PICK_UP -> NornCompositor.FoodMode.PICKUP
                    else -> NornCompositor.FoodMode.NONE
                }
                NornCompositor.draw(g, def, sprites, action, phase, facing, originX, originY, sx, hAge, def.groundOffset, food = foodMode, highlight = selected)
                g.color = if (bgMode == 2) Color(60, 50, 40) else Color(245, 240, 228)
                g.font = Font("SansSerif", Font.PLAIN, 13)
                g.drawString("$breed a$age   ${action.name}   facing ${if (facing > 0) "▶" else "◀"}   sel:$selected   stride ${"%.2f".format(def.walkStride)}", 12, 20)
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
            m.anchorU = s.anchorU; m.anchorV = s.anchorV; m.pivotU = s.pivotU; m.pivotV = s.pivotV
            m.restAngle = s.restAngle; m.z = -s.z   // 0-centred → negate to put the mirror on the far side
            val sa = s.animFor(action); val ma = m.animFor(action)
            ma.bias = sa.bias; ma.amp = sa.amp; ma.freq = sa.freq; ma.sign = sa.sign
            ma.phase = sa.phase + symPhase
        }

        // ---- a float slider bound to dynamic getter/setter ----
        fun fslider(name: String, lo: Float, hi: Float, get: () -> Float, set: (Float) -> Unit, snap: Float? = null): JPanel {
            val valLabel = JLabel().apply { preferredSize = Dimension(52, 18); minimumSize = preferredSize }
            val nameLabel = JLabel(name).apply { preferredSize = Dimension(92, 18); minimumSize = preferredSize }
            val slider = JSlider(0, 1000)
            fun pos(v: Float) = (((v - lo) / (hi - lo)) * 1000f).roundToInt().coerceIn(0, 1000)
            slider.addChangeListener {
                if (suppress) return@addChangeListener
                var v = lo + (slider.value / 1000f) * (hi - lo)
                if (snap != null) {                                  // round to increment + click the knob into place
                    v = (lo + ((v - lo) / snap).roundToInt() * snap).coerceIn(lo, hi)
                    suppress = true; slider.value = pos(v); suppress = false
                }
                set(v); applySymmetry(); valLabel.text = "%.2f".format(v); canvas.repaint()
            }
            syncList += {
                val v = get()
                slider.value = pos(v)
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
        val phaseSlider = JSlider(0, 100, 0).apply { addChangeListener { if (!suppress) { phase = value / 100f * tau; canvas.repaint() } } }
        val speedSlider = JSlider(0, 100, 18).apply { addChangeListener { phaseSpeed = value / 100f } }
        val sizeSlider = JSlider(200, 1000, 560).apply { addChangeListener { heightPx = value.toFloat(); canvas.repaint() } }
        val bgBox = JComboBox(arrayOf("Albia sky", "Flat dark", "White")).apply { addActionListener { bgMode = selectedIndex; canvas.repaint() } }
        val onionChk = JCheckBox("Onion").apply { addActionListener { onion = isSelected; canvas.repaint() } }
        val refChk = JCheckBox("Reference").apply { addActionListener { showRef = isSelected; canvas.repaint() } }
        val floorChk = JCheckBox("Floor", true).apply { addActionListener { showFloor = isSelected; canvas.repaint() } }
        val symChk = JCheckBox("Symmetry").apply { addActionListener { symmetry = isSelected; applySymmetry(); canvas.repaint() } }
        val symPhaseSlider = JSlider(0, 100, 50).apply {
            toolTipText = "Phase offset on the mirrored side, in turns (0.5 = anti-phase)"
            addChangeListener { if (!suppress) { symPhase = value / 100f * tau; applySymmetry(); canvas.repaint() } }
        }

        fun rebuildPartCombo() {
            building = true
            partBox.removeAllItems()
            for (p in def.parts) partBox.addItem(p.id)
            selected = def.parts.firstOrNull()?.id ?: ""
            partBox.selectedItem = selected
            building = false
        }
        fun switchRig() {
            NornParts.load(breed, age)?.let { sp ->
                sprites = sp
                def = rigCache.getOrPut(rigKey(breed, age)) { loadRig(breed, age, sp) }
                rebuildPartCombo(); syncAll(); canvas.repaint()
            } ?: JOptionPane.showMessageDialog(canvas, "No art for $breed a$age.")
        }

        breedBox.addActionListener { if (!building) { breed = breedBox.selectedItem as String; switchRig() } }
        ageBox.addActionListener { if (!building) { age = ageBox.selectedItem as Int; switchRig() } }
        actionBox.addActionListener { action = actionBox.selectedItem as CreatureAction; phase = 0f; syncAll(); canvas.repaint() }
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
                val fc = JFileChooser(assetsDir() ?: File(".")); fc.selectedFile = rigFile(breed, age)
                if (fc.showSaveDialog(canvas) == JFileChooser.APPROVE_OPTION) runCatching { fc.selectedFile.writeText(def.toText()) }
            }
        }
        val loadBtn = JButton("Load rig…").apply {
            addActionListener {
                val fc = JFileChooser(assetsDir() ?: File(".")); fc.selectedFile = rigFile(breed, age)
                if (fc.showOpenDialog(canvas) == JFileChooser.APPROVE_OPTION) runCatching {
                    def = NornRigDef.parse(fc.selectedFile.readText(), sprites); rigCache[rigKey(breed, age)] = def
                    rebuildPartCombo(); syncAll(); canvas.repaint()
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
        val resetBtn = JButton("Reset").apply { addActionListener { def = NornRigDef.default(sprites); rigCache[rigKey(breed, age)] = def; rebuildPartCombo(); syncAll(); canvas.repaint() } }

        // ---- the editable-dial panels ----
        val controls = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(row(JLabel("breed"), breedBox, JLabel("age"), ageBox))
            add(row(JLabel("action"), actionBox, facingBtn))
            add(row(playBtn, JLabel("spd"), speedSlider))
            add(row(JLabel("phase"), phaseSlider))
            add(row(JLabel("size"), sizeSlider, JLabel("bg"), bgBox))
            add(row(onionChk, refChk, floorChk, loadRefBtn))
            add(row(saveBtn, loadBtn, exportBtn, resetBtn))
            add(Box.createVerticalStrut(6))
            add(row(JLabel("PART"), partBox))
            add(row(symChk, JLabel("symΦ"), symPhaseSlider))
        }

        val dials = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); border = BorderFactory.createEmptyBorder(4, 8, 8, 8)
            add(header("ANCHOR (frac of parent)"))
            add(fslider("anchor u", -0.5f, 1.5f, { sel()?.anchorU ?: 0f }, { sel()?.anchorU = it }))
            add(fslider("anchor v", -0.5f, 1.5f, { sel()?.anchorV ?: 0f }, { sel()?.anchorV = it }))
            add(header("PIVOT (frac of self)"))
            add(fslider("pivot u", -0.5f, 1.5f, { sel()?.pivotU ?: 0f }, { sel()?.pivotU = it }))
            add(fslider("pivot v", -0.5f, 1.5f, { sel()?.pivotV ?: 0f }, { sel()?.pivotV = it }))
            add(header("PART"))
            add(fslider("rest (turns)", -0.5f, 0.5f, { (sel()?.restAngle ?: 0f) / tau }, { sel()?.restAngle = it * tau }))
            add(fslider("z-order", -8f, 8f, { (sel()?.z ?: 0).toFloat() }, { sel()?.z = it.roundToInt() }, snap = 1f))
            add(header("ANIM (this action)"))
            add(fslider("bias (turns)", -0.3f, 0.3f, { (selAnim()?.bias ?: 0f) / tau }, { selAnim()?.bias = it * tau }))
            add(fslider("amp (turns)", 0f, 0.3f, { (selAnim()?.amp ?: 0f) / tau }, { selAnim()?.amp = it * tau }))
            add(fslider("freq", 0f, 6f, { selAnim()?.freq ?: 1f }, { selAnim()?.freq = it }, snap = 0.25f))
            add(fslider("phase (turns)", 0f, 1f, { (selAnim()?.phase ?: 0f) / tau }, { selAnim()?.phase = it * tau }))
            add(fslider("sign", -1f, 1f, { selAnim()?.sign ?: 1f }, { selAnim()?.sign = if (it < 0f) -1f else 1f }, snap = 2f))
            add(header("BODY (this action)"))
            add(fslider("bob", 0f, 1f, { glob().bobAmp }, { glob().bobAmp = it }))
            add(fslider("bobFreq", 0f, 6f, { glob().bobFreq }, { glob().bobFreq = it }, snap = 0.25f))
            add(fslider("lean (turns)", -0.25f, 0.25f, { glob().lean / tau }, { glob().lean = it * tau }))
            add(fslider("hop", 0f, 1f, { glob().hopAmp }, { glob().hopAmp = it }))
            add(fslider("hopFreq", 0f, 6f, { glob().hopFreq }, { glob().hopFreq = it }, snap = 0.25f))
            add(header("ENVIRONMENT (this age's rig)"))
            add(fslider("ground off", -1f, 1f, { def.groundOffset }, { def.groundOffset = it }))
            add(fslider("walk stride", 0f, 4f, { def.walkStride }, { def.walkStride = it }))
            add(fslider("pickup reach", -2f, 2f, { def.pickupReachX }, { def.pickupReachX = it }))
            add(fslider("held food x", -0.5f, 0.5f, { def.heldFoodX }, { def.heldFoodX = it }))
            add(fslider("held food y", -0.5f, 0.5f, { def.heldFoodY }, { def.heldFoodY = it }))
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
                }
            }
        })

        Timer(33) {
            if (playing) {
                phase += phaseSpeed
                // ground scrolls only while walking, at the rate the stride implies (so foot-slip shows):
                // px/frame = (cycles/frame) · (worldUnits/cycle) · (px/worldUnit)
                if (action == CreatureAction.WALK) floorScroll += (phaseSpeed / tau) * def.walkStride * (heightPx / NornRigStore.heightForAge(age))
                suppress = true
                phaseSlider.value = ((phase / tau).rem(1f) * 100).roundToInt().coerceIn(0, 100)
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
private fun renderContactSheet(out: File, ageOverride: Int? = null) {
    System.setProperty("java.awt.headless", "true")
    val first = NornParts.firstAvailable() ?: run { println("no Norn parts found"); return }
    val breed = first.first
    val age = ageOverride ?: first.second
    val sprites = NornParts.load(breed, age) ?: first.third
    val def = NornRigStore.rigFor(breed, age) ?: NornRigDef.default(sprites)
    val actions = CreatureAction.entries
    val phases = listOf(0.25f, 0.75f).map { it * (2.0 * PI).toFloat() }   // quarter + three-quarter cycle
    val tileW = 300; val tileH = 360
    val img = BufferedImage(tileW * actions.size, tileH * phases.size, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    for ((rowi, ph) in phases.withIndex()) for ((coli, a) in actions.withIndex()) {
        val ox = coli * tileW; val oy = rowi * tileH
        g.paint = GradientPaint(0f, oy.toFloat(), Color(232, 220, 188), 0f, (oy + tileH).toFloat(), Color(74, 58, 44))
        g.fillRect(ox, oy, tileW, tileH)
        val hAge = NornRigStore.heightForAge(age); val sxT = (tileH * 0.62f) / hAge; val cx = ox + tileW / 2f; val cy = oy + tileH * 0.86f
        val fm = when (a) { CreatureAction.EAT -> NornCompositor.FoodMode.HAND; CreatureAction.PICK_UP -> NornCompositor.FoodMode.PICKUP; else -> NornCompositor.FoodMode.NONE }
        NornCompositor.draw(g, def, sprites, a, ph, 1, cx, cy, sxT, targetHeightUnits = hAge, food = fm)
        g.color = Color(40, 30, 20); g.font = Font("SansSerif", Font.BOLD, 14); g.drawString(a.name, ox + 10, oy + 22)
    }
    g.dispose(); out.parentFile?.mkdirs(); ImageIO.write(img, "png", out); println("wrote ${out.absolutePath}")
}

private fun headless() = System.setProperty("java.awt.headless", "true")
private fun assetsNornsDir(): File? = listOf(File("assets/norns"), File("../../assets/norns")).firstOrNull { it.isDirectory }

/** Rewrite legacy pixel-coord rig files into the normalized format. args: [breed] [ages...] */
private fun cmdNormalize(args: Array<String>) {
    headless(); val dir = assetsNornsDir() ?: return println("no assets/norns dir")
    val breed = args.getOrElse(1) { "denali" }
    for (age in args.drop(2).mapNotNull { it.toIntOrNull() }.ifEmpty { listOf(0, 3) }) {
        val sprites = NornParts.load(breed, age) ?: continue
        val f = dir.resolve("rig-$breed-a$age.txt"); if (!f.isFile) continue
        f.writeText(NornRigDef.parse(f.readText(), sprites).toText())
        println("normalized ${f.path}")
    }
}

/** Copy one (age,action)'s animation (per-part rotation + global bob/lean/hop) onto one or more
 *  (age,action) targets, leaving their structure intact.
 *  args: <srcAge> <srcAction> <dstAgesCSV> <dstActionsCSV> [breed]
 *  e.g. `2 REST 2,3 COURT,EAT,PICK_UP` seeds those actions from a2's rest; `1 REST 0 REST` clones. */
private fun cmdCopyAction(args: Array<String>) {
    headless(); val dir = assetsNornsDir() ?: return println("no assets/norns dir")
    val breed = args.getOrElse(5) { "denali" }
    val srcAge = args[1].toInt(); val srcAction = CreatureAction.valueOf(args[2])
    val dstAges = args[3].split(",").map { it.toInt() }
    val dstActions = args[4].split(",").map { CreatureAction.valueOf(it) }
    val srcSprites = NornParts.load(breed, srcAge) ?: return println("no art $breed a$srcAge")
    val src = NornRigDef.parse(dir.resolve("rig-$breed-a$srcAge.txt").readText(), srcSprites)
    for (dstAge in dstAges) {
        val dstSprites = NornParts.load(breed, dstAge)
        if (dstSprites == null) { println("no art $breed a$dstAge"); continue }
        val dstFile = dir.resolve("rig-$breed-a$dstAge.txt")
        val dst = NornRigDef.parse(dstFile.readText(), dstSprites)
        for (dstAction in dstActions) {
            for (p in dst.parts) src.part(p.id)?.anim?.get(srcAction)?.let { p.anim[dstAction] = it.copy() }
            dst.global[dstAction] = src.globalFor(srcAction).copy()
        }
        dstFile.writeText(dst.toText())
        println("seeded a$dstAge ${dstActions.joinToString(",")} from a$srcAge $srcAction ($breed)")
    }
}

/** Scale a rig's world-unit calibrations by newH/oldH so they stay physically correct after a size
 *  change (stride/seat/reach/held-food all scale with the creature). args: <age> <oldH> <newH> [breed] */
private fun cmdRescale(args: Array<String>) {
    headless(); val dir = assetsNornsDir() ?: return println("no assets/norns dir")
    val age = args[1].toInt(); val f = args[3].toFloat() / args[2].toFloat(); val breed = args.getOrElse(4) { "denali" }
    val sprites = NornParts.load(breed, age) ?: return println("no art $breed a$age")
    val file = dir.resolve("rig-$breed-a$age.txt")
    val def = NornRigDef.parse(file.readText(), sprites)
    def.groundOffset *= f; def.walkStride *= f; def.pickupReachX *= f; def.heldFoodX *= f; def.heldFoodY *= f
    file.writeText(def.toText())
    println("rescaled a$age calibrations by ${"%.4f".format(f)}")
}

/** Render the four ages at their TRUE relative world heights (shared px-per-world-unit). args: [png] */
private fun cmdSizes(args: Array<String>) {
    headless()
    val w = 920; val h = 420; val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.paint = GradientPaint(0f, 0f, Color(232, 220, 188), 0f, h.toFloat(), Color(74, 58, 44)); g.fillRect(0, 0, w, h)
    val baseY = h * 0.86f; val unit = h * 0.30f      // shared px per world unit
    g.color = Color(150, 120, 84); g.fillRect(0, baseY.roundToInt(), w, 2)
    for (a in 0..3) {
        val sprites = NornParts.load("denali", a) ?: continue
        val def = NornRigStore.rigFor("denali", a) ?: continue
        val cx = w * (0.18f + a * 0.215f); val hAge = NornRigStore.heightForAge(a)
        NornCompositor.draw(g, def, sprites, CreatureAction.REST, 0.6f, 1, cx, baseY, unit, hAge, def.groundOffset)
        g.color = Color(40, 30, 20); g.font = Font("SansSerif", Font.BOLD, 13); g.drawString("a$a ($hAge)", cx - 30, baseY + 20)
    }
    g.dispose(); val out = File(args.getOrElse(1) { "build/norn-sizes.png" }); out.parentFile?.mkdirs(); ImageIO.write(img, "png", out); println("wrote ${out.absolutePath}")
}

fun main(args: Array<String>) {
    when (args.getOrNull(0)) {
        "--normalize" -> cmdNormalize(args)
        "--copy-action" -> cmdCopyAction(args)
        "--rescale" -> cmdRescale(args)
        "--sizes" -> cmdSizes(args)
        "--render" -> renderContactSheet(File(args.getOrElse(1) { "build/norn-anim-sheet.png" }), args.getOrNull(2)?.toIntOrNull())
        else -> SwingUtilities.invokeLater { NornsAnimViewer.run() }
    }
}
