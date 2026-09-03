package me.livephoto.assist

import java.util.zip.ZipFile

/**
 * 运行时 dex 结构探测：不依赖混淆类名，按方法签名特征定位 remux worker。
 *
 * 特征（8.0.77 / Play 8.0.72 / 8.0.78 三版静态验证）：
 *  - remux worker：同一个类同时声明
 *      聊天  (String,String,String,..,Continuation)->Object   （Vi / mh / dj）
 *      SNS   (RecordConfigProvider,Continuation)->Object       （Ui / vh / cj）
 *  - remux 结果类：worker 同 dex 内的 (ZI) 构造器类，同名 e 优先（re0.e / ad0.e / nf0.e）
 *
 * 只读 ZipFile + dex 方法表，不加载类；微信进程启动时一次性调用。
 */
internal object DexProbe {

    data class Remux(val worker: String, val chat: String, val sns: String, val result: String)

    private const val CONT = "Lkotlin/coroutines/Continuation;"
    private const val PROVIDER = "Lcom/tencent/mm/plugin/recordvideo/jumper/RecordConfigProvider;"

    fun findRemux(apkPath: String): Remux? {
        ZipFile(apkPath).use { zip ->
            for (e in zip.entries()) {
                val n = e.name
                if (!n.startsWith("classes") || !n.endsWith(".dex")) continue
                val bytes = zip.getInputStream(e).use { it.readBytes() }
                findInDex(bytes)?.let { return it }
            }
        }
        return null
    }

    /**
     * 结构探测实况包装类：field_ids 表中「类型为 LivePhotoCore 的静态字段」的宿主类。
     * 8.0.77=wp.b / Play 8.0.72=fq.b / 8.0.78=wp.b。
     */
    fun findWrapper(apkPath: String): String? {
        ZipFile(apkPath).use { zip ->
            for (e in zip.entries()) {
                val n = e.name
                if (!n.startsWith("classes") || !n.endsWith(".dex")) continue
                val bytes = zip.getInputStream(e).use { it.readBytes() }
                findWrapperInDex(bytes)?.let { return it }
            }
        }
        return null
    }

    // ---------- 最小 dex 解析：string/type/proto/method 四张表 ----------

    private class Reader(val b: ByteArray) {
        fun u4(p: Int): Int =
            (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8) or
                ((b[p + 2].toInt() and 0xff) shl 16) or ((b[p + 3].toInt() and 0xff) shl 24)
        fun u2(p: Int): Int = (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8)
        fun uleb(p0: Int): Pair<Int, Int> {
            var p = p0; var res = 0; var sh = 0
            while (true) {
                val x = b[p].toInt() and 0xff; p++
                res = res or ((x and 0x7f) shl sh)
                if (x and 0x80 == 0) break
                sh += 7
            }
            return res to p
        }
        fun mutf8(idx: Int, strData: IntArray): String {
            val (len, s0) = uleb(strData[idx])
            val sb = StringBuilder(len)
            var p = s0
            repeat(len) {
                val x = b[p].toInt() and 0xff; p++
                when {
                    x < 0x80 -> if (x != 0) sb.append(x.toChar())
                    x and 0xe0 == 0xc0 -> { val y = b[p].toInt() and 0xff; p++; sb.append(((x and 0x1f) shl 6 or (y and 0x3f)).toChar()) }
                    else -> { val y = b[p].toInt() and 0xff; p++; val z = b[p].toInt() and 0xff; p++; sb.append(((x and 0x0f) shl 12 or (y and 0x3f) shl 6 or (z and 0x3f)).toChar()) }
                }
            }
            return sb.toString()
        }
    }

    /** 在单个 dex 中找同时具备聊天+SNS 签名的类；找不到返回 null */
    internal fun findInDex(b: ByteArray): Remux? {
        if (b.size < 0x70) return null
        val r = Reader(b)
        val strSize = r.u4(0x38); val strOff = r.u4(0x3c)
        val typeSize = r.u4(0x40); val typeOff = r.u4(0x44)
        val protoSize = r.u4(0x48); val protoOff = r.u4(0x4c)
        val methodSize = r.u4(0x58); val methodOff = r.u4(0x5c)
        if (strSize <= 0 || strSize > 5_000_000 || typeSize > 1_000_000 || protoSize > 1_000_000 || methodSize > 5_000_000) return null
        val strData = IntArray(strSize) { k -> r.u4(strOff + 4 * k) }
        val types = Array(typeSize) { k -> r.mutf8(r.u4(typeOff + 4 * k), strData) }
        val protoParams = Array(protoSize) { k ->
            val listOff = r.u4(protoOff + 12 * k + 8)
            if (listOff == 0) emptyList()
            else List(r.u4(listOff)) { j -> types[r.u2(listOff + 4 + 2 * j)] }
        }
        val protoRet = Array(protoSize) { k -> types[r.u4(protoOff + 12 * k + 4)] }

        // 类 -> (聊天方法名, SNS 方法名, 聊天签名第 4 参类型)
        data class Sig(var chat: String? = null, var sns: String? = null, var chatArg4: String? = null)
        val sigs = HashMap<String, Sig>()
        for (k in 0 until methodSize) {
            val o = methodOff + 8 * k
            val cls = types.getOrNull(r.u2(o)) ?: continue
            if (!cls.startsWith("L") || !cls.contains('/')) continue
            val pi = r.u2(o + 2)
            val params = protoParams.getOrNull(pi) ?: continue
            val ret = protoRet.getOrNull(pi) ?: continue
            if (ret != "Ljava/lang/Object;") continue
            val name = r.mutf8(r.u4(o + 4), strData)
            val s = sigs.getOrPut(cls) { Sig() }
            if (params.size >= 4 && params[0] == "Ljava/lang/String;" && params[1] == "Ljava/lang/String;" &&
                params[2] == "Ljava/lang/String;" && params.last() == CONT
            ) { s.chat = name; s.chatArg4 = params[3] }
            if (params.size == 2 && params[0] == PROVIDER && params[1] == CONT) s.sns = name
        }
        val hit = sigs.entries.firstOrNull { it.value.chat != null && it.value.sns != null } ?: return null
        val (cls, s) = hit
        // 结果类由 worker 在同一 dex 内 new-instance 构造（np4.b0.mh→ad0/e、yt4.b0.Vi→re0/e、ox4.b0.dj→nf0/e），
        // 故只需在本 dex 内找 (ZI) 构造器类；同 dex 可能不止一个，同名 e 优先
        val zi = ArrayList<String>()
        for (k in 0 until methodSize) {
            val o = methodOff + 8 * k
            if (r.mutf8(r.u4(o + 4), strData) != "<init>") continue
            val pp = protoParams.getOrNull(r.u2(o + 2)) ?: continue
            if (pp.size == 2 && pp[0] == "Z" && pp[1] == "I") types.getOrNull(r.u2(o))?.let { zi.add(it) }
        }
        val result = (zi.firstOrNull { it.endsWith("/e;") } ?: zi.firstOrNull() ?: "").drop(1).dropLast(1).replace('/', '.')
        return Remux(cls.drop(1).dropLast(1).replace('/', '.'), s.chat!!, s.sns!!, result)
    }

    /** 在单个 dex 中找「持有 LivePhotoCore 类型字段的类」（字段静态与否在运行时校验） */
    internal fun findWrapperInDex(b: ByteArray): String? {
        if (b.size < 0x70) return null
        val r = Reader(b)
        val strSize = r.u4(0x38); val strOff = r.u4(0x3c)
        val typeSize = r.u4(0x40); val typeOff = r.u4(0x44)
        val fieldSize = r.u4(0x50); val fieldOff = r.u4(0x54)
        if (strSize <= 0 || strSize > 5_000_000 || typeSize > 1_000_000 || fieldSize > 3_000_000) return null
        val strData = IntArray(strSize) { k -> r.u4(strOff + 4 * k) }
        val types = Array(typeSize) { k -> r.mutf8(r.u4(typeOff + 4 * k), strData) }
        for (k in 0 until fieldSize) {
            val o = fieldOff + 8 * k
            val owner = types.getOrNull(r.u2(o)) ?: continue
            if (types.getOrNull(r.u2(o + 2)) == "Lcom/motion/core/LivePhotoCore;" && owner.startsWith("L")) {
                return owner.drop(1).dropLast(1).replace('/', '.')
            }
        }
        return null
    }
}
