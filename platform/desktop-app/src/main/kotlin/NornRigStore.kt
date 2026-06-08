package org.emerge.desktop

import java.io.File

/**
 * Supplies the **authored rig** ([NornRigDef]) for the live world, per breed + life-stage age.
 *
 * Rigs are scoped per **(breed, age)** — `assets/norns/rig-<breed>-a<age>.txt` — because baby (crawl)
 * and adult (upright) layouts are fundamentally different (different parts, anchors, and motion).
 * A (breed,age) with its own file is rendered from it directly — structure *and* animation. Any
 * other breed at that age gets its **own** correct default structure (anchors from its `.att`
 * points) with the **reference** breed's *motion for that same age* overlaid — because swing/timing
 * is breed-independent (within an age) while anchors are breed-specific pixels. Drop in more
 * `rig-<breed>-a<age>.txt` files to author each breed/age fully.
 */
object NornRigStore {

    private const val REFERENCE_BREED = "denali"   // the breed the shared motion is authored on

    private val cache = HashMap<String, NornRigDef?>()
    private val refByAge = HashMap<Int, String?>()

    /** The reference breed's authored motion for [age] (null if not yet authored). */
    private fun referenceFor(age: Int): String? = refByAge.getOrPut(age) { res("/assets/norns/rig-$REFERENCE_BREED-a$age.txt") }

    /** The rig for [breed] at life-stage [age] (0..3), or null if the breed's art is missing. */
    fun rigFor(breed: String, age: Int): NornRigDef? = cache.getOrPut("$breed:$age") {
        val sprites = NornParts.load(breed, age) ?: return@getOrPut null
        val own = res("/assets/norns/rig-$breed-a$age.txt")
        when {
            own != null -> NornRigDef.parse(own, sprites)                       // authored for this breed+age: full
            else -> {
                val d = NornRigDef.default(sprites)                             // this breed's own anchors
                referenceFor(age)?.let { NornRigDef.parse(it, sprites, applyParts = false, into = d) }  // + shared motion (same age)
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
