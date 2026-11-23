package app.focusfence.dns

import app.focusfence.dns.Group.Role
import app.focusfence.dns.GroupProtocol.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A group mailbox the server can't read, as an in-memory ordered log of sealed blobs. */
private class Net(val secret: ByteArray = Pairing.generateSecret()) {
    val keys = Pairing.derive(secret)
    val groupId = keys.mailboxId
    val log = ArrayList<ByteArray>()
    fun post(msg: Map<String, Any?>) { log += GroupProtocol.seal(keys, msg) }
    fun view(): GroupProtocol.View = GroupProtocol.replay(groupId, log.mapNotNull { GroupProtocol.open(keys, it) })
}

/** One phone: its identity, its own bookkeeping, and the pairings the protocol told it to make. */
private class Phone(name: String, val role: Role = Role.FILTERED) {
    val me = GroupProtocol.newIdentity(name)
    var local = GroupProtocol.Local()
    val pairings = HashMap<String, String>() // viewerId -> pairing code I made for them
    val watches = HashMap<String, String>()  // ownerId -> pairing code they gave me
    var clock = 1000L

    fun join(net: Net) = net.post(GroupProtocol.hello(me, net.groupId, role, clock++))
    fun approve(vararg ids: String) { local = GroupProtocol.Local(local.approved + ids, local.offered, local.applied, local.watching) }

    /** One pass: read the log, ask the protocol what to do, do it. Returns whether anything happened. */
    fun step(net: Net): Boolean {
        val acts = GroupProtocol.plan(net.view(), net.groupId, me, local, clock++)
        var offered = local.offered; var applied = local.applied; var watching = local.watching
        for (a in acts) when (a) {
            is Action.Post -> net.post(a.msg)
            is Action.PairFor -> { pairings[a.viewerId] = a.code; offered = offered + a.viewerId }
            is Action.Watch -> { watches[a.ownerId] = a.code; applied = applied + a.offerId; watching = watching + a.ownerId }
            is Action.Unpair -> { pairings.remove(a.viewerId); offered = offered - a.viewerId }
            is Action.Unwatch -> { watches.remove(a.ownerId); watching = watching - a.ownerId }
        }
        local = GroupProtocol.Local(local.approved, offered, applied, watching)
        return acts.isNotEmpty()
    }
}

private fun settle(net: Net, vararg phones: Phone) {
    repeat(10) { if (phones.map { it.step(net) }.none { it }) return }
    fail("did not settle")
}

private fun ownKeys(p: Phone) = GroupProtocol.MemberKeys(
    p.me.sign.public, p.me.enc.public, p.me.sign.public.encoded, p.me.enc.public.encoded,
)

/** Everyone compares fingerprints in person, finds them equal, and approves everyone else. */
private fun everyoneApprovesEveryone(net: Net, phones: List<Phone>) {
    val view = net.view()
    for (p in phones) for (q in phones) if (p !== q) {
        assertEquals("fingerprints must match what ${q.me.name} sees on their own phone",
            GroupProtocol.fingerprint(ownKeys(q)), GroupProtocol.fingerprint(view.keys.getValue(q.me.memberId)))
        p.approve(q.me.memberId)
    }
}

class GroupProtocolTest {

    private fun makeGroup(vararg phones: Phone): Net {
        val net = Net()
        for (p in phones) p.join(net)
        return net
    }

    @Test fun fourFilteredPhonesEndUpWithEveryPairingAndMatchingCodes() {
        val a = Phone("Ann"); val b = Phone("Bo"); val c = Phone("Cy"); val d = Phone("Di")
        val all = listOf(a, b, c, d)
        val net = makeGroup(a, b, c, d)
        assertEquals(4, net.view().state!!.members.size)
        everyoneApprovesEveryone(net, all)
        settle(net, *all.toTypedArray())
        for (owner in all) for (viewer in all) if (owner !== viewer) {
            val made = owner.pairings[viewer.me.memberId]
            assertEquals("${viewer.me.name} should hold the code ${owner.me.name} made for them", made, viewer.watches[owner.me.memberId])
            assertTrue(Pairing.decode(made!!) != null)
        }
        assertEquals(12, all.sumOf { it.pairings.size })
        assertEquals(12, all.sumOf { it.watches.size })
        // each pair has its own secret
        assertEquals(12, all.flatMap { it.pairings.values }.toSet().size)
    }

    @Test fun aPartnerOnlyMemberWatchesEveryoneAndOffersNothing() {
        val a = Phone("Ann"); val b = Phone("Bo"); val p = Phone("Pat", Role.PARTNER)
        val net = makeGroup(a, b, p)
        everyoneApprovesEveryone(net, listOf(a, b, p))
        settle(net, a, b, p)
        assertTrue(p.pairings.isEmpty())
        assertEquals(setOf(a.me.memberId, b.me.memberId), p.watches.keys)
        assertEquals(setOf(b.me.memberId, p.me.memberId), a.pairings.keys)
    }

    @Test fun anUnapprovedNewcomerIsVisibleButGetsNothing() {
        val a = Phone("Ann"); val b = Phone("Bo")
        val net = makeGroup(a, b)
        everyoneApprovesEveryone(net, listOf(a, b))
        val stranger = Phone("Mallory") // has the leaked invite
        stranger.join(net)
        settle(net, a, b, stranger)
        val view = net.view()
        assertEquals(3, view.state!!.members.size) // they show up, so people can see and remove them
        assertTrue(a.pairings.keys.none { it == stranger.me.memberId })
        assertTrue(stranger.watches.isEmpty())
        // the approval prompt lists them with a fingerprint
        val pending = GroupProtocol.awaitingApproval(view, a.me, a.local)
        assertEquals(listOf("Mallory"), pending.map { it.first.name })
        assertEquals(GroupProtocol.fingerprint(ownKeys(stranger)), pending.single().second)
    }

    @Test fun aSealedPairingCannotBeReadByAnyoneElse() {
        val a = Phone("Ann"); val b = Phone("Bo"); val c = Phone("Cy")
        val net = makeGroup(a, b, c)
        everyoneApprovesEveryone(net, listOf(a, b, c))
        settle(net, a, b, c)
        val offerToB = net.view().offers.single { it.from == a.me.memberId && it.to == b.me.memberId }
        try { Ecies.decrypt(offerToB.ct, c.me.enc.private); fail("Cy must not be able to open Bo's offer") } catch (e: Exception) { /* expected */ }
        assertEquals(a.pairings[b.me.memberId], String(Ecies.decrypt(offerToB.ct, b.me.enc.private)))
    }

    @Test fun aForgedMessageFromAnInviteHolderChangesNothing() {
        val a = Phone("Ann"); val b = Phone("Bo")
        val net = makeGroup(a, b)
        val mallory = Phone("Mallory")
        // Mallory has the invite and posts "Ann leaves" and "Ann removes Bo" signed with Mallory's own key but naming Ann
        val forgedLeave = GroupProtocol.leave(mallory.me, net.groupId, 5).toMutableMap().also { it["by"] = a.me.memberId }
        val forgedRemove = GroupProtocol.remove(mallory.me, net.groupId, b.me.memberId, 6).toMutableMap().also { it["by"] = a.me.memberId }
        net.post(forgedLeave); net.post(forgedRemove)
        val view = net.view()
        assertEquals(setOf(a.me.memberId, b.me.memberId), view.state!!.members.map { it.id }.toSet())
        assertEquals(2, view.dropped)
    }

    @Test fun aHelloThatClaimsSomeoneElsesIdIsDropped() {
        val a = Phone("Ann")
        val net = makeGroup(a)
        val mallory = Phone("Mallory")
        net.post(GroupProtocol.hello(mallory.me, net.groupId, Role.FILTERED, 9).toMutableMap().also { it["by"] = a.me.memberId })
        assertEquals(1, net.view().state!!.members.size)
        assertEquals(1, net.view().dropped)
    }

    @Test fun theGroupIsCappedAtFourAndTheCreatorCanCloseIt() {
        val ps = listOf(Phone("A"), Phone("B"), Phone("C"), Phone("D"))
        val net = makeGroup(*ps.toTypedArray())
        Phone("E").join(net)
        assertEquals(4, net.view().state!!.members.size)

        val a = Phone("Ann"); val b = Phone("Bo")
        val net2 = makeGroup(a, b)
        net2.post(GroupProtocol.close(b.me, net2.groupId, 50)) // not the creator: ignored
        assertFalse(net2.view().closed)
        net2.post(GroupProtocol.close(a.me, net2.groupId, 51))
        assertTrue(net2.view().closed)
        Phone("Late").join(net2)
        assertEquals(2, net2.view().state!!.members.size)
    }

    @Test fun narrowingWhoSeesMeRevokesThatViewersPairingBothWays() {
        val a = Phone("Ann"); val b = Phone("Bo"); val c = Phone("Cy")
        val all = listOf(a, b, c)
        val net = makeGroup(a, b, c)
        everyoneApprovesEveryone(net, all)
        settle(net, a, b, c)
        assertTrue(c.watches.containsKey(a.me.memberId))
        net.post(GroupProtocol.viewers(a.me, net.groupId, setOf(b.me.memberId), 100))
        settle(net, a, b, c)
        assertFalse(a.pairings.containsKey(c.me.memberId))
        assertFalse(c.watches.containsKey(a.me.memberId))
        assertTrue(b.watches.containsKey(a.me.memberId))
        assertTrue(net.view().notices.any { it.contains("1 of 2") })
    }

    @Test fun leavingStopsEveryoneWatchingYouAndEveryoneYouWatched() {
        val a = Phone("Ann"); val b = Phone("Bo"); val c = Phone("Cy")
        val all = listOf(a, b, c)
        val net = makeGroup(a, b, c)
        everyoneApprovesEveryone(net, all)
        settle(net, a, b, c)
        net.post(GroupProtocol.leave(c.me, net.groupId, 200))
        settle(net, a, b, c)
        assertFalse(a.watches.containsKey(c.me.memberId))
        assertFalse(b.watches.containsKey(c.me.memberId))
        assertFalse(a.pairings.containsKey(c.me.memberId))
        assertTrue(net.view().notices.contains("Cy left the group."))
    }

    @Test fun anOldOfferIsNotAppliedTwiceButANewOneAfterReAddingIs() {
        val a = Phone("Ann"); val b = Phone("Bo")
        val net = makeGroup(a, b)
        everyoneApprovesEveryone(net, listOf(a, b))
        settle(net, a, b)
        val first = b.watches[a.me.memberId]
        settle(net, a, b)
        assertEquals(first, b.watches[a.me.memberId])
        // Ann narrows to nobody, then widens again: a fresh pairing is made
        net.post(GroupProtocol.viewers(a.me, net.groupId, emptySet(), 300)); settle(net, a, b)
        assertFalse(b.watches.containsKey(a.me.memberId))
        net.post(GroupProtocol.viewers(a.me, net.groupId, null, 301)); settle(net, a, b)
        val second = b.watches[a.me.memberId]
        assertNotEquals(first, second)
        assertEquals(second, a.pairings[b.me.memberId])
    }

    @Test fun fingerprintsDifferPerMemberAndLookLikeTwelveDigits() {
        val a = Phone("Ann"); val b = Phone("Bo")
        val fa = GroupProtocol.fingerprint(ownKeys(a)); val fb = GroupProtocol.fingerprint(ownKeys(b))
        assertNotEquals(fa, fb)
        assertTrue(fa.matches(Regex("\\d{3}-\\d{3}-\\d{3}-\\d{3}")))
        assertEquals(fa, GroupProtocol.fingerprint(ownKeys(a)))
    }

    @Test fun theGroupMailboxRejectsTamperingAndOtherGroupsKeys() {
        val net = Net()
        val a = Phone("Ann")
        val blob = GroupProtocol.seal(net.keys, GroupProtocol.hello(a.me, net.groupId, Role.FILTERED, 1))
        assertTrue(GroupProtocol.open(net.keys, blob) != null)
        val tampered = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(GroupProtocol.open(net.keys, tampered))
        assertNull(GroupProtocol.open(Net().keys, blob))
    }

    @Test fun garbagePostedToTheMailboxIsSkippedAndNeverThrows() {
        val net = Net()
        for (junk in listOf("", "!!!not base64!!!", "AAAA", "////", "a".repeat(5000), "=")) {
            assertNull("junk <$junk> must be ignored", GroupProtocol.openB64(net.keys, junk))
        }
        val a = Phone("Ann")
        val good = B64.encode(GroupProtocol.seal(net.keys, GroupProtocol.hello(a.me, net.groupId, Role.FILTERED, 1)))
        assertTrue(GroupProtocol.openB64(net.keys, good) != null)
    }

    @Test fun aSignatureBindsTheGroupSoAMessageCantBeReplayedIntoAnotherGroup() {
        val a = Phone("Ann"); val b = Phone("Bo")
        val net1 = makeGroup(a, b)
        val leave = GroupProtocol.leave(b.me, net1.groupId, 77)
        val net2 = makeGroup(a, b)
        net2.post(leave) // signed for group 1
        assertEquals(2, net2.view().state!!.members.size)
        assertEquals(1, net2.view().dropped)
    }
}

class GroupInviteTest {
    @Test fun roundTripsTheCodeAndServer() {
        val secret = Pairing.generateSecret()
        val text = GroupInvite.format(Pairing.encode(secret), "https://ff.example.com")
        val p = GroupInvite.parse(text)!!
        assertTrue(secret.contentEquals(p.secret))
        assertEquals("https://ff.example.com", p.server)
    }

    @Test fun toleratesChatNoiseAndATrailingPunctuation() {
        val secret = Pairing.generateSecret()
        val code = Pairing.encode(secret).lowercase()
        val p = GroupInvite.parse("hey join us!!\n$code\n(server https://ff.example.com/.)")!!
        assertTrue(secret.contentEquals(p.secret))
        assertEquals("https://ff.example.com", p.server)
    }

    @Test fun rejectsAMistypedCodeAndText() {
        val code = Pairing.encode(Pairing.generateSecret())
        val typo = code.replaceFirst(code[0], if (code[0] == 'A') 'B' else 'A')
        assertNull(GroupInvite.parse(typo))
        assertNull(GroupInvite.parse("no code here https://x.example"))
        assertNull(GroupInvite.parse(""))
    }
}
