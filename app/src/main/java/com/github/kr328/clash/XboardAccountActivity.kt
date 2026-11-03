package com.github.kr328.clash

import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
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
    private val logout by lazy { findViewById<Button>(R.id.account_logout) }
    private val status by lazy { findViewById<TextView>(R.id.account_status) }
    private var binding: XboardBinding? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_xboard_account)
        val page = findViewById<View>(R.id.account_page)
        ViewCompat.setOnApplyWindowInsetsListener(page) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        findViewById<Button>(R.id.account_back).setOnClickListener { finish() }
        primary.setOnClickListener { submit() }
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
            if (signingIn && updated?.profileId != null) {
                Toast.makeText(this, R.string.xb_login_ready, Toast.LENGTH_LONG).show()
                finish()
            }
        }
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
            }
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        primary.isEnabled = !value
        logout.isEnabled = !value
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
                if (sub.total > 0) add(getString(R.string.xb_traffic,
                    Formatter.formatFileSize(this@XboardAccountActivity, sub.upload + sub.download),
                    Formatter.formatFileSize(this@XboardAccountActivity, sub.total)))
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

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
