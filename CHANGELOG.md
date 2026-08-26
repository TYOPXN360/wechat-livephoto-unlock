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
