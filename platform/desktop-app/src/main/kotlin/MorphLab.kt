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
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/** The default cute baseline norn genome, shared by the editor and the headless render check. */
internal fun defaultNornGenome(): MorphNode {
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
        init { preferredSize = Dimension(RES, RES); background = BG }
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
        frame.add(title, BorderLayout.NORTH)
        frame.add(canvas, BorderLayout.CENTER)
        frame.add(buildControls(), BorderLayout.EAST)
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
        return JScrollPane(panel).apply { preferredSize = Dimension(300, RES); horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER }
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
        nodeEditor.add(rowLabel("name (eye/nose drive material)")); nodeEditor.add(name)
        nodeEditor.add(slider("offset forward (ox)", -2.5, 2.5, { n.ox.toDouble() }) { n.ox = it.toFloat(); requestRender() })
        nodeEditor.add(slider("offset along parent (oy)", -2.5, 2.5, { n.oy.toDouble() }) { n.oy = it.toFloat(); requestRender() })
        nodeEditor.add(slider("size (scale)", 0.05, 3.0, { n.scale.toDouble() }) { n.scale = it.toFloat(); requestRender() })
        nodeEditor.add(JCheckBox("bilateral pair (mirrored)", n.mirrored).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            addActionListener { n.mirX = if (isSelected) 1f else 0f; requestRender() }
        })
        nodeEditor.revalidate(); nodeEditor.repaint()
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
        val snap = genome.deepClone(); val furC = fur
        exec.submit {
            val baked = CreatureRenderer.Baked(snap); val moods = CreatureRenderer.Mood.PRESETS; val tile = 280
            val img = BufferedImage(tile * moods.size, tile, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = BG; g.fillRect(0, 0, img.width, img.height)
            for ((i, nm) in moods.withIndex()) {
                CreatureRenderer.render(baked, nm.second, furC, img, i * tile, tile, BG.rgb and 0xFFFFFF)
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
        exec.submit {
            if (myGen != gen.get()) return@submit
            val img = BufferedImage(RES, RES, BufferedImage.TYPE_INT_RGB)
            CreatureRenderer.render(CreatureRenderer.Baked(snap), mood, furC, img, 0, RES, BG.rgb and 0xFFFFFF)
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
