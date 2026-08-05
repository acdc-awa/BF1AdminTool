package com.bf1.admin.tool.data.remote

import org.json.JSONObject

/** 查询的 EAID 无对应 persona（含响应结构异常导致的取不到 ID）。 */
class PersonaNotFoundException(message: String) : Exception(message)

/**
 * gateway.ea.com/proxy/identity/personas 响应解析（实测结构，见
 * D:\战地1管理工具\用户名查PID\eaid_to_pid.py）：
 *
 * { "personas": { "persona": [ { "personaId": "...", "displayName": "..." } ] } }
 *
 * 多个 persona 时取第一个（与脚本行为一致）。
 */
internal fun parsePersonaId(json: JSONObject): String {
    val personas = json.optJSONObject("personas")
        ?: throw PersonaNotFoundException("personas 字段缺失")
    val persona = personas.optJSONArray("persona")
    if (persona == null || persona.length() == 0) {
        throw PersonaNotFoundException("未找到该 EAID 对应的账号")
    }
    val pid = persona.getJSONObject(0).optString("personaId")
    if (pid.isEmpty()) throw PersonaNotFoundException("persona 缺少 personaId")
    return pid
}
