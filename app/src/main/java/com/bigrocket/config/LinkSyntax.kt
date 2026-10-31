package com.bigrocket.config

import org.json.JSONObject
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Low-level text helpers shared by every link parser. Pure Kotlin/JVM: no Android types,
 * so every rule is covered by plain unit tests.
 */
internal object LinkSyntax {

    /**
     * Characters that are invisible on screen but break a link: RLM/LRM and bidi isolates
     * that RTL messengers wrap around Latin text, zero-width spaces, word joiner, BOM,
     * soft hyphen. ZWNJ/ZWJ are kept: they are part of Persian words and emoji sequences.
     */
    private val INVISIBLE =
        Regex("[\u00AD\u061C\u180E\u200B\u200E\u200F\u202A-\u202E\u2060-\u2064\u2066-\u2069\uFEFF]")

    fun cleanInput(text: String): String =
        text.replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(INVISIBLE, "")
            .replace('\u00A0', ' ')
            .replace('\u202F', ' ')
            .trim()

    /** URL-decodes [s]; a literal `+` stays a plus (share links never use it as a space). */
    fun decodeUrl(s: String): String =
        runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    fun hostPort(host: String, port: Int): String =
        if (host.contains(':') && !host.startsWith("[")) "[$host]:$port" else "$host:$port"

    fun isDomain(host: String): Boolean = host.any { it.isLetter() } && !host.contains(':')

    fun splitHostPort(text: String): Pair<String, Int> {
        val t = text.trim()
        val host: String
        val portText: String
        if (t.startsWith("[")) {
            val end = t.indexOf(']')
            if (end <= 0) throw ConfigParseException("آدرس IPv6 نامعتبر است")
            host = t.substring(1, end)
            portText = t.substringAfter("]:", "")
        } else {
            host = t.substringBeforeLast(':', "")
            portText = if (t.contains(':')) t.substringAfterLast(':') else ""
        }
        val port = portText.trim().toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw ConfigParseException("لینک پورت معتبر ندارد")
        if (host.isBlank()) throw ConfigParseException("لینک آدرس سرور ندارد")
        return host.trim() to port
    }

    fun requireUserId(id: String) {
        if (id.isBlank()) throw ConfigParseException("لینک شناسه کاربر ندارد")
    }

    fun looksBase64(s: String): Boolean =
        s.isNotEmpty() && s.all { (it in 'A'..'Z') || (it in 'a'..'z') || (it in '0'..'9') || it in "+/=-_" }

    /**
     * Decodes standard or URL-safe base64, with or without padding, to UTF-8 text.
     * Null when [text] is not base64 or does not decode to text (binary garbage).
     */
    fun decodeLoose(text: String): String? {
        val clean = text.filterNot { it.isWhitespace() }
        if (clean.isEmpty() || !looksBase64(clean)) return null
        val bytes = LooseBase64.decode(clean) ?: return null
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val s = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            return null
        }
        if (s.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) return null
        return s
    }

    /** A trimmed string field of [o]; empty when absent or JSON null. */
    fun str(o: JSONObject, key: String): String =
        if (o.isNull(key)) "" else o.opt(key).toString().trim()

    /** `scheme://userinfo@host:port?query#fragment`, split without java.net.URI's strictness. */
    data class UriParts(
        val user: String,
        val host: String,
        val port: Int,
        val query: Map<String, String>,
        val name: String,
    ) {
        fun q(vararg keys: String): String =
            keys.firstNotNullOfOrNull { k -> query[k]?.takeIf { it.isNotEmpty() } } ?: ""

        companion object {
            fun of(link: String, needUser: Boolean = true): UriParts {
                var rest = link.substringAfter("://")
                val name = if ('#' in rest) decodeUrl(rest.substringAfter('#')) else ""
                rest = rest.substringBefore('#')
                val queryText = if ('?' in rest) rest.substringAfter('?') else ""
                rest = rest.substringBefore('?').trimEnd('/')
                val at = rest.lastIndexOf('@')
                if (needUser && at < 0) throw ConfigParseException("لینک شناسه کاربر یا رمز عبور ندارد")
                val user = if (at >= 0) decodeUrl(rest.substring(0, at)) else ""
                val (host, port) = splitHostPort(if (at >= 0) rest.substring(at + 1) else rest)
                val query = LinkedHashMap<String, String>()
                for (pair in queryText.split('&')) {
                    if (pair.isEmpty()) continue
                    val k = decodeUrl(pair.substringBefore('=')).trim()
                    val v = if ('=' in pair) decodeUrl(pair.substringAfter('=')) else ""
                    if (k.isNotEmpty() && k !in query) query[k] = v
                }
                return UriParts(user, host, port, query, name)
            }
        }
    }
}

/**
 * Standard and URL-safe base64 decoder (padding optional). Own implementation because
 * java.util.Base64 needs API 26 and BigRocket's minSdk is 24.
 */
internal object LooseBase64 {
    private val REVERSE = IntArray(128) { -1 }.also { table ->
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        for (i in alphabet.indices) table[alphabet[i].code] = i
        table['-'.code] = 62
        table['_'.code] = 63
    }

    fun decode(text: String): ByteArray? {
        val s = text.filterNot { it.isWhitespace() }.trimEnd('=')
        if (s.isEmpty() || s.length % 4 == 1) return null
        val out = ByteArray(s.length * 3 / 4)
        var buffer = 0
        var bits = 0
        var n = 0
        for (ch in s) {
            val v = if (ch.code < 128) REVERSE[ch.code] else -1
            if (v < 0) return null
            buffer = ((buffer shl 6) or v) and 0xFFFFFF
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[n++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return out.copyOf(n)
    }
}
