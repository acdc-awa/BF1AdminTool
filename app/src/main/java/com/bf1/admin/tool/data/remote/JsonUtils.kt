package com.bf1.admin.tool.data.remote

import org.json.JSONObject

/**
 * 与 JSONObject.optString 不同：字段缺失、JSON 显式 null（JSONObject.NULL）或空字符串
 * 时返回 null，而不是字面字符串 "null"。
 *
 * org.json 的 optString 对显式 null 返回 "null"（String.valueOf(JSONObject.NULL)），会漏过
 * isEmpty() 守卫 —— 曾导致查 PID 时把 "null" 当 personaId 走完整 RPC 链路（addAdmin/
 * removeAdmin 假成功）。统一用本函数读取「可为空」的字符串字段。
 */
internal fun jsonOptString(json: JSONObject, key: String): String? {
    if (json.isNull(key)) return null
    return json.optString(key).takeIf { it.isNotEmpty() }
}

/** 读取必填字符串字段：缺失 / null / 空串一律抛 PersonaNotFoundException。 */
internal fun requirePersonaId(
    json: JSONObject,
    key: String,
    message: String = "$key 缺失"
): String = jsonOptString(json, key) ?: throw PersonaNotFoundException(message)
