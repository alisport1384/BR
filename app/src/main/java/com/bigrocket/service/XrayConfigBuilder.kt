package com.bigrocket.service

import com.bigrocket.config.ConfigInputParser
import com.bigrocket.config.ConfigParseException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the runtime Xray config for BigRocket's two Xray chains from the
 * user-supplied configuration (XrayConfigStore). Input parsing lives in
 * com.bigrocket.config (ConfigInputParser): share links (vless, vmess, trojan, ss,
 * socks, wireguard), several links in one text, base64 subscription bodies, and full
 * client JSON (v2rayNG / v2rayN style, or a bare outbound). When the input holds more
 * than one config, the first one is used.
 *
 * The parsed outbound is treated as a TEMPLATE for the proxy server itself. The final
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

    /**
     * @param rawConfig    user input: share link(s), subscription body or client JSON
     *                     (see ConfigInputParser)
     * @param inboundPort  local SOCKS5 port Xray must listen on (loopback only)
     * @param chainPort    local SOCKS5 port Xray must dial out through
     *                     (BondingSocksServer.PORT or Aether's SOCKS port)
     * @return the complete runtime config JSON text
     */
    fun build(rawConfig: String, inboundPort: Int, chainPort: Int): String {
        val configs = try {
            ConfigInputParser.parse(rawConfig)
        } catch (e: ConfigParseException) {
            AppLogger.log("XrayConfig", "config rejected: ${e.message}")
            throw e
        }
        val selected = configs.first()
        AppLogger.log(
            "XrayConfig",
            "parsed ${configs.size} config(s); using #1 (${selected.protocol})",
        )
        val proxyOutbound = JSONObject(selected.outbound.toString()) // deep copy

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
}
