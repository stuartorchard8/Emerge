package org.emerge.desktop

import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.CytoRenderer
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL11.glFinish
import org.lwjgl.opengl.GL11.glViewport
import org.lwjgl.system.MemoryUtil.NULL
import java.io.File

/**
 * **Render (FPS) benchmark** — the draw-thread counterpart to [CytoBenchmarkKt]'s sim-tick (TPS) profiler.
 * Cyto's desktop host runs the sim and the draw loop on separate threads, so a tick-rate regression and a
 * frame-rate regression are independent problems; this measures the latter.
 *
 * Renders the **real** [CytoRenderer] pipeline into a hidden GLFW window (the agent-harness technique) with
 * the sim frozen — every frame redraws one published snapshot, so what's measured is purely per-frame draw
 * cost, with no sim work in the loop. [glFinish] after each frame charges GPU time to that frame instead of
 * letting it queue, so the reported figure is the true frame cost rather than CPU submission time.
 *
 * Runs the default config, then the same scene with one feature subtracted at a time — the delta attributes
 * the cost. Zoom is swept because the cell pass has no off-screen culling: if frame time is flat across
 * zoom, per-cell draw-call overhead dominates fill rate.
 *
 * --args="<savePath> [frames] [width] [height]"
 */
object CytoRenderBenchmark {

    private class Bench(val w: Int, val h: Int) {
        var window: Long = NULL
        lateinit var renderer: CytoRenderer
        val controller = CytoController()

        fun init() {
            if (!glfwInit()) error("GLFW init failed")
            glfwDefaultWindowHints()
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, 1)
            window = glfwCreateWindow(w, h, "cyto-render-bench", NULL, NULL)
            if (window == NULL) error("failed to create hidden GLFW window (no GL context available?)")
            glfwMakeContextCurrent(window)
            glfwSwapInterval(0)   // never block on vsync — we want the raw frame cost
            org.lwjgl.opengl.GL.createCapabilities()
            renderer = CytoRenderer()
            renderer.setResolution(w.toFloat(), h.toFloat())
            glViewport(0, 0, w, h)
            println("GL renderer: ${org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER)}")
        }

        fun cleanup() {
            if (window != NULL) { runCatching { renderer.cleanup() }; glfwDestroyWindow(window) }
            glfwTerminate()
        }

        var lastAllocPerFrame = 0L

        /** Time [frames] draws of the frozen snapshot, returning per-frame micros sorted for percentiles. */
        fun measure(frames: Int): LongArray {
            val frame = controller.latestFrame()
            repeat(WARMUP) { renderer.draw(frame); glFinish() }   // JIT + shader compile + GPU clocks
            val times = LongArray(frames)
            val alloc0 = allocatedBytes()
            for (i in 0 until frames) {
                val t = System.nanoTime()
                renderer.draw(frame)
                glFinish()
                times[i] = (System.nanoTime() - t) / 1000
            }
            lastAllocPerFrame = (allocatedBytes() - alloc0) / frames
            return times
        }

        private fun allocatedBytes(): Long {
            val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            return bean.getThreadAllocatedBytes(Thread.currentThread().id)
        }

        fun report(label: String, times: LongArray) {
            val sorted = times.sortedArray()
            val avg = times.average()
            val p50 = sorted[sorted.size / 2]
            val p95 = sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)]
            val fps = if (avg > 0) 1_000_000.0 / avg else 0.0
            println(
                "  %-28s avg=%7.2fms  p50=%6.2fms  p95=%6.2fms  → %6.1f FPS  alloc=%5d KB/frame".format(
                    label, avg / 1000.0, p50 / 1000.0, p95 / 1000.0, fps, lastAllocPerFrame / 1024,
                )
            )
        }
    }

    private const val WARMUP = 60

    @JvmStatic
    fun main(args: Array<String>) {
        val path = args.getOrNull(0) ?: "apps/cyto/desktop/cyto-saves/squish.bin"
        val frames = args.getOrNull(1)?.toIntOrNull() ?: 200
        val w = args.getOrNull(2)?.toIntOrNull() ?: 1920
        val h = args.getOrNull(3)?.toIntOrNull() ?: 1080

        val b = Bench(w, h)
        b.init()
        try {
            b.controller.restoreSnapshot(File(path).readBytes())
            b.renderer.resetView()
            b.controller.publish()

            val cells = b.controller.latestFrame().state.components
                .getTable<CytoCellComponent>().asMap()
            println("=== Cyto Render Benchmark ===")
            println("source: $path  cells=${cells.size}  res=${w}x$h  frames=$frames")
            println("60 FPS budget = 16.67ms/frame\n")

            // ── Feature attribution (default view: whole world) ──
            println("-- feature attribution (fit-world view) --")
            b.renderer.showLightField = true; b.renderer.showMatterField = false; b.renderer.showGeneParticles = true
            b.report("default (light, genes)", b.measure(frames))

            b.renderer.showGeneParticles = false
            b.report("  − gene particles", b.measure(frames))

            b.renderer.showLightField = false
            b.report("  − gene, − light field", b.measure(frames))

            // The MATTER/LIGHT grid button is one mutually-exclusive toggle (CytoControls), so matter-on
            // means light-off — mirror that rather than stacking both.
            b.renderer.showGeneParticles = true
            b.renderer.showLightField = false
            b.renderer.showMatterField = true
            b.report("MATTER grid (light off)", b.measure(frames))
            // Split the overlay's cost: the CPU channel tally + texel fill is timed inside the renderer,
            // so whatever the overlay adds beyond it is texture upload + the full-screen warp shader.
            println(
                "      ↳ CPU rasterizeMatter = %.2fms over %d texels (rest = upload + warp shader)".format(
                    b.renderer.lastRasterizeUs / 1000.0, b.renderer.lastTexelCount,
                )
            )
            b.renderer.showMatterField = false

            // ── Zoom sweep: the cell pass draws every cell regardless of view, so a flat curve here
            //    means per-cell draw-call overhead, not fill rate. ──
            println("\n-- zoom sweep (default config; cell pass is unculled) --")
            b.renderer.showGeneParticles = true; b.renderer.showLightField = true
            for (zoom in listOf(1f, 4f, 16f, 64f)) {
                b.renderer.resetView()
                b.renderer.zoomByFactor(zoom)
                b.report("zoom ${zoom.toInt()}x", b.measure(frames))
            }
        } finally {
            b.cleanup()
        }
    }
}
