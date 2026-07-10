package org.emerge.render.ui.gallery

import java.awt.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Headless Java2D snapshot of the UI Gallery layout.
 * `--args="<outPng>"` (default: build/ui-gallery.png)
 */
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "build/ui-gallery.png" })
    val W = 1280
    val H = 860
    val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

    val WHITE = Color(255, 255, 255)
    val DIM = Color(154, 154, 154)
    val GRAY = Color(200, 200, 200)
    val CYAN = Color(0, 170, 204)
    val AMBER = Color(238, 221, 68)
    val BLUE = Color(58, 110, 165)
    val RED = Color(204, 51, 51)
    val GREEN = Color(46, 139, 64)
    val PURPLE = Color(138, 91, 192)
    val DBTN = Color(68, 76, 92)
    val DFIELD = Color(48, 56, 72)
    val OFIELD = Color(42, 74, 106)
    val DBG = Color(26, 34, 51)
    val SEL = Color(255, 224, 112)
    val LOT = Color(207, 224, 255)
    val YLW = Color(238, 221, 68)

    val titleFont = Font("SansSerif", Font.BOLD, 14)
    val rowFont = Font("SansSerif", Font.PLAIN, 11)
    val keyFont = Font("SansSerif", Font.PLAIN, 10)
    val btnFont = Font("SansSerif", Font.BOLD, 10)

    fun wm(t: String, font: Font): Int = g.getFontMetrics(font).stringWidth(t)
    fun ay(h: Int, font: Font) = h / 2 + g.getFontMetrics(font).descent + 1

    fun panel(x: Int, y: Int, pw: Int, ph: Int) {
        g.color = Color(0, 0, 0, 192)
        g.fillRoundRect(x, y, pw, ph, 4, 4)
        g.color = Color(60, 60, 80, 128)
        g.drawRoundRect(x, y, pw, ph, 4, 4)
    }

    fun btn(x: Int, y: Int, bw: Int, bh: Int, col: Color, label: String) {
        g.color = col
        g.fillRoundRect(x + 1, y, bw - 2, bh, 3, 3)
        val lum = col.red * 299 + col.green * 587 + col.blue * 114
        g.color = if (lum < 128 * 255) WHITE else Color(0, 0, 0)
        g.font = btnFont
        g.drawString(label, (x + bw / 2 - wm(label, btnFont) / 2).toFloat(), (y + ay(bh, btnFont)).toFloat())
    }

    fun t(t: String, x: Int, y: Int, font: Font, c: Color) {
        g.font = font; g.color = c
        g.drawString(t, x.toFloat(), y.toFloat())
    }

    g.color = Color(18, 18, 22)
    g.fillRect(0, 0, W, H)

    val PAD = 8
    val MGN = 12
    val RW = 18
    val LX = 12

    // ═══ PANEL 1: Text Widgets ═══
    var cy = 12
    var p1y = cy

    val p1labels = listOf(
        Triple("TEXT WIDGETS", titleFont, WHITE),
        Triple("Standard gray row text", rowFont, GRAY),
        Triple("This row uses medium gray (0xC8C8C8FF)", rowFont, GRAY),
        Triple("Colored row: cyan", rowFont, CYAN),
        Triple("Colored row: amber", rowFont, AMBER),
    )
    for ((text, font, col) in p1labels) {
        t(text, LX + PAD, p1y + ay(RW, font), font, col)
        p1y += RW
    }
    p1y += 4

    val p1kv = listOf(
        Pair(Pair("Label", "Value"), Pair(DIM, WHITE)),
        Pair(Pair("Font Size", "18px row height"), Pair(DIM, WHITE)),
        Pair(Pair("Left/Right", "key left-aligned, value right"), Pair(DIM, WHITE)),
    )
    for ((kv, kvc) in p1kv) {
        t(kv.first, LX + PAD, p1y + ay(RW, keyFont), keyFont, kvc.first)
        t(kv.second, LX + 500 - PAD - wm(kv.second, keyFont), p1y + ay(RW, keyFont), keyFont, kvc.second)
        p1y += RW
    }
    t("Custom Key", LX + PAD, p1y + ay(RW, keyFont), keyFont, Color(170, 102, 255))
    t("Custom Value", LX + 500 - PAD - wm("Custom Value", keyFont), p1y + ay(RW, keyFont), keyFont, Color(255, 136, 0))
    p1y += RW

    val p1h = (p1y - cy + PAD * 2).toInt()
    panel(LX, cy, 500, p1h)
    cy = cy + p1h + MGN + 16

    // ═══ PANEL 2: Buttons ═══
    var p2y = cy
    t("BUTTONS", LX + PAD, p2y + ay(RW, titleFont), titleFont, WHITE)
    p2y += RW

    val bts = listOf(Pair("Click Me (0)", BLUE), Pair("Delete", RED), Pair("Save", GREEN), Pair("Special", PURPLE))
    for ((lbl, col) in bts) {
        val bw = wm(lbl, btnFont) + 40
        btn(LX + PAD, p2y, bw, RW, col, lbl)
        p2y += RW
    }
    val cts = listOf("Counter: 0", "Delete count: 0", "Save count: 0", "Special count: 0")
    for (c in cts) {
        t(c, LX + PAD, p2y + ay(RW, rowFont), rowFont, DIM)
        p2y += RW
    }

    p2y += 4
    t("SPAN BUTTON", LX + PAD, p2y + ay(RW, titleFont), titleFont, WHITE)
    p2y += RW
    btn(LX + PAD, p2y, wm("SAVE / DELETE / CANCEL", btnFont) + 40, RW, DFIELD, "SAVE / DELETE / CANCEL")
    p2y += RW
    t("Span button clicks: 0", LX + PAD, p2y + ay(RW, rowFont), rowFont, DIM)
    p2y += RW + 4

    // Action row
    val actY = p2y
    val acts = listOf(Pair("Undo", DBTN), Pair("Redo", DBTN), Pair("Clear", RED), Pair("Reset", BLUE))
    var ax = LX + PAD
    for ((lbl, col) in acts) {
        val bw = wm(lbl, btnFont) + 30
        btn(ax, actY, bw, RW, col, lbl)
        ax += bw + 6
    }
    p2y = actY + RW
    t("Undo: 0 | Redo: 0 | Clear: 0 | Reset: 0", LX + PAD, p2y + ay(RW, rowFont), rowFont, DIM)
    p2y += RW

    val p2h = (p2y - cy + PAD * 2).toInt()
    panel(LX, cy, 500, p2h)
    cy = cy + p2h + MGN + 16

    // ═══ PANEL 3: Pickers ═══
    var p3y = cy
    t("PICKER / DROPDOWN", LX + PAD, p3y + ay(RW, titleFont), titleFont, WHITE)
    p3y += RW

    // Picker 1 - open
    t("Color", LX + PAD, p3y + ay(RW, keyFont), keyFont, DIM)
    var fv1 = maxOf(wm("Red", keyFont), 120) + 24
    var fx1 = LX + 500 - PAD - fv1 - wm("Color", keyFont) - 8
    g.color = OFIELD
    g.fillRoundRect(fx1, p3y + 1, fv1, RW - 2, 2, 2)
    t("Red", fx1 + 4, p3y + ay(RW, keyFont), keyFont, WHITE)
    t("v", fx1 + fv1 - 12, p3y + ay(RW, keyFont), keyFont, Color(170, 204, 255))
    val p1opts = listOf("Red", "Green", "Blue", "Amber", "Purple", "Cyan")
    var doy = p3y + RW
    for (opt in p1opts) {
        g.color = DBG
        g.fillRoundRect(fx1, doy, fv1, RW, 2, 2)
        t(opt, fx1 + 4, doy + ay(RW, keyFont), keyFont, if (opt == "Red") SEL else LOT)
        doy += RW
    }
    p3y = doy + 8

    // Picker 2 - closed
    t("Preset", LX + PAD, p3y + ay(RW, keyFont), keyFont, DIM)
    var fv2 = maxOf(wm("Default", keyFont), 100) + 24
    var fx2 = LX + 500 - PAD - fv2 - wm("Preset", keyFont) - 8
    g.color = DFIELD
    g.fillRoundRect(fx2, p3y + 1, fv2, RW - 2, 2, 2)
    t("Default", fx2 + 4, p3y + ay(RW, keyFont), keyFont, WHITE)
    t("v", fx2 + fv2 - 12, p3y + ay(RW, keyFont), keyFont, Color(170, 204, 255))
    p3y += RW + 8

    t("Selected color: Red", LX + PAD, p3y + ay(RW, rowFont), rowFont, DIM)
    p3y += RW
    t("Selected preset: Default", LX + PAD, p3y + ay(RW, rowFont), rowFont, DIM)
    p3y += RW

    val p3h = (p3y - cy + PAD * 2).toInt()
    panel(LX, cy, 500, p3h)
    cy = cy + p3h + MGN + 16

    // ═══ PANEL 4: Steppers ═══
    var p4y = cy
    t("STEPPER (hold to repeat)", LX + PAD, p4y + ay(RW, titleFont), titleFont, WHITE)

    fun drawStepper(label: String, value: String, sy: Int): Int {
        t(label, LX + PAD, sy + ay(RW, keyFont), keyFont, DIM)
        val bW = (keyFont.getSize() * 1.6f).toInt()
        val vW = maxOf(wm(value, keyFont), keyFont.getSize() * 3)
        val gx = LX + 500 - PAD - bW * 2 - vW
        g.color = DBTN
        g.fillRoundRect(gx + 1, sy + 1, bW - 2, RW - 2, 3, 3)
        t("-", gx + bW / 2, sy + ay(RW, keyFont), keyFont, WHITE)
        t(value, gx + bW + vW / 2, sy + ay(RW, keyFont), keyFont, WHITE)
        g.fillRoundRect(gx + bW + vW + 1, sy + 1, bW - 2, RW - 2, 3, 3)
        t("+", gx + bW + vW + bW / 2, sy + ay(RW, keyFont), keyFont, WHITE)
        return sy + RW
    }

    p4y = drawStepper("Scale", "100", p4y + RW)
    p4y = drawStepper("Offset", "0", p4y)
    p4y = drawStepper("Rate", "1000", p4y)
    p4y += 4
    t("Hold +/- buttons to see accelerating repeat", LX + PAD, p4y + ay(RW, rowFont), rowFont, DIM)
    p4y += RW
    t("Values: S:100 O:0 R:1000", LX + PAD, p4y + ay(RW, rowFont), rowFont, DIM)
    p4y += RW

    val p4h = (p4y - cy + PAD * 2).toInt()
    panel(LX, cy, 500, p4h)
    cy = cy + p4h + MGN + 16

    // ═══ PANEL 5: Gap demo ═══
    var p5y = cy
    t("GAP / SPACING", LX + PAD, p5y + ay(RW, titleFont), titleFont, WHITE)
    p5y += RW
    t("Small gap (default 6px):", LX + PAD, p5y + ay(RW, rowFont), rowFont, DIM); p5y += 6
    t("Medium gap (16px):", LX + PAD, p5y + ay(RW, rowFont), rowFont, DIM); p5y += 16
    t("Large gap (32px):", LX + PAD, p5y + ay(RW, rowFont), rowFont, DIM); p5y += 32
    t("Extra large gap (60px):", LX + PAD, p5y + ay(RW, rowFont), rowFont, DIM); p5y += 60
    panel(LX, cy, 500, (p5y - cy + PAD).toInt())

    // ═══ RIGHT COLUMN: State Panel ═══
    val RX = 660
    panel(RX, 12, 608, 640)
    var SY = 12 + PAD
    fun sLine(k: String, v: String) {
        t(k, RX + PAD, SY + ay(RW, keyFont), keyFont, DIM)
        t(v, RX + 608 - PAD - wm(v, keyFont), SY + ay(RW, keyFont), keyFont, WHITE)
        SY += RW
    }
    t("STATE PANEL", RX + PAD, SY + ay(RW, titleFont), titleFont, YLW); SY += RW
    sLine("FPS", "60"); SY += 6
    sLine("btn1 clicks", "0"); sLine("btn2 clicks", "0"); sLine("btn3 clicks", "0"); sLine("btn4 clicks", "0")
    sLine("spanBtn clicks", "0"); sLine("undo", "0"); sLine("redo", "0"); sLine("clear", "0"); sLine("reset", "0")
    SY += 6
    sLine("color", "Red"); sLine("preset", "Default")
    SY += 6
    sLine("scale", "100"); sLine("offset", "0"); sLine("rate", "1000")

    // ═══ BOTTOM-LEFT panel ═══
    val BLy = H - 140
    panel(12, BLy, 636, 128)
    var bly = BLy + PAD
    t("BOTTOM-LEFT PANEL", 12 + PAD, bly + ay(RW, titleFont), titleFont, GREEN); bly += RW
    t("Anchored BottomLeft", 12 + PAD, bly + ay(RW, rowFont), rowFont, GRAY); bly += RW
    t("Stacks above the edge", 12 + PAD, bly + ay(RW, rowFont), rowFont, GRAY); bly += RW
    t("x", 12 + PAD, bly + ay(RW, keyFont), keyFont, GRAY)
    t("0..1280", 12 + 636 - PAD - wm("0..1280", keyFont), bly + ay(RW, keyFont), keyFont, WHITE); bly += RW
    t("y", 12 + PAD, bly + ay(RW, keyFont), keyFont, GRAY)
    t("0..860", 12 + 636 - PAD - wm("0..860", keyFont), bly + ay(RW, keyFont), keyFont, WHITE); bly += RW + 4

    // ═══ BOTTOM-RIGHT panel ═══
    panel(W - 280, BLy, 268, 128)
    bly = BLy + PAD
    t("BOTTOM-RIGHT PANEL", W - 280 + PAD, bly + ay(RW, titleFont), titleFont, RED); bly += RW
    t("Anchored BottomRight", W - 280 + PAD, bly + ay(RW, rowFont), rowFont, GRAY); bly += RW
    t("Another stack at same anchor", W - 280 + PAD, bly + ay(RW, rowFont), rowFont, GRAY); bly += RW
    t("This panel sits above the bottom-right corner", W - 280 + PAD, bly + ay(RW, rowFont), rowFont, GRAY); bly += RW + 4
    btn(W - 280 + PAD, bly, wm("I'm a button", btnFont) + 40, RW, BLUE, "I'm a button")
    bly += RW
    t("BottomRight clicks: 0", W - 280 + PAD, bly + ay(RW, rowFont), rowFont, DIM)

    g.dispose()
    out.parentFile?.mkdirs()
    ImageIO.write(img, "png", out)
    println("wrote ${out.absolutePath}")
}
