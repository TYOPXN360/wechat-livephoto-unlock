package me.livephoto.assist

import android.app.Application
import android.content.SharedPreferences
import android.os.Process
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Method

/**
 * 微信实况照片解锁 —— 两阶段自动切换（libxposed Modern API 102，纯 Kotlin）。
 *
 * 阶段一（无条件）：写本地配置，打开实况照片接收+发送的 UI 闸门
 *   - Repairer MMKV：
 *       RepairerConfig_Chatting_C2C_Live_Preview_V2 = 1（聊天实况预览）
 *       RepairerConfig_Chatting_C2C_Live_Send_V4    = 1（聊天发送实况）
 *   - 云控 expt MMKV（{uin}_WxExptAppKeyMmkv / AppIdMmkv）：
 *       clicfg_chatting_c2c_live_send_v4 = 1（发送开关，exptId=100000）
 *   - hook wp/b.<clinit> 后强制 e=true（设备支持标志）
 *   → 接收/发送入口出现 → 在线使用/上报 → 服务端识别并下发 Tinker 补丁。
 *
 * 阶段二（检测到补丁已安装）：额外写 clicfg_live_photo_extra_manufacturer（G6）= true
 *   （exptId=99999，官方额外厂商开关）→ sync → 杀微信进程。
 *   之后用户禁用模块并重启微信：原生读到 G6 → 跳过厂商白名单 →
 *   new LivePhotoCore() 解析到补丁真实类 → 永久解锁，彻底摆脱 hook。
 *
 * 风控安全：所有写入都是微信官方的本地配置（MMKV），
 *   不 hook 桩类方法、不改系统属性、不改任何上报内容；
 *   <clinit> 原生上报与任何不支持机型完全一致。
 */
class LivePhotoUnlockHook : XposedModule() {

    private var hostLoader: ClassLoader? = null

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != PKG_WECHAT) return

        // 微信的 ClassLoader：反射微信内部类（com.tencent.mmkv.MMKV 等）必须用它
        hostLoader = param.classLoader
        // 只处理主进程（com.tencent.mm），不碰 :tools / :push 等
        if (!isMainProcess()) return

        // ===== 阶段一 hook：wp/b.<clinit> 结束后强制 e=true =====
        try {
            val wpb = Class.forName(CLS_MM_LIVE_PHOTO, false, param.classLoader)
            hookClassInitializer(wpb)
                .setPriority(PRIORITY_HIGHEST)
                .intercept { chain ->
                    val result = chain.proceed() // 原生 <clinit>：上报与失败路径原样执行
                    val ok = setLivePhotoSupport(wpb, true)
                    log(
                        if (ok) Log.INFO else Log.ERROR, TAG,
                        if (ok) "stage-1: wp.b.<clinit> done, e=true (UI unlocked)"
                        else "stage-1: wp.b.<clinit> done, failed to set e=true"
                    )
                    result
                }
            log(Log.INFO, TAG, "stage-1 hook registered on wp.b")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "stage-1 hook setup failed (WeChat version changed?)", t)
        }

        // ===== hook Application.onCreate：写本地配置 + 检测补丁 → 阶段二 =====
        try {
            val onCreate = Application::class.java.getDeclaredMethod("onCreate")
            hook(onCreate)
                .setPriority(PRIORITY_HIGHEST)
                .intercept { chain ->
                    val result = chain.proceed()
                    val app = chain.getThisObject() as? Application
                    if (app != null) {
                        try {
                            // 1. Repairer 配置：预览 + 发送（默认 0，置 1）
                            writeRepairerConfig(KEY_REPAIRER_PREVIEW, 1)
                            writeRepairerConfig(KEY_REPAIRER_SEND, 1)

                            // 2. 云控发送开关（exptId=100000）
                            writeExptConfig(app, KEY_EXPT_SEND, EXPT_ID_SEND)

                            // 3. 检测补丁 → 阶段二（写 G6 固化 + 杀进程）
                            handleStage2(app)
                        } catch (t: Throwable) {
                            log(Log.ERROR, TAG, "onCreate write failed", t)
                        }
                    }
                    result
                }
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "onCreate hook setup failed", t)
        }
    }

    /** 写 Repairer MMKV（int 值） */
    private fun writeRepairerConfig(key: String, value: Int) {
        val mmkv = mmkv(MMKV_REPAIRER) ?: return
        mmkvPutInt(mmkv, key, value)
        mmkvSync(mmkv)
        log(Log.INFO, TAG, "Repairer written: $key=$value")
    }

    /** 写云控 expt MMKV（key → exptId → JSON，值 Base64("1")） */
    private fun writeExptConfig(app: Application, key: String, exptId: Int) {
        val uin = app.getSharedPreferences(SP_SYSTEM_CONFIG, Application.MODE_PRIVATE)
            .getInt(KEY_UIN, 0)
        if (uin == 0) {
            log(Log.ERROR, TAG, "uin=0, cannot write expt config $key")
            return
        }
        val keyMkv = mmkv("${uin}_WxExptAppKeyMmkv") ?: return
        val idMkv = mmkv("${uin}_WxExptAppIdMmkv") ?: return
        mmkvPutInt(keyMkv, key, exptId)
        mmkvPutString(idMkv, exptId.toString(), exptJson(exptId, key))
        mmkvSync(keyMkv)
        mmkvSync(idMkv)
        log(Log.INFO, TAG, "expt written: $key (exptId=$exptId, uin=$uin)")
    }

    /** 阶段二：检测 Tinker 补丁是否已安装，是则写 G6 并杀微信 */
    private fun handleStage2(app: Application) {
        val sp = app.getSharedPreferences(SP_TINKER_SHARE, Application.MODE_PRIVATE)
        val installInfo = sp.getString(KEY_BOOTS_INSTALL, "")
        val hasPatch = !installInfo.isNullOrEmpty()
        log(Log.INFO, TAG, "stage-2 check: tinker-boots-install-info=$installInfo")

        if (!hasPatch) {
            log(Log.INFO, TAG, "no tinker patch installed yet, staying stage-1")
            return
        }

        // 补丁已安装 → 写 G6 固化（防重标记）→ 杀进程
        val uin = app.getSharedPreferences(SP_SYSTEM_CONFIG, Application.MODE_PRIVATE)
            .getInt(KEY_UIN, 0)
        if (uin == 0) {
            log(Log.ERROR, TAG, "uin=0, cannot write G6")
            return
        }

        val keyMkv = mmkv("${uin}_WxExptAppKeyMmkv") ?: return
        val already = mmkvGetInt(keyMkv, KEY_WRITTEN_MARK, 0)
        if (already != 0) {
            log(Log.INFO, TAG, "G6 already written, skip kill")
            return
        }
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
            val mmkvCls = mkv.javaClass
            mmkvCls.getMethod("putInt", String::class.java, Int::class.javaPrimitiveType).invoke(mkv, key, value)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "mmkv putInt failed: $key", t)
        }
    }

    private fun mmkvPutString(mkv: Any, key: String, value: String) {
        try {
            val mmkvCls = mkv.javaClass
            mmkvCls.getMethod("putString", String::class.java, String::class.java).invoke(mkv, key, value)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "mmkv putString failed: $key", t)
        }
    }

    private fun mmkvGetInt(mkv: Any, key: String, def: Int): Int {
        return try {
            val mmkvCls = mkv.javaClass
            mmkvCls.getMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
                .invoke(mkv, key, def) as? Int ?: def
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "mmkv getInt failed: $key", t)
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

    /** 反射设置 wp.b.e（static boolean LivePhotoOsSupportLivePhoto）为 true */
    private fun setLivePhotoSupport(wpb: Class<*>, value: Boolean): Boolean {
        return try {
            val field = wpb.getDeclaredField("e")
            field.isAccessible = true
            field.setBoolean(null, value)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 判断是否为微信主进程 */
    private fun isMainProcess(): Boolean {
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val name = activityThread.getDeclaredMethod("currentProcessName").invoke(null) as? String
            name == PKG_WECHAT
        } catch (t: Throwable) {
            true // 反射失败时默认允许（主进程 fallback）
        }
    }

    companion object {
        private const val TAG = "LivePhotoUnlock"
        private const val PKG_WECHAT = "com.tencent.mm"
        private const val CLS_MM_LIVE_PHOTO = "wp.b"

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

        // Base64("1") = "MQ=="（值必须是 "1"，v8.O 用 Integer.decode 解析，"true" 会解析失败）
        private const val VAL_BASE64_ONE = "MQ=="

        /** 构造实验 JSON（字段名精确匹配微信 Lu92/a 解析器，与 WABTest 格式一致） */
        private fun exptJson(exptId: Int, key: String): String =
            "{\"ExptId\":$exptId,\"GroupId\":0,\"ExptSequence\":1,\"Priority\":1,\"NeedReport\":0," +
                "\"StartTime\":0,\"EndTime\":0,\"ExptType\":4,\"SvrType\":1,\"ExptCheckSum\":\"\"," +
                "\"Args\":[{\"Key\":\"$key\",\"Val\":\"$VAL_BASE64_ONE\"}]}"
    }
}