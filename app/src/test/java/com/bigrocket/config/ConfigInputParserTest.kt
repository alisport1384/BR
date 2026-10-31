package com.bigrocket.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Covers every accepted input model and every rejection path of ConfigInputParser. */
class ConfigInputParserTest {

    private val uuid = "d9924deb-67fa-45c1-b867-d0110af15f4a"

    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    private fun rejected(input: String): String {
        try {
            ConfigInputParser.parse(input)
        } catch (e: ConfigParseException) {
            return e.message ?: ""
        }
        throw AssertionError("expected ConfigParseException for: $input")
    }

    private fun settingsServer(c: ParsedConfig) =
        c.outbound.getJSONObject("settings").let { it.optJSONArray("vnext") ?: it.getJSONArray("servers") }
            .getJSONObject(0)

    // ---------------------------------------------------------------- links

    @Test
    fun `vless reality link`() {
        val c = ConfigInputParser.parse(
            "vless://$uuid@1.2.3.4:443?encryption=none&flow=xtls-rprx-vision&security=reality" +
                "&sni=www.example.com&fp=chrome&pbk=PUBKEY&sid=ab12&spx=%2F&type=tcp#My%20Name",
        ).single()
        assertEquals("My Name", c.name)
        assertEquals("vless", c.protocol)
        assertEquals("1.2.3.4:443", c.server)
        val user = settingsServer(c).getJSONArray("users").getJSONObject(0)
        assertEquals(uuid, user.getString("id"))
        assertEquals("xtls-rprx-vision", user.getString("flow"))
        val stream = c.outbound.getJSONObject("streamSettings")
        assertEquals("tcp", stream.getString("network"))
        assertEquals("reality", stream.getString("security"))
        val r = stream.getJSONObject("realitySettings")
        assertEquals("PUBKEY", r.getString("publicKey"))
        assertEquals("ab12", r.getString("shortId"))
        assertEquals("/", r.getString("spiderX"))
        assertEquals("www.example.com", r.getString("serverName"))
        assertFalse(stream.has("sockopt"))
    }

    @Test
    fun `vless transports keep their settings`() {
        fun stream(q: String) = ConfigInputParser.parse("vless://$uuid@h.example.com:443?$q").single()
            .outbound.getJSONObject("streamSettings")

        val grpc = stream("type=grpc&serviceName=svc&mode=multi&security=tls")
        assertEquals("svc", grpc.getJSONObject("grpcSettings").getString("serviceName"))
        assertTrue(grpc.getJSONObject("grpcSettings").getBoolean("multiMode"))

        val xhttp = stream("type=xhttp&path=%2Fp&host=h.example.com&mode=packet-up&security=tls")
        assertEquals("packet-up", xhttp.getJSONObject("xhttpSettings").getString("mode"))
        assertEquals("/p", xhttp.getJSONObject("xhttpSettings").getString("path"))

        val hu = stream("type=httpupgrade&path=%2Fu&security=tls")
        assertEquals("/u", hu.getJSONObject("httpupgradeSettings").getString("path"))

        val kcp = stream("type=kcp&headerType=wechat-video&seed=abc")
        assertEquals("abc", kcp.getJSONObject("kcpSettings").getString("seed"))
        assertEquals("wechat-video", kcp.getJSONObject("kcpSettings").getJSONObject("header").getString("type"))

        val ws = stream("type=ws&path=%2Fvl%3Fed%3D2560&host=h.example.com&security=tls&alpn=h2,http%2F1.1&allowInsecure=1")
        assertEquals("/vl?ed=2560", ws.getJSONObject("wsSettings").getString("path"))
        val tls = ws.getJSONObject("tlsSettings")
        assertTrue(tls.getBoolean("allowInsecure"))
        assertEquals(2, tls.getJSONArray("alpn").length())
        assertEquals("h.example.com", tls.getString("serverName"))
    }

    @Test
    fun `vmess base64 json link`() {
        val json = """{"v":"2","ps":"vm node","add":"host.example.com","port":"443","id":"$uuid","aid":"0",
            "scy":"auto","net":"ws","type":"none","host":"host.example.com","path":"/ws","tls":"tls","sni":"host.example.com"}"""
        val c = ConfigInputParser.parse("vmess://" + b64(json)).single()
        assertEquals("vm node", c.name)
        assertEquals("vmess", c.protocol)
        assertEquals("host.example.com:443", c.server)
        val stream = c.outbound.getJSONObject("streamSettings")
        assertEquals("ws", stream.getString("network"))
        assertEquals("/ws", stream.getJSONObject("wsSettings").getString("path"))
        assertEquals("tls", stream.getString("security"))
        assertEquals(uuid, settingsServer(c).getJSONArray("users").getJSONObject(0).getString("id"))
    }

    @Test
    fun `vmess numeric port and uri form`() {
        val numeric = """{"add":"h.example.com","port":8443,"id":"$uuid","net":"tcp"}"""
        assertEquals("h.example.com:8443", ConfigInputParser.parse("vmess://" + b64(numeric)).single().server)

        val uri = ConfigInputParser.parse("vmess://$uuid@h.example.com:443?type=ws&security=tls&path=%2Fx#n").single()
        assertEquals("n", uri.name)
        assertEquals("ws", uri.outbound.getJSONObject("streamSettings").getString("network"))
    }

    @Test
    fun `trojan link defaults to tls`() {
        val c = ConfigInputParser.parse("trojan://s3cret@h.example.com:443?sni=h.example.com#T").single()
        assertEquals("trojan", c.protocol)
        assertEquals("s3cret", settingsServer(c).getString("password"))
        assertEquals("tls", c.outbound.getJSONObject("streamSettings").getString("security"))
    }

    @Test
    fun `shadowsocks sip002 legacy and 2022 forms`() {
        val sip = ConfigInputParser.parse("ss://" + b64("aes-256-gcm:pass") + "@1.1.1.1:8388#ss1").single()
        assertEquals("aes-256-gcm", settingsServer(sip).getString("method"))
        assertEquals("pass", settingsServer(sip).getString("password"))
        assertEquals("ss1", sip.name)

        val legacy = ConfigInputParser.parse("ss://" + b64("chacha20-ietf-poly1305:pw@2.2.2.2:443")).single()
        assertEquals("2.2.2.2:443", legacy.server)
        assertEquals("chacha20-ietf-poly1305", settingsServer(legacy).getString("method"))

        val v2022 = ConfigInputParser.parse("ss://2022-blake3-aes-256-gcm:KEY%3D@3.3.3.3:443").single()
        assertEquals("2022-blake3-aes-256-gcm", settingsServer(v2022).getString("method"))
        assertEquals("KEY=", settingsServer(v2022).getString("password"))
    }

    @Test
    fun `shadowsocks plugin is rejected explicitly`() {
        val msg = rejected("ss://" + b64("aes-256-gcm:pass") + "@1.1.1.1:8388?plugin=v2ray-plugin%3Bmode%3Dwebsocket")
        assertTrue(msg.contains("v2ray-plugin"))
    }

    @Test
    fun `socks link with and without credentials`() {
        val plain = ConfigInputParser.parse("socks5://1.2.3.4:1080#s").single()
        assertFalse(settingsServer(plain).has("users"))
        val auth = ConfigInputParser.parse("socks://user:pw@1.2.3.4:1080").single()
        assertEquals("user", settingsServer(auth).getJSONArray("users").getJSONObject(0).getString("user"))
        val encoded = ConfigInputParser.parse("socks://" + b64("u2:p2") + "@1.2.3.4:1080").single()
        assertEquals("p2", settingsServer(encoded).getJSONArray("users").getJSONObject(0).getString("pass"))
    }

    @Test
    fun `wireguard link`() {
        val c = ConfigInputParser.parse(
            "wireguard://PRIVKEY@5.6.7.8:51820?publickey=PUB&address=10.0.0.2/32,fd00::2/128&mtu=1420&reserved=1,2,3#wg",
        ).single()
        assertEquals("wireguard", c.protocol)
        val s = c.outbound.getJSONObject("settings")
        assertEquals("PRIVKEY", s.getString("secretKey"))
        assertEquals(2, s.getJSONArray("address").length())
        assertEquals(1420, s.getInt("mtu"))
        assertEquals(3, s.getJSONArray("reserved").length())
        assertEquals("5.6.7.8:51820", s.getJSONArray("peers").getJSONObject(0).getString("endpoint"))
    }

    @Test
    fun `ipv6 literal server`() {
        val c = ConfigInputParser.parse("trojan://pw@[2001:db8::1]:443").single()
        assertEquals("[2001:db8::1]:443", c.server)
        assertEquals("2001:db8::1", settingsServer(c).getString("address"))
    }

    // ---------------------------------------------------------------- containers

    @Test
    fun `several links in messy text`() {
        val text = """
            کانفیگ جدید: vless://$uuid@a.example.com:443?security=tls#A
            "trojan://pw@b.example.com:443#B"
            random line without a config
            ss://${b64("aes-256-gcm:pass")}@c.example.com:8388#C vless://$uuid@d.example.com:443?security=tls#D
        """.trimIndent()
        val names = ConfigInputParser.parse(text).map { it.name }
        assertEquals(listOf("A", "B", "C", "D"), names)
    }

    @Test
    fun `invalid links are skipped while a valid one exists`() {
        val c = ConfigInputParser.parse("vless://$uuid@nohost\ntrojan://pw@b.example.com:443#ok")
        assertEquals(listOf("ok"), c.map { it.name })
    }

    @Test
    fun `base64 subscription body`() {
        val body = b64("trojan://pw@a.example.com:443#A\nvless://$uuid@b.example.com:443?security=tls#B\n")
        assertEquals(listOf("A", "B"), ConfigInputParser.parse(body).map { it.name })
        // URL-safe alphabet, no padding, wrapped lines.
        val urlSafe = body.replace('+', '-').replace('/', '_').trimEnd('=').chunked(40).joinToString("\n")
        assertEquals(2, ConfigInputParser.parse(urlSafe).size)
    }

    @Test
    fun `invisible rtl marks around a link are ignored`() {
        val c = ConfigInputParser.parse("\u200Evless://$uuid@a.example.com:443?security=tls#N\u200F").single()
        assertEquals("N", c.name)
    }

    @Test
    fun `full xray json takes the proxy outbound and drops sockopt dialer`() {
        val json = """
            {"remarks":"R","inbounds":[{"port":10808,"protocol":"mixed"}],
             "outbounds":[
               {"protocol":"freedom","tag":"direct"},
               {"protocol":"vless","tag":"proxy","proxySettings":{"tag":"x"},
                "settings":{"vnext":[{"address":"a.example.com","port":443,"users":[{"id":"$uuid"}]}]},
                "streamSettings":{"network":"ws","sockopt":{"dialerProxy":"d","mark":255,"tcpFastOpen":true},
                  "tlsSettings":{"show":true,"serverName":"a.example.com"}}}]}
        """.trimIndent()
        val c = ConfigInputParser.parse(json).single()
        assertEquals("R", c.name)
        assertEquals("a.example.com:443", c.server)
        assertFalse(c.outbound.has("tag"))
        assertFalse(c.outbound.has("proxySettings"))
        val so = c.outbound.getJSONObject("streamSettings").getJSONObject("sockopt")
        assertFalse(so.has("dialerProxy"))
        assertFalse(so.has("mark"))
        assertTrue(so.getBoolean("tcpFastOpen"))
        assertFalse(c.outbound.getJSONObject("streamSettings").getJSONObject("tlsSettings").has("show"))
    }

    @Test
    fun `json list and bare outbound`() {
        val outbound = """{"protocol":"trojan","settings":{"servers":[{"address":"t.example.com","port":443,"password":"p"}]}}"""
        assertEquals("t.example.com:443", ConfigInputParser.parse(outbound).single().server)
        val list = "[$outbound, {\"outbounds\":[{\"protocol\":\"socks\",\"settings\":{\"servers\":[{\"address\":\"1.1.1.1\",\"port\":1080}]}}]}]"
        assertEquals(2, ConfigInputParser.parse(list).size)
    }

    @Test
    fun `json wireguard outbound reports its endpoint`() {
        val wg = """{"protocol":"wireguard","settings":{"secretKey":"k","peers":[{"publicKey":"p","endpoint":"9.9.9.9:51820"}]}}"""
        assertEquals("9.9.9.9:51820", ConfigInputParser.parse(wg).single().server)
    }

    @Test
    fun `sanitize never mutates its input`() {
        val original = org.json.JSONObject(
            """{"protocol":"vless","tag":"proxy","streamSettings":{"sockopt":{"dialerProxy":"d"}}}""",
        )
        val before = original.toString()
        JsonConfigParser.sanitize(original)
        assertEquals(before, original.toString())
    }

    // ---------------------------------------------------------------- rejection

    @Test
    fun `empty and unsupported input is rejected`() {
        rejected("")
        rejected("   \n  ")
        rejected("ftp://not-a-config")
        rejected("hello world")
        rejected("{\"outbounds\":[{\"protocol\":\"freedom\"}]}")
        rejected("{ not json")
        rejected("[1,2,3]")
    }

    @Test
    fun `malformed links name the problem`() {
        assertTrue(rejected("vless://$uuid@host.example.com").contains("پورت"))
        assertTrue(rejected("vless://host.example.com:443").contains("شناسه"))
        assertTrue(rejected("vless://$uuid@host.example.com:99999").contains("پورت"))
        assertTrue(rejected("vless://$uuid@h.example.com:443?security=reality").contains("pbk"))
        assertTrue(rejected("vless://$uuid@h.example.com:443?type=quic").contains("QUIC"))
        assertTrue(rejected("vless://$uuid@h.example.com:443?type=carrier-pigeon").contains("carrier-pigeon"))
        assertTrue(rejected("trojan://@h.example.com:443").contains("رمز"))
        assertTrue(rejected("wireguard://KEY@h.example.com:51820").contains("کلید"))
        assertTrue(rejected("vmess://" + b64("{not json")).isNotEmpty())
    }

    @Test
    fun `mtproto and napsternet are explained not misparsed`() {
        assertEquals(ConfigInputParser.MTPROTO_UNSUPPORTED, rejected("tg://proxy?server=1.1.1.1&port=443&secret=abc"))
        assertEquals(ConfigInputParser.MTPROTO_UNSUPPORTED, rejected("https://t.me/proxy?server=1.1.1.1&port=443&secret=abc"))
        assertEquals(ConfigInputParser.NAPSTERNET_UNSUPPORTED, rejected("NPVT1\nAAAA,BBBB,CCCC"))
        assertEquals(ConfigInputParser.NAPSTERNET_UNSUPPORTED, rejected("npvt://AAAA"))
    }

    @Test
    fun `oversized input is rejected before parsing`() {
        rejected("a".repeat(ConfigInputParser.MAX_INPUT_CHARS + 1))
    }

    @Test
    fun `base64 nesting terminates`() {
        var text = "trojan://pw@a.example.com:443"
        repeat(6) { text = b64(text) }
        rejected(text)
    }

    // ---------------------------------------------------------------- helpers

    @Test
    fun `subscription url detection`() {
        assertTrue(ConfigInputParser.isSubscriptionUrl("https://example.com/sub/abc?token=1"))
        assertTrue(ConfigInputParser.isSubscriptionUrl("  http://example.com/s \n"))
        assertFalse(ConfigInputParser.isSubscriptionUrl("vless://$uuid@a.example.com:443"))
        assertFalse(ConfigInputParser.isSubscriptionUrl("https://t.me/proxy?server=1.1.1.1&secret=abc"))
        assertFalse(ConfigInputParser.isSubscriptionUrl("https://example.com/a b"))
        assertFalse(ConfigInputParser.isSubscriptionUrl("{\"a\":1}"))
    }

    @Test
    fun `loose base64 handles padding alphabets and garbage`() {
        assertEquals("hello", String(LooseBase64.decode("aGVsbG8")!!))
        assertEquals("hello", String(LooseBase64.decode("aGVsbG8=")!!))
        assertEquals(">>>???", String(LooseBase64.decode("Pj4-Pz8_")!!))
        assertNull(LooseBase64.decode("a"))
        assertNull(LooseBase64.decode("ab\$cd"))
        assertNotNull(LinkSyntax.decodeLoose(b64("text")))
        assertNull(LinkSyntax.decodeLoose(Base64.getEncoder().encodeToString(byteArrayOf(0, 1, 2, -1, -2))))
    }
}
