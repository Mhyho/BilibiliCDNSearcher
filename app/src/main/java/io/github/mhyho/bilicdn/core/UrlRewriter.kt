package io.github.mhyho.bilicdn.core

/**
 * 点播流地址改写规则。
 *
 * B 站的 upos 地址签名（upsig / hdnts）不包含域名，所以同一条地址换到别的 upos 镜像或
 * cn-xxx 自建节点通常仍然有效。PCDN（mcdn、IP:端口 形式）路径和签名规则不同，不能直接换域名。
 */
object UrlRewriter {

    data class Plan(val base: String, val backups: List<String>)

    private val ipHost = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /** 返回 "scheme://authority" 之后的位置区间；不是 http(s) 地址返回 null。 */
    private fun authorityRange(url: String): IntRange? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = url.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return null
        val start = schemeEnd + 3
        var end = url.indexOfAny(charArrayOf('/', '?', '#'), start)
        if (end < 0) end = url.length
        if (end <= start) return null
        return start until end
    }

    fun hostOf(url: String): String? {
        val r = authorityRange(url) ?: return null
        val authority = url.substring(r).substringAfterLast('@')
        return authority.substringBefore(':').lowercase()
    }

    private fun hasPort(url: String): Boolean {
        val r = authorityRange(url) ?: return false
        return url.substring(r).substringAfterLast('@').contains(':')
    }

    fun replaceHost(url: String, host: String): String {
        val r = authorityRange(url) ?: return url
        return url.substring(0, r.first) + host + url.substring(r.last + 1)
    }

    /** 直播流不处理。 */
    fun isLive(url: String) = url.contains("live-bvc") || url.contains("-gotcha")

    /** PCDN：用户节点 / 第三方小节点。 */
    fun isPcdn(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return host.endsWith("szbdyd.com") ||
            host.contains(".mcdn.bilivideo") ||
            ipHost.matches(host) ||
            hasPort(url)
    }

    /** 能否安全地换域名。szbdyd 类 PCDN 路径与 upos 相同，可以换（哔哩漫游同样处理）。 */
    fun isSwappable(url: String): Boolean {
        val host = hostOf(url) ?: return false
        if (isLive(url)) return false
        if (host.endsWith("szbdyd.com")) return true
        return !isPcdn(url)
    }

    /**
     * 计算一个分片的新主地址与备用地址。原地址全部保留为备用（去重），
     * 所以选定节点失效时播放器仍能回落到官方分配的地址。
     */
    fun plan(base: String, backups: List<String>, cfg: CdnConfig): Plan {
        val all = (listOf(base) + backups).filter { it.isNotEmpty() }.distinct()
        if (!cfg.active || all.isEmpty() || all.any(::isLive)) return Plan(base, backups)

        val normal = all.filterNot(::isPcdn)
        val pcdn = all.filter(::isPcdn)

        val newBase = when {
            cfg.host != null -> {
                // akamai 校验 hdnts 令牌，只有 akamai 来源的地址带它
                val akamaiSrc = if (cfg.host.endsWith("akamaized.net")) normal.firstOrNull { "hdnts=" in it } else null
                val src = akamaiSrc ?: normal.firstOrNull() ?: all.firstOrNull(::isSwappable)
                if (src != null) replaceHost(src, cfg.host) else base
            }
            cfg.blockPcdn -> normal.firstOrNull() ?: base
            else -> base
        }

        val ordered = if (cfg.blockPcdn && normal.isNotEmpty()) normal else normal + pcdn
        return Plan(newBase, ordered.filter { it != newBase })
    }
}
