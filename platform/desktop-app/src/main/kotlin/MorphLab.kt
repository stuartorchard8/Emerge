package org.emerge.desktop

import org.emerge.demo.norns.morph.MorphCodec
import org.emerge.demo.norns.morph.MorphNode
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JSplitPane
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/** Build a node with `extra` params set concisely. */
private fun mn(name: String, ox: Float = 0f, oy: Float = 0f, scale: Float = 1f, mir: Float = 0f, vararg extra: Pair<String, Float>): MorphNode {
    val n = MorphNode(name, ox, oy, scale, mirX = mir)
    for ((k, v) in extra) n.extra[k] = v
    return n
}

/**
 * The cute, **expressive** baseline norn genome — the real facial features are nodes with authored
 * valence/arousal response, so the baseline emotes out of the box and every channel is editable in
 * MorphLab. A starting point to tune, not a fixed look. (`extra` keys: see [CreatureRenderer].)
 */
internal fun defaultNornGenome(): MorphNode {
    val body = mn("body", scale = 0.82f, extra = arrayOf("sx" to 1.15f))
    val head = mn("head", oy = 1.25f, scale = 1.85f)
    head.children.add(mn("crown", oy = 0.42f, scale = 0.82f))
    head.children.add(mn("ear", ox = -0.34f, oy = 0.66f, scale = 0.5f, mir = 1f, extra = arrayOf("sy" to 1.1f, "z" to 0.55f)))
    val muzzle = mn("muzzle", ox = 0.86f, oy = -0.22f, scale = 0.5f)
    muzzle.children.add(mn("nose", ox = 0.5f, oy = 0.02f, scale = 0.34f, extra = arrayOf("z" to 0.3f)))
    // mouth: a wide thin line on the muzzle; valence tilts it (smile/frown), arousal opens it
    muzzle.children.add(mn("mouth", ox = 0.35f, oy = -0.32f, scale = 0.5f,
        extra = arrayOf("sx" to 1.5f, "sy" to 0.32f, "z" to 0.3f, "vrot" to 22f, "asy" to 0.9f)))
    head.children.add(muzzle)
    // eye: big, near side; squints a little with sadness, widens with arousal. z = the bilateral half-separation.
    val eye = mn("eye", ox = 0.48f, oy = 0.16f, scale = 0.46f, mir = 1f, extra = arrayOf("z" to 0.5f, "asy" to 0.12f, "vsy" to -0.08f))
    eye.children.add(mn("iris", ox = 0.04f, scale = 0.55f, extra = arrayOf("z" to 0.34f)))
    // upper lid: a fur cap over the eye; arousal raises it (wide), low arousal/sadness lowers it (closes)
    eye.children.add(mn("upperlid", oy = 0.30f, scale = 0.9f,
        extra = arrayOf("sx" to 1.3f, "sy" to 0.55f, "z" to 0.15f, "ady" to 0.5f, "vdy" to 0.12f)))
    // brow ridge above the eye: tilts for anger (inner-down) / sadness (inner-up), raises with surprise
    eye.children.add(mn("brow", oy = 0.78f, scale = 0.55f,
        extra = arrayOf("sx" to 1.8f, "sy" to 0.35f, "z" to 0.1f, "vrot" to 16f, "arot" to 6f, "ady" to 0.18f)))
    head.children.add(eye)
    body.children.add(head)
    body.children.add(mn("arm", ox = 0.62f, oy = -0.35f, scale = 0.4f, mir = 1f, extra = arrayOf("z" to 0.25f)))
    body.children.add(mn("leg", ox = 0.16f, oy = -0.95f, scale = 0.58f, mir = 1f, extra = arrayOf("z" to 0.32f)))
    return body
}

/**
 * **MorphLab** — the live authoring tool for the creature baseline. Sculpt the genome (select a part,
 * drag its offset/size/mirror, add/remove parts), pick a mood to watch it emote, set the fur, and
 * save/load the genome as a `.morph` file. The lit side-profile is baked by [CreatureRenderer] on a
 * background thread (stale renders are dropped) so dragging stays responsive.
 */
class MorphLab {
    private var genome = defaultNornGenome()
    private var selected: MorphNode? = genome
    private var valence = 0.0
    private var arousal = 0.0
    private var camYaw = 0.0
    private var camPitch = 0.0
    private var fur = Color(176, 142, 104)
    private var currentFile: File? = null

    private val canvas = Canvas()
    private val tree = JList<String>()
    private val treeModel = DefaultListModel<String>()
    private val nodeEditor = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val title = JLabel("MorphLab — baseline")

    private val exec = Executors.newSingleThreadExecutor { Thread(it, "morphlab-render").apply { isDaemon = true } }
    private val gen = AtomicInteger(0)
    private var rows = ArrayList<MorphNode>()

    private inner class Canvas : JPanel() {
        var img: BufferedImage? = null
        init { preferredSize = Dimension(RES, RES); minimumSize = Dimension(140, 140); background = BG }
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            g.color = BG; g.fillRect(0, 0, width, height)
            val im = img ?: return
            val s = minOf(width.toDouble() / im.width, height.toDouble() / im.height)
            val w = (im.width * s).toInt(); val h = (im.height * s).toInt()
            g.drawImage(im, (width - w) / 2, (height - h) / 2, w, h, null)
        }
    }

    fun show() {
        val frame = JFrame("MorphLab — creature baseline authoring")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.layout = BorderLayout()
        title.border = BorderFactory.createEmptyBorder(6, 8, 6, 8); title.font = title.font.deriveFont(Font.BOLD)
        // canvas | controls in a draggable split pane, so the controls width is yours to size
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, canvas, buildControls()).apply {
            resizeWeight = 1.0           // window-resize grows the canvas, keeps the controls' width
            isOneTouchExpandable = true
        }
        frame.add(title, BorderLayout.NORTH)
        frame.add(split, BorderLayout.CENTER)
        rebuildTree(); selectNode(genome)
        frame.pack(); frame.setLocationRelativeTo(null); frame.isVisible = true
        requestRender()
    }

    private fun buildControls(): JComponent {
        val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = BorderFactory.createEmptyBorder(8, 8, 8, 8) }

        panel.add(heading("Mood"))
        panel.add(slider("valence (sad ↔ happy)", -1.0, 1.0, { valence }) { valence = it; requestRender() })
        panel.add(slider("arousal (calm ↔ excited)", -1.0, 1.0, { arousal }) { arousal = it; requestRender() })
        val presetRow = JPanel(java.awt.GridLayout(0, 4, 3, 3))
        for ((name, m) in CreatureRenderer.Mood.PRESETS) presetRow.add(JButton(name).apply {
            margin = java.awt.Insets(1, 2, 1, 2); addActionListener { valence = m.v; arousal = m.a; refreshMoodSliders(); requestRender() }
        })
        presetRow.maximumSize = Dimension(Int.MAX_VALUE, presetRow.preferredSize.height)
        panel.add(presetRow)

        panel.add(heading("Camera (0° yaw = side profile)"))
        panel.add(slider("yaw (orbit)", -180.0, 180.0, { camYaw }) { camYaw = it; requestRender() })
        panel.add(slider("pitch (tilt)", -80.0, 80.0, { camPitch }) { camPitch = it; requestRender() })

        panel.add(heading("Body — parts"))
        tree.model = treeModel; tree.selectionMode = ListSelectionModel.SINGLE_SELECTION
        tree.font = Font("Monospaced", Font.PLAIN, 12)
        tree.addListSelectionListener { if (!it.valueIsAdjusting) { val i = tree.selectedIndex; if (i in rows.indices) selectNode(rows[i]) } }
        val treeScroll = JScrollPane(tree).apply { preferredSize = Dimension(260, 150); maximumSize = Dimension(Int.MAX_VALUE, 150) }
        panel.add(treeScroll)
        val partBtns = JPanel(java.awt.GridLayout(1, 2, 3, 3))
        partBtns.add(JButton("add child").apply { addActionListener { addChild() } })
        partBtns.add(JButton("delete").apply { addActionListener { deleteSelected() } })
        partBtns.maximumSize = Dimension(Int.MAX_VALUE, partBtns.preferredSize.height)
        panel.add(partBtns)

        panel.add(heading("Selected part"))
        panel.add(nodeEditor)

        panel.add(heading("Fur"))
        panel.add(slider("red", 0.0, 255.0, { fur.red.toDouble() }) { fur = Color(it.toInt(), fur.green, fur.blue); requestRender() })
        panel.add(slider("green", 0.0, 255.0, { fur.green.toDouble() }) { fur = Color(fur.red, it.toInt(), fur.blue); requestRender() })
        panel.add(slider("blue", 0.0, 255.0, { fur.blue.toDouble() }) { fur = Color(fur.red, fur.green, it.toInt()); requestRender() })

        panel.add(heading("File"))
        val fileBtns = JPanel(java.awt.GridLayout(0, 2, 3, 3))
        fileBtns.add(JButton("New").apply { addActionListener { genome = defaultNornGenome(); currentFile = null; rebuildTree(); selectNode(genome); requestRender() } })
        fileBtns.add(JButton("Load…").apply { addActionListener { load() } })
        fileBtns.add(JButton("Save…").apply { addActionListener { save() } })
        fileBtns.add(JButton("Export sheet…").apply { addActionListener { exportSheet() } })
        fileBtns.maximumSize = Dimension(Int.MAX_VALUE, fileBtns.preferredSize.height)
        panel.add(fileBtns)

        panel.add(Box.createVerticalGlue())
        return JScrollPane(panel).apply {
            preferredSize = Dimension(480, RES)
            minimumSize = Dimension(300, 200)
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_ALWAYS
            verticalScrollBar.unitIncrement = 16
        }
    }

    // ---- node editor (rebuilt per selection so sliders bind to the chosen node) ----
    private val moodSliders = ArrayList<Pair<JSlider, () -> Double>>()
    private fun refreshMoodSliders() = moodSliders.forEach { (s, get) -> s.value = sliderRaw(get(), -1.0, 1.0) }

    private fun selectNode(n: MorphNode) {
        selected = n
        nodeEditor.removeAll()
        val name = JTextField(n.name).apply {
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            addActionListener { n.name = text.trim().ifEmpty { n.name }; rebuildTree(); requestRender() }
        }
        nodeEditor.add(rowLabel("name — role by prefix: eye/iris/pupil/nose/mouth/lip; else fur"))
        nodeEditor.add(name)

        nodeEditor.add(subhead("Placement (ox=forward, oy=up, z=toward viewer)"))
        nodeEditor.add(slider("ox", -2.5, 2.5, { n.ox.toDouble() }) { n.ox = it.toFloat(); requestRender() })
        nodeEditor.add(slider("oy", -2.5, 2.5, { n.oy.toDouble() }) { n.oy = it.toFloat(); requestRender() })
        nodeEditor.add(extraSlider(n, "z", "z (depth)", -1.5, 1.5, 0f))
        nodeEditor.add(slider("scale", 0.05, 3.0, { n.scale.toDouble() }) { n.scale = it.toFloat(); requestRender() })
        nodeEditor.add(extraSlider(n, "rot", "rotation (deg)", -90.0, 90.0, 0f))
        nodeEditor.add(JCheckBox("bilateral pair (mirrored)", n.mirrored).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            addActionListener { n.mirX = if (isSelected) 1f else 0f; requestRender() }
        })

        nodeEditor.add(subhead("Shape (per-axis radius)"))
        nodeEditor.add(extraSlider(n, "sx", "sx (width)", 0.1, 3.0, 1f))
        nodeEditor.add(extraSlider(n, "sy", "sy (height)", 0.1, 3.0, 1f))
        nodeEditor.add(extraSlider(n, "sz", "sz (depth)", 0.1, 3.0, 1f))

        nodeEditor.add(subhead("Response × valence (sad ↔ happy)"))
        nodeEditor.add(extraSlider(n, "vdx", "→ move x", -1.0, 1.0, 0f))
        nodeEditor.add(extraSlider(n, "vdy", "→ move y", -1.0, 1.0, 0f))
        nodeEditor.add(extraSlider(n, "vrot", "→ rotate (deg)", -60.0, 60.0, 0f))
        nodeEditor.add(extraSlider(n, "vsx", "→ widen", -1.0, 1.0, 0f))
        nodeEditor.add(extraSlider(n, "vsy", "→ heighten", -1.0, 1.0, 0f))

        nodeEditor.add(subhead("Response × arousal (calm ↔ excited)"))
        nodeEditor.add(extraSlider(n, "adx", "→ move x", -1.0, 1.0, 0f))
        nodeEditor.add(extraSlider(n, "ady", "→ move y", -1.0, 1.0, 0f))
        nodeEditor.add(extraSlider(n, "arot", "→ rotate (deg)", -60.0, 60.0, 0f))
        nodeEditor.add(extraSlider(n, "asx", "→ widen", -1.0, 1.0, 0f))
        nodeEditor.add(extraSlider(n, "asy", "→ heighten", -1.0, 1.0, 0f))

        nodeEditor.revalidate(); nodeEditor.repaint()
    }

    /** A slider bound to a node's [MorphNode.extra] entry, falling back to [default] (and removed when
     *  set back to default, so the genome serialises minimally). */
    private fun extraSlider(n: MorphNode, key: String, label: String, min: Double, max: Double, default: Float): JComponent =
        slider(label, min, max, { (n.extra[key] ?: default).toDouble() }) { v ->
            if (kotlin.math.abs(v - default) < 1e-4) n.extra.remove(key) else n.extra[key] = v.toFloat()
            requestRender()
        }

    private fun addChild() {
        val parent = selected ?: genome
        val child = MorphNode("part${parent.treeSize()}", ox = 0.4f, oy = 0.4f, scale = 0.5f)
        parent.children.add(child); rebuildTree(); selectNode(child); requestRender()
    }

    private fun deleteSelected() {
        val target = selected ?: return
        if (target === genome) { JOptionPane.showMessageDialog(canvas, "Can't delete the root body."); return }
        val parent = parentOf(genome, target) ?: return
        parent.children.remove(target); rebuildTree(); selectNode(parent); requestRender()
    }

    private fun parentOf(node: MorphNode, target: MorphNode): MorphNode? {
        for (c in node.children) { if (c === target) return node; parentOf(c, target)?.let { return it } }
        return null
    }

    private fun rebuildTree() {
        rows = ArrayList(); treeModel.clear()
        fun rec(n: MorphNode, depth: Int) { rows.add(n); treeModel.addElement("  ".repeat(depth) + n.name); n.children.forEach { rec(it, depth + 1) } }
        rec(genome, 0)
        selected?.let { sel -> val i = rows.indexOfFirst { it === sel }; if (i >= 0) tree.selectedIndex = i }
    }

    // ---- file ----
    private fun chooser(save: Boolean): File? {
        val fc = JFileChooser(currentFile?.parentFile ?: File("."))
        val r = if (save) fc.showSaveDialog(canvas) else fc.showOpenDialog(canvas)
        return if (r == JFileChooser.APPROVE_OPTION) fc.selectedFile else null
    }
    private fun save() {
        val f = chooser(true) ?: return
        val target = if (f.extension.isEmpty()) File(f.parentFile, f.name + ".morph") else f
        target.writeText(MorphCodec.format(genome)); currentFile = target; title.text = "MorphLab — ${target.name}"
    }
    private fun load() {
        val f = chooser(false) ?: return
        try { genome = MorphCodec.parse(f.readText()); currentFile = f; title.text = "MorphLab — ${f.name}"; rebuildTree(); selectNode(genome); requestRender() }
        catch (e: Exception) { JOptionPane.showMessageDialog(canvas, "Couldn't load: ${e.message}") }
    }
    private fun exportSheet() {
        val f = chooser(true) ?: return
        val target = if (f.extension.isEmpty()) File(f.parentFile, f.name + ".png") else f
        val snap = genome.deepClone(); val furC = fur; val yaw = camYaw; val pitch = camPitch
        exec.submit {
            val moods = CreatureRenderer.Mood.PRESETS; val tile = 280
            val img = BufferedImage(tile * moods.size, tile, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = BG; g.fillRect(0, 0, img.width, img.height)
            for ((i, nm) in moods.withIndex()) {
                CreatureRenderer.render(CreatureRenderer.Baked(snap, nm.second), furC, img, i * tile, tile, BG.rgb and 0xFFFFFF, yawDeg = yaw, pitchDeg = pitch)
                g.color = Color(60, 50, 40); g.font = Font("SansSerif", Font.BOLD, 14); g.drawString(nm.first, i * tile + 10, 22)
            }
            g.dispose(); ImageIO.write(img, "png", target)
            SwingUtilities.invokeLater { title.text = "MorphLab — wrote ${target.name}" }
        }
    }

    // ---- rendering (background; drop stale) ----
    private fun requestRender() {
        val myGen = gen.incrementAndGet()
        val snap = genome.deepClone(); val mood = CreatureRenderer.Mood(valence, arousal); val furC = fur
        val yaw = camYaw; val pitch = camPitch
        exec.submit {
            if (myGen != gen.get()) return@submit
            val img = BufferedImage(RES, RES, BufferedImage.TYPE_INT_RGB)
            CreatureRenderer.render(CreatureRenderer.Baked(snap, mood), furC, img, 0, RES, BG.rgb and 0xFFFFFF, yawDeg = yaw, pitchDeg = pitch)
            if (myGen != gen.get()) return@submit
            SwingUtilities.invokeLater { if (myGen == gen.get()) { canvas.img = img; canvas.repaint() } }
        }
    }

    // ---- small UI helpers ----
    private fun heading(t: String) = JLabel(t).apply {
        alignmentX = JComponent.LEFT_ALIGNMENT; font = font.deriveFont(Font.BOLD)
        border = BorderFactory.createEmptyBorder(10, 0, 2, 0)
    }
    private fun rowLabel(t: String) = JLabel(t).apply { alignmentX = JComponent.LEFT_ALIGNMENT }
    private fun subhead(t: String) = JLabel(t).apply {
        alignmentX = JComponent.LEFT_ALIGNMENT; font = font.deriveFont(Font.BOLD, font.size - 1f)
        foreground = Color(70, 70, 90); border = BorderFactory.createEmptyBorder(8, 0, 1, 0)
    }
    private fun sliderRaw(v: Double, min: Double, max: Double) = (((v - min) / (max - min)) * 1000).toInt().coerceIn(0, 1000)

    private fun slider(label: String, min: Double, max: Double, get: () -> Double, set: (Double) -> Unit): JComponent {
        val box = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); alignmentX = JComponent.LEFT_ALIGNMENT }
        val lbl = JLabel("$label = ${"%.2f".format(get())}").apply { alignmentX = JComponent.LEFT_ALIGNMENT }
        val s = JSlider(0, 1000, sliderRaw(get(), min, max)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT; maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        s.addChangeListener { val v = min + (max - min) * s.value / 1000.0; lbl.text = "$label = ${"%.2f".format(v)}"; set(v) }
        if (label.startsWith("valence") || label.startsWith("arousal")) moodSliders.add(s to get)
        box.add(lbl); box.add(s); box.maximumSize = Dimension(Int.MAX_VALUE, box.preferredSize.height)
        return box
    }

    companion object {
        private const val RES = 420
        private val BG = Color(236, 232, 224)
    }
}

fun main() {
    SwingUtilities.invokeLater { MorphLab().show() }
}
