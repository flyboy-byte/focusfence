package app.focusfence.dns

import java.util.Locale

/**
 * Builders for every log record type, so the schema lives in one place
 * (documented in docs/specs/event-log.md). Each returns a flat field map for
 * [LogJson]; the Android-side store stamps `seq` on append.
 *
 * Two channels, deliberately separate:
 *
 * - **activity** — the accountability/detection record: what happened on the
 *   phone ([dns], [url], [app], [enforce]) and when protection itself changed
 *   state ([service]). Allowed traffic is logged as well as blocked, and a rule
 *   match that did not block (budget not used up, outside a schedule window,
 *   section rule) carries its rule, so the record answers "what happened", not
 *   only "what was stopped". This is what a partner report is built from.
 * - **diag** — how well the app itself is working: [battery] samples, [perf]
 *   windows (latency percentiles, failure counts, event volumes) and [error]s.
 *   Never needed by a partner; needed to decide what to build next.
 */
object LogEvents {

    /** Schema version written into every export header. Bump on breaking changes. */
    const val SCHEMA_VERSION = 1

    const val TYPE_DNS = "dns"
    const val TYPE_URL = "url"
    const val TYPE_APP = "app"
    const val TYPE_SCREEN = "screen"
    /** Experimental: visible text captured from a watched app. */
    const val TYPE_TEXT = "text"
    /** Experimental: a classified screenshot (score; image only if retained). */
    const val TYPE_SHOT = "shot"
    const val TYPE_ENFORCE = "enforce"
    const val TYPE_SERVICE = "svc"
    const val TYPE_INTEGRITY = "integ"
    const val TYPE_BATTERY = "bat"
    const val TYPE_PERF = "perf"
    const val TYPE_THREADS = "thr"
    const val TYPE_ERROR = "err"
    const val TYPE_META = "meta"

    const val DECISION_ALLOWED = "allowed"
    const val DECISION_BLOCKED = "blocked"
    /** Every upstream resolver failed; the client got no answer. */
    const val DECISION_FAILED = "failed"

    const val REASON_RULE = "rule"
    const val REASON_ADS = "ads"
    const val REASON_GUARD = "guard"
    /** Matched a content category (blocked only under a BLOCK policy). */
    const val REASON_CATEGORY = "cat"
    /** Allowed because the user allowlisted it over an imported list. */
    const val REASON_ALLOW = "allow"

    fun ruleMode(rule: BlockRule): String = when {
        rule.hasPathExceptions -> "section"
        rule.hasBudget -> "budget"
        rule.isScheduled -> "window"
        else -> "always"
    }

    /**
     * A DNS query. [reason] says which layer matched ([REASON_RULE], [REASON_ADS],
     * [REASON_GUARD]) or null for no match; [rule] is set whenever a user rule
     * covers the host, even if it did not block. Resolver latency is not
     * per-line here — it is aggregated into diag [perf] windows.
     */
    fun dns(
        t: Long,
        host: String,
        qtype: Int,
        decision: String,
        reason: String? = null,
        rule: BlockRule? = null,
        categories: List<String> = emptyList(),
        /** Requesting app, when known (Device Owner network logs only). */
        pkg: String? = null,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "t" to t, "type" to TYPE_DNS, "host" to host, "qtype" to qtypeName(qtype),
        "decision" to decision,
    ).apply {
        if (reason != null) put("reason", reason)
        if (categories.isNotEmpty()) put("cat", categories.joinToString(","))
        if (pkg != null) put("pkg", pkg)
        if (rule != null) {
            put("rule", rule.domain)
            put("mode", ruleMode(rule))
        }
    }

    /** A browser address seen by the accessibility service. Query/fragment are dropped. */
    /** [cat]: bundle categories the address-bar host matches (a second detection path to DNS). */
    fun url(t: Long, pkg: String, host: String, path: String, cat: String? = null): Map<String, Any?> =
        linkedMapOf<String, Any?>("t" to t, "type" to TYPE_URL, "pkg" to pkg, "host" to host, "path" to path)
            .apply { if (cat != null) put("cat", cat) }

    /** App moved to foreground ("fg") or background ("bg"), from UsageStats. */
    fun app(t: Long, pkg: String, state: String): Map<String, Any?> =
        linkedMapOf("t" to t, "type" to TYPE_APP, "pkg" to pkg, "state" to state)

    /** Experimental: visible text from [pkg], already length-capped by the capturer. */
    /**
     * [hits]: hostnames in the text that match a category bundle, "host:cat,cat;…";
     * [cat]: those categories, comma-joined. Both absent when nothing matched.
     */
    fun text(t: Long, pkg: String, text: String, hits: String? = null, cat: String? = null): Map<String, Any?> =
        linkedMapOf<String, Any?>("t" to t, "type" to TYPE_TEXT, "pkg" to pkg, "text" to text).apply {
            if (hits != null) put("hits", hits)
            if (cat != null) put("cat", cat)
        }

    /**
     * Experimental: one screenshot was classified. [score] is the top risky
     * class probability (null without a model), [label] its class, [kept]
     * whether an encrypted image was retained, under id [shotId].
     */
    fun shot(
        t: Long, pkg: String?, score: Double?, label: String?, kept: Boolean, shotId: String?, model: String?,
        /** Every label's score ([Signals.scoresCompact]) — for tuning the threshold from real data. */
        scores: String? = null,
        flagged: Boolean? = null,
        /** Inference wall time. */
        ms: Long? = null,
        /** "timer" or "event": what triggered the capture. */
        trigger: String? = null,
    ): Map<String, Any?> =
        linkedMapOf<String, Any?>("t" to t, "type" to TYPE_SHOT, "pkg" to pkg, "score" to score, "label" to label, "kept" to kept).apply {
            if (shotId != null) put("id", shotId)
            if (model != null) put("model", model)
            if (scores != null) put("scores", scores)
            if (flagged != null) put("flagged", flagged)
            if (ms != null) put("ms", ms)
            if (trigger != null) put("trigger", trigger)
        }

    /** Screen became interactive ("on") or not ("off"), from UsageStats. */
    fun screen(t: Long, state: String): Map<String, Any?> =
        linkedMapOf("t" to t, "type" to TYPE_SCREEN, "state" to state)

    /** An enforcement action taken outside DNS (app bounce, section Back press). */
    fun enforce(
        t: Long,
        action: String,
        pkg: String,
        host: String? = null,
        path: String? = null,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "t" to t, "type" to TYPE_ENFORCE, "action" to action, "pkg" to pkg,
    ).apply {
        if (host != null) put("host", host)
        if (path != null) put("path", path)
    }

    /**
     * A protection/monitoring component changed state. These are the integrity
     * trail: a gap in coverage must be visible in the log, never silent.
     */
    fun service(t: Long, component: String, state: String, detail: String? = null): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "t" to t, "type" to TYPE_SERVICE, "comp" to component, "state" to state,
        ).apply { if (detail != null) put("detail", detail) }

    /** Diag: one battery/CPU sample. See [BatteryPolicy] for when they are taken. */
    /**
     * A protection component's observed state changed: [key] (e.g. "vpn",
     * "a11y_enabled", "private_dns") moved from [was] (null = first observation)
     * to [value]. Activity channel — this is exactly what a partner must see.
     */
    fun integrity(t: Long, key: String, value: String, was: String?): Map<String, Any?> =
        linkedMapOf("t" to t, "type" to TYPE_INTEGRITY, "key" to key, "value" to value, "was" to was)

    fun battery(t: Long, s: BatterySample): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "t" to t, "type" to TYPE_BATTERY,
            "level" to s.levelPct, "plugged" to s.plugged, "screen" to s.screenOn,
            "cpu" to s.cpuMs, "up" to s.uptimeMs, "pid" to s.pid,
        ).apply {
            if (s.chargeUah != null) put("uah", s.chargeUah)
            if (s.currentUa != null) put("ua", s.currentUa)
            if (s.tempDeciC != null) put("temp", s.tempDeciC)
        }

    /** Diag: counters and latency percentiles accumulated since the previous window. */
    /**
     * Per-thread CPU since the previous battery sample: [top] is
     * [ThreadCpu.encodeTop] ("name=ms;…;other=ms"), [totalMs] the sum, [screen]
     * the screen state the window started in. Diag channel.
     */
    fun threads(t: Long, top: String, totalMs: Long, screen: Boolean): Map<String, Any?> =
        linkedMapOf("t" to t, "type" to TYPE_THREADS, "top" to top, "total" to totalMs, "screen" to screen)

    fun perf(t: Long, windowMs: Long, fields: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>("t" to t, "type" to TYPE_PERF, "win" to windowMs).apply {
            putAll(fields)
        }

    /** Diag: something went wrong that does not by itself stop protection. */
    fun error(t: Long, component: String, what: String, detail: String? = null): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "t" to t, "type" to TYPE_ERROR, "comp" to component, "what" to what,
        ).apply { if (detail != null) put("detail", detail.take(300)) }

    fun qtypeName(qtype: Int): String = when (qtype) {
        1 -> "A"
        28 -> "AAAA"
        5 -> "CNAME"
        12 -> "PTR"
        15 -> "MX"
        16 -> "TXT"
        33 -> "SRV"
        64 -> "SVCB"
        65 -> "HTTPS"
        else -> qtype.toString()
    }

    /**
     * Splits a raw address-bar string into (host, path) for logging, dropping
     * scheme, port, query and fragment — query strings carry search terms and
     * session tokens that add privacy cost and little accountability value.
     * Returns null for text that isn't URL-like (e.g. a half-typed search).
     */
    fun splitUrl(raw: String): Pair<String, String>? {
        val url = raw.trim().lowercase(Locale.ROOT)
        if (url.isEmpty() || url.any { it.isWhitespace() }) return null
        val noScheme = url.substringAfter("://")
        val hostEnd = noScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val hostPart = if (hostEnd >= 0) noScheme.substring(0, hostEnd) else noScheme
        val host = hostPart.substringBefore(':').trim('.')
        if (host.isEmpty() || !host.contains('.')) return null
        if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null
        val rest = if (hostEnd >= 0 && noScheme[hostEnd] == '/') noScheme.substring(hostEnd) else "/"
        val path = rest.substringBefore('?').substringBefore('#').ifEmpty { "/" }
        return host to path
    }
}

/** Collapses repeated reads of the same address bar into one log line per page. */
class UrlDeduper {
    private var lastPkg: String? = null
    private var lastKey: String? = null

    /** True if ([pkg], [host], [path]) differs from the previously accepted URL. */
    @Synchronized
    fun accept(pkg: String, host: String, path: String): Boolean {
        val key = "$host$path"
        if (pkg == lastPkg && key == lastKey) return false
        lastPkg = pkg
        lastKey = key
        return true
    }
}
