package app.focusfence.dns

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * How a group of phones (docs/plans/05-group-and-release.md) agrees on who is in it and hands each viewer the
 * pairing secret for each filtered member's report, over **one blind mailbox the server can't read**.
 *
 * - A group has a random **group secret** (the invite). [Pairing.derive] turns it into a mailbox id, a token and an
 *   encryption key, so the server sees ciphertext under an id it can't link to anyone. Everyone who holds the invite
 *   can read and write the group log, so the log itself must not be trusted blindly:
 * - Every message is **signed by the member who sent it** ([message]); [replay] drops anything whose signature or
 *   identity binding fails. Holding the invite lets you *join* (up to [Group.MAX_MEMBERS]) but not speak for anyone.
 * - **Joining is not trust.** A newcomer shows up in the group, but nobody sends them anything until a member
 *   approves them locally after comparing a [fingerprint] out of band ([Local.approved]). A leaked invite therefore
 *   adds a visible stranger, never a viewer.
 * - The log is ordered by the server, and the rules are deterministic ([Group] plus [replay]), so every phone that
 *   folds the same log gets the same group.
 * - Reports themselves keep flowing over the existing per-pair mailboxes ([Pairing]): the group log only carries
 *   membership changes and, sealed to one member's key ([Ecies]), the pairing secret an owner gives a viewer.
 *
 * Pure JVM, no clock and no storage: the caller passes `now`, keeps [Local] and performs the [Action]s.
 */
object GroupProtocol {

    const val V = 1L

    /** A member's long-lived identity for one group: a signing key and a separate key for receiving sealed secrets. */
    class Identity(val memberId: String, val name: String, val sign: KeyPair, val enc: KeyPair) {
        val pubSign: String get() = B64.encode(sign.public.encoded)
        val pubEnc: String get() = B64.encode(enc.public.encoded)
    }

    /** The member id is a hash of the signing key, so an id can't be claimed without that key. */
    fun newIdentity(name: String): Identity {
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val sign = kpg.generateKeyPair()
        val enc = kpg.generateKeyPair()
        return Identity(idFor(sign.public.encoded), name, sign, enc)
    }

    fun idFor(signKeyDer: ByteArray): String = hex(sha256(signKeyDer)).take(32)

    class MemberKeys(val pubSign: PublicKey, val pubEnc: PublicKey, val signDer: ByteArray, val encDer: ByteArray)

    // ---- wire ------------------------------------------------------------------------------------------------

    fun seal(group: Pairing.Keys, msg: Map<String, Any?>): ByteArray =
        Pairing.seal(group.enc, LogJson.encode(msg).toByteArray(), group.mailboxId.toByteArray())

    fun open(group: Pairing.Keys, blob: ByteArray): Map<String, Any?>? =
        Pairing.open(group.enc, blob, group.mailboxId.toByteArray())?.let { LogJson.decode(String(it)) }

    private val FIELDS = mapOf(
        "hello" to listOf("name", "role", "pubSign", "pubEnc"),
        "close" to emptyList(),
        "leave" to emptyList(),
        "remove" to listOf("target"),
        "role" to listOf("role"),
        "viewers" to listOf("viewers"),
        "offer" to listOf("to", "ct"),
    )

    private fun canonical(groupId: String, m: Map<String, Any?>): ByteArray {
        val kind = m["kind"] as String
        val sb = StringBuilder("ffgroup1\n").append(groupId).append('\n').append(kind).append('\n')
            .append(m["by"]).append('\n').append(m["t"]).append('\n')
        for (f in FIELDS.getValue(kind)) sb.append(f).append('=').append(m[f]?.toString().orEmpty()).append('\n')
        return sb.toString().toByteArray()
    }

    /** A signed group message. [extra] is the kind-specific fields from [FIELDS]. */
    fun message(me: Identity, groupId: String, kind: String, now: Long, extra: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("kind" to kind, "v" to V, "by" to me.memberId, "t" to now)
        m.putAll(extra)
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(me.sign.private); update(canonical(groupId, m)); sign() }
        m["sig"] = B64.encode(sig)
        return m
    }

    fun hello(me: Identity, groupId: String, role: Group.Role, now: Long) = message(
        me, groupId, "hello", now,
        mapOf("name" to me.name, "role" to role.name, "pubSign" to me.pubSign, "pubEnc" to me.pubEnc),
    )

    fun leave(me: Identity, groupId: String, now: Long) = message(me, groupId, "leave", now)
    fun close(me: Identity, groupId: String, now: Long) = message(me, groupId, "close", now)
    fun remove(me: Identity, groupId: String, target: String, now: Long) = message(me, groupId, "remove", now, mapOf("target" to target))
    fun role(me: Identity, groupId: String, role: Group.Role, now: Long) = message(me, groupId, "role", now, mapOf("role" to role.name))

    /** null restores the default (everyone sees my report). */
    fun viewers(me: Identity, groupId: String, viewers: Set<String>?, now: Long) =
        message(me, groupId, "viewers", now, mapOf("viewers" to (viewers?.sorted()?.joinToString(",") ?: "*")))

    // ---- replay ----------------------------------------------------------------------------------------------

    class Offer(val from: String, val to: String, val ct: ByteArray) {
        /** A stable name for "this exact offer", so one is never applied twice. */
        val id: String get() = hex(sha256(from.toByteArray() + to.toByteArray() + ct)).take(32)
    }

    class View(
        val state: Group.State?,
        val creatorId: String?,
        val keys: Map<String, MemberKeys>,
        /** True once the creator closed the group to newcomers: later hellos are ignored. */
        val closed: Boolean,
        val offers: List<Offer>,
        /** Human-readable, in order, for every accepted change. */
        val notices: List<String>,
        /** Messages dropped (bad signature, unknown sender, rule violation): worth showing a count of, never acting on. */
        val dropped: Int,
    )

    /**
     * Folds the ordered, already-decrypted group log into the group. Deterministic, and it trusts nothing: a message
     * counts only if it is well formed, signed by the member it names, and allowed by [Group]'s rules at that point.
     */
    fun replay(groupId: String, log: List<Map<String, Any?>>): View {
        var state: Group.State? = null
        var creator: String? = null
        var closed = false
        val keys = LinkedHashMap<String, MemberKeys>()
        val offers = ArrayList<Offer>()
        val notices = ArrayList<String>()
        var dropped = 0

        fun apply(o: Group.Outcome): Boolean = when (o) {
            is Group.Outcome.Ok -> { state = o.change.state; notices += o.change.notices; true }
            is Group.Outcome.Rejected -> false
        }

        for (m in log) {
            val kind = m["kind"] as? String
            val by = m["by"] as? String
            val t = (m["t"] as? Number)?.toLong()
            val sig = m["sig"] as? String
            val fields = kind?.let { FIELDS[it] }
            if (kind == null || by == null || t == null || sig == null || fields == null || m["v"] != V ||
                !wellFormed(m, fields)
            ) { dropped++; continue }

            if (kind == "hello") {
                val signDer = runCatching { B64.decode(m["pubSign"] as String) }.getOrNull()
                val encDer = runCatching { B64.decode(m["pubEnc"] as String) }.getOrNull()
                val role = runCatching { Group.Role.valueOf(m["role"] as String) }.getOrNull()
                val pk = runCatching { Ecies.publicKeyFromSpki(signDer!!) }.getOrNull()
                val ek = runCatching { Ecies.publicKeyFromSpki(encDer!!) }.getOrNull()
                // identity binding + self-signature: the id must be the hash of the key that signed this hello
                if (signDer == null || encDer == null || role == null || pk == null || ek == null ||
                    by != idFor(signDer) || !verify(pk, canonical(groupId, m), sig)
                ) { dropped++; continue }
                val member = Group.Member(by, m["name"] as String, role, t)
                val s = state
                val ok = when {
                    s == null -> { state = Group.create(groupId, member, t); creator = by; true }
                    closed -> false
                    else -> apply(Group.join(s, member, t))
                }
                if (ok) keys[by] = MemberKeys(pk, ek, signDer, encDer) else dropped++
                continue
            }

            val s = state
            val sender = keys[by]
            if (s == null || sender == null || s.members.none { it.id == by } || !verify(sender.pubSign, canonical(groupId, m), sig)) {
                dropped++; continue
            }
            val ok = when (kind) {
                "close" -> if (by == creator) { closed = true; true } else false
                "leave" -> apply(Group.leave(s, by))
                "remove" -> apply(Group.remove(s, by, m["target"] as String))
                "role" -> runCatching { Group.Role.valueOf(m["role"] as String) }.getOrNull()?.let { apply(Group.setRole(s, by, it)) } ?: false
                "viewers" -> {
                    val v = m["viewers"] as String
                    apply(Group.setViewers(s, by, if (v == "*") null else v.split(',').filter { it.isNotEmpty() }.toSet()))
                }
                "offer" -> {
                    val to = m["to"] as String
                    val ct = runCatching { B64.decode(m["ct"] as String) }.getOrNull()
                    if (ct != null && to != by && s.members.any { it.id == to }) { offers += Offer(by, to, ct); true } else false
                }
                else -> false
            }
            if (!ok) dropped++
        }
        return View(state, creator, keys, closed, offers, notices, dropped)
    }

    private fun wellFormed(m: Map<String, Any?>, fields: List<String>): Boolean {
        for (f in fields) {
            val v = m[f] as? String ?: return false
            if (v.length > 2048 || v.any { it.isISOControl() }) return false
        }
        val by = m["by"] as String
        return by.length in 8..64 && by.all { it.isLetterOrDigit() }
    }

    private fun verify(key: PublicKey, data: ByteArray, sigB64: String): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(data); verify(B64.decode(sigB64)) }
    } catch (e: Exception) { false }

    /**
     * 12 digits (about 40 bits) of the member's keys, shown as `482-193-775-208`. Members compare it out of band
     * (in person, a call) before approving; 40 bits is too many to grind a matching key for casually.
     */
    fun fingerprint(k: MemberKeys): String {
        val n = BigInteger(1, sha256(k.signDer + k.encDer).copyOf(8)).mod(BigInteger.TEN.pow(12)).toString().padStart(12, '0')
        return n.chunked(3).joinToString("-")
    }

    // ---- what this phone should do next -----------------------------------------------------------------------

    /**
     * This phone's own bookkeeping, kept by the caller between runs. [approved]: members I trust after comparing
     * fingerprints (the person who invited me is approved on joining). [offered]: viewers I already gave a pairing
     * to. [applied]: offer ids I already turned into a watch. [watching]: owners whose report I watch.
     */
    class Local(
        val approved: Set<String> = emptySet(),
        val offered: Set<String> = emptySet(),
        val applied: Set<String> = emptySet(),
        val watching: Set<String> = emptySet(),
    )

    sealed class Action {
        /** Send this signed message to the group mailbox (the caller [seal]s it). */
        data class Post(val msg: Map<String, Any?>) : Action()
        /** Create my pairing (mailbox, keys) for this viewer from [code]; [Post] carries it to them, sealed. */
        data class PairFor(val viewerId: String, val viewerName: String, val code: String) : Action()
        /** Start watching this owner's report with the pairing [code] they sent me. */
        data class Watch(val ownerId: String, val ownerName: String, val code: String, val offerId: String) : Action()
        /** Stop sending my report to this viewer (they were removed, left, or I narrowed who sees it). */
        data class Unpair(val viewerId: String) : Action()
        /** Stop watching this owner (they left, or no longer share with me). */
        data class Unwatch(val ownerId: String) : Action()
    }

    /** Members I haven't approved yet, with the fingerprint to compare. For the "Sam wants to join" prompt. */
    fun awaitingApproval(view: View, me: Identity, local: Local): List<Pair<Group.Member, String>> {
        val s = view.state ?: return emptyList()
        return s.members.filter { it.id != me.memberId && it.id !in local.approved }
            .mapNotNull { m -> view.keys[m.id]?.let { m to fingerprint(it) } }
    }

    fun plan(view: View, groupId: String, me: Identity, local: Local, now: Long, newSecret: () -> ByteArray = { Pairing.generateSecret() }): List<Action> {
        val s = view.state ?: return emptyList()
        if (s.members.none { it.id == me.memberId }) return emptyList()
        val out = ArrayList<Action>()
        val myViewers = Group.viewersOf(s, me.memberId)

        // pairings I owe: one per approved viewer of my report, handed over sealed to that viewer's key
        for (v in myViewers) {
            if (v !in local.approved || v in local.offered) continue
            val to = view.keys[v] ?: continue
            val name = s.members.first { it.id == v }.name
            val code = Pairing.encode(newSecret())
            val ct = B64.encode(Ecies.encrypt(code.toByteArray(), to.pubEnc))
            out += Action.PairFor(v, name, code)
            out += Action.Post(message(me, groupId, "offer", now, mapOf("to" to v, "ct" to ct)))
        }

        // pairings offered to me: take the newest unapplied offer from each approved owner who shares with me
        for (o in view.offers.filter { it.to == me.memberId }.groupBy { it.from }.mapValues { it.value.last() }.values) {
            if (o.id in local.applied || o.from !in local.approved || me.memberId !in Group.viewersOf(s, o.from)) continue
            val code = runCatching { String(Ecies.decrypt(o.ct, me.enc.private)) }.getOrNull() ?: continue
            if (Pairing.decode(code) == null) continue
            out += Action.Watch(o.from, s.members.first { it.id == o.from }.name, code, o.id)
        }

        // revocations: viewers who no longer see me, owners who no longer share with me
        for (v in local.offered) if (v !in myViewers) out += Action.Unpair(v)
        for (w in local.watching) if (s.members.none { it.id == w } || me.memberId !in Group.viewersOf(s, w)) out += Action.Unwatch(w)
        return out
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
