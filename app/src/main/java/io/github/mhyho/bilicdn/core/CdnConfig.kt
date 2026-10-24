package io.github.mhyho.bilicdn.core

/** 模块 App 与 B 站进程共用的配置。通过 libxposed RemotePreferences 下发。 */
data class CdnConfig(
    /** 选定的节点域名；null 表示不替换。 */
    val host: String? = null,
    /** 是否把 PCDN（用户节点）地址挪到最后或去掉。 */
    val blockPcdn: Boolean = false,
) {
    val active: Boolean get() = host != null || blockPcdn

    companion object {
        const val PREFS_GROUP = "cdn"
        const val KEY_HOST = "host"
        const val KEY_BLOCK_PCDN = "block_pcdn"

        fun of(host: String?, blockPcdn: Boolean) =
            CdnConfig(host?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }, blockPcdn)
    }
}
