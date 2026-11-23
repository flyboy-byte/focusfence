package app.focusfence.dns

import app.focusfence.dns.Group.Outcome
import app.focusfence.dns.Group.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupTest {
    private fun m(id: String, role: Role = Role.FILTERED, name: String = id.uppercase()) = Group.Member(id, name, role, 0)
    private fun ok(o: Outcome): Group.Change = (o as Outcome.Ok).change
    private fun rejected(o: Outcome): String = (o as Outcome.Rejected).reason

    private fun group(vararg members: Group.Member): Group.State {
        var s = Group.create("g", members.first(), 1)
        for (x in members.drop(1)) s = ok(Group.join(s, x, 2)).state
        return s
    }

    @Test fun capIsFourAndNamesAndPhonesAreUnique() {
        val s = group(m("a"), m("b"), m("c"), m("d"))
        assertTrue(rejected(Group.join(s, m("e"), 3)).contains("4"))
        val three = group(m("a"), m("b"), m("c"))
        assertTrue(rejected(Group.join(three, m("a", name = "Z"), 3)).contains("already in the group"))
        assertTrue(rejected(Group.join(three, m("x", name = "b"), 3)).contains("name"))
        assertTrue(rejected(Group.join(three, m("y", name = "  "), 3)).contains("name"))
    }

    @Test fun everyoneSeesEveryFilteredMemberByDefault() {
        val s = group(m("a"), m("b"), m("p", Role.PARTNER))
        assertEquals(setOf("b", "p"), Group.viewersOf(s, "a"))
        assertEquals(setOf("a", "p"), Group.viewersOf(s, "b"))
        assertEquals(emptySet<String>(), Group.viewersOf(s, "p")) // partner-only has no report
        assertEquals(listOf("A", "B"), Group.watchedBy(s, "p").map { it.name })
        assertEquals(listOf("B"), Group.watchedBy(s, "a").map { it.name }) // not themselves
    }

    @Test fun anyViewerCanUnlock() {
        val s = group(m("a"), m("b"), m("p", Role.PARTNER))
        assertEquals(setOf("b", "p"), Group.unlockersOf(s, "a"))
    }

    @Test fun pairingsNeededIsOnePerFilteredMemberPerViewer() {
        val s = group(m("a"), m("b"), m("p", Role.PARTNER))
        assertEquals(setOf("a" to "b", "a" to "p", "b" to "a", "b" to "p"), Group.pairingsNeeded(s).toSet())
        assertEquals(12, Group.pairingsNeeded(group(m("a"), m("b"), m("c"), m("d"))).size)
    }

    @Test fun narrowingWhoSeesYouLoosensAndWideningDoesNot() {
        val s = group(m("a"), m("b"), m("c"))
        val narrowed = ok(Group.setViewers(s, "a", setOf("b")))
        assertTrue(narrowed.loosens)
        assertEquals(setOf("b"), Group.viewersOf(narrowed.state, "a"))
        assertTrue(narrowed.notices.single().contains("1 of 2"))
        val widened = ok(Group.setViewers(narrowed.state, "a", null))
        assertFalse(widened.loosens)
        assertEquals(setOf("b", "c"), Group.viewersOf(widened.state, "a"))
        assertTrue(rejected(Group.setViewers(s, "a", null)).contains("No change"))
    }

    @Test fun unknownOrSelfViewersAreIgnoredAndPartnersCantNarrow() {
        val s = group(m("a"), m("b"), m("p", Role.PARTNER))
        assertEquals(setOf("b"), Group.viewersOf(ok(Group.setViewers(s, "a", setOf("b", "a", "ghost"))).state, "a"))
        assertTrue(rejected(Group.setViewers(s, "p", setOf("a"))).contains("filtered"))
    }

    @Test fun aFilteredMemberLeavingLoosensButAPartnerLeavingOnlyMattersIfTheyWereAViewer() {
        val s = group(m("a"), m("b"), m("p", Role.PARTNER))
        assertTrue(ok(Group.leave(s, "a")).loosens)
        assertFalse(ok(Group.leave(s, "p")).loosens) // leave() by a partner: their report stops existing, nothing weaker
        assertTrue(ok(Group.leave(s, "p")).notices.single().contains("left"))
    }

    @Test fun removingAViewerOfAFilteredMemberLoosens() {
        val s = group(m("a"), m("p", Role.PARTNER))
        val c = ok(Group.remove(s, "a", "p")) // the filtered member removes their only partner
        assertTrue(c.loosens)
        assertTrue(c.notices.single().contains("removed from the group by A"))
        assertEquals(1, c.state.members.size)
        assertFalse(Group.isActive(c.state))
    }

    @Test fun removedMembersDisappearFromChosenViewerLists() {
        val s = group(m("a"), m("b"), m("c"), m("d"))
        val narrowed = ok(Group.setViewers(s, "a", setOf("b", "c"))).state // d can't see a
        val after = ok(Group.remove(narrowed, "a", "b")).state
        assertEquals(setOf("c"), Group.viewersOf(after, "a"))
        assertEquals(setOf("c"), after.members.first { it.id == "a" }.viewers)
    }

    @Test fun removingYourselfIsLeaving() {
        val s = group(m("a"), m("b"))
        assertEquals(ok(Group.leave(s, "a")).notices, ok(Group.remove(s, "a", "a")).notices)
    }

    @Test fun stoppingTheFilterLoosensStartingItTightens() {
        val s = group(m("a"), m("b"))
        val toPartner = ok(Group.setRole(s, "a", Role.PARTNER))
        assertTrue(toPartner.loosens)
        assertEquals(emptySet<String>(), Group.viewersOf(toPartner.state, "a"))
        assertFalse(ok(Group.setRole(toPartner.state, "a", Role.FILTERED)).loosens)
        assertTrue(rejected(Group.setRole(s, "a", Role.FILTERED)).contains("Already"))
    }

    @Test fun everyChangeProducesANoticeForTheGroup() {
        val s = group(m("a"), m("b"))
        val c = ok(Group.join(s, m("c", Role.PARTNER), 5))
        assertEquals(listOf("C joined the group as partner-only."), c.notices)
        assertEquals(5L, c.state.members.last().joinedMs)
    }

    @Test fun controlCharactersInNamesAreStrippedAndLongNamesCut() {
        val s = Group.create("g", Group.Member("a", "Al\nice\u0000".padEnd(80, 'x'), Role.FILTERED, 0), 1)
        assertTrue(s.members.single().name.length <= Group.MAX_NAME)
        assertFalse(s.members.single().name.any { it.isISOControl() })
    }
}
