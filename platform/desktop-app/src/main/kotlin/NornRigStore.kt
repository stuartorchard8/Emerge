package org.emerge.desktop

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
    private fun rigText(age: Int): String? =
        rigTextByAge.getOrPut(age) { NornParts.res("/assets/norns/rig-$RIG_BREED-a$age.txt")?.toString(Charsets.UTF_8) }

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

    /** The canonical drawn height (world units) per part-age — the single source the game + editor +
     *  size preview all read, so they stay in sync. Babies tiny, growing up to adult. */
    val AGE_HEIGHTS = floatArrayOf(1.2f, 1.5867f, 1.9733f, 2.36f)
    fun heightForAge(age: Int): Float = AGE_HEIGHTS[age.coerceIn(0, AGE_HEIGHTS.lastIndex)]

    /** Drawn height per life stage. OLD reuses adult art a touch smaller (stooped). */
    fun targetHeight(stage: String): Float = if (stage == "OLD") heightForAge(3) * 0.966f else heightForAge(ageOf(stage))
}
