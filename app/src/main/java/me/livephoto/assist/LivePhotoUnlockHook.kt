package me.livephoto.assist

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.io.File
import java.io.RandomAccessFile

/**
 * 微信实况照片解锁 v2 —— B线第一阶段：模拟"真核心"（libxposed 102，纯 Kotlin）。
 *
 * 修正后的核心模型（逆向结论）：
 *   - 所有官方 APK/补丁里的 com.motion.core.LivePhotoCore 都是桩类：
 *     initCore/exportLivePhoto 返回 -1000，isLivePhoto 返回空表，
 *     且判定语义是【返回 0 才算成功】→ 纯 APK 在任何机型上都没有完整实况能力。
 *   - 厂商白名单（小米/OV/荣耀等）只决定是否创建核心实例（wp.b.b），
 *     不提供实现本身；能用的设备必然运行着带真核心的构建。
 *   - 聊天查看门控 nm5/f.a() = RepairerConfig_Chatting_C2C_Live_Preview_V2==1 && wp.b.e
 *     播放数据链路 wp/b.b() 第一步检查 wp.b.b != null → Pixel 上为 null 直接失败；
 *     朋友圈走独立播放器所以只靠 e=true 就能看。
 *
 * 本版改动（在 v1 基础上）：
 *   1. clinit 后除了 e=true，还反射创建 LivePhotoCore 实例并写入静态字段 b
 *      （消除所有 "livePhotoCore is null" 短路）。
 *   2. hook 桩类全部方法：
 *        initCore          → 返回 0（成功）
 *        isSupport         → 返回 true
 *        getVideoMetaData  → 尽力而为：mediaId 按 MediaStore 图片解析出文件路径，
 *                            从 JPEG 尾部扫描内嵌 MP4（ftyp box），抽取到调用方指定的
 *                            savePath，返回 {errorCode,videoPath,videoSize,videoDuration}；
 *                            无法解析时返回 "" 并打日志（采集参数语义）。
 *        isLivePhoto       → 真实识别：MediaStore 批量解析路径 + 文件尾部 MP4 特征检测，
 *                            返回真实 HashMap（相册 LIVE 角标由此点亮）。
 *        getCoreMetaData / exportLivePhoto → 本轮仅日志（采集导出流程参数语义）。
 *
 * 风控面：与 v1 相同的本地 MMKV 写入保持不变；新增 hook 只覆盖腾讯自己的
 *   桩类方法（该类当前无任何功能、无上报依赖其行为），不触碰网络层与消息内容。
 */
class LivePhotoUnlockHook : XposedModule() {

    private var hostLoader: ClassLoader? = null

    /** 真补丁已安装时置 true：所有核心方法 hook 自动放行原生实现（模块退位） */
    @Volatile
    private var nativePatchMode = false

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != PKG_WECHAT) return

        hostLoader = param.classLoader
        if (!isMainProcess()) return

        // ===== 1. wp/b.<clinit> 后：e=true + 创建核心实例 b =====
        try {
            val wpb = Class.forName(CLS_MM_LIVE_PHOTO, false, param.classLoader)
            hookClassInitializer(wpb)
                .setPriority(PRIORITY_HIGHEST)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (nativePatchMode) {
                        log(Log.INFO, TAG, "stage-1: native patch active, wp.b untouched")
                        return@intercept result
                    }
                    val eOk = setStaticBool(wpb, "e", true)
                    val bOk = installCoreInstance(wpb, param.classLoader)
                    log(
                        Log.INFO, TAG,
                        "stage-1: wp.b clinit done, e=$eOk, b-installed=$bOk"
                    )
                    result
                }
            log(Log.INFO, TAG, "stage-1 hook registered on wp.b")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "wp.b hook setup failed (version changed?)", t)
        }

        // ===== 2. LivePhotoCore 桩方法接管 =====
        try {
            val core = Class.forName(CLS_CORE, false, param.classLoader)

            // initCore(Context)I -> 0（原生补丁模式下放行）
            hook(core.getDeclaredMethod("initCore", Context::class.java))
                .setPriority(PRIORITY_HIGHEST)
                .intercept { chain ->
                    if (nativePatchMode) {
                        chain.proceed()
                    } else {
                        log(Log.INFO, TAG, "core.initCore(${argsString(chain.getArgs())}) -> 0")
                        0
                    }
                }

            // isSupport()Z -> true
            hook(core.getDeclaredMethod("isSupport"))
                .setPriority(PRIORITY_HIGHEST)
                .intercept { chain ->
                    if (nativePatchMode) chain.proceed() else true
                }

            // getCoreMetaData()String -> ""（本轮仅观察）
            hook(core.getDeclaredMethod("getCoreMetaData"))
                .setPriority(PRIORITY_HIGHEST)
                .intercept { chain ->
                    if (nativePatchMode) {
                        chain.proceed()
                    } else {
                        log(Log.INFO, TAG, "core.getCoreMetaData() probed -> \"\"")
                        ""
                    }
                }

            // getVideoMetaData(J, String)String -> 尽力提取内嵌视频
            hook(
                core.getDeclaredMethod(
                    "getVideoMetaData",
                    Long::class.javaPrimitiveType, String::class.java
                )
            )
                .setPriority(PRIORITY_HIGHEST)
                .intercept { chain ->
                    if (nativePatchMode) {
                        chain.proceed()
                    } else {
                        val mediaId = (chain.getArgs()[0] as? Number)?.toLong() ?: -1L
                        val savePath = chain.getArgs()[1] as? String ?: ""
                        log(Log.INFO, TAG, "core.getVideoMetaData(mediaId=$mediaId, savePath=$savePath)")
                        val json = tryExtractVideo(mediaId, savePath)
                        if (json != null) {
                            log(Log.INFO, TAG, "core.getVideoMetaData -> $json")
                            json
                        } else {
                            log(Log.WARN, TAG, "core.getVideoMetaData -> \"\" (cannot resolve/extract)")
                            ""
                        }
                    }
                }

            // isLivePhoto(List<Long>)HashMap -> 真实识别
            hook(core.getDeclaredMethod("isLivePhoto", List::class.java))
                .setPriority(PRIORITY_HIGHEST)
                .intercept { chain ->
                    if (nativePatchMode) {
                        chain.proceed()
                    } else {
                        val ids = (chain.getArgs()[0] as? List<Long>) ?: emptyList()
                        val map = detectLivePhotos(ids)
                        log(Log.INFO, TAG, "core.isLivePhoto(${ids.size} ids) -> ${map.count { it.value }} live")
                        map
                    }
                }

            // exportLivePhoto(String json)I -> 真实导出：合成动态JPEG + 封面
            hook(core.getDeclaredMethod("exportLivePhoto", String::class.java))
                .setPriority(PRIORITY_HIGHEST)
                .intercept { chain ->
                    if (nativePatchMode) {
                        chain.proceed()
                    } else {
                        val json = chain.getArgs()[0] as? String ?: ""
                        val ok = tryExportLivePhoto(json)
                        log(
                            Log.INFO, TAG,
                            "core.exportLivePhoto($json) -> ${if (ok) 0 else -1000}"
                        )
                        if (ok) 0 else chain.proceed()
                    }
                }

            log(Log.INFO, TAG, "core hooks registered on $CLS_CORE")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "core hook setup failed", t)
        }

        // ===== 3. Instrumentation.callApplicationOnCreate：比 Application.onCreate 更稳的时机 =====
        try {
            val ins = Class.forName("android.app.Instrumentation", false, param.classLoader)
            val m = ins.getDeclaredMethod(
                "callApplicationOnCreate", Application::class.java
            )
            hook(m)
                .setPriority(PRIORITY_HIGHEST)
                .intercept { chain ->
                    val result = chain.proceed()
                    val app = chain.getArgs()[0] as? Application
                    if (app != null) {
                        try {
                            sAppContext = app.baseContext ?: app.applicationContext
                            scheduleConfigWrites()
                        } catch (t: Throwable) {
                            log(Log.ERROR, TAG, "appOnCreate write failed", t)
                        }
                    }
                    result
                }
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Instrumentation hook setup failed", t)
        }

        // ===== 4. 每次 Activity onResume 兜底重试（补丁加载可能大幅推迟 MMKV 就绪）=====
        try {
            val ins = Class.forName("android.app.Instrumentation", false, param.classLoader)
            val m = ins.getDeclaredMethod("callActivityOnResume", android.app.Activity::class.java)
            hook(m)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (!allWritesDone()) {
                        try { attemptConfigWrites() } catch (_: Throwable) {}
                    }
                    result
                }
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "onResume hook setup failed (non-fatal)", t)
        }
    }

    // ==================== 核心实例注入 ====================

    /** 反射创建 LivePhotoCore 并写入 wp.b.b（消除 null 短路） */
    private fun installCoreInstance(wpb: Class<*>, loader: ClassLoader): Boolean {
        return try {
            val core = Class.forName(CLS_CORE, false, loader)
                .getDeclaredConstructor().newInstance()
            val f = wpb.getDeclaredField("b")
            f.isAccessible = true
            f.set(null, core)
            true
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "installCoreInstance failed", t)
            false
        }
    }

    private fun setStaticBool(wpb: Class<*>, name: String, value: Boolean): Boolean {
        return try {
            val f = wpb.getDeclaredField(name)
            f.isAccessible = true
            f.setBoolean(null, value)
            true
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "setStaticBool($name) failed", t)
            false
        }
    }

    // ==================== 实况识别与视频提取 ====================

    /** 批量解析 MediaStore 图片路径并做运动照片检测 */
    private fun detectLivePhotos(ids: List<Long>): HashMap<Long, Boolean> {
        val out = HashMap<Long, Boolean>()
        val app = hostApp() ?: return out
        val idToPath = resolveImagePaths(app, ids)
        for ((id, path) in idToPath) {
            out[id] = isMotionPhotoFile(path)
        }
        return out
    }

    /** MediaStore 分块查询 _id -> _data */
    private fun resolveImagePaths(app: Application, ids: List<Long>): Map<Long, String> {
        val result = HashMap<Long, String>()
        if (ids.isEmpty()) return result
        val uri: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val chunk = 300
        var i = 0
        while (i < ids.size) {
            val part = ids.subList(i, minOf(i + chunk, ids.size))
            i += chunk
            val sel = StringBuilder("_id IN (")
            val args = arrayOfNulls<String>(part.size)
            part.forEachIndexed { idx, id ->
                if (idx > 0) sel.append(',')
                sel.append('?')
                args[idx] = id.toString()
            }
            sel.append(')')
            var c: Cursor? = null
            try {
                c = app.contentResolver.query(uri, PROJECTION, sel.toString(), args, null)
                while (c != null && c.moveToNext()) {
                    val id = c.getLong(0)
                    val path = c.getString(1)
                    if (!path.isNullOrEmpty()) result[id] = path
                }
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "resolveImagePaths query failed", t)
            } finally {
                try { c?.close() } catch (_: Throwable) {}
            }
        }
        return result
    }

    /** 运动照片检测：文件尾部窗口内寻找 MP4 ftyp box 头 */
    private fun isMotionPhotoFile(path: String): Boolean {
        return try {
            val f = File(path)
            if (!f.isFile || f.length() < 64L) return false
            val len = f.length()
            val tailLen = if (len > TAIL_WINDOW) TAIL_WINDOW else len.toInt()
            val buf = ByteArray(tailLen)
            RandomAccessFile(f, "r").use { raf ->
                raf.seek(len - tailLen)
                raf.readFully(buf)
            }
            findFtyp(buf) >= 0
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "isMotionPhotoFile($path) error", t)
            false
        }
    }

    /**
     * 从 mediaId 对应的图片中提取内嵌 MP4 写到 savePath，
     * 返回微信期望的 JSON；无法完成返回 null。
     */
    private fun tryExtractVideo(mediaId: Long, savePath: String): String? {
        if (savePath.isEmpty()) return null
        val app = hostApp() ?: return null
        val srcPath = resolveImagePaths(app, listOf(mediaId))[mediaId] ?: return null
        return try {
            val src = File(srcPath)
            val data = src.readBytes()
            val off = findFtyp(data)
            if (off < 0) return null
            val dst = File(savePath)
            dst.parentFile?.mkdirs()
            java.io.FileOutputStream(dst).use { fos ->
                fos.write(data, off, data.size - off)
            }
            synchronized(sVideoSource) { sVideoSource[dst.absolutePath] = srcPath }
            val durationMs = parseMvhdDurationMs(data, off)
            val durField = if (durationMs > 0L) ",\"videoDuration\":$durationMs" else ""
            val json =
                "{\"errorCode\":0,\"videoPath\":\"${dst.absolutePath.replace("\\", "\\\\")}\"," +
                    "\"videoSize\":${data.size - off}$durField}"
            json
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "tryExtractVideo($srcPath) failed", t)
            null
        }
    }

    // ==================== 导出：合成动态 JPEG ====================

    /** videoFilePath -> 源图片路径（提取时记录，供导出阶段回查） */
    private val sVideoSource = HashMap<String, String>()

    private fun readJpegFile(f: File): ByteArray? {
        return try {
            if (!f.isFile || f.length() < 4L) return null
            val b = f.readBytes()
            if (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) b else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun findEmbeddedVideoIn(b: ByteArray): ByteArray? {
        val off = findFtyp(b)
        return if (off >= 0) b.copyOfRange(off, b.size) else null
    }

    /**
     * exportLivePhoto(json) 真实实现。
     * json 字段：{videoPath: 独立视频路径, coverPath: 封面输出, exportPath: 合成动态JPEG输出,
     *            coverTimeStampMs}
     * 行为：image(JPEG) + video(MP4) 直接拼接成动态照片格式写入 exportPath；
     *      封面写 coverPath；确保 videoPath 存在。成功返回 true。
     */
    private fun tryExportLivePhoto(json: String): Boolean {
        if (json.isBlank()) return false
        return try {
            val obj = org.json.JSONObject(json)
            val videoPath = obj.optString("videoPath")
            val coverPath = obj.optString("coverPath")
            val exportPath = obj.optString("exportPath")
            if (exportPath.isEmpty()) {
                log(Log.WARN, TAG, "export: no exportPath in json")
                return false
            }

            // ---- 收集视频字节 ----
            var videoBytes: ByteArray? = null
            if (videoPath.isNotEmpty()) {
                val vf = File(videoPath)
                if (vf.isFile && vf.length() > 64L) videoBytes = vf.readBytes()
            }

            // ---- 收集图像字节 ----
            var imageBytes: ByteArray? = null
            imageBytes = readJpegFile(File(coverPath))
            if (imageBytes == null && videoPath.isNotEmpty()) {
                synchronized(sVideoSource) { imageBytes = readJpegFile(File(sVideoSource[videoPath])) }
            }
            if (imageBytes == null && !coverPath.isNullOrEmpty()) {
                // 封面路径同目录找同名/任意 jpg 兜底
                val dir = File(coverPath).parentFile
                if (dir != null && dir.isDirectory) {
                    val cand = dir.listFiles { _, name -> name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) }
                        ?.sortedByDescending { it.lastModified() }?.firstOrNull()
                    if (cand != null) imageBytes = readJpegFile(cand)
                }
            }

            // ---- 图像里若已内嵌视频而视频缺失，可反向提取 ----
            if (videoBytes == null && imageBytes != null) {
                videoBytes = findEmbeddedVideoIn(imageBytes)
            }
            val vb = videoBytes
            val ib = imageBytes
            if (vb == null || ib == null) {
                log(Log.WARN, TAG, "export: missing pieces (img=${ib?.size}, video=${vb?.size})")
                return false
            }

            // ---- 输出 ----
            File(exportPath).parentFile?.mkdirs()
            java.io.FileOutputStream(File(exportPath)).use { it.write(ib + vb) }
            if (coverPath.isNotEmpty()) {
                val cf = File(coverPath)
                cf.parentFile?.mkdirs()
                if (!cf.isFile) java.io.FileOutputStream(cf).use { it.write(ib) }
            }
            if (videoPath.isNotEmpty() && !File(videoPath).isFile) {
                java.io.FileOutputStream(File(videoPath)).use { it.write(vb) }
            }
            log(
                Log.INFO, TAG,
                "export ok: img=${ib.size}B video=${vb.size}B -> $exportPath"
            )
            true
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "tryExportLivePhoto failed", t)
            false
        }
    }

    /** 在视频数据里找 mvhd 解析时长毫秒（全范围扫描 + box 大小合法性校验）；失败返回 0 */
    private fun parseMvhdDurationMs(data: ByteArray, from: Int): Long {
        return try {
            var i = from
            val end = data.size - 4
            while (i < end) {
                if (data[i] == 'm'.code.toByte() && data[i + 1] == 'v'.code.toByte() &&
                    data[i + 2] == 'h'.code.toByte() && data[i + 3] == 'd'.code.toByte()
                ) {
                    // mvhd 前面 4 字节是自身 box size（典型 100~152）
                    val boxSize = readI32(data, i - 4)
                    if (boxSize in 80..4096) {
                        val p = i + 4 // version byte
                        val dur = if (data[p].toInt() == 1) {
                            // v1: creation(8) modification(8) timescale(4)@p+20 duration(8)@p+24
                            val ts = readI32(data, p + 20)
                            val du = readI64(data, p + 24)
                            if (ts > 0) du * 1000 / ts else 0L
                        } else {
                            // v0: creation(4) modification(4) timescale(4)@p+12 duration(4)@p+16
                            val ts = readI32(data, p + 12)
                            val du = readI32(data, p + 16).toLong() and 0xFFFFFFFFL
                            if (ts > 0) du * 1000 / ts else 0L
                        }
                        if (dur > 0) return dur
                    }
                }
                i++
            }
            0L
        } catch (_: Throwable) {
            0L
        }
    }

    private fun readI32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun readI64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (k in 0 until 8) v = (v shl 8) or (b[o + k].toLong() and 0xFF)
        return v
    }

    /** 反向扫描 ftyp box 头（[size:4]['f','t','y','p']），返回起始偏移或 -1 */
    private fun findFtyp(buf: ByteArray): Int {
        var i = buf.size - 8
        while (i >= 0) {
            if (buf[i + 4] == 'f'.code.toByte() && buf[i + 5] == 't'.code.toByte() &&
                buf[i + 6] == 'y'.code.toByte() && buf[i + 7] == 'p'.code.toByte()
            ) {
                val sz = readI32(buf, i)
                if (sz >= 8 && sz < 200_000_000) return i
            }
            i--
        }
        return -1
    }

    // ==================== 配置写入（持久收敛：读回校验 + 多触发点） ====================

    private val sWriteLock = Any()
    private var sAppContext: android.content.Context? = null
    private var sPreviewDone = false
    private var sSendDone = false
    private var sExptDone = false
    private var sStage2Done = false
    private var sRetryScheduled = false
    private var sFailCount = 0

    private fun allWritesDone(): Boolean =
        sPreviewDone && sSendDone && sExptDone && sStage2Done

    /** 延迟重试（0/8/20/40s）+ 每 10s 周期兜底，全部成功后自停 */
    private fun scheduleConfigWrites() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val delays = longArrayOf(0L, 8_000L, 20_000L, 40_000L)
        for (d in delays) {
            handler.postDelayed({ runAttempt() }, d)
        }
        synchronized(sWriteLock) {
            if (!sRetryScheduled) {
                sRetryScheduled = true
                val periodic = object : Runnable {
                    override fun run() {
                        if (allWritesDone()) return
                        runAttempt()
                        handler.postDelayed(this, 10_000L)
                    }
                }
                handler.postDelayed(periodic, 60_000L)
            }
        }
    }

    private fun runAttempt() {
        try {
            attemptConfigWrites()
        } catch (t: Throwable) {
            sFailCount++
            if (sFailCount % 5 == 1) {
                log(Log.WARN, TAG, "config write retry #${sFailCount} failed", t)
            }
        }
    }

    private fun attemptConfigWrites() {
        val ctx = sAppContext ?: return
        synchronized(sWriteLock) {
            var dirty = false
            val mkv = if (!sPreviewDone || !sSendDone) mmkv(MMKV_REPAIRER) else null

            // ---- Repairer 预览开关（写后读回校验）----
            if (!sPreviewDone && mkv != null) {
                mmkvPutInt(mkv, KEY_REPAIRER_PREVIEW, 1); mmkvSync(mkv)
                if (mmkvGetInt(mkv, KEY_REPAIRER_PREVIEW, 0) == 1) {
                    sPreviewDone = true; dirty = true
                    log(Log.INFO, TAG, "Repairer written+verified: $KEY_REPAIRER_PREVIEW=1")
                }
            }
            // ---- Repairer 发送开关 ----
            if (!sSendDone && mkv != null) {
                mmkvPutInt(mkv, KEY_REPAIRER_SEND, 1); mmkvSync(mkv)
                if (mmkvGetInt(mkv, KEY_REPAIRER_SEND, 0) == 1) {
                    sSendDone = true; dirty = true
                    log(Log.INFO, TAG, "Repairer written+verified: $KEY_REPAIRER_SEND=1")
                }
            }
            // ---- 云控 expt 发送开关 ----
            if (!sExptDone) {
                val uin = ctx.getSharedPreferences(SP_SYSTEM_CONFIG, Application.MODE_PRIVATE)
                    .getInt(KEY_UIN, 0)
                if (uin != 0) {
                    val keyMkv = mmkv("${uin}_WxExptAppKeyMmkv")
                    val idMkv = mmkv("${uin}_WxExptAppIdMmkv")
                    if (keyMkv != null && idMkv != null) {
                        mmkvPutInt(keyMkv, KEY_EXPT_SEND, EXPT_ID_SEND)
                        mmkvPutString(idMkv, EXPT_ID_SEND.toString(), exptJson(EXPT_ID_SEND, KEY_EXPT_SEND))
                        mmkvSync(keyMkv); mmkvSync(idMkv)
                        if (mmkvGetInt(keyMkv, KEY_EXPT_SEND, 0) == EXPT_ID_SEND) {
                            sExptDone = true; dirty = true
                            log(Log.INFO, TAG, "expt written+verified: $KEY_EXPT_SEND")
                        }
                    }
                }
            }
            // ---- 阶段二检测（配置就绪后执行一次）----
            if (!sStage2Done && sPreviewDone && sExptDone) {
                sStage2Done = true
                handleStage2(ctx)
            }
            if (!allWritesDone()) {
                log(
                    Log.INFO, TAG,
                    "config pending: preview=$sPreviewDone send=$sSendDone expt=$sExptDone stage2=$sStage2Done"
                )
            } else if (dirty) {
                log(Log.INFO, TAG, "all config writes verified ✓")
            }
        }
    }

    private fun handleStage2(context: android.content.Context) {
        val sp = context.getSharedPreferences(SP_TINKER_SHARE, Application.MODE_PRIVATE)
        val installInfo = sp.getString(KEY_BOOTS_INSTALL, "")
        val hasPatch = !installInfo.isNullOrEmpty()
        nativePatchMode = hasPatch
        log(
            Log.INFO, TAG,
            "stage-2 check: tinker-boots-install-info=$installInfo, nativePatchMode=$hasPatch"
        )
        if (!hasPatch) return

        val uin = context.getSharedPreferences(SP_SYSTEM_CONFIG, Application.MODE_PRIVATE)
            .getInt(KEY_UIN, 0)
        if (uin == 0) return

        val keyMkv = mmkv("${uin}_WxExptAppKeyMmkv") ?: return
        val already = mmkvGetInt(keyMkv, KEY_WRITTEN_MARK, 0)
        if (already != 0) return
        mmkvPutInt(keyMkv, KEY_G6, EXPT_ID_G6)
        mmkvPutInt(keyMkv, KEY_WRITTEN_MARK, 1)
        val idMkv = mmkv("${uin}_WxExptAppIdMmkv") ?: return
        mmkvPutString(idMkv, EXPT_ID_G6.toString(), exptJson(EXPT_ID_G6, KEY_G6))
        mmkvSync(keyMkv)
        mmkvSync(idMkv)

        log(Log.INFO, TAG, "stage-2: G6 written (uin=$uin), killing WeChat")
        Process.killProcess(Process.myPid())
    }

    // ==================== 反射 MMKV 工具 ====================

    private fun mmkv(mmapId: String): Any? {
        val loader = hostLoader ?: return null
        return try {
            val mmkvCls = Class.forName(MMKV_CLASS, false, loader)
            mmkvCls.getMethod("mmkvWithID", String::class.java).invoke(null, mmapId)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "mmkvWithID failed: $mmapId", t)
            null
        }
    }

    private fun mmkvPutInt(mkv: Any, key: String, value: Int) {
        try {
            mkv.javaClass.getMethod("putInt", String::class.java, Int::class.javaPrimitiveType)
                .invoke(mkv, key, value)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "mmkv putInt failed: $key", t)
        }
    }

    private fun mmkvPutString(mkv: Any, key: String, value: String) {
        try {
            mkv.javaClass.getMethod("putString", String::class.java, String::class.java)
                .invoke(mkv, key, value)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "mmkv putString failed: $key", t)
        }
    }

    private fun mmkvGetInt(mkv: Any, key: String, def: Int): Int {
        return try {
            mkv.javaClass.getMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
                .invoke(mkv, key, def) as? Int ?: def
        } catch (t: Throwable) {
            def
        }
    }

    private fun mmkvSync(mkv: Any) {
        try {
            mkv.javaClass.getMethod("sync").invoke(mkv)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "mmkv sync failed", t)
        }
    }

    // ==================== 工具 ====================

    private fun hostApp(): Application? {
        return try {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? Application
        } catch (t: Throwable) {
            null
        }
    }

    /** hook 参数安全转字符串 */
    private fun argsString(args: Any?): String = when (args) {
        is Array<*> -> args.joinToString(", ")
        is Collection<*> -> args.joinToString(", ")
        else -> args.toString()
    }

    private fun isMainProcess(): Boolean {
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val name = activityThread.getDeclaredMethod("currentProcessName").invoke(null) as? String
            name == PKG_WECHAT
        } catch (_: Throwable) {
            true
        }
    }

    companion object {
        private const val TAG = "LivePhotoUnlock"
        private const val PKG_WECHAT = "com.tencent.mm"
        private const val CLS_MM_LIVE_PHOTO = "wp.b"
        private const val CLS_CORE = "com.motion.core.LivePhotoCore"

        private const val SP_TINKER_SHARE = "tinker_patch_share_config"
        private const val KEY_BOOTS_INSTALL = "tinker-boots-install-info"

        private const val MMKV_CLASS = "com.tencent.mmkv.MMKV"
        private const val MMKV_REPAIRER = "Repairer"
        private const val KEY_REPAIRER_PREVIEW = "RepairerConfig_Chatting_C2C_Live_Preview_V2"
        private const val KEY_REPAIRER_SEND = "RepairerConfig_Chatting_C2C_Live_Send_V4"

        private const val SP_SYSTEM_CONFIG = "system_config_prefs"
        private const val KEY_UIN = "default_uin"

        private const val KEY_EXPT_SEND = "clicfg_chatting_c2c_live_send_v4"
        private const val KEY_G6 = "clicfg_live_photo_extra_manufacturer"
        private const val KEY_WRITTEN_MARK = "_g6_written"
        private const val EXPT_ID_G6 = 99999
        private const val EXPT_ID_SEND = 100000

        private val PROJECTION = arrayOf("_id", "_data")

        /** 运动照片尾部检测窗口（MP4 一般 1~3 秒，几 MB 内） */
        private const val TAIL_WINDOW = 12 * 1024 * 1024

        private const val VAL_BASE64_ONE = "MQ=="

        private fun exptJson(exptId: Int, key: String): String =
            "{\"ExptId\":$exptId,\"GroupId\":0,\"ExptSequence\":1,\"Priority\":1,\"NeedReport\":0," +
                "\"StartTime\":0,\"EndTime\":0,\"ExptType\":4,\"SvrType\":1,\"ExptCheckSum\":\"\"," +
                "\"Args\":[{\"Key\":\"$key\",\"Val\":\"$VAL_BASE64_ONE\"}]}"
    }
}
