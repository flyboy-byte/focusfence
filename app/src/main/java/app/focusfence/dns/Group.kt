package app.focusfence.dns

/**
 * Group accountability rules (docs/plans/05-group-and-release.md): 2 to [MAX_MEMBERS] phones, each a member with a
 * role. Pure and clock-free; storage, the join protocol and the UI sit on top of this, so the rules that matter
 * (the cap, who can see whom, which changes weaken accountability) are tested on the JVM.
 *
 * Roles: a [Role.FILTERED] member runs the filter and uploads their own report; a [Role.PARTNER] member does not
 * (no VPN, nothing uploaded) and only watches. **Everyone in the group watches every filtered member by default**
 * (owner's decision, 2026-11-23); a filtered member may narrow who sees theirs ([setViewers]). Any viewer of a
 * filtered member can issue that member's unlock code ([unlockersOf]).
 *
 * Every change returns notices for the whole group: nobody joins, leaves, is removed or loosens their own
 * accountability silently. Changes that weaken accountability are marked [Change.loosens] so the UI sends them
 * through the friction guard, as removing a partner already is.
 */
object Group {

    const val MAX_MEMBERS = 4
    const val MAX_NAME = 40

    enum class Role { FILTERED, PARTNER }

    data class Member(
        val id: String,
        val name: String,
        val role: Role,
        val joinedMs: Long,
        /** Who may see this member's report: null = every other member (the default); only meaningful when FILTERED. */
        val viewers: Set<String>? = null,
    )

    data class State(val groupId: String, val createdMs: Long, val members: List<Member>)

    sealed class Outcome {
        data class Ok(val change: Change) : Outcome()
        data class Rejected(val reason: String) : Outcome()
    }

    /** [notices] go to every member (including the one who acted) and into the activity log. */
    data class Change(val state: State, val notices: List<String>, val loosens: Boolean)

    fun create(groupId: String, creator: Member, now: Long): State =
        State(groupId, now, listOf(creator.copy(name = clean(creator.name), joinedMs = now, viewers = null)))

    fun join(state: State, member: Member, now: Long): Outcome {
        if (state.members.size >= MAX_MEMBERS) return rejected("This group already has $MAX_MEMBERS members.")
        if (state.members.any { it.id == member.id }) return rejected("That phone is already in the group.")
        val name = clean(member.name)
        if (name.isEmpty()) return rejected("Enter a name.")
        if (state.members.any { it.name.equals(name, ignoreCase = true) }) return rejected("Someone in the group already uses that name.")
        val m = member.copy(name = name, joinedMs = now, viewers = null)
        return ok(state.copy(members = state.members + m), "$name joined the group as ${label(m.role)}.", loosens = false)
    }

    /** A member leaves on their own. A filtered member leaving takes their report away from everyone, so it loosens. */
    fun leave(state: State, memberId: String): Outcome {
        val m = state.members.firstOrNull { it.id == memberId } ?: return rejected("That phone isn't in the group.")
        return ok(without(state, memberId), "${m.name} left the group.", loosens = m.role == Role.FILTERED)
    }

    /**
     * [byId] removes [targetId]. Allowed for any member (a group isn't a hierarchy), but it is never silent, and it loosens
     * when the removed member was watching a filtered member (they lose a viewer) or was filtered themselves.
     */
    fun remove(state: State, byId: String, targetId: String): Outcome {
        val by = state.members.firstOrNull { it.id == byId } ?: return rejected("That phone isn't in the group.")
        val target = state.members.firstOrNull { it.id == targetId } ?: return rejected("That phone isn't in the group.")
        if (byId == targetId) return leave(state, byId)
        val losesAViewer = state.members.any { it.role == Role.FILTERED && it.id != targetId && targetId in viewersOf(state, it.id) }
        return ok(without(state, targetId), "${target.name} was removed from the group by ${by.name}.", loosens = target.role == Role.FILTERED || losesAViewer)
    }

    /** Switching from filtered to partner stops this phone's filter and report, so it loosens; the other way tightens. */
    fun setRole(state: State, memberId: String, role: Role): Outcome {
        val m = state.members.firstOrNull { it.id == memberId } ?: return rejected("That phone isn't in the group.")
        if (m.role == role) return rejected("Already ${label(role)}.")
        val next = m.copy(role = role, viewers = if (role == Role.FILTERED) m.viewers else null)
        return ok(replace(state, next), "${m.name} is now ${label(role)}.", loosens = m.role == Role.FILTERED)
    }

    /**
     * A filtered member chooses who sees their report: null restores the default (every other member). Narrowing loosens.
     * A viewer id that isn't a member, or the member themselves, is ignored.
     */
    fun setViewers(state: State, memberId: String, viewers: Set<String>?): Outcome {
        val m = state.members.firstOrNull { it.id == memberId } ?: return rejected("That phone isn't in the group.")
        if (m.role != Role.FILTERED) return rejected("Only a filtered member has a report to share.")
        val ids = state.members.map { it.id }.toSet() - memberId
        val chosen = viewers?.intersect(ids)
        val before = viewersOf(state, memberId)
        val after = chosen ?: ids
        if (before == after) return rejected("No change.")
        val next = m.copy(viewers = chosen)
        val text = if (chosen == null) "${m.name} lets everyone in the group see their report."
        else "${m.name} now shows their report to ${chosen.size} of ${ids.size} other members."
        return ok(replace(state, next), text, loosens = !after.containsAll(before))
    }

    /** Members who can see [memberId]'s report: everyone else by default, or the chosen subset. Empty for a partner-only member. */
    fun viewersOf(state: State, memberId: String): Set<String> {
        val m = state.members.firstOrNull { it.id == memberId } ?: return emptySet()
        if (m.role != Role.FILTERED) return emptySet()
        val others = state.members.map { it.id }.toSet() - memberId
        return m.viewers?.intersect(others) ?: others
    }

    /** The filtered members [memberId] can see. */
    fun watchedBy(state: State, memberId: String): List<Member> =
        state.members.filter { it.role == Role.FILTERED && memberId in viewersOf(state, it.id) }

    /** Who can issue [memberId]'s unlock code: any viewer of their report. */
    fun unlockersOf(state: State, memberId: String): Set<String> = viewersOf(state, memberId)

    /** (owner, viewer) pairs that each need a pairing secret: one per filtered member per viewer. */
    fun pairingsNeeded(state: State): List<Pair<String, String>> =
        state.members.filter { it.role == Role.FILTERED }.flatMap { o -> viewersOf(state, o.id).map { o.id to it } }

    /** A group needs two people to mean anything. */
    fun isActive(state: State): Boolean = state.members.size >= 2

    private fun label(r: Role) = if (r == Role.FILTERED) "filtered" else "partner-only"
    private fun clean(name: String) = name.filter { !it.isISOControl() }.trim().take(MAX_NAME)
    private fun rejected(why: String) = Outcome.Rejected(why)
    private fun ok(state: State, notice: String, loosens: Boolean) = Outcome.Ok(Change(state, listOf(notice), loosens))
    private fun replace(s: State, m: Member) = s.copy(members = s.members.map { if (it.id == m.id) m else it })
    /** Drops a member and removes them from everyone's chosen-viewer lists. */
    private fun without(s: State, id: String) =
        s.copy(members = s.members.filter { it.id != id }.map { it.copy(viewers = it.viewers?.minus(id)) })
}
