package com.bf1.admin.tool.data.remote

/**
 * 一次认证流程中 EA 轮换出的新 remid/sid。
 * null 表示本次流程没有下发该 cookie，调用方应沿用原值。
 */
data class RotatedCookies(val remid: String? = null, val sid: String? = null) {
    val hasAny: Boolean get() = remid != null || sid != null
}

/**
 * 把一次响应的 Set-Cookie 累积进 [previous]。
 *
 * 每个字段只在本次响应确实带了新值时才覆盖 —— 一次认证流程会经过多个端点，
 * 若某一步只下发了 sid 就把 remid 一并清空，之前拿到的新 remid 就丢了。
 * remid 是长期凭证，丢一次轮换就离"账号失效需重新登录"更近一步。
 *
 * 空值（如 `remid=; Max-Age=0`）是删除指令而非新凭证，一律忽略，
 * 以免用空串覆盖掉库里可用的凭证。
 */
internal fun accumulateRotatedCookies(
    previous: RotatedCookies,
    setCookieHeaders: List<String>
): RotatedCookies {
    var remid = previous.remid
    var sid = previous.sid
    for (header in setCookieHeaders) {
        val parts = header.substringBefore(';').trim().split("=", limit = 2)
        if (parts.size != 2) continue
        val value = parts[1].trim()
        if (value.isEmpty()) continue
        when (parts[0].trim().lowercase()) {
            "remid" -> remid = value
            "sid" -> sid = value
        }
    }
    return RotatedCookies(remid, sid)
}

/**
 * 增量落库取值：只覆盖本次确实轮换出的字段，null 字段沿用库里现值。
 * 与 accumulateRotatedCookies 同原则 —— 丢一次轮换就离"账号失效需重新登录"更近一步。
 */
internal fun mergeRotatedPersist(
    existingRemid: String,
    existingSid: String,
    rotated: RotatedCookies
): Pair<String, String> = (rotated.remid ?: existingRemid) to (rotated.sid ?: existingSid)
