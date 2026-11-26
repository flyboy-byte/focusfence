package app.focusfence.server

import kotlinx.serialization.Serializable

/** Every group request is signed by a member phone. Fields beyond the signature depend on the action. */
@Serializable data class GroupRequest(
    val deviceId: String, val t: Long, val signature: String,
    val groupId: String = "", val name: String = "", val code: String = "", val level: String = "",
    val ownerDeviceId: String = "", val partnerId: String = "", val publicKeySpki: String = "", val label: String = "",
)
@Serializable data class GroupCodeResponse(val groupId: String, val name: String, val code: String, val expiresMs: Long, val qrUrl: String)
@Serializable data class LookupRequest(val code: String)
/** [kind] "group" or "invite", so the app's one code box can say which before joining. */
@Serializable data class LookupResponse(val kind: String, val name: String, val members: List<String> = emptyList(), val full: Boolean = false)
/** A pair a group made in which this phone reads [name]'s report and hasn't sent its device key yet. */
@Serializable data class GroupPair(val ownerDeviceId: String, val partnerId: String, val token: String, val name: String, val groupName: String)
@Serializable data class GroupKeyResponse(val deviceId: String)
/** [theirToken]: the link of this member's report as this phone reads it, so the app finds its own copy. */
@Serializable data class GroupMemberInfo(val name: String, val me: Boolean, val joinedMs: Long, val theirToken: String = "", val lastViewMs: Long = 0)
@Serializable data class GroupInfo(val id: String, val name: String, val members: List<GroupMemberInfo>, val codeExpiresMs: Long, val max: Int, val level: String)
@Serializable data class GroupsResponse(val groups: List<GroupInfo>)

/** A refusal the phone shows as is. */
class GroupError(message: String, val status: Int = 409) : Exception(message)

/**
 * Plan 14. A group is a set of plan 13 partner pairs made both ways: joining makes the joiner a partner on every member's
 * phone and every member a partner on the joiner's. Reports, keys and alerts then travel exactly as for any partner. A
 * pair two groups (or a group and an invite) both made is one pair, kept while any reason for it remains.
 */
class Groups(private val store: Store, private val cfg: Config) {

    private fun err(msg: String): Nothing = throw GroupError(msg)

    private fun memberName(g: Store.Group, deviceId: String) = g.members.firstOrNull { it.deviceId == deviceId }?.name.orEmpty().ifEmpty { "A member" }

    private fun member(g: Store.Group?, deviceId: String): Store.Group =
        g?.takeIf { it.members.any { m -> m.deviceId == deviceId } } ?: throw GroupError("You're not in that group.", 404)

    /** A new code replaces the group's old one. */
    private fun code(g: Store.Group, now: Long): String {
        val code = newInviteCode()
        g.codeHash = Integrity.sha256(code)
        g.codeExpiresMs = now + CODE_TTL_MS
        g.lastChangeMs = now
        return code
    }

    private fun response(g: Store.Group, code: String) =
        GroupCodeResponse(g.id, g.name, code.chunked(4).joinToString("-"), g.codeExpiresMs, "${cfg.publicUrl}/c/$code")

    fun create(device: Store.Device, name: String, myName: String): GroupCodeResponse {
        val now = cfg.now()
        if (name.isBlank()) err("Give the group a name.")
        if (store.groupsOf(device.id).size >= MAX_GROUPS) err("You're already in $MAX_GROUPS groups, the most a phone can be in.")
        val me = myName.ifBlank { device.ownerName }.ifBlank { "Me" }
        val g = Store.Group(java.util.UUID.randomUUID().toString(), name, listOf(Store.GroupMember(device.id, me, now)), createdMs = now)
        val c = code(g, now)
        store.saveGroup(g)
        if (myName.isNotBlank()) store.updateDevice(device.id) { if (it.ownerName.isEmpty()) it.ownerName = myName }
        return response(g, c)
    }

    fun newCode(device: Store.Device, groupId: String): GroupCodeResponse {
        member(store.group(groupId), device.id)
        val now = cfg.now()
        return store.updateGroup(groupId) { g ->
            if (g.members.size >= MAX_MEMBERS) err("The group is full ($MAX_MEMBERS people).")
            response(g, code(g, now))
        } ?: err("That group is gone.")
    }

    fun lookup(code: String): Store.Group? = normalizeCode(code).takeIf { it.length == 8 }?.let { store.groupByCode(Integrity.sha256(it), cfg.now()) }

    fun join(device: Store.Device, code: String, myName: String): Store.Group {
        val now = cfg.now()
        val found = lookup(code) ?: throw GroupError("That code isn't valid. It may have expired; ask for a new one.", 404)
        if (found.members.any { it.deviceId == device.id }) err("You're already in ${found.name}.")
        if (store.groupsOf(device.id).size >= MAX_GROUPS) err("You're already in $MAX_GROUPS groups, the most a phone can be in.")
        val me = myName.ifBlank { device.ownerName }.ifBlank { "Member" }
        val joined = store.updateGroup(found.id) { g ->
            if (g.members.any { it.deviceId == device.id }) err("You're already in ${g.name}.")
            if (g.members.size >= MAX_MEMBERS) err("${g.name} is full ($MAX_MEMBERS people).")
            if (!g.codeOpen(now)) err("That code just ran out. Ask for a new one.")
            // Each side gains at most one partner per other member: refuse before anyone is paired, not halfway.
            val others = g.members.map { it.deviceId }.filter { store.device(it) != null }
            if (watchers(device.id) + others.count { o -> store.device(device.id)?.partners?.none { it.isPhone(o) } != false } > MAX_WATCHERS)
                err("Too many people already see your report ($MAX_WATCHERS at most).")
            if (others.any { o -> watchers(o) >= MAX_WATCHERS && store.device(o)?.partners?.none { it.isPhone(device.id) } != false })
                err("Someone in ${g.name} already has the most people watching ($MAX_WATCHERS).")
            g.members = g.members + Store.GroupMember(device.id, me, now)
            g.lastChangeMs = now
            if (g.members.size >= MAX_MEMBERS) { g.codeHash = ""; g.codeExpiresMs = 0 }
            g.copy()
        } ?: throw GroupError("That group is gone.", 404)
        for (m in joined.members) if (m.deviceId != device.id && store.device(m.deviceId) != null) {
            pair(m.deviceId, device.id, joined, me)
            pair(device.id, m.deviceId, joined, m.name)
            store.addOwnerNotice(m.deviceId, "$me joined ${joined.name}.", now, "group_joined")
        }
        if (myName.isNotBlank()) store.updateDevice(device.id) { if (it.ownerName.isEmpty()) it.ownerName = myName }
        return joined
    }

    private fun watchers(deviceId: String) = store.device(deviceId)?.partners?.size ?: 0

    /** [readerId]'s phone becomes a partner on [ownerId]'s, or the existing pair gains this group as a reason. */
    private fun pair(ownerId: String, readerId: String, g: Store.Group, readerName: String) = store.updateDevice(ownerId) { d ->
        val p = d.partners.firstOrNull { it.isPhone(readerId) }
        if (p != null) {
            p.groupIds = (p.groupIds + g.id).distinct()
            p.memberDeviceId = readerId
        } else {
            d.partners = d.partners + Store.Partner(java.util.UUID.randomUUID().toString(), readerName, newToken(), cfg.now(),
                memberDeviceId = readerId, groupIds = listOf(g.id), alertLevel = app.focusfence.dns.AlertPrefs.Level.IMPORTANT.key)
        }
    }

    /** Drops [groupId] as a reason; a pair with none left goes, and the owner's phone starts a new report key. */
    private fun unpair(ownerId: String, readerId: String, groupId: String) = store.updateDevice(ownerId) { d ->
        val p = d.partners.firstOrNull { it.isPhone(readerId) && groupId in it.groupIds } ?: return@updateDevice
        p.groupIds = p.groupIds - groupId
        if (p.groupIds.isEmpty() && !p.chosen) {
            d.partners = d.partners.filter { it.id != p.id }
            d.rotateKey = true
        }
    }

    fun leave(device: Store.Device, groupId: String) {
        val g = member(store.group(groupId), device.id)
        val me = memberName(g, device.id)
        store.updateGroup(groupId) { it.members = it.members.filter { m -> m.deviceId != device.id } }
        val now = cfg.now()
        for (m in g.members) if (m.deviceId != device.id) {
            unpair(m.deviceId, device.id, groupId)
            unpair(device.id, m.deviceId, groupId)
            store.addOwnerNotice(m.deviceId, "$me left ${g.name}.", now, "group_left")
        }
    }

    /** Pairs where this phone reads someone's report and hasn't sent a device key for it yet. */
    fun pending(deviceId: String): List<GroupPair> = store.groupsOf(deviceId).flatMap { g ->
        g.members.filter { it.deviceId != deviceId }.mapNotNull { m ->
            store.device(m.deviceId)?.partners?.firstOrNull { it.memberDeviceId == deviceId && it.devices.isEmpty() && g.id in it.groupIds }
                ?.let { GroupPair(m.deviceId, it.id, it.token, m.name, g.name) }
        }
    }.distinctBy { it.partnerId }

    /** This phone's key for reading [partnerId]'s pair on [ownerId]'s phone. Only the member it was made for can add it. */
    fun addKey(device: Store.Device, ownerId: String, partnerId: String, spki: String, label: String): String {
        val pd = Store.PartnerDevice(java.util.UUID.randomUUID().toString(), Store.KIND_APP, label.ifEmpty { "Phone" }, spki, cfg.now())
        var ok = false
        store.updateDevice(ownerId) { d ->
            d.partners.firstOrNull { it.id == partnerId && it.memberDeviceId == device.id && it.devices.isEmpty() }?.let { it.devices = listOf(pd); ok = true }
        }
        if (!ok) throw GroupError("unknown pair", 404)
        return pd.id
    }

    fun list(device: Store.Device): GroupsResponse {
        val now = cfg.now()
        val mine = store.device(device.id) ?: device
        return GroupsResponse(store.groupsOf(device.id).sortedBy { it.createdMs }.map { g ->
            var level = ""
            val members = g.members.mapNotNull { m ->
                if (m.deviceId == device.id) return@mapNotNull GroupMemberInfo(m.name, true, m.joinedMs)
                val other = store.device(m.deviceId) ?: return@mapNotNull null
                val reading = other.partners.firstOrNull { it.isPhone(device.id) }
                if (reading != null && level.isEmpty()) level = reading.alertLevel
                GroupMemberInfo(m.name, false, m.joinedMs, reading?.token.orEmpty(),
                    mine.partners.firstOrNull { it.isPhone(m.deviceId) }?.lastViewMs ?: 0)
            }
            GroupInfo(g.id, g.name, members, if (g.codeOpen(now)) g.codeExpiresMs else 0, MAX_MEMBERS, level.ifEmpty { app.focusfence.dns.AlertPrefs.Level.IMPORTANT.key })
        })
    }

    /** How this phone hears about the group's members: its alert setting on each pair it reads in this group. */
    fun setLevel(device: Store.Device, groupId: String, level: String) {
        val g = member(store.group(groupId), device.id)
        val key = app.focusfence.dns.AlertPrefs.Level.fromKey(level).key
        for (m in g.members) if (m.deviceId != device.id) store.updateDevice(m.deviceId) { d ->
            d.partners.firstOrNull { it.isPhone(device.id) && groupId in it.groupIds }?.alertLevel = key
        }
    }

    /** A group of this phone's is being joined right now: check in every minute so keys arrive within one. */
    fun busy(deviceId: String): Boolean {
        val now = cfg.now()
        return store.groupsOf(deviceId).any { it.codeOpen(now) && now - it.lastChangeMs < BUSY_MS }
    }

    /** The first group that made this pair, for wording ("Sam joined Tuesday group"); empty for a partner by invite. */
    fun groupName(p: Store.Partner): String = if (p.chosen) "" else p.groupIds.firstOrNull()?.let { store.group(it)?.name }.orEmpty()

    companion object {
        const val MAX_MEMBERS = 6
        const val MAX_GROUPS = 4
        /** People who can see one phone's report, private partners and group members together. */
        const val MAX_WATCHERS = 20
        const val CODE_TTL_MS = 24 * 3_600_000L
        const val BUSY_MS = 30 * 60_000L
    }
}
