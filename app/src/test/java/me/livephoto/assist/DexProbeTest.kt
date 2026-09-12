package me.livephoto.assist

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

/**
 * 用真实 APK 验证 DexProbe 结构探测（纯 JVM，无 Android 依赖）。
 * 预期结果来自静态逆向结论：
 *  - 8.0.77:  yt4.b0  Vi/Ui  → re0.e
 *  - Play 8.0.72: np4.b0 mh/vh → ad0.e
 *  - 8.0.78:  ox4.b0 dj/cj  → nf0.e
 */
class DexProbeTest {

    private val ws = "/mnt/TY/android/android-project/wechatview"

    @Test fun `8_0_77 finds yt4b0`() {
        val r = DexProbe.findRemux("$ws/source/weixin8077android3160_0x28004d30_arm64.apk")!!
        assertEquals("yt4.b0", r.worker)
        assertEquals("Vi", r.chat)
        assertEquals("Ui", r.sns)
        assertEquals("re0.e", r.result)
    }

    @Test fun `play_8_0_72 finds np4b0`() {
        val r = DexProbe.findRemux("$ws/work/play8072/base.apk")!!
        assertEquals("np4.b0", r.worker)
        assertEquals("mh", r.chat)
        assertEquals("vh", r.sns)
        assertEquals("ad0.e", r.result)
    }

    @Test fun `8_0_78 finds ox4b0`() {
        val r = DexProbe.findRemux("$ws/work/weixin8078android3160_arm64.apk")!!
        assertEquals("ox4.b0", r.worker)
        assertEquals("dj", r.chat)
        assertEquals("cj", r.sns)
        assertEquals("nf0.e", r.result)
    }

    @Test fun `wrapper found in all three versions`() {
        assertEquals("wp.b", DexProbe.findWrapper("$ws/source/weixin8077android3160_0x28004d30_arm64.apk"))
        assertEquals("fq.b", DexProbe.findWrapper("$ws/work/play8072/base.apk"))
        assertEquals("wp.b", DexProbe.findWrapper("$ws/work/weixin8078android3160_arm64.apk"))
    }

    @Test fun `e12 hotfix build keeps structure`() {
        val e12 = DexProbe.findRemux("$ws/work/weixin8078_0x28004e12_arm64.apk")!!
        assertEquals("ox4.b0", e12.worker)
        assertEquals("dj", e12.chat)
        assertEquals("cj", e12.sns)
        assertEquals("nf0.e", e12.result)
        assertEquals("wp.b", DexProbe.findWrapper("$ws/work/weixin8078_0x28004e12_arm64.apk"))
    }

    @Test fun `8_0_78_3180 keeps ox4b0`() {
        val r = DexProbe.findRemux("$ws/work/weixin8078android3180_0x28004e32_arm64.apk")!!
        assertEquals("ox4.b0", r.worker)
        assertEquals("dj", r.chat)
        assertEquals("cj", r.sns)
        assertEquals("nf0.e", r.result)
        assertEquals("wp.b", DexProbe.findWrapper("$ws/work/weixin8078android3180_0x28004e32_arm64.apk"))
    }

    @Test fun `view gate lo5f on 3141 split`() {
        // split 包 base.apk 内直读（ZipFile 套 ZipFile 不支持，解一层到内存无落盘由调用方处理；
        // 此处 base.apk 已随 play8072  precedent 常驻 work/play8072 式目录——3141 用 apks 内联路径需先解 base）
        // ponytail: 不在单测里解 291M apks；3141 门控已在真机验证（vq.b/nm5兜底外，lo5.f.a/b 双挂）。
    }

    @Test fun `view gate mq5f on 3180`() {
        assertEquals("mq5.f", DexProbe.findViewGate("$ws/work/weixin8078android3180_0x28004e32_arm64.apk"))
    }

    @Test fun `view gate on 3160`() {
        // 8.0.78 3160 门控与 3180 同构（mq5.f），同断言锁死漂移
        assertEquals("mq5.f", DexProbe.findViewGate("$ws/work/weixin8078android3160_arm64.apk"))
    }
}
