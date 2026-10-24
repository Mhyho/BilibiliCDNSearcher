package io.github.mhyho.bilicdn.hook

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.mhyho.bilicdn.core.CdnConfig
import io.github.mhyho.bilicdn.core.UrlRewriter
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap

/**
 * 在 B 站播放器构建分片时改写地址。
 *
 * hook 点：tv.danmaku.ijk.media.player.IjkMediaAsset$MediaAssertSegment$Builder
 *   - <init>(String url, int)          主地址
 *   - setBackupUrls(Collection)        备用地址
 * 已在 tv.danmaku.bili 9.16.1 上核对存在（类名未混淆）。
 */
class CdnHook : XposedModule() {

    @Volatile
    private var config = CdnConfig()

    // RemotePreferences 用 WeakHashMap 保存监听器，这里必须持有强引用
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** 构造函数里被改写的分片 → 原始主地址，供 setBackupUrls 重新规划。 */
    private val originals = Collections.synchronizedMap(WeakHashMap<Any, String>())

    @Volatile
    private var loggedForConfig: CdnConfig? = null

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage || param.packageName !in TARGETS) return
        loadConfig()
        try {
            installHooks(param.classLoader)
            log(Log.INFO, TAG, "hooks installed in ${param.packageName}, config=$config")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "install hooks failed in ${param.packageName}", t)
        }
    }

    private fun loadConfig() {
        val prefs = try {
            getRemotePreferences(CdnConfig.PREFS_GROUP)
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "remote preferences unavailable", t)
            return
        }
        fun read() {
            config = CdnConfig.of(
                prefs.getString(CdnConfig.KEY_HOST, null),
                prefs.getBoolean(CdnConfig.KEY_BLOCK_PCDN, false),
            )
        }
        read()
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            read()
            log(Log.INFO, TAG, "config updated: $config")
        }.also { prefs.registerOnSharedPreferenceChangeListener(it) }
    }

    private fun installHooks(cl: ClassLoader) {
        val builderClass = Class.forName(BUILDER, false, cl)
        val segmentClass = Class.forName(SEGMENT, false, cl)
        val targetField: Field = builderClass.getDeclaredField("target").apply { isAccessible = true }
        val urlField: Field = segmentClass.getDeclaredField("url").apply { isAccessible = true }

        val ctor = builderClass.getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType)
        hook(ctor).intercept { chain ->
            val cfg = config
            val url = chain.getArg(0) as String?
            if (!cfg.active || url == null) return@intercept chain.proceed()
            val newUrl = UrlRewriter.plan(url, emptyList(), cfg).base
            if (newUrl == url) return@intercept chain.proceed()
            originals[chain.thisObject] = url
            logOnce(cfg, url, newUrl)
            chain.proceed(arrayOf(newUrl, chain.getArg(1)))
        }

        val setBackup = builderClass.getDeclaredMethod("setBackupUrls", Collection::class.java)
        hook(setBackup).intercept { chain ->
            val cfg = config
            val builder = chain.thisObject
            val original = originals.remove(builder)
            if (!cfg.active) return@intercept chain.proceed()
            val target = targetField.get(builder) ?: return@intercept chain.proceed()
            val current = urlField.get(target) as String? ?: return@intercept chain.proceed()
            val backups = (chain.getArg(0) as Collection<*>?)?.filterIsInstance<String>().orEmpty()
            val plan = UrlRewriter.plan(original ?: current, backups, cfg)
            if (plan.base != current) {
                urlField.set(target, plan.base)
                logOnce(cfg, current, plan.base)
            }
            chain.proceed(arrayOf<Any>(ArrayList(plan.backups)))
        }
    }

    private fun logOnce(cfg: CdnConfig, from: String, to: String) {
        if (loggedForConfig == cfg) return
        loggedForConfig = cfg
        log(Log.INFO, TAG, "rewrite ${UrlRewriter.hostOf(from)} -> ${UrlRewriter.hostOf(to)}")
    }

    companion object {
        private const val TAG = "BiliCDN"
        private const val SEGMENT = "tv.danmaku.ijk.media.player.IjkMediaAsset\$MediaAssertSegment"
        private const val BUILDER = "$SEGMENT\$Builder"
        val TARGETS = setOf(
            "tv.danmaku.bili",
            "com.bilibili.app.blue",
            "tv.danmaku.bilibilihd",
            "com.bilibili.app.in",
        )
    }
}
