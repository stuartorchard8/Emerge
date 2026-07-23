package org.emerge.demo.cyto.campaign

import org.emerge.demo.cyto.host.CampaignContent
import org.emerge.demo.cyto.sim.CytoScenario
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The campaign map is a **reveal**. What it must never do is leak: a chapter the player has not earned
 * their way to may appear as a marker at most, and one layer beyond that must not appear at all.
 */
class CampaignMapTest {

    /** A chapter with only the fields the map reads. */
    private fun ch(id: String, title: String = id.uppercase(), branchesTo: List<String>? = null) =
        Chapter(id, 1, title, "", CytoScenario.DEFAULT, emptyList(), branchesTo = branchesTo)

    /** A linear a → b → c, and a fork at c. The two leaves declare an EMPTY `branchesTo` — "leads nowhere",
     *  as the authored branch endings do; without it each would lead to whatever follows it in the list, and
     *  the left branch would run into the right one. */
    private val flow = listOf(
        ch("a"), ch("b"), ch("c", branchesTo = listOf("left", "right")),
        ch("left", branchesTo = listOf("left2")), ch("left2", branchesTo = emptyList()),
        ch("right", branchesTo = listOf("right2")), ch("right2", branchesTo = emptyList()),
    )

    private fun map(vararg completed: String) = CampaignMap.build(flow, { it in completed })

    private fun node(m: CampaignMap, id: String) = m.nodes.firstOrNull { it.id == id }

    @Test
    fun aFreshMapShowsTheFirstChapterAndOneMarkerBeyondIt() {
        val m = map()
        assertEquals(listOf("a", "b"), m.nodes.map { it.id })
        assertEquals(CampaignMap.State.Available, node(m, "a")!!.state)
        assertEquals(CampaignMap.State.Ghost, node(m, "b")!!.state)
        assertNull(node(m, "c"), "two layers ahead is not on the map at all")
    }

    /** A ghost is existence and nothing else — no title, no chapter to start. */
    @Test
    fun aGhostRevealsNothingButItself() {
        val g = node(map(), "b")!!
        assertNull(g.chapter, "no chapter behind it means the menu cannot offer to start it")
        assertFalse(g.revealed)
        assertEquals("???", g.label)
    }

    /**
     * The point of showing one layer: at a fork the player can see that there IS a fork, and how many ways it
     * goes, without being told what either way is. A fork you cannot see is just a corridor.
     */
    @Test
    fun aForkIsVisibleAsAForkBeforeEitherBranchIsUnlocked() {
        val m = map("a", "b")
        val ghosts = m.nodes.filter { it.state == CampaignMap.State.Ghost }
        assertEquals(listOf("left", "right"), ghosts.map { it.id })
        assertTrue(ghosts.all { it.chapter == null }, "which way is which is still hidden")
        assertNull(node(m, "left2"), "and nothing past the fork exists yet")
    }

    @Test
    fun completingAChapterNamesTheOnesItLeadsTo() {
        val m = map("a", "b", "c")
        for (id in listOf("left", "right")) {
            val n = node(m, id)!!
            assertEquals(CampaignMap.State.Available, n.state)
            assertEquals(id.uppercase(), n.label)
        }
        assertEquals(CampaignMap.State.Completed, node(m, "c")!!.state)
    }

    /** Edges exist only between nodes that are both on the map — a connector to nothing would advertise the
     *  layer the map is deliberately hiding. */
    @Test
    fun everyEdgeJoinsTwoVisibleNodes() {
        for (m in listOf(map(), map("a"), map("a", "b"), map("a", "b", "c", "left"))) {
            for (e in m.edges) {
                assertTrue(e.from in m.nodes.indices && e.to in m.nodes.indices)
            }
            assertEquals(
                m.edges.size, m.edges.distinctBy { it.from to it.to }.size, "no duplicate connectors",
            )
        }
    }

    /**
     * Layout: a branch keeps its own half of the width, all the way down. Without this a row that happens to
     * hold one node re-centres it, and a chapter three steps down the right-hand road is drawn on the left.
     */
    @Test
    fun aBranchStaysOnItsOwnSideOfTheMap() {
        val m = map("a", "b", "c", "left", "right")
        val left = node(m, "left")!!
        val right = node(m, "right")!!
        assertTrue(left.x < 0.5f && right.x > 0.5f, "the fork splits the width")
        assertTrue(node(m, "left2")!!.x < 0.5f, "and its descendant stays under it")
        assertTrue(node(m, "right2")!!.x > 0.5f)
        assertEquals(0.5f, node(m, "a")!!.x, "the single root is centred")
    }

    @Test
    fun depthIsTheRowAndTheRootIsRowZero() {
        val m = map("a", "b", "c")
        assertEquals(0, node(m, "a")!!.depth)
        assertEquals(1, node(m, "b")!!.depth)
        assertEquals(2, node(m, "c")!!.depth)
        assertEquals(3, node(m, "left")!!.depth)
        // Five rows: a, b, c, the two unlocked branches, and the markers past them.
        assertEquals(5, m.depthCount)
    }

    /** The menu opens on something obvious to click: the chapter the player is up to. */
    @Test
    fun currentIsTheFirstUnfinishedChapter() {
        assertEquals("a", map().current?.id)
        assertEquals("b", map("a").current?.id)
        assertEquals("left", map("a", "b", "c").current?.id)
    }

    @Test
    fun anEmptyCampaignIsAnEmptyMap() {
        val m = CampaignMap.build(emptyList(), { false })
        assertTrue(m.nodes.isEmpty() && m.edges.isEmpty())
        assertEquals(0, m.depthCount)
        assertNull(m.current)
    }

    /** A cycle can only get in by an authoring slip, but it must not hang the menu that draws it. */
    @Test
    fun aCyclicGraphStillTerminates() {
        val cyclic = listOf(ch("x", branchesTo = listOf("y")), ch("y", branchesTo = listOf("x")))
        val m = CampaignMap.build(cyclic, { true })
        assertEquals(2, m.nodes.size)
    }

    // ── The authored campaign ────────────────────────────────────────────────────────────────────────

    /** The real thing: nothing but Genesis and a marker until the player starts playing. */
    @Test
    fun theAuthoredCampaignOpensOnGenesisAlone() {
        val m = CampaignMap.build(CampaignContent.PLAYABLE_CHAPTERS, { false })
        val named = m.nodes.filter { it.revealed }
        assertEquals(listOf("ch00-genesis"), named.map { it.id })
        assertEquals(1, m.nodes.count { it.state == CampaignMap.State.Ghost })
    }

    /** ...and the fork after Divide shows as two unnamed markers — Stu's case: you can see the road splits,
     *  but not that it splits into Exhaust and Competition. */
    @Test
    fun theAuthoredForkAfterDivideShowsAsTwoMarkers() {
        val m = CampaignMap.build(CampaignContent.PLAYABLE_CHAPTERS, { it == "ch00-genesis" })
        val ghosts = m.nodes.filter { it.state == CampaignMap.State.Ghost }
        assertEquals(
            listOf(CampaignContent.BRANCH_PHOTOSYNTHESIS, CampaignContent.BRANCH_CONVERSION).sorted(),
            ghosts.map { it.id }.sorted(),
        )
        assertTrue(ghosts.none { it.chapter != null })
        assertNotNull(node(m, "ch01-divide")?.chapter, "the chapter they can actually play is named")
    }

    /** The pre-inversion campaign is orphaned: it must not be reachable from the map at all. */
    @Test
    fun theOldCampaignIsNotOnTheMap() {
        val all = CampaignMap.build(CampaignContent.PLAYABLE_CHAPTERS, { true })
        assertTrue(
            all.nodes.none { it.id in CampaignContent.ORDER },
            "the pre-inversion chapters read wrong under the current chemistry - they stay unreachable",
        )
    }
}
