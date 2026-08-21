package me.livephoto.assist;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Process;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 微信实况照片解锁 —— 两阶段自动切换（libxposed Modern API 102）。
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
public class LivePhotoUnlockHook extends XposedModule {

    private static final String TAG = "LivePhotoUnlock";
    private static final String PKG_WECHAT = "com.tencent.mm";
    private static final String CLS_MM_LIVE_PHOTO = "wp.b";

    private static final String SP_TINKER_SHARE = "tinker_patch_share_config";
    private static final String KEY_BOOTS_INSTALL = "tinker-boots-install-info";

    private static final String MMKV_CLASS = "com.tencent.mmkv.MMKV";
    private static final String MMKV_REPAIRER = "Repairer";
    private static final String KEY_REPAIRER_PREVIEW = "RepairerConfig_Chatting_C2C_Live_Preview_V2";
    private static final String KEY_REPAIRER_SEND = "RepairerConfig_Chatting_C2C_Live_Send_V4";

    private static final String SP_SYSTEM_CONFIG = "system_config_prefs";
    private static final String KEY_UIN = "default_uin";

    private static final String KEY_EXPT_SEND = "clicfg_chatting_c2c_live_send_v4";
    private static final String KEY_G6 = "clicfg_live_photo_extra_manufacturer";
    private static final String KEY_WRITTEN_MARK = "_g6_written";
    private static final int EXPT_ID_G6 = 99999;
    private static final int EXPT_ID_SEND = 100000;

    // Base64("1") = "MQ=="（值必须是 "1"，v8.O 用 Integer.decode 解析，"true" 会解析失败）
    private static final String VAL_BASE64_ONE = "MQ==";

    /** 构造实验 JSON（字段名精确匹配微信 Lu92/a 解析器，与 WABTest 格式一致） */
    private static String exptJson(int exptId, String key) {
        return "{\"ExptId\":" + exptId + ",\"GroupId\":0,\"ExptSequence\":1,\"Priority\":1,\"NeedReport\":0," +
                "\"StartTime\":0,\"EndTime\":0,\"ExptType\":4,\"SvrType\":1,\"ExptCheckSum\":\"\"," +
                "\"Args\":[{\"Key\":\"" + key + "\",\"Val\":\"MQ==\"}]}";
    }

    public LivePhotoUnlockHook() {
    }

    private ClassLoader hostLoader;

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!PKG_WECHAT.equals(param.getPackageName())) {
            return;
        }
        // 微信的 ClassLoader：反射微信内部类（com.tencent.mmkv.MMKV 等）必须用它
        hostLoader = param.getClassLoader();
        // 只处理主进程（com.tencent.mm），不碰 :tools / :push 等
        if (!isMainProcess()) {
            return;
        }

        // ===== 阶段一 hook：wp/b.<clinit> 结束后强制 e=true =====
        try {
            Class<?> wpb = Class.forName(CLS_MM_LIVE_PHOTO, false, param.getClassLoader());
            hookClassInitializer(wpb)
                    .setPriority(PRIORITY_HIGHEST)
                    .intercept(chain -> {
                        Object result = chain.proceed(); // 原生 <clinit>：上报与失败路径原样执行
                        boolean ok = setLivePhotoSupport(wpb, true);
                        log(ok ? android.util.Log.INFO : android.util.Log.ERROR, TAG,
                                ok ? "stage-1: wp.b.<clinit> done, e=true (UI unlocked)"
                                   : "stage-1: wp.b.<clinit> done, failed to set e=true");
                        return result;
                    });
            log(android.util.Log.INFO, TAG, "stage-1 hook registered on wp.b");
        } catch (Throwable t) {
            log(android.util.Log.ERROR, TAG, "stage-1 hook setup failed (WeChat version changed?)", t);
        }

        // ===== hook Application.onCreate：写本地配置 + 检测补丁 → 阶段二 =====
        try {
            Method onCreate = Application.class.getDeclaredMethod("onCreate");
            hook(onCreate)
                    .setPriority(PRIORITY_HIGHEST)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Application app = (Application) chain.getThisObject();

                            // 1. Repairer 配置：预览 + 发送（默认 0，置 1）
                            writeRepairerConfig(KEY_REPAIRER_PREVIEW, 1);
                            writeRepairerConfig(KEY_REPAIRER_SEND, 1);

                            // 2. 云控发送开关（exptId=100000）
                            writeExptConfig(app, KEY_EXPT_SEND, EXPT_ID_SEND);

                            // 3. 检测补丁 → 阶段二（写 G6 固化 + 杀进程）
                            handleStage2(app);
                        } catch (Throwable t) {
                            log(android.util.Log.ERROR, TAG, "onCreate write failed", t);
                        }
                        return result;
                    });
        } catch (Throwable t) {
            log(android.util.Log.ERROR, TAG, "onCreate hook setup failed", t);
        }
    }

    /** 写 Repairer MMKV（int 值） */
    private void writeRepairerConfig(String key, int value) throws Exception {
        Class<?> mmkvCls = Class.forName(MMKV_CLASS, false, hostLoader);
        Method mmkvWithID = mmkvCls.getMethod("mmkvWithID", String.class);
        Method putInt = mmkvCls.getMethod("putInt", String.class, int.class);
        Method sync = mmkvCls.getMethod("sync");

        Object mkv = mmkvWithID.invoke(null, MMKV_REPAIRER);
        putInt.invoke(mkv, key, value);
        sync.invoke(mkv);
        log(android.util.Log.INFO, TAG, "Repairer written: " + key + "=" + value);
    }

    /** 写云控 expt MMKV（key → exptId → JSON，值 Base64("1")） */
    private void writeExptConfig(Application app, String key, int exptId) throws Exception {
        SharedPreferences cfg = app.getSharedPreferences(SP_SYSTEM_CONFIG, Application.MODE_PRIVATE);
        int uin = cfg.getInt(KEY_UIN, 0);
        if (uin == 0) {
            log(android.util.Log.ERROR, TAG, "uin=0, cannot write expt config " + key);
            return;
        }

        Class<?> mmkvCls = Class.forName(MMKV_CLASS, false, hostLoader);
        Method mmkvWithID = mmkvCls.getMethod("mmkvWithID", String.class);
        Method putInt = mmkvCls.getMethod("putInt", String.class, int.class);
        Method putString = mmkvCls.getMethod("putString", String.class, String.class);
        Method sync = mmkvCls.getMethod("sync");

        Object keyMkv = mmkvWithID.invoke(null, uin + "_WxExptAppKeyMmkv");
        Object idMkv = mmkvWithID.invoke(null, uin + "_WxExptAppIdMmkv");
        putInt.invoke(keyMkv, key, exptId);
        putString.invoke(idMkv, String.valueOf(exptId), exptJson(exptId, key));
        sync.invoke(keyMkv);
        sync.invoke(idMkv);
        log(android.util.Log.INFO, TAG, "expt written: " + key + " (exptId=" + exptId + ", uin=" + uin + ")");
    }

    /** 阶段二：检测 Tinker 补丁是否已安装，是则写 G6 并杀微信 */
    private void handleStage2(Application app) throws Exception {
        SharedPreferences sp = app.getSharedPreferences(SP_TINKER_SHARE, Application.MODE_PRIVATE);
        String installInfo = sp.getString(KEY_BOOTS_INSTALL, "");
        boolean hasPatch = installInfo != null && !installInfo.isEmpty();
        log(android.util.Log.INFO, TAG, "stage-2 check: tinker-boots-install-info=" + installInfo);

        if (!hasPatch) {
            log(android.util.Log.INFO, TAG, "no tinker patch installed yet, staying stage-1");
            return;
        }

        // 补丁已安装 → 写 G6 固化（防重标记）→ 杀进程
        SharedPreferences cfg = app.getSharedPreferences(SP_SYSTEM_CONFIG, Application.MODE_PRIVATE);
        int uin = cfg.getInt(KEY_UIN, 0);
        if (uin == 0) {
            log(android.util.Log.ERROR, TAG, "uin=0, cannot write G6");
            return;
        }

        Class<?> mmkvCls = Class.forName(MMKV_CLASS, false, hostLoader);
        Method mmkvWithID = mmkvCls.getMethod("mmkvWithID", String.class);
        Method putInt = mmkvCls.getMethod("putInt", String.class, int.class);
        Method getInt = mmkvCls.getMethod("getInt", String.class, int.class);
        Method sync = mmkvCls.getMethod("sync");

        Object keyMkv = mmkvWithID.invoke(null, uin + "_WxExptAppKeyMmkv");
        int already = (int) getInt.invoke(keyMkv, KEY_WRITTEN_MARK, 0);
        if (already != 0) {
            log(android.util.Log.INFO, TAG, "G6 already written, skip kill");
            return;
        }
        putInt.invoke(keyMkv, KEY_G6, EXPT_ID_G6);
        putInt.invoke(keyMkv, KEY_WRITTEN_MARK, 1);
        Object idMkv = mmkvWithID.invoke(null, uin + "_WxExptAppIdMmkv");
        putStringVia(idMkv, mmkvCls, String.valueOf(EXPT_ID_G6), exptJson(EXPT_ID_G6, KEY_G6));
        sync.invoke(keyMkv);
        sync.invoke(idMkv);

        log(android.util.Log.INFO, TAG, "stage-2: G6 written (uin=" + uin + "), killing WeChat");
        Process.killProcess(Process.myPid());
    }

    private static void putStringVia(Object mkv, Class<?> mmkvCls, String k, String v) throws Exception {
        Method putString = mmkvCls.getMethod("putString", String.class, String.class);
        putString.invoke(mkv, k, v);
    }

    /** 反射设置 wp.b.e（static boolean LivePhotoOsSupportLivePhoto）为 true */
    private static boolean setLivePhotoSupport(Class<?> wpb, boolean value) {
        try {
            java.lang.reflect.Field e = wpb.getDeclaredField("e");
            e.setAccessible(true);
            e.setBoolean(null, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 判断是否为微信主进程 */
    private static boolean isMainProcess() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentProcessName = activityThread.getDeclaredMethod("currentProcessName");
            String name = (String) currentProcessName.invoke(null);
            return PKG_WECHAT.equals(name);
        } catch (Throwable t) {
            return true;
        }
    }
}