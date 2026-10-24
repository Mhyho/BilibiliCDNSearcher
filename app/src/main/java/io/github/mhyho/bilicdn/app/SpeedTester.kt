package io.github.mhyho.bilicdn.app

import io.github.mhyho.bilicdn.core.UrlRewriter
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 测速用的样本：一条带签名的真实视频分片地址，以及本次接口返回的官方节点。 */
data class Sample(val url: String, val officialHosts: List<String>)

data class TestResult(
    val ok: Boolean,
    /** 首字节耗时（含 DNS、握手、服务器响应），毫秒。 */
    val ttfbMs: Long = -1,
    /** 下载速度，KB/s（从首字节开始计时）。 */
    val speedKBps: Long = 0,
    /** 从发起请求到读完的总耗时，毫秒。快速探测按它排名。 */
    val totalMs: Long = Long.MAX_VALUE,
    val bytes: Long = 0,
    val error: String? = null,
    /** true = 已做完整下载测试；false = 只做了快速探测。 */
    val full: Boolean = false,
)

object SpeedTester {
    // 样本是网页版（platform=pc）地址：移动端 UA 或缺 Referer 会被部分节点 403
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    private const val REFERER = "https://www.bilibili.com/"

    /** 公开、无地区限制的样本视频（aid, cid），依次尝试。第一个 480p 约 20 MB，够完整测速用。 */
    private val SAMPLES = listOf(
        80433022L to 137649199L,
        355749246L to 1115447032L,
    )

    /**
     * 从网页版播放接口取一条视频分片地址。未登录即可获取，签名与域名无关，
     * 换到其他节点即可测速。
     */
    fun fetchSample(): Sample {
        var last: Exception? = null
        for ((aid, cid) in SAMPLES) {
            try {
                return fetchSample(aid, cid)
            } catch (e: java.io.IOException) {
                throw e // 网络不通，换样本也没用
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: RuntimeException("获取样本失败")
    }

    private fun fetchSample(aid: Long, cid: Long): Sample {
        val api = "https://api.bilibili.com/x/player/playurl?avid=$aid&cid=$cid&qn=80&fnval=16&fourk=1"
        val conn = URL(api).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Referer", REFERER)
        val body = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val json = JSONObject(body)
        if (json.optInt("code", -1) != 0) throw RuntimeException("接口返回 ${json.optInt("code")} ${json.optString("message")}")
        val dash = json.getJSONObject("data").getJSONObject("dash")

        val urls = ArrayList<String>()
        var best: List<String> = emptyList()
        var bestBw = -1L
        for (key in listOf("video", "audio")) {
            val arr = dash.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val stream = ArrayList<String>()
                s.optString("baseUrl").ifEmpty { s.optString("base_url") }.let { if (it.isNotEmpty()) stream += it }
                val backups = s.optJSONArray("backupUrl") ?: s.optJSONArray("backup_url")
                for (j in 0 until (backups?.length() ?: 0)) stream += backups!!.getString(j)
                urls += stream
                // 选码率最高的视频流，文件足够大，测速才准
                val bw = s.optLong("bandwidth")
                if (key == "video" && bw > bestBw && stream.isNotEmpty()) {
                    bestBw = bw
                    best = stream
                }
            }
        }
        if (best.isEmpty()) best = urls
        // 优先用带 hdnts 的 akamai 来源地址：它同时带 upsig，换到任何节点都能用；
        // 不带 hdnts 的地址换到 akamai 会 403。
        val sample = best.firstOrNull { "hdnts=" in it && !UrlRewriter.isPcdn(it) }
            ?: best.firstOrNull { !UrlRewriter.isPcdn(it) }
            ?: best.firstOrNull()
            ?: throw RuntimeException("接口没有返回分片地址")
        return Sample(sample, urls.mapNotNull(UrlRewriter::hostOf).distinct())
    }

    /**
     * 对一个节点测速：最多下载 [maxBytes] 字节或 [maxMillis] 毫秒。阻塞调用。
     * [cancelled] 返回 true 时尽快中止。
     */
    fun test(
        host: String,
        sample: Sample,
        maxBytes: Long,
        maxMillis: Long,
        cancelled: () -> Boolean = { false },
    ): TestResult {
        val url = UrlRewriter.replaceHost(sample.url, host)
        val start = System.nanoTime()
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 4000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Referer", REFERER)
            conn.setRequestProperty("Range", "bytes=0-${maxBytes - 1}")
            val code = conn.responseCode
            val ttfb = (System.nanoTime() - start) / 1_000_000
            if (code !in 200..299) return TestResult(false, ttfb, error = "HTTP $code")

            val buf = ByteArray(64 * 1024)
            var total = 0L
            val readStart = System.nanoTime()
            conn.inputStream.use { input ->
                while (total < maxBytes && !cancelled()) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if ((System.nanoTime() - readStart) / 1_000_000 >= maxMillis) break
                }
            }
            val ms = ((System.nanoTime() - readStart) / 1_000_000).coerceAtLeast(1)
            if (total == 0L) return TestResult(false, ttfb, error = "无数据")
            TestResult(true, ttfb, total * 1000 / 1024 / ms, (System.nanoTime() - start) / 1_000_000, total)
        } catch (e: Exception) {
            TestResult(false, error = e.javaClass.simpleName.removeSuffix("Exception").ifEmpty { "错误" })
        } finally {
            conn?.disconnect()
        }
    }
}
