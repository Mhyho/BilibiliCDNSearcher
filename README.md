# B站 CDN 选择器（BilibiliCDNSearcher）

一个基于现代 Xposed API（libxposed API 101/102）的模块：在当前网络下搜索、测速哔哩哔哩视频 CDN 节点，并让 B 站客户端改用你选定的节点。

## 功能

- **搜索**：内置约 1070 个点播节点（upos 云存储镜像、`cn-<地区>-<运营商>` 自建节点、视频边缘节点、海外节点），可按域名 / 地区 / 运营商关键字和类型筛选；可在 App 内更新节点库，也可添加自定义域名。测速时还会把 B 站接口本次给当前网络分配的节点标为“★分配”。
- **测速**：先用小请求并发探测列表中的全部节点（256 KB），再对最快的 10 个逐个做完整测速（最多 16 MB / 5 秒），显示下载速度和首包耗时。测速在手机上进行，结果反映当前网络。
- **选择**：点节点 →“使用这个节点”。选择通过 libxposed RemotePreferences 实时下发到 B 站进程，重新打开视频即生效。“恢复官方分配”取消替换。
- **屏蔽 PCDN**：把 mcdn / szbdyd / IP:端口 形式的用户节点挪出主地址。

## 工作原理

- **hook 点**：`tv.danmaku.ijk.media.player.IjkMediaAsset$MediaAssertSegment$Builder` 的构造函数 `(String url, int)` 和 `setBackupUrls(Collection)`。播放器在这里拿到每个分片的主地址和备用地址，模块把主地址换成选定节点，原地址全部保留为备用，选定节点不可用时播放器会自动回落。
- **为什么能换域名**：upos 地址的签名（`upsig`）不含域名，同一条地址可以在各镜像和自建节点间通用。akamai 节点还要校验 `hdnts` 令牌，选 akamai 时会优先用带令牌的那条地址做源。PCDN 地址的路径和签名规则不同，不能直接换域名。
- **测速样本**：模块 App 调用网页版播放接口（无需登录）取一条公开视频的分片地址，把域名换成各个节点后用 Range 请求下载。
- **只处理点播**：直播流（`live-bvc` / gotcha）不改动。

改写规则在 `core/UrlRewriter.kt`，有单元测试覆盖。

## 使用要求

- 已 root，并安装支持 **libxposed API 101 及以上**的框架，例如 [Vector](https://github.com/JingMatrix/Vector)。官方 LSPosed 最后一个正式版 1.9.2 不支持这套 API。
- 框架需支持远程配置（`PROP_CAP_REMOTE`），否则选择无法下发到 B 站进程。
- Android 8.0（API 26）及以上。
- 作用域：`tv.danmaku.bili`（国内版）、`com.bilibili.app.blue`（概念版）、`tv.danmaku.bilibilihd`（HD）、`com.bilibili.app.in`（国际版）。

## 使用步骤

1. 安装 APK，在框架管理器里启用模块，勾选要生效的 B 站版本，然后强行停止 B 站。
2. 打开“B站 CDN 选择器”，顶部应显示“框架：…已连接”。
3. 用搜索或筛选缩小范围（例如输入“香港”“电信”“upos”），点“测速列表中的 N 个节点”。
4. 测完后点最快的节点 →“使用这个节点”，回到 B 站重新打开视频。
5. 换了网络（Wi-Fi ↔ 移动数据、出国等）后，建议重新测速再选。

## 构建

需要 JDK 17 以上（本仓库在 JDK 25 上构建）和 Android SDK（platform 37.2）。

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25.0.3"
.\gradlew.bat testDebugUnitTest assembleRelease
```

产物在 `app/build/outputs/apk/`。release 目前用 debug 签名，仅供自用。

## 验证情况

- hook 的类名、方法签名、字段名（`target`、`url`）已在哔哩哔哩 9.16.1（2026-10-22 官方包）上用 dexdump 核对。
- 节点换域名、测速逻辑已在桌面网络上实测：upos 镜像、`cn-hk-eq`、`cn-bj-fx` 等节点可用，部分节点（如 `mirror08c`）已无法解析。
- 模块 App 界面已在 WSA 上验证（WSA 无 Xposed 框架，也无外网）。
- **尚未在装有框架的真机上验证 hook 实际生效**。如果 B 站更新后不生效，先在框架日志里搜 `BiliCDN`。

## 数据来源与致谢

- 节点库来自 [babywbx/BiliCDN](https://github.com/babywbx/BiliCDN)（MIT），该项目每周通过 DNS 与 HTTP 探测更新 B 站 CDN 节点目录。`app/src/main/assets/nodes.meta.json` 是其数据快照。
- 播放器层改写地址、PCDN 判断的思路参考了哔哩漫游（BiliRoaming，GPL-3.0），本仓库代码为独立实现。

仅供学习与个人使用。
