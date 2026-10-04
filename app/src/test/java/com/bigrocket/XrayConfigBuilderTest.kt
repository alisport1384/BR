package com.bigrocket

import com.bigrocket.service.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the runtime Xray config generated for BigRocket's two Xray chains from
 * both supported input models: a vless:// share link (model 1) and a full
 * v2rayNG/v2rayN-style JSON client config (model 2).
 */
class XrayConfigBuilderTest {

    private val vlessLink =
        "vless://d9924deb-67fa-45c1-b867-d0110af15f4a@example.alex-test.org:443" +
            "?encryption=none&security=tls&sni=example.alex-test.org&fp=firefox" +
            "&alpn=http%2F1.1&insecure=0&allowInsecure=0&type=ws&host=example.alex-test.org" +
            "&path=%2Fvl%2F09N6testGvwMS7F7%3Fed%3D2560#%F0%9F%92%A6%2026.%20VLESS"

    private fun clientJson(): String = JSONObject(
        """
        {
          "remarks": "VLESS - Domain : 443",
          "log": {"loglevel": "none"},
          "dns": {"servers": ["8.8.8.8"]},
          "inbounds": [
            {"listen": "127.0.0.1", "port": 10808, "protocol": "mixed", "tag": "mixed-in"}
          ],
          "outbounds": [
            {
              "protocol": "vless",
              "settings": {"vnext": [{"address": "example.alex-test.org", "port": 443,
                "users": [{"id": "d9924deb-67fa-45c1-b867-d0110af15f4a", "encryption": "none"}]}]},
              "streamSettings": {
                "network": "ws",
                "wsSettings": {"host": "example.alex-test.org", "path": "/vl/Etest?ed=2560"},
                "security": "tls",
                "tlsSettings": {"serverName": "example.alex-test.org", "fingerprint": "firefox"},
                "sockopt": {"domainStrategy": "UseIP", "tcpFastOpen": true}
              },
              "tag": "proxy"
            },
            {"protocol": "freedom", "tag": "direct"},
            {"protocol": "blackhole", "tag": "block"}
          ],
          "routing": {"rules": [{"network": "tcp", "outboundTag": "proxy", "type": "field"}]}
        }
        """.trimIndent()
    ).toString()

    @Test
    fun `vless link builds complete chained runtime config`() {
        val config = JSONObject(XrayConfigBuilder.build(vlessLink, inboundPort = 1839, chainPort = 12347))

        val inbounds = config.getJSONArray("inbounds")
        assertEquals(1, inbounds.length())
        val inbound = inbounds.getJSONObject(0)
        assertEquals("127.0.0.1", inbound.getString("listen"))
        assertEquals(1839, inbound.getInt("port"))
        assertEquals("socks", inbound.getString("protocol"))
        assertTrue(inbound.getJSONObject("settings").getBoolean("udp"))

        val outbounds = config.getJSONArray("outbounds")
        assertEquals(2, outbounds.length())

        val proxy = outbounds.getJSONObject(0)
        assertEquals("proxy", proxy.getString("tag"))
        assertEquals("vless", proxy.getString("protocol"))
        val vnext = proxy.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
        assertEquals("example.alex-test.org", vnext.getString("address"))
        assertEquals(443, vnext.getInt("port"))
        val user = vnext.getJSONArray("users").getJSONObject(0)
        assertEquals("d9924deb-67fa-45c1-b867-d0110af15f4a", user.getString("id"))
        assertEquals("none", user.getString("encryption"))

        val stream = proxy.getJSONObject("streamSettings")
        assertEquals("ws", stream.getString("network"))
        assertEquals("/vl/09N6testGvwMS7F7?ed=2560", stream.getJSONObject("wsSettings").getString("path"))
        assertEquals("example.alex-test.org", stream.getJSONObject("wsSettings").getString("host"))
        assertEquals("tls", stream.getString("security"))
        val tls = stream.getJSONObject("tlsSettings")
        assertEquals("example.alex-test.org", tls.getString("serverName"))
        assertEquals("firefox", tls.getString("fingerprint"))
        assertEquals("http/1.1", tls.getJSONArray("alpn").getString(0))
        assertFalse(tls.optBoolean("allowInsecure", false))

        // The transport dial MUST be chained through the local boundary.
        assertEquals("bonding-chain", stream.getJSONObject("sockopt").getString("dialerProxy"))
        val chain = outbounds.getJSONObject(1)
        assertEquals("bonding-chain", chain.getString("tag"))
        assertEquals("socks", chain.getString("protocol"))
        val server = chain.getJSONObject("settings").getJSONArray("servers").getJSONObject(0)
        assertEquals("127.0.0.1", server.getString("address"))
        assertEquals(12347, server.getInt("port"))
    }

    @Test
    fun `client json keeps proxy outbound but replaces inbounds routing and sockopt`() {
        val config = JSONObject(XrayConfigBuilder.build(clientJson(), inboundPort = 1839, chainPort = 1819))

        // Client-config inbounds (10808 mixed) and routing/dns must not survive.
        assertNull(config.optJSONObject("routing"))
        assertNull(config.optJSONObject("dns"))
        val inbound = config.getJSONArray("inbounds").getJSONObject(0)
        assertEquals(1839, inbound.getInt("port"))
        assertEquals("socks", inbound.getString("protocol"))

        val proxy = config.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("vless", proxy.getString("protocol"))
        assertEquals(
            "example.alex-test.org",
            proxy.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address"),
        )
        val sockopt = proxy.getJSONObject("streamSettings").getJSONObject("sockopt")
        // User sockopt (UseIP/tcpFastOpen = direct physical dial tuning) must be gone;
        // only the chain dialerProxy may remain.
        assertEquals("bonding-chain", sockopt.getString("dialerProxy"))
        assertFalse(sockopt.has("domainStrategy"))
        assertFalse(sockopt.has("tcpFastOpen"))

        // Aether chain target for Direct+Aether+Xray.
        val chain = config.getJSONArray("outbounds").getJSONObject(1)
        assertEquals(
            1819,
            chain.getJSONObject("settings").getJSONArray("servers").getJSONObject(0).getInt("port"),
        )
    }

    @Test
    fun `json without proxy outbound is rejected`() {
        val bad = """{"outbounds":[{"protocol":"freedom","tag":"direct"}]}"""
        try {
            XrayConfigBuilder.build(bad, 1839, 12347)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `unsupported input is rejected`() {
        try {
            XrayConfigBuilder.build("ftp://not-a-config", 1839, 12347)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
