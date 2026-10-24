package io.github.mhyho.bilicdn.app

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.mhyho.bilicdn.core.CdnConfig
import java.util.concurrent.CopyOnWriteArraySet

class App : Application(), XposedServiceHelper.OnServiceListener {

    override fun onCreate() {
        super.onCreate()
        instance = this
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        App.service = service
        // 框架连上后把本地保存的选择同步过去
        runCatching { Settings.pushRemote(service, Settings.load(this)) }
            .onFailure { Log.w(TAG, "push config failed", it) }
        listeners.forEach { it() }
    }

    override fun onServiceDied(service: XposedService) {
        App.service = null
        listeners.forEach { it() }
    }

    companion object {
        private const val TAG = "BiliCDN"
        lateinit var instance: App
            private set

        @Volatile
        var service: XposedService? = null
            private set

        val listeners = CopyOnWriteArraySet<() -> Unit>()
    }
}

/** 本地保存一份选择，框架在线时同时写入 RemotePreferences。 */
object Settings {
    private const val NAME = "settings"
    private const val KEY_CUSTOM = "custom_hosts"

    fun load(ctx: Context): CdnConfig {
        val p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return CdnConfig.of(p.getString(CdnConfig.KEY_HOST, null), p.getBoolean(CdnConfig.KEY_BLOCK_PCDN, false))
    }

    /** 返回 true 表示已下发到框架；false 表示只存在本地（框架未连接）。 */
    fun save(ctx: Context, cfg: CdnConfig): Boolean {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString(CdnConfig.KEY_HOST, cfg.host)
            .putBoolean(CdnConfig.KEY_BLOCK_PCDN, cfg.blockPcdn)
            .apply()
        val s = App.service ?: return false
        return runCatching { pushRemote(s, cfg) }.isSuccess
    }

    fun pushRemote(service: XposedService, cfg: CdnConfig) {
        service.getRemotePreferences(CdnConfig.PREFS_GROUP).edit()
            .putString(CdnConfig.KEY_HOST, cfg.host)
            .putBoolean(CdnConfig.KEY_BLOCK_PCDN, cfg.blockPcdn)
            .apply()
    }

    fun customHosts(ctx: Context): Set<String> =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getStringSet(KEY_CUSTOM, emptySet()).orEmpty()

    fun addCustomHost(ctx: Context, host: String) {
        val p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        p.edit().putStringSet(KEY_CUSTOM, customHosts(ctx) + host).apply()
    }
}
