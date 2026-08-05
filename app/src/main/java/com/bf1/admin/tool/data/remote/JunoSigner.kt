package com.bf1.admin.tool.data.remote

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

/** base64url（无填充），与 Python base64.urlsafe_b64encode(...).rstrip(b"=") 一致。 */
internal fun b64url(data: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(data)

/** FNV-1a 64 位哈希，与 EAappEmulater 的 Fnv1a64 一致。 */
internal fun fnv1a64(data: ByteArray): Long {
    // FNV-1a 64 位 offset basis = 0xCBF29CE484222325（无符号 14695981039346656037）。
    // Kotlin 2.0.21 不允许十六进制字面量超过 Long.MAX_VALUE（Value out of range），
    // 用等价的有符号十进制字面量（同 64 位补码，位模式一致）。
    var h = -3750763034362895579L
    val prime = 0x100000001B3L
    for (b in data) {
        h = h xor b.toLong()
        h *= prime
    }
    return h
}

private val PC_SIGN_KEY_V1 = "ISa3dpGOc8wW7Adn4auACSQmaccrOyR2".toByteArray(StandardCharsets.UTF_8)
private val PC_SIGN_KEY_V2 = "nt5FfJbdPzNcl2pkC3zgjO43Knvscxft".toByteArray(StandardCharsets.UTF_8)

/**
 * 生成 JUNO 需要的 pc_sign（固定假硬件值，复刻 EAappEmulater CreatePcSign(false)）。
 * [secretVersion] v1/v2 决定签名密钥；[ts] 形如 "2026-08-05 12:34:56:789"（UTC 毫秒）。
 * 返回 "payload.signature"，均为 base64url。
 */
internal fun buildPcSign(secretVersion: String, ts: String): String {
    val boardManufacturer = "Microsoft Corporation"
    val boardSerial = "None"
    val biosManufacturer = "Microsoft Corporation"
    val biosSerial = "None"
    val osInstallDate = "1970-01-0100:00:00.000000000+0000"
    val osSerial = "None"
    val diskSerial = "None"
    val gpuId = 0

    val midSource = boardManufacturer + boardSerial + biosManufacturer + biosSerial +
        osInstallDate + osSerial
    val mid = fnv1a64(midSource.toByteArray(StandardCharsets.UTF_8)).toString()

    // System.Text.Json 默认紧凑输出、保持插入顺序 —— 手动拼 JSON 与之等价
    val payloadJson = """{"av":"v1","bsn":"$biosSerial","gid":$gpuId,"hsn":"$diskSerial","mid":"$mid","msn":"$boardSerial","sv":"$secretVersion","ts":"$ts"}"""
    val payload = b64url(payloadJson.toByteArray(StandardCharsets.UTF_8))
    val key = if (secretVersion == "v1") PC_SIGN_KEY_V1 else PC_SIGN_KEY_V2
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    val signature = b64url(mac.doFinal(payload.toByteArray(StandardCharsets.US_ASCII)))
    return "$payload.$signature"
}
