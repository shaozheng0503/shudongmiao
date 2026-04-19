package com.catagent.data.network

import com.google.gson.JsonArray
import retrofit2.HttpException

/**
 * 将 Retrofit/服务端错误转为可读文案；优先解析 FastAPI 的 JSON `detail` 字段。
 */
fun Throwable.toUserFacingApiMessage(fallback: String): String {
    if (this is HttpException) {
        val raw = response()?.errorBody()?.use { it.string() }
        if (raw != null) {
            parseFastApiDetail(raw)?.let { return it }
        }
        return message() ?: fallback
    }
    return message ?: fallback
}

private fun parseFastApiDetail(body: String): String? {
    return try {
        val obj = com.google.gson.JsonParser.parseString(body).asJsonObject
        if (!obj.has("detail")) return null
        when (val d = obj.get("detail")) {
            is com.google.gson.JsonPrimitive -> d.asString
            is JsonArray -> {
                val parts = mutableListOf<String>()
                for (el in d) {
                    when {
                        el.isJsonObject -> {
                            val o = el.asJsonObject
                            val msg = o.get("msg")?.takeIf { it.isJsonPrimitive }?.asString
                            if (msg != null) parts.add(msg)
                        }
                        el.isJsonPrimitive -> parts.add(el.asString)
                    }
                }
                parts.joinToString("；").ifBlank { null }
            }
            else -> d.toString()
        }
    } catch (_: Exception) {
        null
    }
}
