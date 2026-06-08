package org.emerge.desktop

import java.io.File

/**
 * Supplies the **authored rig** ([NornRigDef]) for the live world, per breed + life-stage age.
 *
 * A breed with its own `assets/norns/rig-<breed>.txt` (authored in [NornsAnimViewer]) is rendered
 * from it directly — structure *and* animation. Any other breed gets its **own** correct default
 * structure (anchors from its `.att` points) with the **reference** rig's *motion* (animation +
 * body bob/lean/hop) overlaid — because the swing/timing is breed-independent while anchors are
 * breed-specific pixels. Drop in more `rig-<breed>.txt` files to author each breed fully.
 */
object NornRigStore {

    private const val REFERENCE_BREED = "denali"   // the breed the shared walk was authored on

    private val cache = HashMap<String, NornRigDef?>()
    private var refText: String? = null
    private var refLoaded = false

    private fun reference(): String? {
        if (!refLoaded) { refLoaded = true; refText = res("/assets/norns/rig-$REFERENCE_BREED.txt") }
        return refText
    }

    /** The rig for [breed] at life-stage [age] (0..3), or null if the breed's art is missing. */
    fun rigFor(breed: String, age: Int): NornRigDef? = cache.getOrPut("$breed:$age") {
        val sprites = NornParts.load(breed, age) ?: return@getOrPut null
        val own = res("/assets/norns/rig-$breed.txt")
        when {
            own != null -> NornRigDef.parse(own, sprites)                       // authored for this breed: full
            else -> {
                val d = NornRigDef.default(sprites)                             // this breed's own anchors
                reference()?.let { NornRigDef.parse(it, sprites, applyParts = false, into = d) }  // + shared motion
                d
            }
        }
    }

    /** Breed name for a creature's heritable breed index. */
    fun breedName(breedIndex: Int): String = NornParts.BREEDS[breedIndex.mod(NornParts.BREEDS.size)]

    /** Sprite-part age set per life stage (matches the live rig: babies/children crawl, rest upright). */
    fun ageOf(stage: String): Int = when (stage) { "BABY" -> 0; "CHILD" -> 1; else -> 3 }

    /** Drawn height (world units) per life stage — babies tiny, growing up to adult. */
    fun targetHeight(stage: String): Float = when (stage) {
        "BABY" -> 0.6f; "CHILD" -> 1.3f; "ADOLESCENT" -> 2.2f; "OLD" -> 2.85f; else -> 2.95f
    }

    private fun res(path: String): String? =
        (NornRigStore::class.java.getResourceAsStream(path)?.readBytes()
            ?: File("assets$path").takeIf { it.exists() }?.readBytes()
            ?: File(System.getProperty("user.dir")).parentFile?.parentFile?.resolve("assets$path")?.takeIf { it.exists() }?.readBytes())
            ?.toString(Charsets.UTF_8)
}
