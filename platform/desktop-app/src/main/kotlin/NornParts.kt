package org.emerge.desktop

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Loads the **individual ripped C2 sprite parts** for a breed/age (body, head, thigh/shin/foot ×2,
 * upper/forearm ×2) plus their named **anchor points** — the raw material the rig compositor
 * ([NornRigDef] / [NornCompositor]) assembles procedurally. Reads the same
 * `assets/norns/<breed>_rig_a<age>.txt` manifest the live [NornRig] uses, but exposes the parts as
 * plain data so a creature can be re-composited with fully custom anchors/animation.
 *
 * Manifest line: `<part> <imageFile> <w> <h> <key>:<x>,<y> ...` — e.g.
 * `body parts/denali/a3/body.png 33 53 head:28,0 hipL:11,35 hipR:11,35 shL:20,22 shR:20,22`.
 */
object NornParts {

    class Part(
        val name: String,
        val img: BufferedImage,
        val w: Int,
        val h: Int,
        val points: Map<String, FloatArray>,
    ) {
        fun pt(k: String): FloatArray = points[k] ?: floatArrayOf(0f, 0f)
    }

    /** Breeds available to the compositor (same roster as the live rig). */
    val BREEDS = listOf("denali", "bavaria", "bilba", "calypso", "cloud", "foxi", "dog", "duck", "daffodil")

    private val cache = HashMap<String, LinkedHashMap<String, Part>?>()

    /** Parts for [breed] at [age] (0..3), in manifest order. Null if the manifest/art is missing. */
    fun load(breed: String, age: Int): LinkedHashMap<String, Part>? = cache.getOrPut("$breed:$age") {
        val txt = res("/assets/norns/${breed}_rig_a$age.txt")?.toString(Charsets.UTF_8) ?: return@getOrPut null
        val parts = LinkedHashMap<String, Part>()
        for (line in txt.lines()) {
            val tok = line.trim().split(" ").filter { it.isNotEmpty() }
            if (tok.size < 4) continue
            val w = tok[2].toIntOrNull() ?: continue
            val h = tok[3].toIntOrNull() ?: continue
            val pts = HashMap<String, FloatArray>()
            for (i in 4 until tok.size) {
                val kv = tok[i].split(":"); if (kv.size != 2) continue
                val xy = kv[1].split(","); if (xy.size != 2) continue
                pts[kv[0]] = floatArrayOf(xy[0].toFloat(), xy[1].toFloat())
            }
            val bytes = res("/assets/norns/" + tok[1]) ?: continue
            val img = bytes.inputStream().use { ImageIO.read(it) } ?: continue
            parts[tok[0]] = Part(tok[0], img, w, h, pts)
        }
        if (parts.containsKey("body")) parts else null
    }

    /** First breed/age that actually loads (so the tool opens with something even if a breed is absent). */
    fun firstAvailable(): Triple<String, Int, LinkedHashMap<String, Part>>? {
        for (b in BREEDS) for (age in intArrayOf(3, 1, 0, 2)) load(b, age)?.let { return Triple(b, age, it) }
        return null
    }

    private fun res(path: String): ByteArray? =
        NornParts::class.java.getResourceAsStream(path)?.readBytes()
            ?: File("assets$path").takeIf { it.exists() }?.readBytes()
            ?: File(System.getProperty("user.dir")).parentFile?.parentFile?.resolve("assets$path")?.takeIf { it.exists() }?.readBytes()
}
