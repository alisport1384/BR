package com.bigrocket.config

import org.json.JSONObject

/** A config input the importer could not accept; the message is shown to the user as-is. */
class ConfigParseException(message: String) : IllegalArgumentException(message)

/**
 * One proxy config extracted from user input.
 *
 * [outbound] is a ready-to-run Xray outbound object WITHOUT a tag. It is built once at
 * import time, so a config that does not parse can never be saved.
 */
data class ParsedConfig(
    val name: String,
    /** The Xray outbound `protocol` value (vless, vmess, trojan, shadowsocks, socks, wireguard...). */
    val protocol: String,
    val outbound: JSONObject,
    /** `host:port` for display. */
    val server: String,
) {
    companion object {
        const val MAX_NAME = 80

        fun of(
            label: String,
            protocol: String,
            outbound: JSONObject,
            host: String,
            port: Int,
            name: String,
        ): ParsedConfig = ParsedConfig(
            name = name.trim().ifBlank { "$label $host" }.take(MAX_NAME),
            protocol = protocol,
            outbound = outbound,
            server = LinkSyntax.hostPort(host, port),
        )
    }
}

/**
 * One implementation per share-link family. Implementations are stateless and throw
 * [ConfigParseException] for any malformed link; they never return a half-built config.
 */
interface ProxyLinkParser {
    /** Lower-case URI schemes this parser handles, without `://`. */
    val schemes: Set<String>

    fun parse(link: String): ParsedConfig
}
