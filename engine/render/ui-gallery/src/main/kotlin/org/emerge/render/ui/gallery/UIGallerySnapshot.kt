package org.emerge.render.ui.gallery

import org.emerge.render.torus.ui.Ui
import org.lwjgl.BufferUtils
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL11.*
import org.lwjgl.system.MemoryUtil.NULL
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders one frame of the UI Gallery with the real OpenGL toolkit into a hidden
 * window and saves it as a PNG.
 * `--args="<outPng>"` (default: build/ui-gallery.png)
 */
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "build/ui-gallery.png" })
    if (!glfwInit()) error("GLFW init failed")
    glfwDefaultWindowHints()
    glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
    glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
    glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
    glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
    glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, 1)

    val window = glfwCreateWindow(GALLERY_WIDTH, GALLERY_HEIGHT, "UI Gallery Snapshot", NULL, NULL)
    if (window == NULL) error("Failed to create GLFW window")
    glfwMakeContextCurrent(window)
    org.lwjgl.opengl.GL.createCapabilities()

    val fbW = IntArray(1)
    val fbH = IntArray(1)
    glfwGetFramebufferSize(window, fbW, fbH)

    glViewport(0, 0, fbW[0], fbH[0])
    val ui = Ui()
    ui.setResolution(fbW[0].toFloat(), fbH[0].toFloat())

    UIGallery.buildGalleryFrame(ui, GalleryState(), fps = 60f)

    glClearColor(0.07f, 0.07f, 0.09f, 1f)
    glClear(GL_COLOR_BUFFER_BIT)
    ui.draw()
    glFinish()

    val pixels = BufferUtils.createByteBuffer(fbW[0] * fbH[0] * 4)
    glReadPixels(0, 0, fbW[0], fbH[0], GL_RGBA, GL_UNSIGNED_BYTE, pixels)

    val img = BufferedImage(fbW[0], fbH[0], BufferedImage.TYPE_INT_RGB)
    for (y in 0 until fbH[0]) {
        val srcRow = fbH[0] - 1 - y // GL rows are bottom-up
        for (x in 0 until fbW[0]) {
            val i = (srcRow * fbW[0] + x) * 4
            val r = pixels.get(i).toInt() and 0xFF
            val g = pixels.get(i + 1).toInt() and 0xFF
            val b = pixels.get(i + 2).toInt() and 0xFF
            img.setRGB(x, y, (r shl 16) or (g shl 8) or b)
        }
    }

    ui.cleanup()
    glfwDestroyWindow(window)
    glfwTerminate()

    out.parentFile?.mkdirs()
    ImageIO.write(img, "png", out)
    println("wrote ${out.absolutePath}")
}
