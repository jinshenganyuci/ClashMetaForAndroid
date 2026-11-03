package com.github.kr328.clash.xboard

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.Locale

data class XboardRegistrationPolicy(
    val emailVerificationRequired: Boolean,
    val inviteRequired: Boolean,
    val captchaRequired: Boolean,
    val emailDomains: List<String>,
    val termsUrl: String?,
) {
    internal companion object {
        fun fromJson(json: JSONObject): XboardRegistrationPolicy {
            fun flag(name: String): Boolean = when (json.opt(name)?.toString()) {
                "1", "true" -> true
                "0", "false" -> false
                else -> throw IOException("面板未返回有效的注册策略")
            }
            val domains = json.optJSONArray("email_whitelist_suffix") ?: JSONArray()
            val terms = (json.opt("tos_url") as? String)?.takeIf { it.isNotBlank() }?.let {
                try {
                    URI("https://ikun.iil.im/").resolve(it).takeIf { url ->
                        url.scheme.equals("https", ignoreCase = true) && !url.host.isNullOrBlank() && url.userInfo == null
                    }?.toASCIIString()
                } catch (_: IllegalArgumentException) { null }
            }
            return XboardRegistrationPolicy(
                emailVerificationRequired = flag("is_email_verify"),
                inviteRequired = flag("is_invite_force"),
                captchaRequired = flag(if (json.has("is_captcha")) "is_captcha" else "is_recaptcha"),
                emailDomains = (0 until domains.length()).mapNotNull {
                    (domains.opt(it) as? String)?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty)
                },
                termsUrl = terms,
            )
        }
    }
}

class XboardRegistrationException internal constructor(
    message: String,
    val retryAfterSeconds: Int? = null,
    val accountMayExist: Boolean = false,
) : IOException(message)

internal fun registrationFailure(
    status: Int,
    payload: JSONObject?,
    registering: Boolean,
    retryAfter: Int? = null,
): XboardRegistrationException {
    // Request English from Xboard, then map only its known messages. A proxy, plugin or
    // validation response could echo the submitted email, password or verification code.
    val message = payload?.optString("message")
    val validation = payload?.optJSONObject("errors")
    val issue = when {
        status == 429 -> "请求过于频繁，请稍后重试" to "Too many requests. Please try again later."
        message == "Email verification code has been sent, please request again later" ->
            return XboardRegistrationException(localText("验证码已发送，请稍候再请求", "A code was already sent. Please wait before requesting another."), 60)
        message == "Email already exists" -> return XboardRegistrationException(
            localText("这个邮箱已注册，请返回登录", "This email is already registered. Return to sign in."), accountMayExist = true,
        )
        message == "Incorrect email verification code" || message == "Email verification code cannot be empty" ->
            "请输入正确的六位邮箱验证码；验证码五分钟内有效" to "Enter the six-digit email code. Codes expire after five minutes."
        message == "You must use the invitation code to register" || message == "Invalid invitation code" ->
            "请填写有效的邀请码" to "Enter a valid invitation code."
        message == "Email suffix is not in whitelist" || message == "Email suffix is not in the Whitelist" ->
            "请使用面板允许的邮箱域名" to "Use an email domain allowed by this site."
        message == "Gmail alias is not supported" ->
            "请使用不含点号或加号别名的邮箱" to "Use an email address without a dot or plus alias."
        message == "Registration has closed" -> "站点已关闭注册" to "Registration is closed."
        message == "Invalid captcha type" || message == "Invalid code is incorrect" ->
            "请打开面板网页完成人机验证和注册" to "Open the registration website to complete the captcha."
        validation?.has("email") == true || message == "Email format is incorrect" ->
            "请填写有效的邮箱地址" to "Enter a valid email address."
        validation?.has("password") == true || message == "Password must be greater than 8 digits" ->
            "密码至少需要八个字符" to "Use a password with at least eight characters."
        registering && (status < 0 || status == 408 || status in 500..599) -> return XboardRegistrationException(
            localText("未能确认注册结果。账号可能已经创建，请先尝试登录，再决定是否重新注册。",
                "Registration could not be confirmed. The account may already exist. Try signing in before registering again."),
            accountMayExist = true,
        )
        registering -> "注册未完成，请检查填写的信息和站点注册规则" to "Registration was declined. Check your details and the site's registration rules."
        else -> "未能确认验证码是否已发送，请先检查邮箱和垃圾邮件，稍后再试" to
            "Could not confirm that a code was sent. Check your inbox and spam folder before requesting another."
    }
    return XboardRegistrationException(localText(issue.first, issue.second), retryAfter)
}

private fun localText(zh: String, en: String) = if (Locale.getDefault().language == "zh") zh else en
