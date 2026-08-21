# LivePhotoUnlock — 微信实况照片解锁（libxposed Modern API 102）

绕过微信 8.0.77 的厂商白名单，让**任何机型**（包括 Pixel、三星等）都能使用实况照片（Live Photo）功能。

## 原理

微信 8.0.77 的实况照片功能有三层闸门：

```
厂商白名单（OPPO/vivo/Xiaomi/samsung 等 9 家）→ 读 Build.MANUFACTURER
  ↓ 不匹配时可被 G6 云控开关跳过
  ↓ 再通过 → 初始化 LivePhotoCore（APK 内的桩类，返回 -1000）
  ↓ 桩类失败 → wp.b.e = false → 所有 UI 闸门关闭
```

本模块使用**两阶段自动切换策略**：

### 阶段一（无补丁时）

- **wp/b.<clinit> hook**：原生 `<clinit>` 完整执行（上报与不支持机型完全一致），然后强制 `e=true`
- **Repairer 配置写入**：`C2C_Live_Preview_V2 = 1`（聊天实况预览开关）+ `C2C_Live_Send_V4 = 1`（发送开关）
- **云控发送开关**：`clicfg_chatting_c2c_live_send_v4 = 1`（expt MMKV）
- **效果**：实况照片 UI 出现（接收/发送入口），在线使用触发服务端下发 Tinker 补丁

### 阶段二（补丁落地后）

- 检测到 `tinker-boots-install-info` 非空 → 自动写入 G6 云控开关 + 杀微信进程
- 用户禁用模块并重启 → 微信原生读取 G6 → 跳过厂商白名单 → 补丁真实类接管 → **永久解锁，彻底摆脱 hook**

### 风控安全

- 不 hook 桩类方法（`initCore`/`isSupport`/`getCoreMetaData`）：避免自相矛盾的上报
- 不改系统属性（`Build.MANUFACTURER`/`SDK_INT`）：Java 与 native 上报全链路一致
- `<clinit>` 原生上报（`can_use_livePhoto=-1` 等）与任何不支持机型完全一致
- 所有配置写入都是微信官方 MMKV 存储（`Repairer` / `WxExptAppKeyMmkv`），纯本地持久化

## 构建

```bash
# 环境要求
# JDK 17+
# Android SDK（compileSdk 37, build-tools 36.0.0+）
# Gradle 9.4.1（wrapper 自动下载）

# 配置 local.properties
sdk.dir=/path/to/your/android-sdk

# 编译
./gradlew :app:assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`（约 10KB，已签名）

## 安装使用

1. 需 LSPosed（支持 libxposed API 102）
2. 安装 APK，LSPosed 中启用模块，作用域勾选 `com.tencent.mm`
3. 重启微信，模块自动进入阶段一（实况入口出现）
4. 在线使用，等待服务端下发 Tinker 补丁（微信自动检查，可多次重启加速）
5. 补丁落地后模块自动写 G6 固化并杀微信
6. LSPosed 禁用模块，重启微信 → 原生永久解锁

## 项目结构

```
LivePhotoUnlockHook.java   ← 核心 hook 逻辑（两阶段自动切换）
META-INF/xposed/           ← libxposed API 102 注册文件
  ├── java_init.list       ← 入口类注册
  ├── module.prop          ← 模块配置（minApiVersion=102）
  └── scope.list           ← 作用域（com.tencent.mm）
```

## 注意事项

- **补丁未下发时，只能查看实况，无法发送**：实况照片的识别、发送、保存依赖 Tinker 补丁中的真实 `LivePhotoCore` 实现。补丁未落地时：
  - ✅ 可接收/查看别人发的实况照片（系统级解码，不依赖补丁）
  - ❌ 聊天相册中自己的实况照片**不显示 LIVE 标记**（`isLivePhoto` 桩类返回空）
  - ❌ 无法发送、保存、导出实况（`exportLivePhoto` 桩类返回 -1000，提示"保存失败"）
- **补丁可能按厂商定向下发**：服务端可能仅对白名单厂商（OPPO/vivo/Xiaomi/samsung 等）下发包含真实 `LivePhotoCore` 的 Tinker 补丁。Pixel（厂商名 `Google`）等非白名单机型可能**收不到补丁**，此时模块只能解锁 UI，功能层面仍受桩类限制。可观察 `tinker-boots-install-info`（微信数据目录 `shared_prefs/tinker_patch_share_config.xml`）判断补丁是否落地。
- 微信版本升级后类名/结构可能变化 → hook 静默降级，不影响微信正常运行
- 换账号需重新写 G6（uin 绑定 MMKV 文件）
- 补丁灰度周期可能为数天到数周，请保持耐心

## 风控免责声明

- 本模块用于个人技术研究，通过修改微信本地配置/内存行为解锁功能
- **使用本模块可能触发微信风控（账号异常、功能受限、封号等），本模块不承担任何责任**
- 模块不伪造系统属性、不改动上报内容，但 LSPosed/Xposed 环境本身会被微信 root 检测标记
- **强烈建议使用测试小号验证，风险自负**