package com.bigrocket.config

import org.json.JSONArray
import org.json.JSONObject

/** `vless://uuid@host:port?...#name` */
class VlessLinkParser : ProxyLinkParser {
    override val schemes = setOf("vless")

    override fun parse(link: String): ParsedConfig {
        val u = LinkSyntax.UriParts.of(link)
        LinkSyntax.requireUserId(u.user)
        val user = JSONObject()
            .put("id", u.user)
            .put("encryption", u.q("encryption").ifEmpty { "none" })
        u.q("flow").takeIf { it.isNotEmpty() }?.let { user.put("flow", it) }
        user.put("level", 8)
        val outbound = JSONObject()
            .put("protocol", "vless")
            .put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", u.host)
                            .put("port", u.port)
                            .put("users", JSONArray().put(user)),
                    ),
                ),
            )
            .put("streamSettings", StreamSettingsBuilder.build(u.query, u.host, defaultSecurity = "none"))
        return ParsedConfig.of("VLESS", "vless", outbound, u.host, u.port, u.name)
    }
}
