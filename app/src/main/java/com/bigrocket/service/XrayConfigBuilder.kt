package com.bigrocket.service

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Builds the runtime Xray config for BigRocket's two Xray chains from the
 * user-supplied configuration (XrayConfigStore), which may be either:
 *
 *  1. a share link (vless://...) — the format most clients export, or
 *  2. a full client JSON config (v2rayNG / v2rayN style).
 *
 * Both inputs are treated as TEMPLATES for the proxy server itself. The final
 * runtime config is always rebuilt deterministically around BigRocket's own
 * topology, because Xray here is never the device VPN — it is one hop inside
 * an already-established chain:
 *
 *   TUN → hev/JVM router → [socks-in 127.0.0.1:SOCKS_PORT] Xray [proxy outbound]
 *       → dialerProxy → [chain-out socks 127.0.0.1:chainPort] → bonding/Aether
 *
 *  - Inbounds from a pasted JSON are DISCARDED: Xray must listen only on
 *    BigRocket's local SOCKS port, never on the client-config ports (10808...).
 *  - The proxy outbound's transport dial is forced through `dialerProxy` onto
 *    the local chain target (BondingSocksServer for Direct+Xray, Aether's
 *    SOCKS listener for Direct+Aether+Xray). Any user `sockopt` is replaced:
 *    options like domainStrategy=UseIP/tcpFastOpen apply to a direct physical
 *    dial, which must never happen here (it would escape the bonded paths).
 *  - DNS/routing/policy blocks are DISCARDED: every flow entering socks-in
 *    exits via the proxy outbound; splitting traffic around the tunnel is the
 *    job of BigRocket's own engines, and geoip/geosite data files are not
 *    shipped with the app.
 */
object XrayConfigBuilder {

    /** Outbound tag Xray's transport dials are chained through. */
    private const val CHAIN_TAG = "bonding-chain"

    /** Protocols that can never be "the" proxy outbound of a client config. */
    private val NON_PROXY_PROTOCOLS = setOf("freedom", "blackhole", "dns", "loopback")

    /**
     * @param rawConfig    user input: share link or full client JSON
     * @param inboundPort  local SOCKS5 port Xray must listen on (loopback only)
     * @param chainPort    local SOCKS5 port Xray must dial out through
     *                     (BondingSocksServer.PORT or Aether's SOCKS port)
     * @return the complete runtime config JSON text
     */
    fun build(rawConfig: String, inboundPort: Int, chainPort: Int): String {
        val trimmed = rawConfig.trim()
        val proxyOutbound = when {
            trimmed.startsWith("vless://", ignoreCase = true) -> {
                AppLogger.log("XrayConfig", "model 1 (vless:// share link) detected")
                parseVlessLink(trimmed)
            }
            trimmed.startsWith("{") -> {
                AppLogger.log("XrayConfig", "model 2 (full client JSON) detected")
                extractProxyOutbound(JSONObject(trimmed))
            }
            else -> {
                AppLogger.log("XrayConfig", "config rejected: neither vless:// link nor JSON object")
                throw IllegalArgumentException(
                    "پیکربندی Xray نامعتبر است: فقط لینک vless:// یا کانفیگ کامل JSON پشتیبانی می‌شود"
                )
            }
        }

        proxyOutbound.put("tag", "proxy")

        // Force the transport dial through the local chain target. The whole
        // sockopt block is replaced on purpose — see the class doc comment.
        val streamSettings = proxyOutbound.optJSONObject("streamSettings") ?: JSONObject().also {
            proxyOutbound.put("streamSettings", it)
        }
        streamSettings.put("sockopt", JSONObject().put("dialerProxy", CHAIN_TAG))

        // Mux would multiplex every flow onto one upstream stream; harmless in
        // principle, but it is part of the discarded client-side tuning and
        // must not survive from a pasted JSON with settings we cannot verify.
        proxyOutbound.remove("mux")

        val config = JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put(
                "inbounds",
                JSONArray().put(
                    JSONObject()
                        .put("tag", "socks-in")
                        .put("listen", "127.0.0.1")
                        .put("port", inboundPort)
                        .put("protocol", "socks")
                        .put(
                            "settings",
                            JSONObject().put("auth", "noauth").put("udp", true)
                        )
                )
            )
            .put(
                "outbounds",
                JSONArray()
                    .put(proxyOutbound)
                    .put(
                        JSONObject()
                            .put("tag", CHAIN_TAG)
                            .put("protocol", "socks")
                            .put(
                                "settings",
                                JSONObject().put(
                                    "servers",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("address", "127.0.0.1")
                                            .put("port", chainPort)
                                    )
                                )
                            )
                    )
            )
        // Operational summary for the in-app log: which server shape the chain uses
        // (never credentials - no uuid/password is ever logged).
        val vnext = proxyOutbound.optJSONObject("settings")?.optJSONArray("vnext")?.optJSONObject(0)
        val net = proxyOutbound.optJSONObject("streamSettings")?.optString("network") ?: "?"
        AppLogger.log(
            "XrayConfig",
            "built: ${proxyOutbound.optString("protocol")}/$net -> " +
                "${vnext?.optString("address") ?: "?"}:${vnext?.optInt("port") ?: 0}, " +
                "inbound=127.0.0.1:$inboundPort (udp on), chain dialerProxy -> 127.0.0.1:$chainPort",
        )
        return config.toString(2)
    }

    // ------------------------------------------------------------------
    // Model 2: full client JSON — extract the proxy outbound
    // ------------------------------------------------------------------

    private fun extractProxyOutbound(root: JSONObject): JSONObject {
        val outbounds = root.optJSONArray("outbounds")
            ?: run {
                AppLogger.log("XrayConfig", "config rejected: JSON has no outbounds section")
                throw IllegalArgumentException("کانفیگ JSON فاقد بخش outbounds است")
            }
        var fallback: JSONObject? = null
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(i) ?: continue
            val protocol = outbound.optString("protocol").lowercase()
            if (protocol.isEmpty() || protocol in NON_PROXY_PROTOCOLS) continue
            if (outbound.optString("tag") == "proxy") {
                return JSONObject(outbound.toString()) // deep copy
            }
            if (fallback == null) fallback = outbound
        }
        return fallback?.let { JSONObject(it.toString()) }
            ?: run {
                AppLogger.log("XrayConfig", "config rejected: no usable proxy outbound in JSON")
                throw IllegalArgumentException("هیچ outbound پروکسی معتبری در کانفیگ JSON یافت نشد")
            }
    }

    // ------------------------------------------------------------------
    // Model 1: vless:// share link
    // ------------------------------------------------------------------

    private fun parseVlessLink(link: String): JSONObject {
        val body = link.substring("vless://".length).substringBefore('#')
        val query = body.substringAfter('?', "")
        val main = body.substringBefore('?')

        val at = main.lastIndexOf('@')
        require(at > 0) { "لینک vless نامعتبر است: UUID یافت نشد" }
        val uuid = decode(main.substring(0, at))
        val hostPort = main.substring(at + 1)

        val (address, port) = splitHostPort(hostPort)
        val params = parseQuery(query)

        val user = JSONObject()
            .put("id", uuid)
            .put("encryption", params["encryption"] ?: "none")
        params["flow"]?.takeIf { it.isNotEmpty() }?.let { user.put("flow", it) }

        val outbound = JSONObject()
            .put("protocol", "vless")
            .put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", address)
                            .put("port", port)
                            .put("users", JSONArray().put(user))
                    )
                )
            )
            .put("streamSettings", buildStreamSettings(params, address))
        return outbound
    }

    private fun buildStreamSettings(params: Map<String, String>, address: String): JSONObject {
        val network = (params["type"] ?: "tcp").ifEmpty { "tcp" }
        val stream = JSONObject().put("network", network)

        when (network) {
            "ws" -> {
                val ws = JSONObject()
                params["path"]?.takeIf { it.isNotEmpty() }?.let { ws.put("path", it) }
                params["host"]?.takeIf { it.isNotEmpty() }?.let { ws.put("host", it) }
                stream.put("wsSettings", ws)
            }
            "httpupgrade" -> {
                val hu = JSONObject()
                params["path"]?.takeIf { it.isNotEmpty() }?.let { hu.put("path", it) }
                params["host"]?.takeIf { it.isNotEmpty() }?.let { hu.put("host", it) }
                stream.put("httpupgradeSettings", hu)
            }
            "xhttp", "splithttp" -> {
                val xh = JSONObject()
                params["path"]?.takeIf { it.isNotEmpty() }?.let { xh.put("path", it) }
                params["host"]?.takeIf { it.isNotEmpty() }?.let { xh.put("host", it) }
                params["mode"]?.takeIf { it.isNotEmpty() }?.let { xh.put("mode", it) }
                stream.put("xhttpSettings", xh)
            }
            "grpc" -> {
                val grpc = JSONObject()
                params["serviceName"]?.takeIf { it.isNotEmpty() }?.let { grpc.put("serviceName", it) }
                if (params["mode"] == "multi") grpc.put("multiMode", true)
                stream.put("grpcSettings", grpc)
            }
            "h2", "http" -> {
                stream.put("network", "http")
                val http = JSONObject()
                params["path"]?.takeIf { it.isNotEmpty() }?.let { http.put("path", it) }
                params["host"]?.takeIf { it.isNotEmpty() }
                    ?.let { http.put("host", JSONArray(it.split(',').map(String::trim))) }
                stream.put("httpSettings", http)
            }
            "kcp" -> {
                val kcp = JSONObject()
                params["headerType"]?.takeIf { it.isNotEmpty() }
                    ?.let { kcp.put("header", JSONObject().put("type", it)) }
                params["seed"]?.takeIf { it.isNotEmpty() }?.let { kcp.put("seed", it) }
                stream.put("kcpSettings", kcp)
            }
            "tcp" -> {
                if (params["headerType"] == "http") {
                    val hostList = JSONArray(
                        (params["host"] ?: address).split(',').map(String::trim)
                    )
                    val request = JSONObject()
                        .put("path", JSONArray().put(params["path"]?.ifEmpty { "/" } ?: "/"))
                        .put("headers", JSONObject().put("Host", hostList))
                    stream.put(
                        "tcpSettings",
                        JSONObject().put(
                            "header",
                            JSONObject().put("type", "http").put("request", request)
                        )
                    )
                }
            }
        }

        when (params["security"] ?: "none") {
            "tls" -> {
                val tls = JSONObject()
                    .put("serverName", params["sni"] ?: params["host"] ?: address)
                params["fp"]?.takeIf { it.isNotEmpty() }?.let { tls.put("fingerprint", it) }
                params["alpn"]?.takeIf { it.isNotEmpty() }
                    ?.let { tls.put("alpn", JSONArray(it.split(',').map(String::trim))) }
                if (params["allowInsecure"] in setOf("1", "true") || params["insecure"] == "1") {
                    tls.put("allowInsecure", true)
                }
                stream.put("security", "tls").put("tlsSettings", tls)
            }
            "reality" -> {
                val reality = JSONObject()
                    .put("serverName", params["sni"] ?: address)
                    .put("publicKey", params["pbk"] ?: "")
                params["fp"]?.takeIf { it.isNotEmpty() }?.let { reality.put("fingerprint", it) }
                params["sid"]?.let { reality.put("shortId", it) }
                params["spx"]?.takeIf { it.isNotEmpty() }?.let { reality.put("spiderX", it) }
                stream.put("security", "reality").put("realitySettings", reality)
            }
        }
        return stream
    }

    private fun splitHostPort(hostPort: String): Pair<String, Int> {
        if (hostPort.startsWith("[")) { // IPv6 literal
            val end = hostPort.indexOf(']')
            require(end > 0 && end + 1 < hostPort.length && hostPort[end + 1] == ':') {
                "لینک vless نامعتبر است: آدرس/پورت قابل تشخیص نیست"
            }
            return hostPort.substring(1, end) to hostPort.substring(end + 2).toInt()
        }
        val sep = hostPort.lastIndexOf(':')
        require(sep > 0) { "لینک vless نامعتبر است: پورت یافت نشد" }
        return hostPort.substring(0, sep) to hostPort.substring(sep + 1).toInt()
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        return query.split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val eq = pair.indexOf('=')
                if (eq < 0) decode(pair) to ""
                else decode(pair.substring(0, eq)) to decode(pair.substring(eq + 1))
            }
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value, "UTF-8")
}
