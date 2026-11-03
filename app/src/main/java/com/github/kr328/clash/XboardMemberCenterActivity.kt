package com.github.kr328.clash

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.github.kr328.clash.xboard.XboardAccount
import com.github.kr328.clash.xboard.XboardBinding
import com.github.kr328.clash.xboard.XboardSessionExpiredException
import com.github.kr328.clash.xboard.XboardSubscription
import com.github.kr328.clash.xboard.isExpired
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import kotlin.math.roundToInt
import com.github.kr328.clash.design.R as DesignR

class XboardMemberCenterActivity : AppCompatActivity() {
    private val scope = MainScope()
    private val account by lazy { XboardAccount(this) }
    private var load: Job? = null
    private var member: XboardBinding? = null
    private var loaded = false
    private var waitingForAccount = View.NO_ID
    private var nextSection = View.NO_ID
    private var returningFromSettings = false
    private val signIn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val next = nextSection
        nextSection = View.NO_ID
        if (result.resultCode == RESULT_OK && next != View.NO_ID) openSection(next)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.xb_member_hub)
        val hub = findViewById<View>(R.id.member_hub)
        ViewCompat.setOnApplyWindowInsetsListener(hub) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        nextSection = savedInstanceState?.getInt("nextSection", View.NO_ID) ?: View.NO_ID
        returningFromSettings = savedInstanceState?.getBoolean("returningFromSettings") ?: false
        findViewById<View>(R.id.member_back).setOnClickListener { finish() }
        findViewById<View>(R.id.member_plan).setOnClickListener { selectSection(it.id) }
        addTiles()
    }

    private fun addTiles() {
        val grid = findViewById<GridLayout>(R.id.member_grid)
        val config = resources.configuration
        val columns = if (config.screenWidthDp < 320 || config.fontScale >= 1.5f) 1 else 2
        grid.columnCount = columns
        val margin = (resources.displayMetrics.density * 4).roundToInt()
        tiles.forEachIndexed { index, tile ->
            val view = layoutInflater.inflate(R.layout.xb_member_tile, grid, false)
            view.id = tile.id
            view.findViewById<ImageView>(R.id.member_icon).setImageResource(tile.icon)
            view.findViewById<TextView>(R.id.member_title).setText(tile.title)
            view.findViewById<TextView>(R.id.member_hint).setText(tile.hint)
            view.setOnClickListener { selectSection(tile.id) }
            val span = if (index == tiles.lastIndex && index % columns == 0) columns else 1
            val params = GridLayout.LayoutParams(
                GridLayout.spec(index / columns, GridLayout.FILL),
                GridLayout.spec(index % columns, span, GridLayout.FILL, 1f)
            ).apply {
                width = 0
                setMargins(margin, margin, margin, margin)
            }
            grid.addView(view, params)
        }
    }

    override fun onStart() {
        super.onStart()
        val recheck = returningFromSettings
        returningFromSettings = false
        load = scope.launch {
            val message = findViewById<TextView>(R.id.member_message)
            message.isVisible = false
            try {
                render(account.cached())
                if (member != null) render(account.refresh(force = false, recheckMembership = recheck))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is XboardSessionExpiredException) render(null)
                message.setText(if (e is XboardSessionExpiredException) R.string.xb_expired_session else R.string.xb_member_load_failed)
                message.isVisible = true
                loaded = true
                waitingForAccount = View.NO_ID
            }
        }
    }

    private fun render(state: XboardBinding?) {
        member = state
        loaded = true
        val sub = state?.subscription
        val name = findViewById<TextView>(R.id.member_plan_name)
        name.text = when {
            state == null -> getString(R.string.xb_member_guest)
            sub == null -> state.email
            sub.planId == null -> getString(R.string.xb_no_plan)
            else -> sub.planName ?: getString(R.string.xb_unnamed)
        }
        findViewById<TextView>(R.id.member_expiry).text = when {
            state == null -> getString(R.string.xb_member_guest_hint)
            sub == null -> getString(R.string.xb_member_plan_hint)
            sub.planId == null -> getString(R.string.xb_member_no_plan_hint)
            sub.isExpired() -> getString(R.string.xb_expired)
            sub.expiredAt != null -> getString(R.string.xb_expires, DateFormat.getDateInstance().format(Date(sub.expiredAt * 1000)))
            else -> getString(R.string.xb_lifetime)
        }
        val bar = findViewById<ProgressBar>(R.id.member_usage_bar)
        val usage = findViewById<TextView>(R.id.member_usage)
        val hasQuota = sub?.planId != null && sub.total > 0
        bar.isVisible = hasQuota
        usage.isVisible = hasQuota
        if (hasQuota && sub != null) {
            val fraction = (sub.upload.toDouble() + sub.download) / sub.total
            bar.progress = (fraction.coerceIn(0.0, 1.0) * bar.max).roundToInt()
            usage.text = sub.usageText(this)
        }
        if (waitingForAccount != View.NO_ID) {
            val section = waitingForAccount
            waitingForAccount = View.NO_ID
            selectSection(section)
        }
    }

    private fun selectSection(id: Int) {
        if (!loaded) {
            waitingForAccount = id
            return
        }
        if (nextSection != View.NO_ID) return
        if (member == null) {
            nextSection = id
            signIn.launch(planIntent())
        } else {
            openSection(id)
        }
    }

    private fun planIntent() = Intent(this, XboardAccountActivity::class.java)
        .putExtra(XboardAccountActivity.FROM_MEMBER_CENTER, true)

    private fun openSection(id: Int) {
        val target = when (id) {
            R.id.member_plan -> planIntent()
            R.id.member_nodes -> Intent(this, XboardNodesActivity::class.java)
            R.id.member_traffic -> Intent(this, XboardTrafficActivity::class.java)
            R.id.member_docs -> XboardMemberWebActivity.intent(this, XboardMemberWebActivity.KNOWLEDGE)
            R.id.member_tickets -> XboardMemberWebActivity.intent(this, XboardMemberWebActivity.TICKETS)
            R.id.member_orders -> XboardMemberWebActivity.intent(this, XboardMemberWebActivity.ORDERS)
            R.id.member_invite -> XboardMemberWebActivity.intent(this, XboardMemberWebActivity.INVITE)
            R.id.member_settings -> XboardMemberWebActivity.intent(this, XboardMemberWebActivity.PROFILE)
            else -> return
        }
        returningFromSettings = id == R.id.member_settings
        startActivity(target)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("nextSection", nextSection)
        outState.putBoolean("returningFromSettings", returningFromSettings)
    }

    override fun onStop() {
        load?.cancel()
        loaded = false
        waitingForAccount = View.NO_ID
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private data class Tile(val id: Int, val title: Int, val hint: Int, val icon: Int)
    private companion object {
        val tiles = listOf(
            Tile(R.id.member_nodes, DesignR.string.xb_nodes_title, R.string.xb_member_nodes_hint, DesignR.drawable.ic_baseline_dns),
            Tile(R.id.member_traffic, R.string.xb_traffic_title, R.string.xb_member_traffic_hint, DesignR.drawable.ic_baseline_swap_vertical_circle),
            Tile(R.id.member_docs, R.string.xb_member_docs, R.string.xb_member_docs_hint, DesignR.drawable.ic_outline_article),
            Tile(R.id.member_tickets, R.string.xb_member_tickets, R.string.xb_member_tickets_hint, DesignR.drawable.ic_baseline_help_center),
            Tile(R.id.member_orders, R.string.xb_member_orders, R.string.xb_member_orders_hint, DesignR.drawable.ic_baseline_assignment),
            Tile(R.id.member_invite, R.string.xb_member_invite, R.string.xb_member_invite_hint, DesignR.drawable.ic_baseline_key),
            Tile(R.id.member_settings, R.string.xb_member_settings, R.string.xb_member_settings_hint, DesignR.drawable.ic_baseline_settings)
        )
    }
}

// The panel displays quota in 1024-based GB. Use the same units on the card and its details page.
internal fun XboardSubscription.usageText(context: Context): String {
    val gb = 1024.0 * 1024 * 1024
    val fmt = NumberFormat.getNumberInstance().apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }
    return context.getString(R.string.xb_member_usage, fmt.format((upload.toDouble() + download) / gb), fmt.format(total / gb))
}
