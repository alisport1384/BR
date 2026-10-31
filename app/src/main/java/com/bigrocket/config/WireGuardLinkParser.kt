package com.bigrocket.config

import org.json.JSONArray
import org.json.JSONObject

/** `wireguard://privatekey@host:port?publickey=...&address=...` (also `wg://`). */
class WireGuardLinkParser : ProxyLinkParser {
    override val schemes = setOf("wireguard", "wg")

    override fun parse(link: String): ParsedConfig {
        val u = LinkSyntax.UriParts.of(link)
        if (u.user.isEmpty()) throw ConfigParseException("لینک wireguard کلید خصوصی ندارد")
        val peerKey = u.q("publickey", "publicKey", "public_key")
        if (peerKey.isEmpty()) throw ConfigParseException("لینک wireguard کلید عمومی peer ندارد")
        val addresses = u.q("address", "ip").ifEmpty { "10.0.0.2/32" }
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }

        val settings = JSONObject()
            .put("secretKey", u.user)
            .put("address", JSONArray(addresses))
            .put("mtu", u.q("mtu").toIntOrNull() ?: 1280)
        u.q("reserved").takeIf { it.isNotEmpty() }?.let { r ->
            settings.put("reserved", JSONArray(r.split(',').mapNotNull { it.trim().toIntOrNull() }))
        }
        val peer = JSONObject().put("publicKey", peerKey)
        u.q("presharedkey", "preSharedKey").takeIf { it.isNotEmpty() }?.let { peer.put("preSharedKey", it) }
        peer.put("endpoint", LinkSyntax.hostPort(u.host, u.port))
        peer.put("allowedIPs", JSONArray(listOf("0.0.0.0/0", "::/0")))
        settings.put("peers", JSONArray().put(peer))

        val outbound = JSONObject().put("protocol", "wireguard").put("settings", settings)
        return ParsedConfig.of("WireGuard", "wireguard", outbound, u.host, u.port, u.name)
    }
}
