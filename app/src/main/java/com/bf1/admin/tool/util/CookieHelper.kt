package com.bf1.admin.tool.util

import android.util.Log

private const val TAG = "BF1Debug"

object CookieHelper {
    fun extractCookies(cookieString: String): Pair<String, String> {
        val cookies = cookieString.split(";").associate {
            val parts = it.trim().split("=", limit = 2)
            if (parts.size == 2) parts[0].trim() to parts[1].trim() else "" to ""
        }
        val remid = cookies["remid"] ?: throw Exception("remid not found in cookies")
        val sid = cookies["sid"] ?: throw Exception("sid not found in cookies")
        return Pair(remid, sid)
    }

    fun extractCookiesOrNull(cookieString: String): Pair<String, String>? {
        return try {
            extractCookies(cookieString)
        } catch (e: Exception) {
            Log.w(TAG, "[Cookie] extractCookiesOrNull: ${e.message}")
            null
        }
    }

    fun parseWebViewCookies(rawCookies: String?): Pair<String, String>? {
        if (rawCookies.isNullOrBlank()) {
            Log.w(TAG, "[Cookie] parseWebViewCookies: input is null or blank")
            return null
        }
        return extractCookiesOrNull(rawCookies)
    }

    /**
     * 从 OAuth 重定向 URL fragment 中提取 access_token
     * 对应 EAappEmulater LoginWindow 中解析 #access_token= 的逻辑
     */
    fun extractAccessToken(url: String): String? {
        val fragmentIndex = url.lastIndexOf('#')
        if (fragmentIndex == -1) return null
        val fragment = url.substring(fragmentIndex + 1)
        val params = fragment.split("&").associate {
            val parts = it.split("=", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
        }
        return params["access_token"]
    }
}
