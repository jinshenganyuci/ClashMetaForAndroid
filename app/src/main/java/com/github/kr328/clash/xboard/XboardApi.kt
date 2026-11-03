package com.github.kr328.clash.xboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class XboardSessionExpiredException : IOException("登录已失效，请重新登录")

data class XboardSubscription(
    val planId: Int?,
    val planName: String?,
    val expiredAt: Long?,
    val upload: Long,
    val download: Long,
    val total: Long,
    val subscribeUrl: String,
) {
    override fun toString() = "XboardSubscription"

    internal fun toJson(): JSONObject = JSONObject()
        .put("plan_id", planId)
        .put("plan", planName?.let { JSONObject().put("name", it) })
        .put("expired_at", expiredAt)
        .put("u", upload)
        .put("d", download)
        .put("transfer_enable", total)
        .put("subscribe_url", subscribeUrl)

    internal companion object {
        fun fromJson(data: JSONObject): XboardSubscription {
            val url = data.getString("subscribe_url")
            val uri = try { URI(url) } catch (_: java.net.URISyntaxException) { null }
            if (uri == null || !uri.scheme.equals("https", ignoreCase = true) ||
                uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawFragment != null) {
                throw IOException("面板返回的订阅地址无效")
            }
            return XboardSubscription(
                planId = if (data.isNull("plan_id")) null else data.getInt("plan_id"),
                planName = data.optJSONObject("plan")?.optString("name")?.takeIf { it.isNotBlank() },
                expiredAt = if (data.isNull("expired_at")) null else data.getLong("expired_at"),
                upload = data.getLong("u"),
                download = data.getLong("d"),
                total = data.getLong("transfer_enable"),
                subscribeUrl = url,
            )
        }
    }
}

object XboardApi {
    const val WEB_REGISTER_URL = "https://ikun.iil.im/#/register"
    private const val API = "https://ikun.iil.im/api/v1"
    private const val REGISTER = "/passport/auth/register"
    private const val SEND_CODE = "/passport/comm/sendEmailVerify"

    suspend fun login(email: String, password: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email.trim()).put("password", password)
        authData(request("/passport/auth/login", body = body))
    }

    suspend fun registrationPolicy(): XboardRegistrationPolicy = withContext(Dispatchers.IO) {
        XboardRegistrationPolicy.fromJson(data(request("/guest/comm/config")))
    }

    suspend fun sendEmailCode(email: String): Unit = withContext(Dispatchers.IO) {
        try {
            val response = request(SEND_CODE, body = JSONObject().put("email", email.trim()))
            if (response.opt("data") != true) throw registrationFailure(-1, null, registering = false)
        } catch (e: XboardRegistrationException) {
            throw e
        } catch (_: IOException) {
            throw registrationFailure(-1, null, registering = false)
        }
    }

    suspend fun register(email: String, password: String, emailCode: String, inviteCode: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email.trim()).put("password", password)
        if (emailCode.isNotBlank()) body.put("email_code", emailCode.trim())
        if (inviteCode.isNotBlank()) body.put("invite_code", inviteCode.trim())
        try {
            authData(request(REGISTER, body = body))
        } catch (e: XboardRegistrationException) {
            throw e
        } catch (_: IOException) {
            throw registrationFailure(-1, null, registering = true)
        }
    }

    suspend fun getSubscribe(bearer: String): XboardSubscription = withContext(Dispatchers.IO) {
        val data = data(request("/user/getSubscribe", bearer = bearer))
        try {
            XboardSubscription.fromJson(data)
        } catch (_: JSONException) {
            throw IOException("面板返回的订阅信息不完整")
        }
    }

    private fun data(response: JSONObject): JSONObject =
        response.optJSONObject("data") ?: throw IOException("面板未返回有效的数据")

    private fun authData(response: JSONObject): String {
        val bearer = data(response).optString("auth_data")
        if (!bearer.matches(Regex("Bearer [A-Za-z0-9._~+/=-]+"))) {
            throw IOException("面板未返回有效的登录凭据")
        }
        return bearer
    }

    private fun request(path: String, bearer: String? = null, body: JSONObject? = null): JSONObject {
        val connection = URL(API + path).openConnection() as HttpsURLConnection
        try {
            // Keep credentials on the configured origin, including POST bodies on 307/308.
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Language", "en-US")
            bearer?.let { connection.setRequestProperty("Authorization", it) }
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.requestMethod = "POST"
                connection.doOutput = true
                // Stream a single, non-replayable POST, especially for registration.
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val error = connection.errorStream?.let {
                    try { JSONObject(readBody(it)) } catch (_: JSONException) { null }
                }
                if (bearer != null && (status == 401 || (status == 403 &&
                        error?.optString("status") == "fail" && error.optString("message") == "未登录或登陆已过期"))) {
                    throw XboardSessionExpiredException()
                }
                if (path == REGISTER || path == SEND_CODE) {
                    val retry = connection.getHeaderField("Retry-After")?.toIntOrNull()?.takeIf { it in 1..86400 }
                    throw registrationFailure(status, error, path == REGISTER, retry)
                }
                val message = when {
                    body != null && status in setOf(400, 401, 403, 422) -> "登录失败，请检查邮箱、密码和账号状态"
                    status == 429 -> "请求过于频繁，请稍后重试"
                    else -> "面板请求失败（HTTP $status）"
                }
                throw IOException(message)
            }
            val response = JSONObject(readBody(connection.inputStream))
            if (response.optString("status") == "fail" && (path == REGISTER || path == SEND_CODE)) {
                throw registrationFailure(400, response, path == REGISTER)
            }
            return response
        } catch (_: JSONException) {
            throw IOException("面板返回的数据格式无效")
        } finally {
            connection.disconnect()
        }
    }

    private fun readBody(stream: InputStream): String {
        return stream.bufferedReader(Charsets.UTF_8).use { reader ->
            val text = StringBuilder()
            val buffer = CharArray(4096)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (text.length + count > 256 * 1024) throw IOException("面板响应过大")
                text.append(buffer, 0, count)
            }
            text.toString()
        }
    }
}
