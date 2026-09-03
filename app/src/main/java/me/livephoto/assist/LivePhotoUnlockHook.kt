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
 * 微信实况照片解锁（libxposed 102，纯 Kotlin）。
 *
 * 核心模型（逆向结论）：
 *   - 所有官方 APK/补丁里的 com.motion.core.LivePhotoCore 都是桩类：
 *     initCore/exportLivePhoto 返回 -1000，isLivePhoto 返回空表，
 *     且判定语义是【返回 0 才算成功】→ 纯 APK 在任何机型上都没有完整实况能力。
 *   - 厂商白名单（小米/OV/荣耀等）只决定是否创建核心实例（wp.b.b），
 *     不提供实现本身；能用的设备必然运行着带真核心的构建。
 *   - 聊天查看门控 nm5/f.a() = RepairerConfig_Chatting_C2C_Live_Preview_V2==1 && wp.b.e
 *
 * 实现要点：
 *   1. hook 注册时机：Instrumentation.callApplicationOnCreate 之后（Tinker 补丁
 *      已挂载），用 app.classLoader 解析类——否则 hook 挂在原版类上全部失效。
 *   2. 桩类接管：
 *        initCore → 0、isSupport → true、getCoreMetaData → ""
 *        isLivePhoto → 真实识别（MediaStore 解析 + 文件尾 ftyp 扫描）
 *        getVideoMetaData → 提取内嵌 MP4 + mvhd 时长，返回微信约定 JSON
 *        exportLivePhoto → 图+视频合成动态 JPEG（保存到相册带 LIVE 标记）
 *   3. 配置写入持久收敛：写后读回校验；MMKV.initialize/mmkvWithID 双钩子对齐
 *      微信生命周期；补丁版 MMKV 需先 initialize（幂等），有重入保护防递归。
 *   4. 补丁自退位：检测到已安装补丁时自动放行原生实现。
 *
 * 风控面：只写微信官方 MMKV 本地配置，hook 只覆盖腾讯无功能的桩类与 MMKV 观察点，
 *   不触碰网络层与消息内容上报。
 */
class LivePhotoUnlockHook : XposedModule() {

    /** 已解析的实况包装类（多版本自适应匹配结果） */
    private var wrapperClass: Class<*>? = null

    /** 真补丁已安装时置 true：所有核心方法 hook 自动放行原生实现（模块退位） */
    @Volatile
    private var nativePatchMode = false

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != PKG_WECHAT) return

        if (!isMainProcess()) return

        // ===== 1. 等 Application 创建完成（Tinker 补丁已挂载）后，用最终 classloader 注册全部 hook =====
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
                            registerAllHooks(app.classLoader)
                            scheduleConfigWrites()
                        } catch (t: Throwable) {
                            log(Log.ERROR, TAG, "appOnCreate failed", t)
                        }
                    }
                    result
                }
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Instrumentation hook setup failed", t)
        }

        // ===== 2. 每次 Activity onResume 兜底重试 =====
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

    /** 多版本自适应：名单快路径 → dex 结构探测兜底（内含 LivePhotoCore 类型字段的类） */
    private fun findLivePhotoWrapper(loader: ClassLoader): Class<*>? {
        for (name in WRAPPER_CANDIDATES) {
            try {
                val cls = Class.forName(name, false, loader)
                if (findCoreField(cls) != null) return cls
            } catch (_: Throwable) {}
        }
        // 兜底：扫 APK field_ids，找「持有 LivePhotoCore 类型字段的类」
        val probed = runCatching { DexProbe.findWrapper(appApkPath()) }.getOrNull() ?: return null
        return try { Class.forName(probed, false, loader) } catch (_: Throwable) { null }
    }

    /** 找到 LivePhotoCore 类型的字段（类型匹配，不依赖字段名） */
    private fun findCoreField(wrapper: Class<*>): java.lang.reflect.Field? {
        return runCatching { wrapper.getDeclaredField("b") }.getOrNull()?.takeIf { it.type.name == CLS_CORE }
            ?: wrapper.declaredFields.firstOrNull { it.type.name == CLS_CORE }
    }

    /** 在补丁已加载的最终 classloader 上注册所有 hook */
    private fun registerAllHooks(loader: ClassLoader) {
        // ----- 实况包装类 <clinit> 后：e=true + 创建核心实例 b -----
        try {
            val wpb = findLivePhotoWrapper(loader) ?: throw ClassNotFoundException("no wrapper found for candidates $WRAPPER_CANDIDATES")
            wrapperClass = wpb
            hookClassInitializer(wpb)
                .setPriority(PRIORITY_HIGHEST)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (nativePatchMode) {
                        log(Log.INFO, TAG, "stage-1: native patch active, ${wpb.name} untouched")
                        return@intercept result
                    }
                    val eOk = setStaticBool(wpb, "e", true)
                    val bOk = installCoreInstance(wpb, loader)
                    log(
                        Log.INFO, TAG,
                        "stage-1: ${wpb.name} clinit done, e=$eOk, b-installed=$bOk"
                    )
                    result
                }
            log(Log.INFO, TAG, "stage-1 hook registered on ${wpb.name}")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "wrapper hook setup failed (version changed?)", t)
        }

        // ----- LivePhotoCore 桩方法接管 -----
        try {
            val core = Class.forName(CLS_CORE, false, loader)

            hook(core.getDeclaredMethod("initCore", Context::class.java))
                .setPriority(PRIORITY_HIGHEST)
                .intercept { chain ->
                    if (nativePatchMode) { chain.proceed() } else {
                        log(Log.INFO, TAG, "core.initCore(${argsString(chain.getArgs())}) -> 0"); 0
                    }
                }

            hook(core.getDeclaredMethod("isSupport"))
                .setPriority(PRIORITY_HIGHEST)
                .intercept { chain -> if (nativePatchMode) chain.proceed() else true }

            hook(core.getDeclaredMethod("getCoreMetaData"))
                .setPriority(PRIORITY_HIGHEST)
                .intercept { chain ->
                    if (nativePatchMode) chain.proceed() else {
                        log(Log.INFO, TAG, "core.getCoreMetaData() probed -> \"\""); ""
                    }
                }

            hook(core.getDeclaredMethod("getVideoMetaData", Long::class.javaPrimitiveType, String::class.java))
                .setPriority(PRIORITY_HIGHEST)
                .intercept { chain ->
                    if (nativePatchMode) chain.proceed() else {
                        val mediaId = (chain.getArgs()[0] as? Number)?.toLong() ?: -1L
                        val savePath = chain.getArgs()[1] as? String ?: ""
                        log(Log.INFO, TAG, "core.getVideoMetaData(mediaId=$mediaId, savePath=$savePath)")
                        val json = tryExtractVideo(mediaId, savePath)
                        if (json != null) { log(Log.INFO, TAG, "core.getVideoMetaData -> $json"); json }
                        else { log(Log.WARN, TAG, "core.getVideoMetaData -> \"\""); "" }
                    }
                }

            hook(core.getDeclaredMethod("isLivePhoto", List::class.java))
                .setPriority(PRIORITY_HIGHEST)
                .intercept { chain ->
                    if (nativePatchMode) chain.proceed() else {
                        val ids = (chain.getArgs()[0] as? List<Long>) ?: emptyList()
                        val map = detectLivePhotos(ids)
                        log(Log.INFO, TAG, "core.isLivePhoto(${ids.size} ids) -> ${map.count { it.value }} live")
                        map
                    }
                }

            hook(core.getDeclaredMethod("exportLivePhoto", String::class.java))
                .setPriority(PRIORITY_HIGHEST)
                .intercept { chain ->
                    if (nativePatchMode) chain.proceed() else {
                        val json = chain.getArgs()[0] as? String ?: ""
                        val ok = tryExportLivePhoto(json)
                        log(Log.INFO, TAG, "core.exportLivePhoto -> ${if (ok) 0 else -1000}")
                        if (ok) 0 else chain.proceed()
                    }
                }

            log(Log.INFO, TAG, "core hooks registered on $CLS_CORE")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "core hook setup failed", t)
        }

        // ----- 包装类 b() 校验放行（放行 ratio error / 格式不兼容等所有校验） -----
        try {
            val wpb = wrapperClass ?: throw IllegalStateException("wrapper class not resolved")
            val bMethod = wpb.getDeclaredMethod(
                "b", Long::class.javaPrimitiveType, String::class.java,
                String::class.java, Long::class.javaPrimitiveType
            )
            hook(bMethod)
                .setPriority(PRIORITY_HIGHEST)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (result != null) {
                        try {
                            val cls = result.javaClass
                            val t0 = cls.getDeclaredField("a").apply { isAccessible = true }.get(result)
                            if (t0 != null) {
                                val t0Cls = t0.javaClass
                                val successField = t0Cls.getDeclaredField("a").apply { isAccessible = true }
                                val origSuccess = successField.getBoolean(t0)
                                successField.setBoolean(t0, true)
                                val errField = t0Cls.getDeclaredField("b").apply { isAccessible = true }
                                val origErr = errField.getInt(t0)
                                if (!origSuccess) {
                                    errField.setInt(t0, 0)
                                }
                                if (!origSuccess) {
                                    val mediaId = (chain.getArgs().firstOrNull() as? Number)?.toLong() ?: -1L
                                    log(Log.INFO, TAG, "wp/b.b forced: origSuccess=false origErr=$origErr -> true id=$mediaId")
                                }
                            }
                        } catch (t: Throwable) {
                            log(Log.WARN, TAG, "wp/b.b force success failed", t)
                        }
                    }
                    result
                }
        } catch (_: Throwable) {}

        // ----- 强制聊天实况门控（已验证对聊天查看有效） -----
        // 根因：8.0.78 门控 mq5.f.a() = sj(RepairerConfigC2CLiveImagePreview,true)==1 && wp.b.e，
        // 而该 config 的默认值 c() 带设备指纹白名单（非白名单恒 0），写 MMKV 无效。
        // 修法：config 类名三版稳定未混淆（repairer 反射注册依赖），直接 hook 其默认值 c() -> 1；
        // 再 hook 门控方法 a()（8.0.77 nm5.f / 8.0.78 mq5.f，名字各异，hook 失败不影响 c() 方案）。
        try {
            runCatching {
                val cfg = Class.forName("com.tencent.mm.repairer.config.chatting.RepairerConfigC2CLiveImagePreview", false, loader)
                val c = cfg.declaredMethods.firstOrNull {
                    it.name == "c" && it.parameterTypes.isEmpty() && it.returnType == Any::class.java
                } ?: error("c() not found")
                hook(c).setPriority(PRIORITY_HIGHEST).intercept { _ -> 1 }
                log(Log.INFO, TAG, "preview config default forced: ${cfg.simpleName}.c() -> 1")
            }.onFailure { log(Log.WARN, TAG, "preview config hook failed", it) }
            // 门控方法 a() 兜底（类名随版本变，找到哪个算哪个）
            for (gateName in arrayOf("nm5.f", "mq5.f")) {
                runCatching {
                    val g = Class.forName(gateName, false, loader)
                    hook(g.getDeclaredMethod("a")).setPriority(PRIORITY_HIGHEST).intercept { _ -> true }
                    log(Log.INFO, TAG, "preview gate: $gateName.a() -> true")
                }
            }
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "preview gate hook failed", t)
        }

        // ----- 设备 HEVC 硬件编码能力放行（让 Pixel 走硬件编码器，不卡软编） -----
        try {
            val checkerCls = Class.forName("px3.n", false, loader)
            for (m in checkerCls.methods) {
                if (m.name == "c" && m.parameterTypes.size == 1 && m.returnType == Boolean::class.javaPrimitiveType) {
                    hook(m)
                        .setPriority(PRIORITY_HIGHEST)
                        .intercept { _ -> true }
                    break
                }
            }
        } catch (_: Throwable) {}

        // ----- 直通 Remux 转码：跳过软/硬编直接复制目标文件（解决聊天与朋友圈转码卡死/降级/发表失败） -----
        // 结构探测：同类含「三 String 挂起」+「RecordConfigProvider 挂起」= remux worker（三版验证）
        try {
            val probed = DexProbe.findRemux(appApkPath())
            val remuxCls = probed?.let { p ->
                runCatching { Class.forName(p.worker, false, loader) }.getOrNull().also {
                    if (it != null) log(Log.INFO, TAG, "dex-probe remux: ${p.worker}.${p.chat}/${p.sns} -> ${p.result}")
                }
            } ?: throw ClassNotFoundException("no remux class (dex-probe failed)")
            for (m in remuxCls.methods) {
                // 1. 聊天 C2C 实况转码直通（签名特征：3+ 参且前 3 参均为 String；src,dst,thumb）
                if (m.name == probed?.chat || (probed == null && m.parameterTypes.size >= 4 &&
                            m.parameterTypes[0] == String::class.java && m.parameterTypes[1] == String::class.java &&
                            m.parameterTypes[2] == String::class.java && m.name.length <= 2)) {
                    hook(m)
                        .setPriority(PRIORITY_HIGHEST)
                        .intercept { chain ->
                            val args = chain.getArgs()
                            val srcPath = args[0] as? String ?: ""
                            val dstPath = args[1] as? String ?: ""
                            val thumbPath = args.getOrNull(2) as? String ?: ""
                            log(Log.INFO, TAG, "chat remux bypass: src=$srcPath dst=$dstPath thumb=$thumbPath")
                            var copyOk = false
                            if (srcPath.isNotEmpty() && dstPath.isNotEmpty()) {
                                try {
                                    val src = File(srcPath)
                                    val dst = File(dstPath)
                                    dst.parentFile?.mkdirs()
                                    if (src.isFile && src.length() > 0L) {
                                        src.copyTo(dst, overwrite = true)
                                        copyOk = true
                                        log(Log.INFO, TAG, "chat remux copy ok: ${src.length()}B -> $dstPath")
                                    }
                                } catch (t: Throwable) {
                                    log(Log.ERROR, TAG, "chat remux copy failed", t)
                                }
                            }
                            ensureThumbFile(srcPath, thumbPath)
                            newRemuxResult(loader, copyOk) ?: chain.proceed()
                        }
                    log(Log.INFO, TAG, "${remuxCls.name}.${m.name} (chat remux) bypass hooked")
                }

                // 2. 朋友圈 SNS 实况转码直通（签名特征：2 参 RecordConfigProvider + Continuation）
                if (m.parameterTypes.size == 2 && m.parameterTypes[0].name == "com.tencent.mm.plugin.recordvideo.jumper.RecordConfigProvider") {
                    hook(m)
                        .setPriority(PRIORITY_HIGHEST)
                        .intercept { chain ->
                            val provider = chain.getArgs()[0]
                            var srcPath = ""
                            var dstPath = ""
                            var thumbPath = ""
                            if (provider != null) {
                                try {
                                    val pCls = provider.javaClass
                                    srcPath = (pCls.getField("A").get(provider) as? String)
                                        ?: (pCls.getDeclaredField("A").apply { isAccessible = true }.get(provider) as? String) ?: ""
                                    dstPath = (pCls.getField("B").get(provider) as? String)
                                        ?: (pCls.getDeclaredField("B").apply { isAccessible = true }.get(provider) as? String) ?: ""
                                    thumbPath = (pCls.getField("C").get(provider) as? String)
                                        ?: (pCls.getDeclaredField("C").apply { isAccessible = true }.get(provider) as? String) ?: ""
                                } catch (t: Throwable) {
                                    log(Log.WARN, TAG, "sns remux provider read failed", t)
                                }
                            }
                            log(Log.INFO, TAG, "sns remux bypass: src=$srcPath dst=$dstPath thumb=$thumbPath")
                            var copyOk = false
                            if (srcPath.isNotEmpty() && dstPath.isNotEmpty()) {
                                try {
                                    val src = File(srcPath)
                                    val dst = File(dstPath)
                                    dst.parentFile?.mkdirs()
                                    if (src.isFile && src.length() > 0L) {
                                        src.copyTo(dst, overwrite = true)
                                        copyOk = true
                                        log(Log.INFO, TAG, "sns remux copy ok: ${src.length()}B -> $dstPath")
                                    }
                                } catch (t: Throwable) {
                                    log(Log.ERROR, TAG, "sns remux copy failed", t)
                                }
                            }
                            ensureThumbFile(srcPath, thumbPath)
                            newRemuxResult(loader, copyOk) ?: chain.proceed()
                        }
                    log(Log.INFO, TAG, "${remuxCls.name}.${m.name} (sns remux) bypass hooked")
                }
            }
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "remux bypass hook setup failed (non-fatal)", t)
        }

        // ----- Android 17 跨进程 Parcelable 脱敏传递（解决 system_server 反序列化 BadParcelableException） -----
        try {
            // 1. 拦截 Intent 放入 Parcelable 列表，将包含 SnsPublishLivePhotoItem 的 Extra 序列化为 byte[]
            val intentCls = android.content.Intent::class.java
            val putParcList = intentCls.getMethod("putParcelableArrayListExtra", String::class.java, java.util.ArrayList::class.java)
            hook(putParcList)
                .setPriority(PRIORITY_HIGHEST)
                .intercept { chain ->
                    val key = chain.getArgs()[0] as? String ?: ""
                    val list = chain.getArgs()[1] as? java.util.ArrayList<*>
                    val intent = chain.getThisObject() as? android.content.Intent
                    if (intent != null && list != null && list.isNotEmpty() && list[0]?.javaClass?.name?.contains("SnsPublishLivePhotoItem") == true) {
                        try {
                            val parcel = android.os.Parcel.obtain()
                            parcel.writeList(list)
                            val bytes = parcel.marshall()
                            parcel.recycle()
                            intent.putExtra(PARCEL_BLOB_PREFIX + key, bytes)
                            log(Log.INFO, TAG, "parcelable sanitized for intent: key=$key count=${list.size} bytes=${bytes.size}")
                            return@intercept intent
                        } catch (t: Throwable) {
                            log(Log.WARN, TAG, "parcelable sanitization failed, fallback normal", t)
                        }
                    }
                    chain.proceed()
                }

            // 2. 拦截 Intent 读取 Parcelable 列表，如果存在脱敏 byte[] 则用微信 classloader 反序列化还原
            val getParcList = intentCls.getMethod("getParcelableArrayListExtra", String::class.java)
            hook(getParcList)
                .setPriority(PRIORITY_HIGHEST)
                .intercept { chain ->
                    val key = chain.getArgs()[0] as? String ?: ""
                    val intent = chain.getThisObject() as? android.content.Intent
                    val blobKey = PARCEL_BLOB_PREFIX + key
                    if (intent != null && intent.hasExtra(blobKey)) {
                        val bytes = intent.getByteArrayExtra(blobKey)
                        if (bytes != null && bytes.isNotEmpty()) {
                            try {
                                val parcel = android.os.Parcel.obtain()
                                parcel.unmarshall(bytes, 0, bytes.size)
                                parcel.setDataPosition(0)
                                val list = java.util.ArrayList<Any?>()
                                parcel.readList(list, loader)
                                parcel.recycle()
                                log(Log.INFO, TAG, "parcelable restored from blob: key=$key count=${list.size}")
                                return@intercept list
                            } catch (t: Throwable) {
                                log(Log.WARN, TAG, "parcelable restore failed from blob", t)
                            }
                        }
                    }
                    chain.proceed()
                }
            log(Log.INFO, TAG, "Android 17 Intent parcelable sanitizer hooked")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "parcelable sanitizer hook setup failed", t)
        }

        // ----- MMKV.initialize / mmkvWithID 钩子（补丁版 MMKV） -----
        try {
            val mmkvCls = Class.forName(MMKV_CLASS, false, loader)
            for (m in mmkvCls.methods) {
                if (m.name == "initialize" && java.lang.reflect.Modifier.isStatic(m.modifiers)) {
                    hook(m).setPriority(PRIORITY_HIGHEST).intercept { chain ->
                        val r = chain.proceed()
                        // 我们自己 mmkv() 里的 initialize 调用不触发写入（防递归）
                        if (!sInMmkvInit && !allWritesDone()) {
                            try { attemptConfigWrites() } catch (_: Throwable) {}
                        }
                        r
                    }
                }
            }
            for (m in mmkvCls.methods) {
                if (m.name == "mmkvWithID" && java.lang.reflect.Modifier.isStatic(m.modifiers)) {
                    hook(m).setPriority(PRIORITY_HIGHEST).intercept { chain ->
                        val r = chain.proceed()
                        if (r != null) {
                            val id = chain.getArgs().firstOrNull() as? String
                            if (id != null) synchronized(sCachedMmkv) { sCachedMmkv[id] = r }
                        }
                        r
                    }
                }
            }
            log(Log.INFO, TAG, "MMKV initialize/mmkvWithID hooks registered")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "MMKV hook setup failed (non-fatal)", t)
        }
    }

    // ==================== 核心实例注入 ====================

    /** 反射创建 LivePhotoCore 并写入 wp.b.b（消除 null 短路） */
    private fun installCoreInstance(wpb: Class<*>, loader: ClassLoader): Boolean {
        return try {
            val core = Class.forName(CLS_CORE, false, loader)
                .getDeclaredConstructor().newInstance()
            val f = findCoreField(wpb) ?: return false
            f.isAccessible = true
            f.set(null, core)
            true
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "installCoreInstance failed", t)
            false
        }
    }

    /** 静态布尔字段按类型找（wrapper 的「是否支持」开关），不依赖字段名 */
    private fun setStaticBool(wpb: Class<*>, @Suppress("UNUSED_PARAMETER") name: String, value: Boolean): Boolean {
        return try {
            val f = wpb.declaredFields.firstOrNull {
                java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == Boolean::class.javaPrimitiveType
            } ?: return false
            f.isAccessible = true
            f.setBoolean(null, value)
            true
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "setStaticBool failed", t)
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
            val (w, h) = parseTkhdSize(data, off)
            val durField = if (durationMs > 0L) ",\"videoDuration\":$durationMs" else ""
            val sizeField = if (w > 0 && h > 0) ",\"videoWidth\":$w,\"videoHeight\":$h" else ""
            val json =
                "{\"errorCode\":0,\"videoPath\":\"${dst.absolutePath.replace("\\", "\\\\")}\"," +
                    "\"videoSize\":${data.size - off}$durField$sizeField,\"coverTimeStampMs\":0}"
            json
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "tryExtractVideo($srcPath) failed", t)
            null
        }
    }

    // ==================== 导出：合成动态 JPEG ====================

    /** videoFilePath -> 源图片路径（提取时记录，供导出与转码阶段回查） */
    private val sVideoSource = HashMap<String, String>()

    /** 构造 remux 结果对象（(ZI) 构造器）：优先用 dex 探测到的结果类，回退已知名单 */
    private fun newRemuxResult(loader: ClassLoader, ok: Boolean): Any? {
        val names = arrayOfNulls<String>(3).also {
            it[0] = runCatching { DexProbe.findRemux(appApkPath())?.result }.getOrNull()
            it[1] = "re0.e"; it[2] = "ad0.e"
        }
        for (n in names) {
            if (n.isNullOrEmpty()) continue
            try {
                val ctor = Class.forName(n, false, loader).getDeclaredConstructor(
                    Boolean::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                ctor.isAccessible = true
                return ctor.newInstance(ok, 0)
            } catch (_: Throwable) {}
        }
        return null
    }

    /** 微信 APK 路径（供 DexProbe 扫描；探测失败不影响主流程） */
    private fun appApkPath(): String = sAppContext?.applicationInfo?.sourceDir ?: ""

    /** 确保转码产物的封面缩略图存在（若不存在则从源图生成/复制，满足微信 UploadManager 的存在性校验） */
    private fun ensureThumbFile(srcVideoPath: String, thumbPath: String) {
        if (thumbPath.isEmpty()) return
        val dst = File(thumbPath)
        if (dst.isFile && dst.length() > 0L) return
        dst.parentFile?.mkdirs()
        // 1. 从 sVideoSource 反查原始图片提取首部纯 JPEG
        val srcImg = synchronized(sVideoSource) { sVideoSource[srcVideoPath] }
        if (!srcImg.isNullOrEmpty()) {
            val f = File(srcImg)
            if (f.isFile && f.length() > 0L) {
                try {
                    val data = f.readBytes()
                    val ftypOff = findFtyp(data)
                    val jpegLen = if (ftypOff > 0) ftypOff else data.size
                    java.io.FileOutputStream(dst).use { it.write(data, 0, jpegLen) }
                    log(Log.INFO, TAG, "thumb generated from srcImg: ${jpegLen}B -> $thumbPath")
                    return
                } catch (t: Throwable) {
                    log(Log.WARN, TAG, "ensureThumbFile failed from $srcImg", t)
                }
            }
        }
        // 2. 兜底：在源视频同目录下找任意 jpg 作为封面
        val dir = File(srcVideoPath).parentFile
        if (dir != null && dir.isDirectory) {
            val jpg = dir.listFiles { _, name -> name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) }
                ?.sortedByDescending { it.lastModified() }?.firstOrNull()
            if (jpg != null && jpg.isFile && jpg.length() > 0L) {
                try {
                    jpg.copyTo(dst, overwrite = true)
                    log(Log.INFO, TAG, "thumb copied from sibling: ${jpg.length()}B -> $thumbPath")
                    return
                } catch (_: Throwable) {}
            }
        }
    }

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

    /** 在视频数据里找 tkhd box 解析宽高（16.16 定点数→整数）；失败返回 (0,0) */
    private fun parseTkhdSize(data: ByteArray, from: Int): Pair<Int, Int> {
        return try {
            var i = from
            val end = data.size - 4
            while (i < end) {
                if (data[i] == 't'.code.toByte() && data[i + 1] == 'k'.code.toByte() &&
                    data[i + 2] == 'h'.code.toByte() && data[i + 3] == 'd'.code.toByte()
                ) {
                    val boxSize = readI32(data, i - 4)
                    if (boxSize in 80..4096) {
                        val p = i + 4 // version 字节
                        val off = if (data[p].toInt() == 1) 88 else 76
                        val w = ((readI32(data, p + off).toLong() and 0xFFFFFFFFL) ushr 16).toInt()
                        val h = ((readI32(data, p + off + 4).toLong() and 0xFFFFFFFFL) ushr 16).toInt()
                        if (w in 2..19200 && h in 2..19200) return Pair(w, h)
                    }
                }
                i++
            }
            0 to 0
        } catch (_: Throwable) {
            0 to 0
        }
    }

    // ==================== 配置写入（持久收敛：读回校验 + 多触发点） ====================

    private val sWriteLock = Any()
    /** 微信已打开的 MMKV 实例缓存（hook mmkvWithID 截获，避免补丁下直接调失败） */
    private val sCachedMmkv = HashMap<String, Any>()
    private var sAppContext: android.content.Context? = null
    private var sPreviewDone = false
    private var sSendDone = false
    private var sExptDone = false
    private var sStage2Done = false
    private var sAttemptCount = 0
    private var sLastAttemptMs = 0L

    private fun allWritesDone(): Boolean =
        sPreviewDone && sSendDone && sExptDone && sStage2Done

    /** 延迟重试（0/8s/20s），每次间隔至少 5s（防 onResume 触发刷爆） */
    private fun scheduleConfigWrites() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val delays = longArrayOf(0L, 8_000L, 20_000L)
        for (d in delays) {
            handler.postDelayed({ runAttempt() }, d)
        }
    }

    private fun runAttempt() {
        val now = System.currentTimeMillis()
        synchronized(sWriteLock) {
            if (now - sLastAttemptMs < 5_000L) return // 防重入
            sLastAttemptMs = now
            sAttemptCount++
        }
        try {
            attemptConfigWrites()
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "config write attempt #$sAttemptCount failed", t)
        }
    }

    private fun attemptConfigWrites() {
        val ctx = sAppContext ?: return
        synchronized(sWriteLock) {
            var dirty = false
            val mkv = if (!sPreviewDone || !sSendDone) mmkv(MMKV_REPAIRER) else null

            // ---- 批量写入所有实况相关 Repairer 配置 ----
            for (key in ALL_REPAIRER_KEYS) {
                // Hevc_Soft_Encode 置 0（禁用软编，走硬件硬编通道）
                val targetVal = if (key == "RepairerConfig_Chatting_C2C_Live_Hevc_Soft_Encode") 0 else 1
                if (mkv != null && mmkvGetInt(mkv, key, -1) != targetVal) {
                    mmkvPutInt(mkv, key, targetVal); mmkvSync(mkv)
                    dirty = true
                    log(Log.INFO, TAG, "Repairer written: $key=$targetVal")
                }
            }
            sPreviewDone = true
            sSendDone = true
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

    /** 正在我们的 mmkv() 里调 initialize（防止 initialize hook 里的 attemptConfigWrites 递归） */
    @Volatile
    private var sInMmkvInit = false

    private fun mmkv(mmapId: String): Any? {
        // 优先用微信已打开的实例缓存（hook 截获）
        synchronized(sCachedMmkv) {
            sCachedMmkv[mmapId]?.let { return it }
        }
        // 缓存没有 → 用最终 classloader 解析，先 initialize（幂等）再 mmkvWithID
        val ctx = sAppContext ?: return null
        return try {
            val mmkvCls = Class.forName(MMKV_CLASS, false, ctx.classLoader)
            if (!sInMmkvInit) {
                sInMmkvInit = true
                try {
                    mmkvCls.getMethod("initialize", android.content.Context::class.java)
                        .invoke(null, ctx)
                } catch (ie: Throwable) {
                    log(Log.WARN, TAG, "MMKV.initialize failed: ${ie.cause ?: ie}")
                } finally {
                    sInMmkvInit = false
                }
            }
            mmkvCls.getMethod("mmkvWithID", String::class.java).invoke(null, mmapId)
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            log(Log.ERROR, TAG, "mmkvWithID failed: $mmapId (${cause.javaClass.simpleName}: ${cause.message})")
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
        private const val CLS_CORE = "com.motion.core.LivePhotoCore"

        /** 实况包装类候选名（多版本混淆映射：8.0.77=wp.b / 8.0.76=qp.b / Play 8.0.72=fq.b） */
        private val WRAPPER_CANDIDATES = arrayOf("wp.b", "qp.b", "fq.b")

        private const val SP_TINKER_SHARE = "tinker_patch_share_config"
        private const val KEY_BOOTS_INSTALL = "tinker-boots-install-info"

        private const val MMKV_CLASS = "com.tencent.mmkv.MMKV"
        private const val MMKV_REPAIRER = "Repairer"
        private val ALL_REPAIRER_KEYS = arrayOf(
            "RepairerConfig_Chatting_C2C_Live_Preview_V2",
            "RepairerConfig_Chatting_C2C_Live_Send_V4",
            "RepairerConfig_Chatting_C2C_Live_Album_Auto_Enable",
            "RepairerConfig_Chatting_C2C_Live_Hevc_Soft_Encode",
            "RepairerConfig_SnsSaveLivePhoto",
            "RepairerConfig_SnsPublishLivePhoto",
            "RepairerConfig_SnsCheckSysLivePhoto",
            "RepairerConfig_SnsPreDownloadLivePhoto",
            "RepairerConfig_TextStatus_Gallery_LivePhoto_Enable",
        )

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

        /** Intent 脱敏 byte[] 存储的前缀 key */
        private const val PARCEL_BLOB_PREFIX = "__lp_blob_"

        private fun exptJson(exptId: Int, key: String): String =
            "{\"ExptId\":$exptId,\"GroupId\":0,\"ExptSequence\":1,\"Priority\":1,\"NeedReport\":0," +
                "\"StartTime\":0,\"EndTime\":0,\"ExptType\":4,\"SvrType\":1,\"ExptCheckSum\":\"\"," +
                "\"Args\":[{\"Key\":\"$key\",\"Val\":\"$VAL_BASE64_ONE\"}]}"
    }
}
