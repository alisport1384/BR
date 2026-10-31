package com.bigrocket.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds Xray `streamSettings` from the de-facto share-link parameters (v2rayN / Xray
 * share standard): type, security, sni, fp, alpn, host, path, serviceName, mode,
 * headerType, pbk, sid, spx, pqv, allowInsecure/insecure, seed, extra, authority.
 *
 * The result never contains `sockopt`: how the transport is dialled is decided by
 * BigRocket's XrayConfigBuilder, never by a share link.
 */
internal object StreamSettingsBuilder {

    private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")

    fun build(q: Map<String, String>, address: String, defaultSecurity: String): JSONObject {
        fun v(vararg keys: String): String =
            keys.firstNotNullOfOrNull { key -> q[key]?.trim()?.takeIf { it.isNotEmpty() } } ?: ""

        val requested = v("type", "net").lowercase().ifEmpty { "tcp" }
        val net = when (requested) {
            "tcp", "raw" -> "tcp"
            "ws", "websocket" -> "ws"
            "grpc", "gun" -> "grpc"
            "httpupgrade" -> "httpupgrade"
            "xhttp", "splithttp" -> "xhttp"
            "kcp", "mkcp" -> "kcp"
            "h2", "http" -> "http"
            "quic" -> throw ConfigParseException("ترنسپورت QUIC از Xray حذف شده است")
            else -> throw ConfigParseException("ترنسپورت پشتیبانی‌نشده: $requested")
        }

        val security = v("security").lowercase().ifEmpty { defaultSecurity }
            .let { if (it == "xtls") "tls" else it }
        val host = v("host")
        val path = v("path")
        val sni = v("sni", "serverName", "peer").ifEmpty {
            host.substringBefore(',').ifEmpty { address.takeIf { LinkSyntax.isDomain(it) } ?: "" }
        }
        val insecure = v("allowInsecure", "insecure").lowercase() in setOf("1", "true", "yes")
        val alpn = v("alpn").split(',').map { it.trim() }.filter { it.isNotEmpty() }

        val stream = JSONObject().put("network", net)

        when (security) {
            "tls" -> {
                val tls = JSONObject()
                if (sni.isNotEmpty()) tls.put("serverName", sni)
                if (insecure) tls.put("allowInsecure", true)
                v("pinnedPeerCertSha256").takeIf { it.isNotEmpty() }?.let { pin ->
                    val valid = pin.split(',').all { token ->
                        token.trim().replace(":", "").matches(SHA256_HEX)
                    }
                    if (!valid) throw ConfigParseException("پین گواهی TLS نامعتبر است")
                    tls.put("pinnedPeerCertSha256", pin)
                }
                v("fp", "fingerprint").takeIf { it.isNotEmpty() }?.let { tls.put("fingerprint", it) }
                if (alpn.isNotEmpty()) tls.put("alpn", JSONArray(alpn))
                stream.put("security", "tls").put("tlsSettings", tls)
            }
            "reality" -> {
                val pbk = v("pbk", "publicKey")
                if (pbk.isEmpty()) throw ConfigParseException("لینک reality کلید عمومی (pbk) ندارد")
                val reality = JSONObject()
                if (sni.isNotEmpty()) reality.put("serverName", sni)
                reality.put("fingerprint", v("fp", "fingerprint").ifEmpty { "chrome" })
                reality.put("publicKey", pbk)
                reality.put("shortId", v("sid", "shortId"))
                reality.put("spiderX", v("spx", "spiderX"))
                v("pqv", "mldsa65Verify").takeIf { it.isNotEmpty() }?.let { reality.put("mldsa65Verify", it) }
                stream.put("security", "reality").put("realitySettings", reality)
            }
            "none", "" -> stream.put("security", "none")
            else -> throw ConfigParseException("security پشتیبانی‌نشده: $security")
        }

        when (net) {
            "tcp" -> if (v("headerType").lowercase() == "http") {
                val request = JSONObject()
                    .put("path", JSONArray(path.ifEmpty { "/" }.split(',').map { it.trim() }))
                if (host.isNotEmpty()) {
                    request.put(
                        "headers",
                        JSONObject().put("Host", JSONArray(host.split(',').map { it.trim() })),
                    )
                }
                stream.put(
                    "tcpSettings",
                    JSONObject().put("header", JSONObject().put("type", "http").put("request", request)),
                )
            }
            "ws" -> stream.put(
                "wsSettings",
                JSONObject().put("path", path.ifEmpty { "/" }).also { if (host.isNotEmpty()) it.put("host", host) },
            )
            "httpupgrade" -> stream.put(
                "httpupgradeSettings",
                JSONObject().put("path", path.ifEmpty { "/" }).also { if (host.isNotEmpty()) it.put("host", host) },
            )
            "grpc" -> stream.put(
                "grpcSettings",
                JSONObject()
                    .put("serviceName", v("serviceName").ifEmpty { path })
                    .put("multiMode", v("mode").lowercase() == "multi")
                    .also { g -> v("authority").takeIf { it.isNotEmpty() }?.let { g.put("authority", it) } },
            )
            "xhttp" -> {
                val xh = JSONObject().put("path", path.ifEmpty { "/" })
                if (host.isNotEmpty()) xh.put("host", host)
                xh.put("mode", v("mode").ifEmpty { "auto" })
                v("extra").takeIf { it.isNotEmpty() }?.let { extra ->
                    runCatching { JSONObject(extra) }.getOrNull()?.let { xh.put("extra", it) }
                }
                stream.put("xhttpSettings", xh)
            }
            "kcp" -> {
                val kcp = JSONObject().put(
                    "header",
                    JSONObject().put("type", v("headerType").ifEmpty { "none" }),
                )
                v("seed").takeIf { it.isNotEmpty() }?.let { kcp.put("seed", it) }
                stream.put("kcpSettings", kcp)
            }
            "http" -> {
                val http = JSONObject()
                if (path.isNotEmpty()) http.put("path", path)
                if (host.isNotEmpty()) http.put("host", JSONArray(host.split(',').map { it.trim() }))
                stream.put("httpSettings", http)
            }
        }
        return stream
    }
}
