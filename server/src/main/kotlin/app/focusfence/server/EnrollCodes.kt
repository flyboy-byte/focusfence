package app.focusfence.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Per-person enrollment codes. Only a SHA-256 of each code is stored; the plain code is
 * shown once when it is created. The single `FF_ENROLL_TOKEN` stays valid as a bootstrap code.
 * Kept in `codes.json` in the data dir; the CLI (`admin codes ...`) edits the same file the
 * running server reads, so a change applies on the next enroll with no restart.
 */
class EnrollCodes(private val file: File) {
    @Serializable
    data class Code(
        val id: String,
        val label: String,
        val hash: String,
        val createdMs: Long,
        val expiresMs: Long = 0,   // 0 = never
        val maxUses: Int = 0,      // 0 = unlimited
        val uses: Int = 0,
        val revoked: Boolean = false,
        val lastUsedMs: Long = 0,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ser = ListSerializer(Code.serializer())

    fun list(): List<Code> = synchronized(this) {
        if (!file.exists()) emptyList() else runCatching { json.decodeFromString(ser, file.readText()) }.getOrDefault(emptyList())
    }

    /** Whether any per-person code exists, which makes enrollment closed even with no bootstrap token. */
    fun any(): Boolean = list().isNotEmpty()

    private fun save(codes: List<Code>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(ser, codes))
        java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    /** Returns the plain code (shown once) and its record. */
    fun create(label: String, now: Long, expiresMs: Long = 0, maxUses: Int = 0): Pair<String, Code> = synchronized(this) {
        val plain = newInviteCode() + newInviteCode()
        val code = Code(newInviteCode().lowercase(), label.take(60), Integrity.sha256(normalizeCode(plain)), now, expiresMs, maxUses)
        save(list() + code)
        plain.chunked(4).joinToString("-") to code
    }

    fun revoke(id: String): Boolean = synchronized(this) {
        val all = list()
        if (all.none { it.id == id }) return false
        save(all.map { if (it.id == id) it.copy(revoked = true) else it })
        true
    }

    /** True if [given] is live; counts a use. Constant-time over the stored hashes. */
    fun redeem(given: String, now: Long): Boolean = redeemId(given, now) != null

    /** Like [redeem] but returns the code's id so the device can record which code admitted it. */
    fun redeemId(given: String, now: Long): String? = synchronized(this) {
        val h = Integrity.sha256(normalizeCode(given)).toByteArray()
        val all = list()
        var hit: Code? = null
        for (c in all) if (java.security.MessageDigest.isEqual(h, c.hash.toByteArray()) && hit == null) hit = c
        val c = hit ?: return null
        if (c.revoked || (c.expiresMs != 0L && now > c.expiresMs) || (c.maxUses != 0 && c.uses >= c.maxUses)) return null
        save(all.map { if (it.id == c.id) it.copy(uses = it.uses + 1, lastUsedMs = now) else it })
        c.id
    }
}

/** `focusfence-server admin codes add <label> [--days N] [--uses N] | list | revoke <id>`; returns false if [args] is not an admin command. */
fun runAdmin(args: Array<String>, dataDir: File, now: Long, out: (String) -> Unit): Boolean {
    if (args.size >= 2 && args[0] == "admin" && args[1] == "login-link") {
        val email = args.getOrNull(2)?.trim()?.lowercase()
        if (email.isNullOrEmpty()) { out("usage: admin login-link <admin email>"); return true }
        val base = (System.getenv("FF_PUBLIC_URL") ?: "http://localhost:8080").trimEnd('/')
        out("one-time sign-in link for $email (15 minutes; the authenticator is still asked):\n  $base/admin/link/${AdminLinks.create(dataDir, email, now)}")
        return true
    }
    if (args.size < 2 || args[0] != "admin" || args[1] != "codes") return false
    val codes = EnrollCodes(File(dataDir, "codes.json"))
    when (args.getOrNull(2)) {
        "add" -> {
            val label = args.getOrNull(3) ?: return true.also { out("usage: admin codes add <label> [--days N] [--uses N]") }
            fun opt(n: String) = args.indexOf(n).takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toLongOrNull() }
            val exp = opt("--days")?.let { now + it * 86_400_000 } ?: 0
            val (plain, c) = codes.create(label, now, exp, (opt("--uses") ?: 0).toInt())
            out("code for \"${c.label}\" (id ${c.id}), shown once:\n  $plain")
        }
        "list" -> codes.list().forEach { out("${it.id}  ${it.label}  uses ${it.uses}/${if (it.maxUses == 0) "∞" else it.maxUses}  ${if (it.revoked) "REVOKED" else if (it.expiresMs != 0L && now > it.expiresMs) "expired" else "live"}") }
        "revoke" -> out(if (codes.revoke(args.getOrNull(3) ?: "")) "revoked" else "no such id")
        else -> out("usage: admin codes add <label> [--days N] [--uses N] | list | revoke <id>")
    }
    return true
}
