package com.bigrocket.config

import org.json.JSONArray
import org.json.JSONObject

/** `socks://` / `socks5://` with optional `user:pass` (plain or base64) credentials. */
class SocksLinkParser : ProxyLinkParser {
    override val schemes = setOf("socks", "socks5")

    override fun parse(link: String): ParsedConfig {
        val u = LinkSyntax.UriParts.of(link, needUser = false)
        var user = ""
        var pass = ""
        if (u.user.isNotEmpty()) {
            val creds = if (':' in u.user) u.user else LinkSyntax.decodeLoose(u.user) ?: u.user
            user = creds.substringBefore(':')
            pass = creds.substringAfter(':', "")
        }
        val server = JSONObject().put("address", u.host).put("port", u.port)
        if (user.isNotEmpty()) {
            server.put(
                "users",
                JSONArray().put(JSONObject().put("user", user).put("pass", pass).put("level", 8)),
            )
        }
        val outbound = JSONObject()
            .put("protocol", "socks")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
        return ParsedConfig.of("SOCKS", "socks", outbound, u.host, u.port, u.name)
    }
}
