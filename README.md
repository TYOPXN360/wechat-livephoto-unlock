# LivePhotoUnlock — 微信实况照片解锁（libxposed Modern API 102）

让**任何机型**（包括 Pixel、三星等非白名单设备）完整使用微信实况照片（Live Photo）功能：相册 LIVE 识别、聊天/朋友圈发送、接收查看、保存导出——**无需等待官方热补丁**。

## 原理

逆向确认的核心事实：

```
1. 所有官方 APK / Tinker 补丁里的 com.motion.core.LivePhotoCore 都是桩类：
   initCore → -1000，isLivePhoto → 空表，exportLivePhoto → -1000
2. 判定语义是【返回 0 才算成功】：wp.b.e = (initCore() == 0)
   → 纯 APK 在任何机型上都没有完整的实况能力
3. 厂商白名单（OPPO/vivo/Xiaomi/HONOR/samsung 等）只决定是否尝试创建核心实例，
   不提供实现本身
4. 聊天实况播放门控 = RepairerConfig_Chatting_C2C_Live_Preview_V2 == 1 且 wp.b.e
   接收消息自带 xxx.jpg + xxx.jpg_lp（视频伴生文件），播放不依赖核心
```

本模块的做法：**模拟真核心**。hook 桩类的全部方法，提供可工作的实现：

| 桩方法 / 节点 | 模块实现 | 打通的功能 |
|---|---|---|
| `initCore(Context)` | 返回 0 | 总开关 e=true |
| `isSupport()` | 返回 true | 初始化流程放行 |
| `isLivePhoto(List<Long>)` | MediaStore 解析路径 + 文件尾部 MP4 特征扫描（兼容 Google/Xiaomi 等内嵌式动态照片） | **相册 LIVE 角标、可选实况** |
| `getVideoMetaData(id, savePath)` | 提取内嵌 MP4 写入 savePath，返回 `{errorCode, videoPath, videoSize, videoDuration, videoWidth, videoHeight}`（mvhd/tkhd 解析） | 预览与发送所需的视频数据及尺寸 |
| `exportLivePhoto(String json)` | 按 MMLivePhotoExportData JSON 输出动态 JPEG（图+视频拼接）+ 封面 | 保存到相册 |
| `wp/b.b` 校验出口 | 强制放行成功标志 `t0.a = true` | 绕过 Pixel 照片 4:3 与内嵌视频 16:9 的 **宽高比（Ratio Error）严苛拦截** |
| `Remux worker`（`yt4.b0.Vi` / `np4.b0.mh` / `ox4.b0.dj` 等各版本入口） | 直接透传文件至 VFS 目标，跳过重编码 | 消除非白名单机型走软编导致的**转码死锁/数分钟超时降级**（4ms 秒发） |
| 聊天实况门控（`nm5.f.a()` / `mq5.f.a()` + `RepairerConfigC2CLiveImagePreview.c()`） | 强制返回 true / 1 | 恢复**聊天界面实况按钮与播放能力**（含 8.0.78 设备指纹白名单绕过） |

**多版本自适应（v2.9.0 起）**：微信每次升级都会重新混淆，模块不再依赖固定类名——启动时用内置 dex 方法表解析器按**方法签名结构**在 APK 内探测关键类：

- remux worker：同类同时声明「三 String 挂起方法」+「RecordConfigProvider 挂起方法」
- remux 结果类：worker 同 dex 内的 `(ZI)` 构造器类
- 实况包装类：持有 `LivePhotoCore` 类型字段的类（快路径仍走类名名单）

已实测 8.0.77 / Play 8.0.72 / 8.0.78，JVM 单测用三个真实 APK 回归。

配置门控写入带**持久收敛机制**：

- 写后读回校验，未确认不算完成
- 补全 9 项关键实况配置（包括聊天预览、发送、自动开启、朋友圈保存/发表/预下载、硬编通道等）
- 触发点三重保险：`Instrumentation.callApplicationOnCreate` 之后立即 + 定时重试 + 每次 Activity.onResume 兜底
- 抵御 Tinker 补丁加载导致的 MMKV 初始化时序漂移

**补丁自退位**：一旦检测到已安装的 Tinker 补丁（`tinker-boots-install-info` 非空），所有核心方法 hook 自动放行原生实现——若腾讯下发了真核心，模块自动让路。

## 已验证功能矩阵

| 功能 | 状态 |
|---|---|
| 相册 LIVE 角标识别（88+ 张照片精准命中） | ✅ |
| 聊天选择实况并发送（直通秒发） | ✅ |
| 朋友圈发表实况（直通秒发） | ✅ |
| 接收查看聊天实况（动图+实况按钮正常播放） | ✅ |
| 接收查看朋友圈实况 | ✅ |
| 保存到相册（带 LIVE 标记） | ✅ |

测试环境：Redmi K30 Pro（Android 16 / Play 8.0.72 小号观察中）、Google Pixel 9 Pro XL（Android 17 / 8.0.78 无补丁 主号），另回归 8.0.77 有补丁环境。

## 构建

```bash
# 环境要求：JDK 17+、Android SDK（compileSdk 37）
# 配置 local.properties: sdk.dir=/path/to/android-sdk

./gradlew :app:assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

## 安装使用

1. 需要 LSPosed（支持 libxposed API 102）
2. 安装 APK → LSPosed 启用模块 → 作用域勾选 `com.tencent.mm`
3. 重启微信即可，全部功能自动生效，无需等待任何补丁

## 注意事项

- 微信版本升级后混淆类名会变化 → 模块按**方法签名结构**在 APK 内自动探测关键类（remux worker / 包装类 / 结果类），不依赖固定类名；已实测 8.0.77、Play 8.0.72、8.0.78
- 未安装模块的接收方如果其微信没有实况能力，看到的将是静态图片（与普通不支持机型的表现一致）；白名单真机用户收到的可正常查看
- 长按相册中的实况条目会复制一段内部状态字符串——这是微信官方自带的灰度调试功能，无害
- 日志标签：LSPosed 日志中过滤 `LivePhotoUnlock` 可观察各环节执行情况

## 风控免责声明

- 本模块用于个人技术研究，通过修改微信本地配置/内存行为解锁功能
- **使用本模块可能触发微信风控（账号异常、功能受限、封号等），本模块不承担任何责任**
- 模块不伪造系统属性、不改动网络上报内容；但 LSPosed/Xposed 环境本身可能被检测标记
- **强烈建议先在测试小号上验证，稳定观察数日后再考虑日常使用，风险自负**
