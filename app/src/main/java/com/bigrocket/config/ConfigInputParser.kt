package com.bigrocket.config

/**
 * Turns whatever the user pasted, loaded from a file or fetched from a subscription
 * into proxy configs. Input is auto-detected:
 *
 *  - share links: vless://, vmess:// (base64 JSON and URI form), trojan://, ss://
 *    (SIP002 and legacy), socks:// / socks5://, wireguard:// / wg:// — any number of
 *    them, in any text (one per line, or several on a line, with surrounding words);
 *  - a base64 subscription body holding such links;
 *  - an Xray / V2Ray JSON config, a list of them, or a bare outbound.
 *
 * A subscription URL (https://...) is recognised by [isSubscriptionUrl]; downloading it
 * is [SubscriptionFetcher]'s job, so this class stays pure and unit-testable.
 *
 * Every rejected input throws [ConfigParseException] with a message fit for the user.
 */
object ConfigInputParser {

    const val MAX_INPUT_CHARS = 2_000_000
    const val MAX_CONFIGS = 1_000
    private const val MAX_BASE64_DEPTH = 3

    private val parsers: List<ProxyLinkParser> = listOf(
        VlessLinkParser(),
        VmessLinkParser(),
        TrojanLinkParser(),
        ShadowsocksLinkParser(),
        SocksLinkParser(),
        WireGuardLinkParser(),
    )

    private val bySchema: Map<String, ProxyLinkParser> =
        parsers.flatMap { p -> p.schemes.map { it to p } }.toMap()

    /** Where a share link starts inside a line; never inside another scheme (`vless://` holds `ss://`). */
    private val LINK_START = Regex(
        "(?i)(?<![A-Za-z0-9+.\\-])(?:" +
            bySchema.keys.sortedByDescending { it.length }.joinToString("|") + ")://",
    )

    /** Punctuation that closes a sentence or a quote around a link, never part of it. */
    private val LINK_TRAILERS = charArrayOf('"', '\'', '`', ')', ']', '}', '>', ',', ';', '.', '،', '؛', '»', '”', '’')

    private val SUBSCRIPTION_URL = Regex("(?i)^https?://\\S+$")

    const val MTPROTO_UNSUPPORTED: String =
        "پروکسی MTProto فقط ترافیک تلگرام را عبور می‌دهد و برای تونل یا زنجیره قابل استفاده نیست؛ " +
            "آن را در تنظیمات پروکسی خود تلگرام وارد کنید"

    const val NAPSTERNET_UNSUPPORTED: String =
        "فایل‌های NapsternetV (npvt) هنوز پشتیبانی نمی‌شوند"

    /** True when [input] is a single http(s) URL, i.e. a subscription link that must be downloaded first. */
    fun isSubscriptionUrl(input: String): Boolean {
        val t = LinkSyntax.cleanInput(input)
        return SUBSCRIPTION_URL.matches(t) && !looksLikeMtProto(t)
    }

    /** All configs found in [input]; at least one, or [ConfigParseException]. */
    fun parse(input: String): List<ParsedConfig> = parseInternal(input, depth = 0)

    private fun parseInternal(input: String, depth: Int): List<ParsedConfig> {
        if (input.length > MAX_INPUT_CHARS) throw ConfigParseException("ورودی بیش از حد بزرگ است")
        val text = LinkSyntax.cleanInput(input)
        if (text.isEmpty()) throw ConfigParseException("ورودی خالی است")
        if (looksLikeMtProto(text)) throw ConfigParseException(MTPROTO_UNSUPPORTED)
        if (looksLikeNapsternet(text)) throw ConfigParseException(NAPSTERNET_UNSUPPORTED)
        if (text.startsWith("{") || text.startsWith("[")) return limit(JsonConfigParser.parse(text))

        val links = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.flatMap { extractLinks(it) }
        if (links.isEmpty()) {
            // A subscription body: the whole text is base64 of a list of links (or of JSON).
            if (depth < MAX_BASE64_DEPTH) {
                val decoded = LinkSyntax.decodeLoose(text)
                if (decoded != null && decoded != text && looksLikeConfigText(decoded)) {
                    return parseInternal(decoded, depth + 1)
                }
            }
            throw ConfigParseException(
                "کانفیگ پشتیبانی‌شده‌ای یافت نشد: لینک vless/vmess/trojan/ss/socks/wireguard، JSON کامل Xray یا لینک اشتراک وارد کنید",
            )
        }

        val out = ArrayList<ParsedConfig>()
        var firstError: String? = null
        for (link in links) {
            if (out.size >= MAX_CONFIGS) break
            try {
                out += parseLink(link)
            } catch (e: ConfigParseException) {
                if (firstError == null) firstError = e.message
            }
        }
        if (out.isEmpty()) throw ConfigParseException(firstError ?: "کانفیگ پشتیبانی‌شده‌ای یافت نشد")
        return out
    }

    /** One share link to one config. */
    fun parseLink(link: String): ParsedConfig {
        val trimmed = link.trim()
        val scheme = trimmed.substringBefore("://").lowercase()
        val parser = bySchema[scheme]
        if (parser == null) {
            if (scheme == "tg" || looksLikeMtProto(trimmed)) throw ConfigParseException(MTPROTO_UNSUPPORTED)
            throw ConfigParseException("نوع لینک پشتیبانی نمی‌شود: $scheme://")
        }
        return parser.parse(trimmed)
    }

    private fun limit(list: List<ParsedConfig>): List<ParsedConfig> = list.take(MAX_CONFIGS)

    private fun looksLikeConfigText(decoded: String): Boolean {
        val t = decoded.trimStart()
        return t.startsWith("{") || t.startsWith("[") ||
            bySchema.keys.any { decoded.contains("$it://", ignoreCase = true) }
    }

    private fun looksLikeNapsternet(text: String): Boolean {
        val t = text.trimStart()
        return t.startsWith("NPVT1") || t.startsWith("NPVTSUB1") || t.startsWith("NPVO1") ||
            t.startsWith("NPVS") || t.startsWith("npvt://", ignoreCase = true)
    }

    /** `t.me/proxy?...&secret=...`, its https form, and tg://proxy|socks. */
    fun looksLikeMtProto(link: String): Boolean {
        val lower = link.trim().lowercase()
        if (lower.startsWith("tg://proxy") || lower.startsWith("tg://socks")) return true
        val telegramHost = lower.startsWith("https://t.me/") || lower.startsWith("http://t.me/") ||
            lower.startsWith("t.me/") || lower.contains("telegram.me/proxy")
        return telegramHost && lower.contains("proxy") && lower.contains("secret=")
    }

    /**
     * Every share link in one line. A link copied from a channel post usually arrives
     * with words around it ("Config: vless://... 🔥"), several links can share a line,
     * and messengers wrap links in quotes or brackets. A link ends where the next one
     * starts; before its `#name` it also ends at the first whitespace (a name may hold
     * spaces, a URI may not).
     */
    internal fun extractLinks(line: String): List<String> {
        val starts = LINK_START.findAll(line).map { it.range.first }.toList()
        if (starts.isEmpty()) return emptyList()
        return starts.mapIndexed { i, start ->
            val end = if (i + 1 < starts.size) starts[i + 1] else line.length
            var link = line.substring(start, end).trim()
            val hash = link.indexOf('#')
            val space = link.indexOfFirst { it.isWhitespace() }
            if (space >= 0 && (hash < 0 || space < hash)) link = link.substring(0, space)
            link = if ('#' in link) link.trimEnd('"', '\'', '`') else link.trimEnd(*LINK_TRAILERS)
            link
        }.filter { it.substringAfter("://").isNotBlank() }
    }
}
