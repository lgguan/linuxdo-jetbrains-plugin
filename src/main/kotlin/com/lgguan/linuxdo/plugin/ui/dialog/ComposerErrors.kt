package com.lgguan.linuxdo.plugin.ui.dialog

import com.google.gson.JsonParser

internal object ComposerErrors {
    fun parse(error: Throwable): String = if (error is com.lgguan.linuxdo.plugin.net.ForumValidationException)
        error.errors.joinToString("; ") else parse(error.message)
    fun format(rawMsg: String): String {
        val sb = StringBuilder()
        var lineLen = 0
        for (ch in rawMsg) {
            sb.append(ch)
            lineLen++
            if (lineLen >= 30 && (ch == '，' || ch == ',' || ch == '。' || ch == ' ' || ch == '：' || ch == ':')) {
                sb.append("\n")
                lineLen = 0
            } else if (lineLen >= 40) {
                sb.append("\n")
                lineLen = 0
            }
        }
        return sb.toString().trim()
    }

    fun parse(raw: String?): String {
        if (raw.isNullOrBlank()) return "未知错误"
        if (raw.contains("Just a moment", ignoreCase = true) || raw.contains("cloudflare", ignoreCase = true)) {
            return "需要完成 Cloudflare 人机验证，请先在侧边栏点击登录/验证"
        }
        if (raw.contains("429") || raw.contains("Too Many Requests", ignoreCase = true)) {
            return "已触发论坛请求频率限制 (HTTP 429)，请等待冷却结束后重试"
        }
        try {
            if (raw.contains("errors") && raw.contains("{")) {
                val jsonPart = raw.substringAfter("{").substringBeforeLast("}")
                val fullJson = "{$jsonPart}"
                val obj = JsonParser.parseString(fullJson).asJsonObject
                val errorsArray = obj.getAsJsonArray("errors")
                if (errorsArray != null && errorsArray.size() > 0) {
                    return errorsArray.joinToString("; ") { it.asString }
                }
            }
        } catch (_: Throwable) {}
        if (raw.contains("403")) return "403 Forbidden (无权限操作或登录已失效，请在侧边栏登录)"
        if (raw.contains("404")) return "404 Not Found (话题不存在或已被删除)"
        return raw.trim()
    }

}
