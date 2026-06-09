package org.emerge.desktop

import java.io.File

/**
 * Supplies the **authored rig** ([NornRigDef]) for the live world, per breed + life-stage age.
 *
 * One authored rig **per age** drives every species: `assets/norns/rig-denali-a<age>.txt`. Because
 * anchor/pivot coords are normalized (fractions of sprite w/h), the same denali rig applies to any
 * breed's parts — it scales to each breed's sprite dimensions. Rigs are still per **age** (baby crawl
 * vs adult upright are different layouts), but not per species. (If a breed looks off, that's an art
 * issue to fix in the sprites, not a reason for a separate rig — Stu's call, 2026-06-09.)
 */
object NornRigStore {

    private const val RIG_BREED = "denali"   // the single breed the rig is authored on; used for all

    private val cache = HashMap<String, NornRigDef?>()
    private val rigTextByAge = HashMap<Int, String?>()

    /** The authored rig text for [age] (null if not yet authored). */
    private fun rigText(age: Int): String? = rigTextByAge.getOrPut(age) { res("/assets/norns/rig-$RIG_BREED-a$age.txt") }

    /** The rig for [breed] at life-stage [age] (0..3), or null if the breed's art is missing. The
     *  single authored rig for the age is applied to this breed's own sprites (normalized → scaled). */
    fun rigFor(breed: String, age: Int): NornRigDef? = cache.getOrPut("$breed:$age") {
        val sprites = NornParts.load(breed, age) ?: return@getOrPut null
        rigText(age)?.let { NornRigDef.parse(it, sprites) } ?: NornRigDef.default(sprites)
    }

    /** Breed name for a creature's heritable breed index. */
    fun breedName(breedIndex: Int): String = NornParts.BREEDS[breedIndex.mod(NornParts.BREEDS.size)]

    /** Sprite-part age set per life stage (matches the live rig: babies/children crawl, rest upright). */
    fun ageOf(stage: String): Int = when (stage) { "BABY" -> 0; "CHILD" -> 1; "ADOLESCENT" -> 2; else -> 3 }

    /** Drawn height (world units) per life stage — babies tiny, growing up to adult. */
    fun targetHeight(stage: String): Float = when (stage) {
        "BABY" -> 1.2f; "CHILD" -> 1.5867f; "ADOLESCENT" -> 1.9733f; "OLD" -> 2.28f; else -> 2.36f
    }

    private fun res(path: String): String? =
        (NornRigStore::class.java.getResourceAsStream(path)?.readBytes()
            ?: File("assets$path").takeIf { it.exists() }?.readBytes()
            ?: File(System.getProperty("user.dir")).parentFile?.parentFile?.resolve("assets$path")?.takeIf { it.exists() }?.readBytes())
            ?.toString(Charsets.UTF_8)
}
