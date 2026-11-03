package com.github.kr328.clash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.github.kr328.clash.xboard.XboardAccount
import com.github.kr328.clash.xboard.XboardBinding
import com.github.kr328.clash.xboard.XboardSessionExpiredException
import com.github.kr328.clash.xboard.isExpired
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class XboardAccountActivity : AppCompatActivity() {
    private val scope = MainScope()
    private val account by lazy { XboardAccount(this) }
    private val email by lazy { findViewById<EditText>(R.id.account_email) }
    private val password by lazy { findViewById<EditText>(R.id.account_password) }
    private val primary by lazy { findViewById<Button>(R.id.account_primary) }
    private val copySubscription by lazy { findViewById<Button>(R.id.account_copy_subscription) }
    private val logout by lazy { findViewById<Button>(R.id.account_logout) }
    private val status by lazy { findViewById<TextView>(R.id.account_status) }
    private var binding: XboardBinding? = null
    private var busy = false
    private var refreshNewAccount = false

    private val registration = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        when (result.resultCode) {
            RESULT_OK -> {
                refreshNewAccount = true
                if (!busy) finishRegistration()
            }
            RESULT_FIRST_USER -> {
                email.setText(result.data?.getStringExtra("email").orEmpty())
                status.setText(R.string.xb_registered_sign_in)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_xboard_account)
        val page = findViewById<View>(R.id.account_page)
        ViewCompat.setOnApplyWindowInsetsListener(page) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        findViewById<Button>(R.id.account_back).apply {
            if (intent.getBooleanExtra(FROM_MEMBER_CENTER, false)) setText(R.string.xb_back_to_member)
            setOnClickListener { finish() }
        }
        findViewById<Button>(R.id.account_register).setOnClickListener {
            registration.launch(Intent(this, XboardRegisterActivity::class.java)
                .putExtra("email", email.text.toString().trim()))
        }
        primary.setOnClickListener { submit() }
        copySubscription.setOnClickListener { copySubscriptionUrl() }
        password.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        logout.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.xb_logout_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.xb_logout) { _, _ ->
                    perform { account.logout(); render(null) }
                }.show()
        }
        perform { render(account.cached()) }
    }

    private fun copySubscriptionUrl() {
        perform {
            status.setText(R.string.xb_fetching_subscription_link)
            val latest = account.latestMembership()
            render(latest)
            val subscription = latest?.subscription ?: return@perform
            if (subscription.planId == null || subscription.isExpired()) return@perform
            val clip = ClipData.newPlainText(getString(R.string.xb_copy_subscription), subscription.subscribeUrl)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Hide the member's subscription token in compatible clipboard previews.
                clip.description.extras = PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
            }
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            status.setText(R.string.xb_subscription_copied)
        }
    }

    private fun finishRegistration() {
        refreshNewAccount = false
        perform {
            val updated = account.refresh(force = true)
            render(updated)
            afterSignIn(updated)
        }
    }

    private fun submit() {
        if (busy) return
        val signingIn = binding == null
        if (signingIn && email.text.isBlank()) {
            email.error = getString(R.string.xb_required)
            return
        }
        if (signingIn && password.text.isEmpty()) {
            password.error = getString(R.string.xb_required)
            return
        }
        perform(signingIn) {
            val updated = if (signingIn) {
                account.login(email.text.toString(), password.text.toString())
            } else {
                account.refresh(force = true)
            }
            password.text.clear()
            render(updated)
            if (signingIn) afterSignIn(updated)
        }
    }

    private fun afterSignIn(state: XboardBinding?) {
        if (state == null) return
        setResult(RESULT_OK)
        if (state.profileId != null) Toast.makeText(this, R.string.xb_login_ready, Toast.LENGTH_LONG).show()
        if (state.profileId != null || intent.getBooleanExtra(FROM_MEMBER_CENTER, false)) finish()
    }

    private fun perform(signingIn: Boolean = false, action: suspend () -> Unit) {
        if (busy) return
        setBusy(true)
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val saved = try { account.cached() } catch (_: java.io.IOException) { null }
                render(saved)
                status.setText(when {
                    e is XboardSessionExpiredException -> R.string.xb_expired_session
                    signingIn && saved == null -> R.string.xb_login_error
                    else -> R.string.xb_error
                })
            } finally {
                setBusy(false)
                if (refreshNewAccount && !isFinishing && !isDestroyed) finishRegistration()
            }
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        primary.isEnabled = !value
        logout.isEnabled = !value
        copySubscription.isEnabled = !value
        findViewById<Button>(R.id.account_register).isEnabled = !value
        email.isEnabled = !value
        password.isEnabled = !value
        findViewById<View>(R.id.account_progress).visibility = if (value) View.VISIBLE else View.GONE
        if (value) status.setText(R.string.xb_checking)
    }

    private fun render(state: XboardBinding?) {
        binding = state
        val sub = state?.subscription
        val signedIn = state != null
        val title = findViewById<TextView>(R.id.account_title)
        val details = findViewById<TextView>(R.id.account_details)
        findViewById<View>(R.id.login_fields).visibility = if (signedIn) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.account_register).visibility = if (signedIn) View.GONE else View.VISIBLE
        copySubscription.visibility = if (signedIn && (sub == null || (sub.planId != null && !sub.isExpired()))) {
            View.VISIBLE
        } else View.GONE
        logout.visibility = if (signedIn) View.VISIBLE else View.GONE
        primary.setText(if (signedIn) R.string.xb_refresh else R.string.xb_submit)
        title.text = when {
            state == null -> getString(R.string.xb_login)
            sub == null -> getString(R.string.xb_plan)
            sub.planId == null -> getString(R.string.xb_no_plan)
            else -> sub.planName ?: getString(R.string.xb_unnamed)
        }
        details.text = if (state == null) getString(R.string.xb_intro) else buildList {
            add(state.email)
            if (sub?.planId != null) {
                add(sub.expiredAt?.let {
                    getString(R.string.xb_expires, DateFormat.getDateInstance().format(Date(it * 1000)))
                } ?: getString(R.string.xb_lifetime))
                if (sub.total > 0) add(sub.usageText(this@XboardAccountActivity))
                state.lastSync?.let {
                    add(getString(R.string.xb_last_sync, DateFormat.getDateTimeInstance().format(Date(it))))
                }
            }
        }.joinToString("\n")
        status.text = when {
            state == null || sub == null || sub.planId == null -> ""
            sub.isExpired() -> getString(R.string.xb_expired)
            state.profileId == null -> getString(R.string.xb_no_nodes)
            else -> getString(R.string.xb_ready)
        }
        if (signedIn) password.text.clear()
    }

    companion object {
        const val FROM_MEMBER_CENTER = "from_member_center"
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
