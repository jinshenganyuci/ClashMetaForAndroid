package com.github.kr328.clash

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.github.kr328.clash.xboard.XboardAccount
import com.github.kr328.clash.xboard.XboardApi
import com.github.kr328.clash.xboard.XboardSessionExpiredException
import com.github.kr328.clash.xboard.XboardTrafficRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class XboardTrafficActivity : AppCompatActivity() {
    private val scope = MainScope()
    private val account by lazy { XboardAccount(this) }
    private val adapter by lazy { TrafficAdapter(this) }
    private var fetch: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.xb_traffic_page)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.xb_traffic_page)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        findViewById<ListView>(R.id.xb_traffic_list).adapter = adapter
        findViewById<View>(R.id.xb_traffic_back).setOnClickListener { finish() }
        findViewById<View>(R.id.xb_traffic_refresh).setOnClickListener { refresh() }
        findViewById<View>(R.id.xb_traffic_sign_in).setOnClickListener {
            startActivity(Intent(this, XboardAccountActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        refresh()
    }

    override fun onStop() {
        fetch?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refresh() {
        if (fetch?.isActive == true) return
        adapter.replace(emptyList())
        showStatus(getString(R.string.xb_traffic_loading), loading = true)
        fetch = scope.launch {
            try {
                val bearer = account.cached()?.bearer
                if (bearer == null) {
                    showStatus(getString(R.string.xb_traffic_login), signIn = true)
                    return@launch
                }
                val rows = try {
                    XboardApi.trafficLog(bearer)
                } catch (e: XboardSessionExpiredException) {
                    account.clearExpiredSession(bearer)
                    throw e
                }
                if (account.cached()?.bearer != bearer) {
                    showStatus(getString(R.string.xb_traffic_login), signIn = true)
                    return@launch
                }
                adapter.replace(rows)
                showStatus(summary(rows))
            } catch (e: CancellationException) {
                throw e
            } catch (_: XboardSessionExpiredException) {
                showStatus(getString(R.string.xb_expired_session), signIn = true)
            } catch (_: Exception) {
                showStatus(getString(R.string.xb_traffic_failed))
            }
        }
    }

    private fun summary(rows: List<XboardTrafficRecord>): String {
        if (rows.isEmpty()) return getString(R.string.xb_traffic_empty)
        val billed = getString(R.string.xb_traffic_total, formatBytes(rows.sumOf { it.billedBytes }))
        if (rows.any { it.actualUpload == null }) return "$billed\n" + getString(R.string.xb_traffic_rate_note)
        return "$billed\n" + getString(R.string.xb_traffic_directions,
            formatBytes(rows.sumOf { it.actualUpload ?: 0.0 }), formatBytes(rows.sumOf { it.actualDownload ?: 0.0 }))
    }

    private fun showStatus(message: String, loading: Boolean = false, signIn: Boolean = false) {
        findViewById<TextView>(R.id.xb_traffic_status).text = message
        findViewById<View>(R.id.xb_traffic_progress).isVisible = loading
        findViewById<View>(R.id.xb_traffic_refresh).isEnabled = !loading
        findViewById<View>(R.id.xb_traffic_sign_in).isVisible = signIn
    }
}

private class TrafficAdapter(private val context: Context) : BaseAdapter() {
    private val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val rate = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 6 }
    private var rows = emptyList<XboardTrafficRecord>()

    fun replace(value: List<XboardTrafficRecord>) {
        rows = value
        notifyDataSetChanged()
    }
    override fun getCount() = rows.size
    override fun getItem(position: Int) = rows[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun areAllItemsEnabled() = false
    override fun isEnabled(position: Int) = false

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.xb_traffic_row, parent, false)
        val row = getItem(position)
        view.findViewById<TextView>(R.id.xb_traffic_date).text = date.format(Date(row.recordedAt))
        view.findViewById<TextView>(R.id.xb_traffic_rate).text = row.rate?.let {
            context.getString(R.string.xb_traffic_rate, rate.format(it))
        } ?: context.getString(R.string.xb_traffic_unknown_rate)
        val unknown = context.getString(R.string.xb_traffic_unavailable)
        val actual = context.getString(R.string.xb_traffic_directions,
            row.actualUpload?.let(::formatBytes) ?: unknown, row.actualDownload?.let(::formatBytes) ?: unknown)
        view.findViewById<TextView>(R.id.xb_traffic_details).text =
            context.getString(R.string.xb_traffic_billed, formatBytes(row.billedBytes)) + "\n" + actual
        return view
    }
}

// Use the same binary units and two decimal places as the Xboard web traffic table.
private fun formatBytes(bytes: Double): String {
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.getDefault(), "%.2f %s", value, units[unit])
}
