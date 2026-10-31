package com.bigrocket.config

import org.json.JSONArray
import org.json.JSONObject

/** `ss://` in SIP002 (`base64(method:pass)@host:port` or plain) and legacy (`base64(all)`) forms. */
class ShadowsocksLinkParser : ProxyLinkParser {
    override val schemes = setOf("ss")

    override fun parse(link: String): ParsedConfig {
        var body = link.substringAfter("://")
        val name = if ('#' in body) LinkSyntax.decodeUrl(body.substringAfter('#')) else ""
        body = body.substringBefore('#')
        val query = if ('?' in body) body.substringAfter('?') else ""
        body = body.substringBefore('?').trimEnd('/')

        val method: String
        val password: String
        val hostPortText: String
        if ('@' in body) {
            val userinfo = body.substring(0, body.lastIndexOf('@'))
            hostPortText = body.substring(body.lastIndexOf('@') + 1)
            val plain = LinkSyntax.decodeUrl(userinfo)
            val creds = if (':' in plain && !LinkSyntax.looksBase64(plain)) {
                plain
            } else {
                LinkSyntax.decodeLoose(userinfo) ?: plain
            }
            method = creds.substringBefore(':')
            password = creds.substringAfter(':', "")
        } else {
            val whole = LinkSyntax.decodeLoose(body)
                ?: throw ConfigParseException("لینک shadowsocks نامعتبر است")
            val at = whole.lastIndexOf('@')
            if (at < 0) throw ConfigParseException("لینک shadowsocks نامعتبر است")
            val creds = whole.substring(0, at)
            hostPortText = whole.substring(at + 1)
            method = creds.substringBefore(':')
            password = creds.substringAfter(':', "")
        }
        if (method.isEmpty() || password.isEmpty()) {
            throw ConfigParseException("لینک shadowsocks متد یا رمز عبور ندارد")
        }
        val plugin = query.split('&').firstOrNull { it.startsWith("plugin=") }
            ?.let { LinkSyntax.decodeUrl(it.substringAfter('=')) }
        if (!plugin.isNullOrBlank() && !plugin.startsWith("none")) {
            throw ConfigParseException("افزونه‌های shadowsocks ($plugin) پشتیبانی نمی‌شوند")
        }
        val (host, port) = LinkSyntax.splitHostPort(hostPortText)
        val server = JSONObject()
            .put("address", host)
            .put("port", port)
            .put("method", method.lowercase())
            .put("password", password)
            .put("level", 8)
        val outbound = JSONObject()
            .put("protocol", "shadowsocks")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
            .put("streamSettings", JSONObject().put("network", "tcp"))
        return ParsedConfig.of("SS", "shadowsocks", outbound, host, port, name)
    }
}
