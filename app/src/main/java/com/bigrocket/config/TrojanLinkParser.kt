package com.bigrocket.config

import org.json.JSONArray
import org.json.JSONObject

/** `trojan://password@host:port?...#name` */
class TrojanLinkParser : ProxyLinkParser {
    override val schemes = setOf("trojan")

    override fun parse(link: String): ParsedConfig {
        val u = LinkSyntax.UriParts.of(link)
        if (u.user.isEmpty()) throw ConfigParseException("لینک trojan رمز عبور ندارد")
        val server = JSONObject()
            .put("address", u.host)
            .put("port", u.port)
            .put("password", u.user)
        u.q("flow").takeIf { it.isNotEmpty() }?.let { server.put("flow", it) }
        server.put("level", 8)
        val outbound = JSONObject()
            .put("protocol", "trojan")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
            .put("streamSettings", StreamSettingsBuilder.build(u.query, u.host, defaultSecurity = "tls"))
        return ParsedConfig.of("Trojan", "trojan", outbound, u.host, u.port, u.name)
    }
}
