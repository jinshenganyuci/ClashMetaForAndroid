package com.github.kr328.clash

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Patterns
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.AndroidViewModel
import com.github.kr328.clash.xboard.XboardAccount
import com.github.kr328.clash.xboard.XboardApi
import com.github.kr328.clash.xboard.XboardRegistrationException
import com.github.kr328.clash.xboard.XboardRegistrationPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal enum class Task { POLICY, SEND_CODE, REGISTER }

internal data class RegisterState(
    val policy: XboardRegistrationPolicy? = null,
    val task: Task? = null,
    val message: String = "",
    val resendAt: Long = 0,
    val registeredEmail: String? = null,
    val sessionSaved: Boolean = false,
)

internal class XboardRegisterModel(app: Application) : AndroidViewModel(app) {
    private val scope = MainScope()
    private val account = XboardAccount(app)
    internal val state = MutableStateFlow(RegisterState())

    init { loadPolicy() }

    fun loadPolicy() = run(Task.POLICY) {
        state.value = state.value.copy(policy = XboardApi.registrationPolicy())
    }

    fun sendCode(email: String) = run(Task.SEND_CODE) {
        XboardApi.sendEmailCode(email)
        state.value = state.value.copy(
            resendAt = SystemClock.elapsedRealtime() + 60_000,
            message = getApplication<Application>().getString(R.string.xb_code_sent),
        )
    }

    fun register(email: String, password: String, code: String, invite: String) = run(Task.REGISTER) {
        val saved = account.register(email, password, code, invite)
        state.value = state.value.copy(registeredEmail = email, sessionSaved = saved)
    }

    private fun run(task: Task, block: suspend () -> Unit) {
        if (state.value.task != null || state.value.registeredEmail != null) return
        state.value = state.value.copy(task = task, message = "")
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = e as? XboardRegistrationException
                val message = failure?.message ?:
                    getApplication<Application>().getString(R.string.xb_registration_network_error)
                val retryAt = if (task == Task.SEND_CODE && failure?.retryAfterSeconds != null) {
                    SystemClock.elapsedRealtime() + failure.retryAfterSeconds * 1000L
                } else state.value.resendAt
                state.value = state.value.copy(message = message, resendAt = retryAt)
            } finally {
                state.value = state.value.copy(task = null)
            }
        }
    }

    override fun onCleared() { scope.cancel() }
}

class XboardRegisterActivity : AppCompatActivity() {
    private val model by viewModels<XboardRegisterModel>()
    private val scope = MainScope()
    private val email by lazy { findViewById<EditText>(R.id.register_email) }
    private val password by lazy { findViewById<EditText>(R.id.register_password) }
    private val confirm by lazy { findViewById<EditText>(R.id.register_confirm) }
    private val code by lazy { findViewById<EditText>(R.id.register_code) }
    private val invite by lazy { findViewById<EditText>(R.id.register_invite) }
    private val terms by lazy { findViewById<CheckBox>(R.id.register_accept) }
    private val sendCode by lazy { findViewById<Button>(R.id.register_send_code) }
    private val submit by lazy { findViewById<Button>(R.id.register_submit) }
    private val status by lazy { findViewById<TextView>(R.id.register_status) }
    private val guardSubmit = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { }
    }
    private val countdown = Runnable { updateResendButton() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_xboard_register)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.register_page)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        onBackPressedDispatcher.addCallback(this, guardSubmit)
        if (savedInstanceState == null) email.setText(intent.getStringExtra("email").orEmpty())
        sendCode.setOnClickListener {
            model.state.value.policy?.let { policy ->
                checkedEmail(policy)?.let(model::sendCode)
            }
        }
        submit.setOnClickListener { submitForm() }
        findViewById<Button>(R.id.register_view_terms).setOnClickListener {
            model.state.value.policy?.termsUrl?.let { openWebsite(it) }
        }
        findViewById<Button>(R.id.register_back).setOnClickListener { finish() }
        scope.launch { model.state.collect { render(it) } }
    }

    private fun checkedEmail(policy: XboardRegistrationPolicy): String? {
        val address = email.text.toString().trim()
        val error = when {
            !Patterns.EMAIL_ADDRESS.matcher(address).matches() -> getString(R.string.xb_invalid_email)
            policy.emailDomains.isNotEmpty() &&
                policy.emailDomains.none { it.equals(address.substringAfterLast('@'), ignoreCase = true) } ->
                getString(R.string.xb_supported_domains, policy.emailDomains.joinToString(", "))
            else -> return address
        }
        email.error = error
        email.requestFocus()
        return null
    }

    private fun submitForm() {
        val policy = model.state.value.policy
        if (policy == null) { model.loadPolicy(); return }
        if (policy.captchaRequired) { openWebsite(XboardApi.WEB_REGISTER_URL); return }
        val address = checkedEmail(policy) ?: return
        val pass = password.text.toString()
        val mailCode = code.text.toString().trim()
        val inviteCode = invite.text.toString().trim()
        fun invalid(field: EditText, message: Int) {
            field.error = getString(message)
            field.requestFocus()
        }
        when {
            pass.codePointCount(0, pass.length) < 8 -> invalid(password, R.string.xb_min_password)
            confirm.text.toString() != pass -> invalid(confirm, R.string.xb_password_mismatch)
            policy.emailVerificationRequired && !mailCode.matches(Regex("[0-9]{6}")) ->
                invalid(code, R.string.xb_invalid_code)
            policy.inviteRequired && inviteCode.isBlank() -> invalid(invite, R.string.xb_required)
            policy.termsUrl != null && !terms.isChecked -> status.setText(R.string.xb_terms_required)
            else -> model.register(address, pass, if (policy.emailVerificationRequired) mailCode else "", inviteCode)
        }
    }

    private fun render(state: RegisterState) {
        if (state.registeredEmail != null) {
            setResult(if (state.sessionSaved) RESULT_OK else RESULT_FIRST_USER,
                Intent().putExtra("email", state.registeredEmail))
            Toast.makeText(this, R.string.xb_registration_success, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val policy = state.policy
        val busy = state.task != null
        val nativeForm = policy != null && !policy.captchaRequired
        guardSubmit.isEnabled = state.task == Task.REGISTER
        findViewById<View>(R.id.register_fields).isVisible = nativeForm
        listOf(email, password, confirm, code, invite, terms).forEach { it.isEnabled = !busy }
        findViewById<View>(R.id.register_mail_row).isVisible = policy?.emailVerificationRequired == true
        findViewById<View>(R.id.register_terms_row).isVisible = policy?.termsUrl != null
        findViewById<View>(R.id.register_back).isEnabled = !guardSubmit.isEnabled
        invite.setHint(if (policy?.inviteRequired == true) R.string.xb_invite_required else R.string.xb_invite_optional)
        submit.isEnabled = !busy
        submit.setText(when {
            policy == null -> R.string.xb_retry_policy
            policy.captchaRequired -> R.string.xb_web_register
            else -> R.string.xb_create_account
        })
        findViewById<View>(R.id.register_progress).isVisible = busy
        status.text = when (state.task) {
            Task.POLICY -> getString(R.string.xb_loading_policy)
            Task.SEND_CODE -> getString(R.string.xb_sending_code)
            Task.REGISTER -> getString(R.string.xb_creating_account)
            null -> if (policy?.captchaRequired == true) getString(R.string.xb_web_challenge) else state.message
        }
        updateResendButton()
    }

    private fun updateResendButton() {
        val state = model.state.value
        sendCode.removeCallbacks(countdown)
        val seconds = (state.resendAt - SystemClock.elapsedRealtime() + 999) / 1000
        sendCode.isEnabled = state.task == null && seconds <= 0
        sendCode.text = if (seconds > 0) getString(R.string.xb_resend_after, seconds) else getString(R.string.xb_send_code)
        if (seconds > 0) sendCode.postDelayed(countdown, 1000)
    }

    private fun openWebsite(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: ActivityNotFoundException) { status.setText(R.string.xb_browser_missing) }
    }

    override fun onDestroy() {
        sendCode.removeCallbacks(countdown)
        scope.cancel()
        super.onDestroy()
    }
}
