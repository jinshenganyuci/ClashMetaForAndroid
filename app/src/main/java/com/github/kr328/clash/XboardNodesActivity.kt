package com.github.kr328.clash

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.github.kr328.clash.xboard.XboardAccount
import com.github.kr328.clash.xboard.XboardApi
import com.github.kr328.clash.xboard.XboardNode
import com.github.kr328.clash.xboard.XboardSessionExpiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

class XboardNodesActivity : AppCompatActivity() {
    private val scope = MainScope()
    private val account by lazy { XboardAccount(this) }
    private val adapter by lazy { NodesAdapter(this) }
    private var fetch: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.xb_nodes_page)
        val page = findViewById<View>(R.id.xb_nodes_page)
        ViewCompat.setOnApplyWindowInsetsListener(page) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        findViewById<ListView>(R.id.xb_nodes_list).adapter = adapter
        findViewById<View>(R.id.xb_nodes_back).setOnClickListener { finish() }
        findViewById<View>(R.id.xb_nodes_refresh).setOnClickListener { refresh() }
        findViewById<View>(R.id.xb_nodes_sign_in).setOnClickListener {
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
        showStatus(getString(R.string.xb_nodes_loading), loading = true)
        fetch = scope.launch {
            try {
                val bearer = account.cached()?.bearer
                if (bearer == null) {
                    showStatus(getString(R.string.xb_nodes_requires_account), signIn = true)
                    return@launch
                }
                val rows = try {
                    XboardApi.nodes(bearer)
                } catch (e: XboardSessionExpiredException) {
                    account.clearExpiredSession(bearer)
                    throw e
                }
                if (account.cached()?.bearer != bearer) {
                    showStatus(getString(R.string.xb_nodes_failed))
                    return@launch
                }
                adapter.replace(rows)
                val summary = if (rows.isEmpty()) getString(R.string.xb_nodes_empty)
                    else resources.getQuantityString(R.plurals.xb_nodes_count, rows.size, rows.size)
                showStatus(summary)
            } catch (e: CancellationException) {
                throw e
            } catch (_: XboardSessionExpiredException) {
                showStatus(getString(R.string.xb_expired_session), signIn = true)
            } catch (_: Exception) {
                showStatus(getString(R.string.xb_nodes_failed))
            }
        }
    }

    private fun showStatus(message: String, loading: Boolean = false, signIn: Boolean = false) {
        findViewById<TextView>(R.id.xb_nodes_status).text = message
        findViewById<View>(R.id.xb_nodes_progress).isVisible = loading
        findViewById<Button>(R.id.xb_nodes_refresh).isEnabled = !loading
        findViewById<View>(R.id.xb_nodes_sign_in).isVisible = signIn
    }
}

private class NodesAdapter(private val context: Context) : BaseAdapter() {
    private var rows = emptyList<XboardNode>()
    private val rateFormat = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 6 }
    private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

    fun replace(nodes: List<XboardNode>) {
        rows = nodes
        notifyDataSetChanged()
    }

    override fun getCount() = rows.size
    override fun getItem(position: Int) = rows[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun areAllItemsEnabled() = false
    override fun isEnabled(position: Int) = false

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.xb_nodes_row, parent, false)
        val node = getItem(position)
        view.findViewById<TextView>(R.id.xb_node_name).text = node.name
        val (label, dotColor) = when (node.online) {
            true -> R.string.xb_nodes_online to R.color.xb_nodes_online_dot
            false -> R.string.xb_nodes_offline to R.color.xb_nodes_offline_dot
            null -> R.string.xb_nodes_unknown to R.color.xb_nodes_neutral_dot
        }
        view.findViewById<TextView>(R.id.xb_node_state).apply {
            setText(label)
            compoundDrawablesRelative[0]?.mutate()?.setTint(ContextCompat.getColor(context, dotColor))
        }
        val metadata = buildList {
            node.type?.let { add(it.uppercase(Locale.ROOT)) }
            if (node.external) add(context.getString(R.string.xb_nodes_external))
            node.rate?.let { add(context.getString(R.string.xb_nodes_rate, rateFormat.format(it))) }
        }
        val details = buildList {
            if (metadata.isNotEmpty()) add(metadata.joinToString(" · "))
            if (node.tags.isNotEmpty()) add(context.getString(R.string.xb_nodes_tags, node.tags.joinToString(", ")))
            node.lastCheckAt?.let { add(context.getString(R.string.xb_nodes_checked, dateFormat.format(Date(it * 1000)))) }
        }
        view.findViewById<TextView>(R.id.xb_node_details).apply {
            text = details.joinToString("\n")
            isVisible = details.isNotEmpty()
        }
        return view
    }
}
