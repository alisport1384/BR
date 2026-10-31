package com.bigrocket.config

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** Reads Xray / V2Ray JSON: a full client config, a list of them, or a bare outbound. */
internal object JsonConfigParser {

    private val NON_PROXY_PROTOCOLS = setOf("freedom", "blackhole", "dns", "loopback")

    fun parse(text: String): List<ParsedConfig> {
        val root = try {
            JSONTokener(text).nextValue()
        } catch (_: JSONException) {
            throw ConfigParseException("خواندن JSON ممکن نیست")
        }
        val configs: List<JSONObject> = when (root) {
            is JSONArray -> (0 until root.length()).mapNotNull { root.optJSONObject(it) }
            is JSONObject -> listOf(root)
            else -> emptyList()
        }
        val out = configs.mapNotNull { cfg ->
            val outbound = pickOutbound(cfg) ?: return@mapNotNull null
            fromOutbound(outbound, cfg.optString("remarks", ""))
        }
        if (out.isEmpty()) throw ConfigParseException("هیچ outbound پروکسی معتبری در کانفیگ JSON یافت نشد")
        return out
    }

    /** The proxy outbound of a full config (tag `proxy` first), or [cfg] itself when it IS an outbound. */
    fun pickOutbound(cfg: JSONObject): JSONObject? {
        if (cfg.has("protocol") && !cfg.has("outbounds")) {
            return cfg.takeIf { isProxy(it) }
        }
        val array = cfg.optJSONArray("outbounds") ?: return null
        val proxies = (0 until array.length()).mapNotNull { array.optJSONObject(it) }.filter { isProxy(it) }
        return proxies.firstOrNull { it.optString("tag") == "proxy" } ?: proxies.firstOrNull()
    }

    private fun isProxy(o: JSONObject): Boolean {
        val p = o.optString("protocol").lowercase()
        return p.isNotEmpty() && p !in NON_PROXY_PROTOCOLS
    }

    /**
     * Cleans an outbound taken from someone else's config: the tag, `proxySettings`,
     * `sendThrough`, any `dialerProxy` / `mark` / `interface` in sockopt and the
     * client-only `show` flag are dropped. Transport, TLS/REALITY and the rest are kept.
     * Returns a deep copy; [o] is never modified.
     */
    fun sanitize(o: JSONObject): JSONObject {
        val copy = JSONObject(o.toString())
        for (key in listOf("tag", "proxySettings", "sendThrough")) copy.remove(key)
        copy.optJSONObject("streamSettings")?.let { stream ->
            stream.optJSONObject("sockopt")?.let { so ->
                for (key in listOf("dialerProxy", "mark", "interface")) so.remove(key)
                if (so.length() == 0) stream.remove("sockopt")
            }
            stream.optJSONObject("tlsSettings")?.remove("show")
            stream.optJSONObject("realitySettings")?.remove("show")
        }
        return copy
    }

    private fun fromOutbound(outbound: JSONObject, remarks: String): ParsedConfig {
        val clean = sanitize(outbound)
        val protocol = clean.optString("protocol").lowercase()
        val (host, port) = serverOf(clean)
        return ParsedConfig(
            name = remarks.trim().ifBlank { "$protocol $host" }.take(ParsedConfig.MAX_NAME),
            protocol = protocol,
            outbound = clean,
            server = if (host.isEmpty()) "" else LinkSyntax.hostPort(host, port),
        )
    }

    /** The first server address and port named in an outbound, for display. */
    fun serverOf(o: JSONObject): Pair<String, Int> {
        val settings = o.optJSONObject("settings") ?: return "" to 0
        val entries = settings.optJSONArray("vnext") ?: settings.optJSONArray("servers")
        val entry = entries?.optJSONObject(0)
        if (entry != null) return entry.optString("address") to portOf(entry.opt("port"))
        if (settings.has("address")) return settings.optString("address") to portOf(settings.opt("port"))
        val endpoint = settings.optJSONArray("peers")?.optJSONObject(0)?.optString("endpoint").orEmpty()
        if (endpoint.isEmpty()) return "" to 0
        return runCatching { LinkSyntax.splitHostPort(endpoint) }.getOrDefault(endpoint to 0)
    }

    private fun portOf(value: Any?): Int = when (value) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull() ?: 0
        else -> 0
    }
}
