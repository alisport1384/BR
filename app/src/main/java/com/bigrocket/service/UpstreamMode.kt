package com.bigrocket.service

/**
 * Which embedded proxy (if any) BigRocket's relay engines chain outbound traffic through.
 *
 * The relay-side entry point is whichever engine sits outermost toward the TUN:
 * [XRAY] for the Direct+Xray and Direct+Aether+Xray chains (engines hand traffic
 * to Xray's local SOCKS5 listener), [AETHER] for Direct+Aether and
 * Direct+Xray+Aether (engines hand traffic to Aether's listener). What that
 * engine then dials through - the bonding boundary, Aether, or Xray - is decided
 * purely by its own startup config (XrayConfigBuilder's dialerProxy target /
 * EmbeddedAetherRuntime's upstreamProxy), never by the relay engines.
 */
enum class UpstreamMode { NONE, AETHER, XRAY }
