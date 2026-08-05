package com.bf1.admin.tool.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets
import java.util.Base64

class JunoSignerTest {

    @Test
    fun fnv1a64MatchesPythonGolden() {
        // golden 由 eaid_to_pid.py 实测生成：
        // mid_source = "Microsoft CorporationNoneMicrosoft CorporationNone1970-01-0100:00:00.000000000+0000None"
        // Python fnv1a64 = 14598647573862213349（无符号），同一 64 位哈希在 Kotlin 中是有符号 Long：-3848096499847338267L
        assertEquals(
            -3848096499847338267L,
            fnv1a64("Microsoft CorporationNoneMicrosoft CorporationNone1970-01-0100:00:00.000000000+0000None".toByteArray())
        )
    }

    @Test
    fun b64urlEncodesWithoutPadding() {
        assertEquals("", b64url(ByteArray(0)))
        assertEquals("aGVsbG8", b64url("hello".toByteArray()))
    }

    @Test
    fun buildPcSignPayloadVerifies() {
        val sign = buildPcSign("v1", "2026-08-05 00:00:00:000")
        val (payload, sig) = sign.split(".")
        // payload 是 base64url 的 JSON，含 av/bsn/gid/hsn/mid/msn/sv/ts；
        // 先解码再断言字段（base64 输出本身不含可读字段名）
        val decoded = String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8)
        assertTrue(decoded.contains("av"))
        // mid 有线文本必须是无符号十进制（对齐 Python/C# 参考），防止回归为有符号负数
        assertTrue(decoded.contains("14598647573862213349"))
        // 签名可用 v1 密钥重算验证
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("ISa3dpGOc8wW7Adn4auACSQmaccrOyR2".toByteArray(), "HmacSHA256"))
        assertEquals(
            Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.toByteArray())),
            sig
        )
    }

    @Test
    fun buildPcSignMatchesPythonGolden() {
        // 完整 "payload.signature" 由 eaid_to_pid.py 以固定 ts / v1 实测生成；
        // mid 为无符号十进制（14598647573862213349），断言与参考实现逐字节一致
        assertEquals(
            "eyJhdiI6InYxIiwiYnNuIjoiTm9uZSIsImdpZCI6MCwiaHNuIjoiTm9uZSIsIm1pZCI6IjE0NTk4NjQ3NTczODYyMjEzMzQ5IiwibXNuIjoiTm9uZSIsInN2IjoidjEiLCJ0cyI6IjIwMjYtMDgtMDUgMDA6MDA6MDA6MDAwIn0.j4uCmBLgWDmAduuo8lwxaMkXA-Rasyy1iIlQ-nG_UHI",
            buildPcSign("v1", "2026-08-05 00:00:00:000")
        )
    }
}
