package com.github.kr328.clash

import android.net.Uri
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.style.URLSpan
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import androidx.core.text.HtmlCompat
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

internal fun XboardMemberPageActivity.showKnowledge() = showGuides("")

private fun XboardMemberPageActivity.showGuides(keyword: String) {
    page(getString(R.string.xb_member_docs)) {
        @Suppress("DEPRECATION")
        val locale = resources.configuration.locale
        val language = when (locale.language) {
            "zh" -> if (locale.script == "Hant" || locale.country in setOf("HK", "TW", "MO")) "zh-TW" else "zh-CN"
            "ja" -> "ja-JP"
            "vi" -> "vi-VN"
            "ko" -> "ko-KR"
            "fa" -> "fa-IR"
            else -> "en-US"
        }
        val result = api("user/knowledge/fetch", mapOf("language" to language, "keyword" to keyword))
        val search = supportField(R.string.xb_support_search_hint, multiline = false).apply {
            filters = arrayOf(InputFilter.LengthFilter(255))
            setText(keyword)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    showGuides(text.toString().trim())
                    true
                } else false
            }
        }
        action(getString(R.string.xb_support_search)) { showGuides(search.text.toString().trim()) }
        // PHP serializes a group keyed by consecutive numeric categories as a JSON array.
        val groups = when (result) {
            is JSONObject -> result.keys().asSequence().map { it to result.getJSONArray(it) }
            is JSONArray -> (0 until result.length()).asSequence().map { "" to result.getJSONArray(it) }
            else -> error("Invalid knowledge list")
        }
        var found = false
        for ((key, articles) in groups) {
            if (articles.length() == 0) continue
            found = true
            val category = articles.getJSONObject(0).optString("category", key)
            if (category.isNotBlank()) label(category)
            for (i in 0 until articles.length()) {
                val article = articles.getJSONObject(i)
                val id = article.getLong("id")
                val title = article.getString("title")
                action(title) {
                    page(title, { showGuides(keyword) }) {
                        val detail = api("user/knowledge/fetch", mapOf("id" to id.toString(), "language" to language)) as JSONObject
                        label(articleText(detail.getString("body"))).setTextIsSelectable(true)
                    }
                }
            }
        }
        if (!found) label(getString(if (keyword.isEmpty()) R.string.xb_support_no_docs else R.string.xb_support_no_results))
    }
}

private fun articleText(markdown: String): CharSequence {
    val html = HtmlRenderer.builder().build().render(Parser.builder().build().parse(markdown))
    val text = SpannableStringBuilder(HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT))
    // Guides can contain the member's private subscription URL. Display link targets as
    // selectable text; an article must never send them to a browser or execute site scripts.
    for (span in text.getSpans(0, text.length, URLSpan::class.java).sortedByDescending { text.getSpanStart(it) }) {
        val start = text.getSpanStart(span)
        val end = text.getSpanEnd(span)
        text.removeSpan(span)
        if (Uri.parse(span.url).scheme in setOf("https", "http") && text.subSequence(start, end).toString() != span.url) {
            text.insert(end, " (" + span.url + ")")
        }
    }
    return text
}

internal fun XboardMemberPageActivity.showTickets() {
    page(getString(R.string.xb_member_tickets)) {
        val tickets = api("user/ticket/fetch") as JSONArray
        action(getString(R.string.xb_support_new_ticket)) { newTicket() }
        if (tickets.length() == 0) label(getString(R.string.xb_support_no_tickets))
        for (i in 0 until tickets.length()) {
            val ticket = tickets.getJSONObject(i)
            val id = ticket.getLong("id")
            val subject = ticket.getString("subject")
            action(subject) { showTicket(id, subject) }
            label(ticketSummary(ticket))
        }
    }
}

private fun XboardMemberPageActivity.ticketSummary(ticket: JSONObject): String {
    val status = when {
        ticket.getInt("status") == 1 -> R.string.xb_support_closed
        ticket.optInt("reply_status") == 1 -> R.string.xb_support_replied
        else -> R.string.xb_support_open
    }
    val levels = resources.getStringArray(R.array.xb_support_priorities)
    return listOfNotNull(getString(status), levels.getOrNull(ticket.optInt("level", -1)),
        supportDate(ticket.optLong("updated_at"))).joinToString(" · ")
}

private fun XboardMemberPageActivity.showTicket(id: Long, subject: String) {
    page(subject, { showTickets() }) {
        val params = mapOf("id" to id.toString())
        val ticket = api("user/ticket/fetch", params) as JSONObject
        label(ticketSummary(ticket))
        supportDate(ticket.optLong("created_at"))?.let { label(getString(R.string.xb_support_created, it)) }
        val messages = ticket.getJSONArray("message")
        for (i in 0 until messages.length()) {
            val msg = messages.getJSONObject(i)
            val mine = msg.optBoolean("is_me") || msg.optInt("is_me") == 1
            val sender = getString(if (mine) R.string.xb_support_me else R.string.xb_support_staff)
            label(listOfNotNull(sender, supportDate(msg.optLong("created_at"))).joinToString(" · "))
            label(msg.getString("message")).setTextIsSelectable(true)
        }
        if (ticket.getInt("status") == 0) {
            val reply = supportField(R.string.xb_support_reply_hint, multiline = true)
            action(getString(R.string.xb_support_send_reply)) {
                val message = reply.requiredText() ?: return@action
                mutate {
                    api("user/ticket/reply", params + ("message" to message), post = true)
                    showTicket(id, subject)
                }
            }
            action(getString(R.string.xb_support_close)) {
                AlertDialog.Builder(this).setTitle(R.string.xb_support_close)
                    .setMessage(R.string.xb_support_close_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.xb_support_close) { _, _ ->
                        mutate {
                            api("user/ticket/close", params, post = true)
                            showTicket(id, subject)
                        }
                    }.show()
            }
        }
    }
}

private fun XboardMemberPageActivity.newTicket() {
    page(getString(R.string.xb_support_new_ticket), { showTickets() }) {
        val title = supportField(R.string.xb_support_subject, multiline = false)
        label(getString(R.string.xb_support_priority))
        val priority = Spinner(this).apply {
            adapter = ArrayAdapter.createFromResource(this@newTicket, R.array.xb_support_priorities,
                android.R.layout.simple_spinner_item).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }
        body.addView(priority)
        val details = supportField(R.string.xb_support_issue, multiline = true)
        action(getString(R.string.xb_support_submit)) {
            val subject = title.requiredText() ?: return@action
            val message = details.requiredText() ?: return@action
            val params = mapOf("subject" to subject, "level" to priority.selectedItemPosition.toString(), "message" to message)
            mutate {
                api("user/ticket/save", params, post = true)
                showTickets()
            }
        }
    }
}

private fun XboardMemberPageActivity.supportField(hintId: Int, multiline: Boolean): EditText {
    val field = EditText(this).apply {
        hint = getString(hintId)
        inputType = if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_CLASS_TEXT
        if (multiline) minLines = 3 else setSingleLine()
    }
    body.addView(field)
    return field
}

private fun EditText.requiredText(): String? {
    val value = text.toString().trim()
    if (value.isNotEmpty()) return value
    error = context.getString(R.string.xb_required)
    requestFocus()
    return null
}

private fun supportDate(seconds: Long): String? = seconds.takeIf { it > 0 }?.let {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it * 1000L))
}
