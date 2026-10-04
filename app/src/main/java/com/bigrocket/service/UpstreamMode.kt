package com.bigrocket.service

/**
 * Which embedded proxy (if any) BigRocket's relay engines chain outbound traffic through.
 *
 * [XRAY] is the relay-side entry point for BOTH Xray upstream chains
 * (Direct → Xray and Direct → Aether → Xray): in either chain the engines hand
 * traffic to Xray's local SOCKS5 listener; whether Xray itself then dials out via
 * the bonding boundary directly or via Aether is decided purely inside Xray's own
 * generated config (see XrayConfigBuilder), never by the relay engines.
 */
enum class UpstreamMode { NONE, AETHER, XRAY }
