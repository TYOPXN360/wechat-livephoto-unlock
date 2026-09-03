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
}
