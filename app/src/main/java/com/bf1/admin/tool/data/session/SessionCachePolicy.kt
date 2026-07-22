package com.bf1.admin.tool.data.session

internal const val SESSION_REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L
internal const val SESSION_MAX_AGE_MS = 12 * 60 * 60 * 1000L

internal fun isSessionUsable(refreshedAt: Long, now: Long): Boolean =
    now >= refreshedAt && now - refreshedAt < SESSION_MAX_AGE_MS

internal fun isSessionRefreshDue(refreshedAt: Long, now: Long): Boolean =
    now < refreshedAt || now - refreshedAt >= SESSION_REFRESH_INTERVAL_MS
