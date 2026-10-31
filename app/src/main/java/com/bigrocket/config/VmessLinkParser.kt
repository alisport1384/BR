package com.bigrocket.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * `vmess://` in both forms: the v2rayN base64-JSON form and the URI form
 * (`vmess://uuid@host:port?type=ws&security=tls...`).
 */
class VmessLinkParser : ProxyLinkParser {
    override val schemes = setOf("vmess")

    override fun parse(link: String): ParsedConfig {
        val body = link.substringAfter("://").trim()
        val decoded = LinkSyntax.decodeLoose(body.substringBefore('#'))
        if (decoded == null || !decoded.trimStart().startsWith("{")) {
            val u = LinkSyntax.UriParts.of(link)
            LinkSyntax.requireUserId(u.user)
            return outbound(
                id = u.user,
                host = u.host,
                port = u.port,
                alterId = u.q("alterId", "aid").toIntOrNull() ?: 0,
                security = u.q("encryption", "scy").ifEmpty { "auto" },
                q = u.query,
                name = u.name,
            )
        }
        val o = runCatching { JSONObject(decoded) }.getOrNull()
            ?: throw ConfigParseException("لینک vmess شامل JSON معتبر نیست")
        fun s(key: String) = LinkSyntax.str(o, key)
        val host = s("add")
        val port = s("port").toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw ConfigParseException("لینک vmess پورت معتبر ندارد")
        if (host.isEmpty()) throw ConfigParseException("لینک vmess آدرس سرور ندارد")
        val id = s("id")
        LinkSyntax.requireUserId(id)
        val q = LinkedHashMap<String, String>()
        q["type"] = s("net").ifEmpty { "tcp" }
        q["headerType"] = s("type")
        q["host"] = s("host")
        q["path"] = s("path")
        q["security"] = s("tls")
        q["sni"] = s("sni")
        q["alpn"] = s("alpn")
        q["fp"] = s("fp")
        q["allowInsecure"] = s("allowInsecure").ifEmpty { s("insecure") }
        if (q["type"] == "grpc") {
            q["serviceName"] = s("path")
            q["mode"] = if (s("type") == "multi") "multi" else "gun"
        }
        if (q["type"] == "kcp") q["seed"] = s("path")
        if (q["type"] == "xhttp" || q["type"] == "splithttp") q["mode"] = s("type").ifEmpty { "auto" }
        return outbound(id, host, port, s("aid").toIntOrNull() ?: 0, s("scy").ifEmpty { "auto" }, q, s("ps"))
    }

    private fun outbound(
        id: String,
        host: String,
        port: Int,
        alterId: Int,
        security: String,
        q: Map<String, String>,
        name: String,
    ): ParsedConfig {
        val user = JSONObject()
            .put("id", id)
            .put("alterId", alterId)
            .put("security", security)
            .put("level", 8)
        val outbound = JSONObject()
            .put("protocol", "vmess")
            .put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", host)
                            .put("port", port)
                            .put("users", JSONArray().put(user)),
                    ),
                ),
            )
            .put("streamSettings", StreamSettingsBuilder.build(q, host, defaultSecurity = "none"))
        return ParsedConfig.of("VMess", "vmess", outbound, host, port, name)
    }
}
