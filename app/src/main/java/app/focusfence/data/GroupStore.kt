package app.focusfence.data

import android.content.Context
import app.focusfence.dns.B64
import app.focusfence.dns.Group
import app.focusfence.dns.GroupProtocol
import app.focusfence.dns.LogJson
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * This phone's membership in ONE group (docs/plans/05-group-and-release.md): the group secret, this member's keys,
 * its own bookkeeping ([GroupProtocol.Local]), the decrypted group log and an outbox of signed messages waiting to be
 * sent. Everything is in `noBackupFilesDir`, never in cloud backups: the group secret and the private keys are
 * exactly what must not leave the phone. The pure rules live in `dns/Group*`; this is only storage.
 */
object GroupStore {

    data class State(
        val code: String,
        val name: String,
        val role: Group.Role,
        val signPriv: String, val signPub: String, val encPriv: String, val encPub: String,
        val cursor: Long = 0,
        val boxReady: Boolean = false,
        val approved: Set<String> = emptySet(),
        val offered: Set<String> = emptySet(),
        val applied: Set<String> = emptySet(),
        val watching: Set<String> = emptySet(),
        /** viewerId -> the pairing code I made for them; ownerId -> the one they made for me. */
        val pairings: Map<String, String> = emptyMap(),
        val watches: Map<String, String> = emptyMap(),
        val noticesSeen: Int = 0,
        val prompted: Set<String> = emptySet(),
        /** A joiner trusts whoever invited them: the creator is approved the first time they appear. */
        val autoApproveCreator: Boolean = false,
        /** Set once this phone is no longer a member (removed, or its hello was refused). */
        val ended: Boolean = false,
    ) {
        val identity: GroupProtocol.Identity by lazy {
            val kf = KeyFactory.getInstance("EC")
            val sign = KeyPair(kf.generatePublic(X509EncodedKeySpec(B64.decode(signPub))), kf.generatePrivate(PKCS8EncodedKeySpec(B64.decode(signPriv))))
            val enc = KeyPair(kf.generatePublic(X509EncodedKeySpec(B64.decode(encPub))), kf.generatePrivate(PKCS8EncodedKeySpec(B64.decode(encPriv))))
            GroupProtocol.Identity(GroupProtocol.idFor(sign.public.encoded), name, sign, enc)
        }
        val local: GroupProtocol.Local get() = GroupProtocol.Local(approved, offered, applied, watching)
    }

    private fun dir(c: Context) = File(c.noBackupFilesDir, "group").apply { mkdirs() }
    private fun stateFile(c: Context) = File(dir(c), "state.json")
    private fun logFile(c: Context) = File(dir(c), "log.jsonl")
    private fun outboxFile(c: Context) = File(dir(c), "outbox.jsonl")

    fun active(c: Context): Boolean = stateFile(c).exists()

    @Synchronized
    fun load(c: Context): State? {
        val f = stateFile(c).takeIf { it.exists() } ?: return null
        val m = LogJson.decode(f.readText()) ?: return null
        fun set(k: String) = (m[k] as? String).orEmpty().split(',').filter { it.isNotEmpty() }.toSet()
        fun map(k: String) = (m[k] as? String).orEmpty().split(',').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        return State(
            code = m["code"] as? String ?: return null,
            name = m["name"] as? String ?: "",
            role = runCatching { Group.Role.valueOf(m["role"] as String) }.getOrDefault(Group.Role.FILTERED),
            signPriv = m["sp"] as? String ?: return null, signPub = m["su"] as? String ?: return null,
            encPriv = m["ep"] as? String ?: return null, encPub = m["eu"] as? String ?: return null,
            cursor = (m["cursor"] as? Number)?.toLong() ?: 0,
            boxReady = m["box"] == true,
            approved = set("approved"), offered = set("offered"), applied = set("applied"), watching = set("watching"),
            pairings = map("pairings"), watches = map("watches"),
            noticesSeen = (m["seen"] as? Number)?.toInt() ?: 0,
            prompted = set("prompted"),
            autoApproveCreator = m["auto"] == true,
            ended = m["ended"] == true,
        )
    }

    @Synchronized
    fun save(c: Context, s: State) {
        val text = LogJson.encode(linkedMapOf(
            "code" to s.code, "name" to s.name, "role" to s.role.name,
            "sp" to s.signPriv, "su" to s.signPub, "ep" to s.encPriv, "eu" to s.encPub,
            "cursor" to s.cursor, "box" to s.boxReady,
            "approved" to s.approved.joinToString(","), "offered" to s.offered.joinToString(","),
            "applied" to s.applied.joinToString(","), "watching" to s.watching.joinToString(","),
            "pairings" to s.pairings.entries.joinToString(",") { "${it.key}=${it.value}" },
            "watches" to s.watches.entries.joinToString(",") { "${it.key}=${it.value}" },
            "seen" to s.noticesSeen, "prompted" to s.prompted.joinToString(","),
            "auto" to s.autoApproveCreator, "ended" to s.ended,
        ))
        // temp + rename: a crash mid-write must not lose the keys
        val tmp = File(dir(c), "state.json.tmp")
        tmp.writeText(text)
        tmp.renameTo(stateFile(c))
    }

    @Synchronized
    fun clear(c: Context) {
        dir(c).deleteRecursively()
    }

    /** A fresh identity for a new membership. */
    fun newState(c: Context, code: String, name: String, role: Group.Role, joiner: Boolean): State {
        val id = GroupProtocol.newIdentity(name)
        val s = State(
            code = code, name = name, role = role,
            signPriv = B64.encode(id.sign.private.encoded), signPub = B64.encode(id.sign.public.encoded),
            encPriv = B64.encode(id.enc.private.encoded), encPub = B64.encode(id.enc.public.encoded),
            autoApproveCreator = joiner,
        )
        save(c, s)
        return s
    }

    // ---- decrypted log (append only) and outbox -----------------------------------------------------------

    @Synchronized fun appendLog(c: Context, msg: Map<String, Any?>) { logFile(c).appendText(LogJson.encode(msg) + "\n") }

    @Synchronized fun readLog(c: Context): List<Map<String, Any?>> =
        logFile(c).takeIf { it.exists() }?.useLines { seq -> seq.mapNotNull { LogJson.decode(it) }.toList() } ?: emptyList()

    @Synchronized fun addOutbox(c: Context, msg: Map<String, Any?>) { outboxFile(c).appendText(LogJson.encode(msg) + "\n") }

    @Synchronized fun outbox(c: Context): List<Map<String, Any?>> =
        outboxFile(c).takeIf { it.exists() }?.useLines { seq -> seq.mapNotNull { LogJson.decode(it) }.toList() } ?: emptyList()

    @Synchronized fun setOutbox(c: Context, msgs: List<Map<String, Any?>>) {
        if (msgs.isEmpty()) outboxFile(c).delete() else outboxFile(c).writeText(msgs.joinToString("\n", postfix = "\n") { LogJson.encode(it) })
    }
}
