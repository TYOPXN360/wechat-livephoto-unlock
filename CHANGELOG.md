# Changelog

## v2.8.0 (2026-09-02)

**适配 Google Play 版微信 8.0.72，多版本混淆类名自适应。**

### 核心突破与修复
1. **多版本包装类自适应 (`findLivePhotoWrapper`)**：
   - Play 版 8.0.72 的实况包装类为 `fq.b`（国内 8.0.77 为 `wp.b`），硬编码类名导致 Play 版模块完全不生效
   - 按「字段 `b` 类型 == `com.motion.core.LivePhotoCore`」特征自动在候选类（`wp.b` / `qp.b` / `fq.b`）中定位包装类，不再依赖固定类名
2. **Remux 直通多版本适配 (`np4.b0`)**：
   - Play 版聊天/朋友圈转码入口为 `np4.b0.mh` / `np4.b0.vh`（国内 8.0.77 为 `yt4.b0.Vi` / `Ui`），结果类为 `ad0.e(ZI)`（国内为 `re0.e`）
   - 此前 Play 版上 remux 旁路注册失败（`ClassNotFoundException: yt4.b0`），发送实况会复发软编卡死；现按候选类自动挂载，Play 版全链路与国内版行为一致
3. **Play 版验证**：
   - Play 8.0.72(3085) 全部 9 组钩子注册成功，`fq.b` 强制成功、核心实例注入、remux 直通均正常工作

---

## v2.7.0 (2026-08-29)

**支持朋友圈实况转码直通与 Android 17 跨进程 Intent 脱敏。**

### 核心突破与修复
1. **朋友圈实况转码直通 (`yt4/b0.Ui`)**：
   - 逆向发现朋友圈实况发表使用的是 `SnsPublishLivePhotoRemuxChain`（走 `yt4.b0.Ui` 接口）
   - 新增针对 `Ui` 的直通复制与封面补全，解决朋友圈发表卡死几分钟超时的问题
2. **封面缩略图自动生成补全 (`ensureThumbFile`)**：
   - 上传管理器（UploadManager）严格校验 `remuxThumbPath` 缩略图文件是否存在
   - 新增从源图提取 JPEG 或同目录同名补全封面逻辑，保证上传校验 100% 通过
3. **Android 17 跨进程 Intent 脱敏 (`BadParcelableException`)**：
   - Android 17 的 `system_server` 对 `startActivity` 传递的私有 Parcelable 做深层解包校验
   - 拦截包含 `SnsPublishLivePhotoItem` 的 Extra 自动封装为标准 `byte[]`，接收端无损还原，避免系统层拦截丢弃跳转

---

## v2.6.0 (2026-08-27)

**打通发送全链路与聊天实况查看，彻底解决发送卡死、超时降级与查看黑屏问题。**

### 核心突破与修复
1. **转码（Remux）直通**：
   - 逆向发现发送时微信后台协程会对视频执行 Remux 压缩，Pixel 等非白名单机型因缺乏硬件编码支持而走入软编死循环或挂起数分钟超时
   - 直通 `yt4.b0.Vi`，瞬间将提取的原始标准 MP4 交付给 VFS 会话目录，发送由“数分钟卡死”缩短为“4 毫秒秒发”
2. **视频宽高比校验（Ratio Error）放行**：
   - 逆向定位到微信底层会比对图片宽高比与视频宽高比（`Math.abs(coverRatio - videoRatio) > 0.1` 则报错丢弃）
   - Pixel 相机拍摄的原图为 4:3，内嵌视频默认 16:9，导致必中 ratio error 校验失败
   - 在 `wp/b.b()` 出口处执行状态放行，无视宽高比差异强行通过
3. **补全视频尺寸信息**：
   - 从视频的 `tkhd` atom 精准提取宽度与高度填入 `videoWidth`/`videoHeight`，满足新版补丁的严格结构校验
4. **聊天查看门控强制接管**：
   - 补丁版替换了 `nm5.f` 门控类，强制 `nm5.f.a() -> true`，彻底恢复聊天界面实况按钮与播放能力
5. **补全 9 项实况专属 Repairer 本地配置**：
   - 写入 HEVC 硬件编码通道、相册自动开启、朋友圈保存/发表/预下载等全部配置项
6. **代码深度清理**：
   - 剔除无用冗余逻辑与多余日志打印，启动初始化耗时降至 0.04 秒

---

## v2.5.0 (2026-08-27)

**修复微信下发 Tinker 热补丁后模块失效的问题。**

逆向发现：Tinker 补丁延迟挂载，在 `onPackageReady` 时用 `param.classLoader` 解析到的
类是原版；而补丁安装后微信运行时实际使用的是**补丁版类**（含 MMKV 本身也被补丁替换）。
此前所有 hook 都挂在原版类上，热补丁一到全部失效。

### 修复
- **hook 注册时机重构**：全部 hook 移至 `Instrumentation.callApplicationOnCreate`
  之后注册，用最终 classloader（`app.classLoader`）解析类——补丁版 wp.b、
  LivePhotoCore、MMKV 全部正确接管
- **MMKV 兜底初始化**：补丁版 MMKV 会报 "You should Call MMKV.initialize() first"，
  写入前先调幂等的 `MMKV.initialize(context)`，并加重入保护防止与 initialize 钩子互相递归（StackOverflowError）
- **MMKV 实例缓存**：hook 所有 `mmkvWithID` 重载，截获微信已打开的实例优先复用
- **配置写入收敛加固**：0/8/20s 定时重试 + Activity.onResume 兜底 + 5 秒最小间隔防刷

### 清理
- 移除无用的 hostLoader 字段；更新文件头注释为当前架构描述
# Changelog

## v2.0.0 (2026-08-26)

**质变版本：从"只能查看"到"全功能"。不再依赖腾讯下发补丁。**

逆向新结论（修正 v1 的错误假设）：
- 所有官方 APK / Tinker 补丁中的 `LivePhotoCore` 均为桩类（`initCore→-1000`、`isLivePhoto→空表`）
- 判定语义为 `e = (initCore() == 0)`，桩类永远失败——纯 APK 在任何机型上都没有完整实况能力
- 厂商白名单只是"允许初始化"的资格，不提供实现；能用的设备必然运行着真核心

新增：
- **模拟真核心**：hook 桩类全部方法并提供可工作实现
  - `initCore → 0`、`isSupport → true`
  - `isLivePhoto`：MediaStore 路径解析 + 文件尾部 MP4 特征扫描，真实识别动态照片（兼容 Google/Xiaomi 内嵌格式）
  - `getVideoMetaData`：提取内嵌 MP4 到指定路径，返回 `{errorCode, videoPath, videoSize, videoDuration}`（mvhd v0/v1 时长解析）
  - `exportLivePhoto`：按 MMLivePhotoExportData JSON 合成动态 JPEG + 封面
- **配置写入持久收敛**：写后读回校验；Instrumentation.callApplicationOnCreate 入口 + 定时/周期/Activity.onResume 多重兜底重试，抵御 Tinker 补丁导致的 MMKV 时序漂移
- **补丁自退位**：检测到已安装补丁时自动放行原生实现

验证：
- 相册 LIVE 角标识别、聊天发送、朋友圈发表、接收查看（聊天+朋友圈）、保存到相册全链路通过
- 实测识别率：66 张照片中正确识别 39 张动态照片，视频时长解析 0.9~3.0s 全部准确

## v1.0.0 (2026-08-24)

- 两阶段自动切换策略：解锁实况 UI + 等待官方 Tinker 补丁落地后固化 G6 云控
- Repairer / WxExptAppKeyMmkv 本地配置写入
- wp.b.<clinit> hook 强制 e=true（保留原生上报行为）
- 局限：只能接收/查看实况，无法识别与发送（v1 误判"等待补丁"可行——后续证实所有已观测补丁均为桩类）
