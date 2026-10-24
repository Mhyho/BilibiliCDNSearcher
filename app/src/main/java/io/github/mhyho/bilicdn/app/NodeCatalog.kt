package io.github.mhyho.bilicdn.app

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Node(
    val domain: String,
    val region: String,
    val family: String,
) {
    val isp: String = ispOf(domain)
    val familyLabel: String get() = FAMILY_LABELS[family] ?: family

    /** 搜索用的拼接文本。 */
    val searchText: String = "$domain $region $isp ${FAMILY_LABELS[family] ?: family}".lowercase()

    companion object {
        val FAMILY_LABELS = mapOf(
            "official" to "本次分配",
            "upos" to "云存储 upos",
            "standard-cn" to "自建节点",
            "video-edge" to "视频边缘",
            "standard-ec" to "海外/特殊",
            "custom" to "自定义",
        )

        private val ISP = mapOf(
            "ct" to "电信", "dx" to "电信",
            "cu" to "联通", "lt" to "联通",
            "cm" to "移动", "cmcc" to "移动", "yd" to "移动",
            "gd" to "广电", "eq" to "Equinix",
        )

        /** cn-<地区>-<运营商>-... 取第三段。 */
        fun ispOf(domain: String): String {
            if (!domain.startsWith("cn-")) return ""
            val code = domain.substringBefore('.').split('-').getOrNull(2) ?: return ""
            return ISP[code] ?: code
        }
    }
}

/**
 * 节点库。数据来自 babywbx/BiliCDN（MIT，每周扫描 DNS + HTTP 存活），
 * 打包一份快照到 assets，可在 App 内更新；另外合并几个目录里缺的 upos 镜像和用户自定义节点。
 */
object NodeCatalog {
    private const val FILE = "nodes.meta.json"
    private val UPDATE_URLS = listOf(
        "https://cdn.jsdelivr.net/gh/babywbx/BiliCDN@latest-data/nodes.meta.json",
        "https://bilicdn.pages.dev/nodes.meta.json",
    )

    /** 哔哩漫游曾内置、但 BiliCDN 目录里没有的 upos 镜像。 */
    private val EXTRA_UPOS = listOf(
        "upos-sz-mirrorali.bilivideo.com" to "UPOS-阿里云",
        "upos-sz-mirroralio1.bilivideo.com" to "UPOS-阿里云",
        "upos-sz-mirrorbos.bilivideo.com" to "UPOS-百度云",
        "upos-sz-mirrorhwb.bilivideo.com" to "UPOS-华为云",
        "upos-sz-mirrorhwo1.bilivideo.com" to "UPOS-华为云",
        "upos-sz-mirror08c.bilivideo.com" to "UPOS-华为云",
        "upos-sz-mirror08h.bilivideo.com" to "UPOS-华为云",
        "upos-sz-mirror08ct.bilivideo.com" to "UPOS-华为云",
        "upos-sz-mirrorhwov.bilivideo.com" to "UPOS-华为云",
        "upos-tf-all-hw.bilivideo.com" to "UPOS-华为云",
        "upos-tf-all-tx.bilivideo.com" to "UPOS-腾讯云",
        "cn-hk-eq-bcache-01.bilivideo.com" to "香港",
    )

    fun load(ctx: Context): List<Node> {
        val local = File(ctx.filesDir, FILE)
        val text = if (local.isFile) local.readText() else ctx.assets.open(FILE).bufferedReader().use { it.readText() }
        val nodes = LinkedHashMap<String, Node>()
        parse(text).forEach { nodes[it.domain] = it }
        EXTRA_UPOS.forEach { (d, r) -> nodes.putIfAbsent(d, Node(d, r, "upos")) }
        Settings.customHosts(ctx).forEach { nodes.putIfAbsent(it, Node(it, "自定义", "custom")) }
        return nodes.values.toList()
    }

    /** 只保留可用于点播的节点。 */
    private fun parse(text: String): List<Node> {
        val arr = JSONArray(text)
        val out = ArrayList<Node>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val usages = o.optJSONArray("usages")
            val video = (0 until (usages?.length() ?: 0)).any { usages!!.optString(it) == "video" }
            if (!video) continue
            out += Node(o.getString("domain").lowercase(), o.optString("region"), o.optString("family"))
        }
        return out
    }

    /** 下载最新目录，成功返回节点数。阻塞调用，放到后台线程。 */
    fun update(ctx: Context): Int {
        var last: Exception? = null
        for (u in UPDATE_URLS) {
            try {
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 15000
                val text = try {
                    if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
                    conn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    conn.disconnect()
                }
                val count = parse(text).size
                if (count == 0) throw RuntimeException("空列表")
                val tmp = File(ctx.filesDir, "$FILE.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(File(ctx.filesDir, FILE))) throw RuntimeException("写入失败")
                return count
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: RuntimeException("更新失败")
    }
}
