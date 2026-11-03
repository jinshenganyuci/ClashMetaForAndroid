package com.github.kr328.clash

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.github.kr328.clash.xboard.XboardAccount
import com.github.kr328.clash.xboard.XboardApi
import com.github.kr328.clash.xboard.XboardMemberRequestException
import com.github.kr328.clash.xboard.XboardSessionExpiredException
import com.github.kr328.clash.xboard.XboardStore
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

class XboardMemberPageActivity : AppCompatActivity() {
    private val scope = MainScope()
    private val store by lazy { XboardStore(this) }
    private var owner: String? = null
    private var reader: Job? = null
    private var writer: Job? = null
    private var goBack: (() -> Unit)? = null
    private var reload: (() -> Unit)? = null
    internal lateinit var body: LinearLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.xb_member_page)
        body = findViewById(R.id.xb_page_body)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.xb_page_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        findViewById<View>(R.id.xb_page_back).setOnClickListener { onBackPressed() }
        findViewById<View>(R.id.xb_page_refresh).setOnClickListener { if (writer?.isActive != true) reload?.invoke() }
        when (intent.getStringExtra("section")) {
            KNOWLEDGE -> showKnowledge()
            TICKETS -> showTickets()
            ORDERS -> showOrders()
            INVITE -> showInvite()
            PROFILE -> showProfile()
            else -> finish()
        }
    }

    internal fun page(title: String, back: (() -> Unit)? = null, render: suspend () -> Unit) {
        reader?.cancel()
        goBack = back
        reload = { page(title, back, render) }
        findViewById<TextView>(R.id.xb_page_title).text = title
        findViewById<ScrollView>(R.id.xb_page_scroll).scrollTo(0, 0)
        body.removeAllViews()
        val progress = findViewById<View>(R.id.xb_page_progress)
        progress.isVisible = true
        reader = scope.launch {
            try {
                render()
            } catch (e: CancellationException) {
                throw e
            } catch (e: XboardSessionExpiredException) {
                signedOut()
            } catch (e: Exception) {
                body.removeAllViews()
                label(failureMessage(e))
                action(getString(R.string.xb_nodes_refresh)) { reload?.invoke() }
            } finally {
                if (!isFinishing && coroutineContext[Job]?.isCancelled != true) progress.isVisible = false
            }
        }
    }

    internal suspend fun apiEnvelope(path: String, params: Map<String, String> = emptyMap(), post: Boolean = false): JSONObject {
        val bearer = withContext(Dispatchers.IO) { store.read()?.bearer } ?: throw XboardSessionExpiredException()
        if (owner == null) owner = bearer
        if (owner != bearer) throw XboardSessionExpiredException()
        return XboardApi.memberRequest(bearer, path, params, post)
    }

    internal suspend fun api(path: String, params: Map<String, String> = emptyMap(), post: Boolean = false): Any? =
        apiEnvelope(path, params, post).get("data")

    internal suspend fun signedOut() {
        owner?.let { XboardAccount(this).clearExpiredSession(it) }
        if (!isFinishing) {
            android.widget.Toast.makeText(this, R.string.xb_expired_session, android.widget.Toast.LENGTH_LONG).show()
            finish()
        }
    }

    internal fun mutate(block: suspend () -> Unit) {
        if (writer?.isActive == true) return
        writer = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: XboardSessionExpiredException) {
                signedOut()
            } catch (e: Exception) {
                if (!isFinishing) AlertDialog.Builder(this@XboardMemberPageActivity).setMessage(failureMessage(e))
                    .setPositiveButton(android.R.string.ok, null).show()
            }
        }
    }

    private fun failureMessage(e: Exception): String =
        (e as? XboardMemberRequestException)?.message?.takeIf { it.isNotBlank() } ?: getString(R.string.xb_page_error)

    internal fun label(text: CharSequence): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setPadding(0, px(7), 0, px(7))
        body.addView(this)
    }

    internal fun action(text: String, onClick: () -> Unit): Button = MaterialButton(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { if (writer?.isActive != true) onClick() }
        body.addView(this)
    }

    private fun px(dp: Int) = (resources.displayMetrics.density * dp).roundToInt()

    @Deprecated("Android back dispatch compatibility")
    override fun onBackPressed() {
        if (writer?.isActive != true) goBack?.invoke() ?: finish()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val KNOWLEDGE = "knowledge"
        const val ORDERS = "order"
        const val INVITE = "invite"
        const val TICKETS = "ticket"
        const val PROFILE = "profile"
        fun intent(context: Context, section: String) =
            Intent(context, XboardMemberPageActivity::class.java).putExtra("section", section)
    }
}
