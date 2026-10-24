package io.github.mhyho.bilicdn.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.mhyho.bilicdn.R
import io.github.mhyho.bilicdn.core.CdnConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()

    private lateinit var status: TextView
    private lateinit var progress: TextView
    private lateinit var btnTest: Button
    private lateinit var pcdnSwitch: Switch
    private lateinit var search: EditText
    private lateinit var filter: Spinner
    private val adapter = NodeAdapter()

    private var nodes: List<Node> = emptyList()
    private var officialHosts: Set<String> = emptySet()
    private val results = ConcurrentHashMap<String, TestResult>()
    private var config = CdnConfig()

    /** 每次开始/停止测速加一，旧任务看到编号变化就退出。 */
    private val generation = AtomicInteger()
    private var testing = false
    private var refreshPosted = false

    private val serviceListener: () -> Unit = { main.post { renderStatus() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        btnTest = findViewById(R.id.btn_test)
        pcdnSwitch = findViewById(R.id.switch_pcdn)
        search = findViewById(R.id.search)
        filter = findViewById(R.id.filter)
        val list = findViewById<ListView>(R.id.list)

        config = Settings.load(this)
        pcdnSwitch.isChecked = config.blockPcdn
        pcdnSwitch.setOnCheckedChangeListener { _, checked -> apply(config.copy(blockPcdn = checked)) }

        findViewById<Button>(R.id.btn_reset).setOnClickListener { apply(config.copy(host = null)) }
        btnTest.setOnClickListener { if (testing) stopTest() else confirmAndTest(adapter.items) }

        filter.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, FILTERS.map { it.first })
        filter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refresh()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })

        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> showNodeDialog(adapter.items[pos]) }

        reloadCatalog()
        renderStatus()
    }

    override fun onStart() {
        super.onStart()
        App.listeners += serviceListener
        renderStatus()
    }

    override fun onStop() {
        App.listeners -= serviceListener
        super.onStop()
    }

    override fun onDestroy() {
        generation.incrementAndGet()
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_UPDATE, 0, R.string.update_catalog)
        menu.add(0, MENU_CUSTOM, 1, R.string.add_custom)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_UPDATE -> updateCatalog()
            MENU_CUSTOM -> showAddCustom()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---------- 配置 ----------

    private fun apply(cfg: CdnConfig) {
        config = cfg
        val pushed = Settings.save(this, cfg)
        val what = cfg.host ?: "官方分配"
        val msg = if (pushed) "已应用：$what。重新打开视频后生效" else "已保存：$what。框架未连接，启用模块后自动同步"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        renderStatus()
        adapter.notifyDataSetChanged()
    }

    private fun renderStatus() {
        val s = App.service
        val framework = if (s != null) {
            runCatching { "${s.frameworkName} ${s.frameworkVersion}（API ${s.apiVersion}）已连接" }.getOrElse { "已连接" }
        } else {
            "未连接（请在 LSPosed/Vector 中启用本模块并勾选哔哩哔哩）"
        }
        status.text = buildString {
            append("框架：").append(framework).append('\n')
            append("当前节点：").append(config.host ?: "官方分配（不替换）")
            if (config.blockPcdn) append(" · 屏蔽 PCDN")
            append('\n')
            append("当前网络：").append(networkLabel())
        }
    }

    private fun networkLabel(): String {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return "未知"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "无网络"
        val parts = ArrayList<String>()
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) parts += "Wi-Fi"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) parts += "移动数据"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) parts += "以太网"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) parts += "VPN"
        return parts.joinToString(" + ").ifEmpty { "其他" }
    }

    // ---------- 节点库 ----------

    private fun reloadCatalog() {
        io.execute {
            val loaded = runCatching { NodeCatalog.load(this) }
            main.post {
                loaded.onSuccess { nodes = it }
                    .onFailure { Toast.makeText(this, "节点库读取失败：${it.message}", Toast.LENGTH_LONG).show() }
                refresh()
            }
        }
    }

    private fun updateCatalog() {
        progress.text = "正在更新节点库……"
        io.execute {
            val r = runCatching { NodeCatalog.update(this) }
            main.post {
                r.onSuccess { progress.text = "节点库已更新：$it 个点播节点"; reloadCatalog() }
                    .onFailure { progress.text = "节点库更新失败：${it.message}" }
            }
        }
    }

    private fun showAddCustom() {
        val input = EditText(this).apply { hint = "例如 upos-sz-mirrorali.bilivideo.com" }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_custom)
            .setView(input)
            .setPositiveButton("添加") { _, _ ->
                val host = input.text.toString().trim().lowercase()
                    .removePrefix("https://").removePrefix("http://").substringBefore('/')
                if (HOST_RE.matches(host)) {
                    Settings.addCustomHost(this, host)
                    reloadCatalog()
                } else {
                    Toast.makeText(this, "域名格式不对", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 列表 ----------

    private fun refresh() {
        val q = search.text.toString().trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val f = FILTERS[filter.selectedItemPosition.coerceAtLeast(0)].second
        val shown = nodes.filter { n ->
            val passFilter = when (f) {
                "" -> true
                "official" -> n.domain in officialHosts
                "ok" -> results[n.domain]?.ok == true
                else -> n.family == f
            }
            passFilter && q.all { n.searchText.contains(it) }
        }
        adapter.items = if (results.isEmpty()) shown else shown.sortedWith(resultOrder)
        adapter.notifyDataSetChanged()
        if (!testing) btnTest.text = "测速列表中的 ${shown.size} 个节点"
    }

    /** 已测可用的按速度降序，未测的居中，失败的放最后。 */
    private val resultOrder = Comparator<Node> { a, b ->
        val ra = results[a.domain]
        val rb = results[b.domain]
        fun rank(r: TestResult?) = when {
            r == null -> 1
            r.ok -> 0
            else -> 2
        }
        val c = rank(ra).compareTo(rank(rb))
        if (c != 0) c else (rb?.speedKBps ?: 0).compareTo(ra?.speedKBps ?: 0)
    }

    /** 测速中结果很多，合并成每 300ms 刷新一次。 */
    private fun postRefresh() {
        if (refreshPosted) return
        refreshPosted = true
        main.postDelayed({ refreshPosted = false; refresh() }, 300)
    }

    private fun showNodeDialog(node: Node) {
        val r = results[node.domain]
        val msg = buildString {
            append("地区：").append(node.region.ifEmpty { "未知" }).append('\n')
            if (node.isp.isNotEmpty()) append("运营商：").append(node.isp).append('\n')
            append("类型：").append(node.familyLabel)
            if (node.domain in officialHosts) append("（本次官方分配）")
            append('\n')
            append("测速：").append(r?.let(::describe) ?: "未测")
        }
        AlertDialog.Builder(this)
            .setTitle(node.domain)
            .setMessage(msg)
            .setPositiveButton("使用这个节点") { _, _ -> apply(config.copy(host = node.domain)) }
            .setNeutralButton("单独测速") { _, _ -> startTest(listOf(node), quick = false) }
            .setNegativeButton("复制域名") { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("host", node.domain))
            }
            .show()
    }

    // ---------- 测速 ----------

    private fun confirmAndTest(targets: List<Node>) {
        if (targets.isEmpty()) {
            Toast.makeText(this, "列表为空", Toast.LENGTH_SHORT).show()
            return
        }
        if (targets.size <= 120) return startTest(targets, quick = true)
        val minutes = (targets.size / PROBE_THREADS * 2.0 / 60).let { "%.1f".format(it) }
        AlertDialog.Builder(this)
            .setMessage("将测试 ${targets.size} 个节点，预计 $minutes 分钟以上。也可以先用搜索或筛选缩小范围（例如输入地区或运营商）。")
            .setPositiveButton("开始") { _, _ -> startTest(targets, quick = true) }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * quick=true：先用小请求并发探测全部节点，再对最快的几个逐个做完整测速
     * （逐个测，避免互相抢带宽）。quick=false：直接完整测速。
     */
    private fun startTest(targets: List<Node>, quick: Boolean) {
        val gen = generation.incrementAndGet()
        val cancelled = { generation.get() != gen }
        testing = true
        btnTest.text = getString(R.string.stop)
        progress.text = "正在获取测速样本……"
        io.execute {
            val sample = try {
                SpeedTester.fetchSample()
            } catch (e: Exception) {
                main.post { if (!cancelled()) { progress.text = "获取样本失败：${e.message}"; finishTest() } }
                return@execute
            }
            main.post { mergeOfficial(sample.officialHosts) }

            val hosts = targets.map { it.domain }
            if (quick) {
                val done = AtomicInteger()
                val pool = Executors.newFixedThreadPool(PROBE_THREADS)
                for (h in hosts) pool.execute {
                    if (cancelled()) return@execute
                    results[h] = SpeedTester.test(h, sample, PROBE_BYTES, PROBE_MILLIS, cancelled)
                    val n = done.incrementAndGet()
                    main.post { if (!cancelled()) progress.text = "快速探测 $n / ${hosts.size}"; postRefresh() }
                }
                pool.shutdown()
                while (!pool.awaitTermination(200, TimeUnit.MILLISECONDS)) {
                    if (cancelled()) pool.shutdownNow()
                }
            }

            val fullTargets = if (quick) {
                hosts.mapNotNull { h -> results[h]?.takeIf { it.ok }?.let { h to it } }
                    .sortedBy { it.second.totalMs }.take(FULL_TOP).map { it.first }
            } else hosts
            fullTargets.forEachIndexed { i, h ->
                if (cancelled()) return@execute
                main.post { if (!cancelled()) progress.text = "完整测速 ${i + 1} / ${fullTargets.size}：$h" }
                results[h] = SpeedTester.test(h, sample, FULL_BYTES, FULL_MILLIS, cancelled).copy(full = true)
                main.post { postRefresh() }
            }

            main.post {
                if (cancelled()) return@post
                val ok = hosts.count { results[it]?.ok == true }
                val best = hosts.mapNotNull { h -> results[h]?.takeIf { it.full && it.ok }?.let { h to it } }
                    .maxByOrNull { it.second.speedKBps }
                progress.text = "完成：可用 $ok / ${hosts.size}" +
                    (best?.let { "，最快 ${it.first}（${formatSpeed(it.second.speedKBps)}）" } ?: "")
                finishTest()
            }
        }
    }

    private fun mergeOfficial(hosts: List<String>) {
        officialHosts = hosts.toSet()
        val known = nodes.map { it.domain }.toHashSet()
        val extra = hosts.filter { it !in known }.map { Node(it, "接口分配", "official") }
        if (extra.isNotEmpty()) nodes = extra + nodes
        refresh()
    }

    private fun stopTest() {
        generation.incrementAndGet()
        progress.text = "已停止"
        finishTest()
    }

    private fun finishTest() {
        testing = false
        refresh()
    }

    private fun describe(r: TestResult): String =
        if (!r.ok) "失败（${r.error}）"
        else "${formatSpeed(r.speedKBps)} · 首包 ${r.ttfbMs} ms" + if (r.full) "" else "（快速探测）"

    private fun formatSpeed(kbps: Long) =
        if (kbps >= 1024) "%.1f MB/s".format(kbps / 1024.0) else "$kbps KB/s"

    private inner class NodeAdapter : BaseAdapter() {
        var items: List<Node> = emptyList()

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_node, parent, false)
            val n = items[position]
            val selected = n.domain == config.host
            v.findViewById<TextView>(R.id.domain).apply {
                text = if (selected) "✓ ${n.domain}" else n.domain
                setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            }
            v.findViewById<TextView>(R.id.meta).text = listOfNotNull(
                n.region.ifEmpty { null },
                n.isp.ifEmpty { null },
                n.familyLabel,
                if (n.domain in officialHosts) "★分配" else null,
            ).joinToString(" · ")
            val r = results[n.domain]
            v.findViewById<TextView>(R.id.result).text = when {
                r == null -> ""
                !r.ok -> r.error ?: "失败"
                r.full -> "${formatSpeed(r.speedKBps)}\n${r.ttfbMs} ms"
                else -> "≈${formatSpeed(r.speedKBps)}\n${r.ttfbMs} ms"
            }
            return v
        }
    }

    companion object {
        private const val MENU_UPDATE = 1
        private const val MENU_CUSTOM = 2

        private const val PROBE_THREADS = 8
        private const val PROBE_BYTES = 256L * 1024
        private const val PROBE_MILLIS = 2500L
        private const val FULL_TOP = 10
        private const val FULL_BYTES = 16L * 1024 * 1024
        private const val FULL_MILLIS = 5000L

        private val HOST_RE = Regex("""^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$""")

        private val FILTERS = listOf(
            "全部" to "",
            "本次分配" to "official",
            "已测可用" to "ok",
            "云存储 upos" to "upos",
            "自建节点" to "standard-cn",
            "视频边缘" to "video-edge",
            "海外/特殊" to "standard-ec",
            "自定义" to "custom",
        )
    }
}
