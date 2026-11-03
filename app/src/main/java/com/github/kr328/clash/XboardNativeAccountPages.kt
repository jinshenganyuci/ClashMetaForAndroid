package com.github.kr328.clash

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.text.InputType
import android.util.TypedValue
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.switchmaterial.SwitchMaterial
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.text.DecimalFormatSymbols
import java.util.Date

internal fun XboardMemberPageActivity.showProfile(): Unit = page(getString(R.string.xb_member_settings)) {
    val info = api("user/info") as JSONObject
    val config = api("user/comm/config") as JSONObject
    val currency = config.optString("currency_symbol", "¥")
    label(info.optString("email"))
    label(getString(R.string.xb_prof_wallet, money(info.opt("balance"), currency))).apply {
        id = R.id.xb_profile_wallet
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
    }
    action(getString(R.string.xb_prof_password)) { passwordDialog() }.id = R.id.xb_profile_password

    val expiry = reminder(R.string.xb_prof_expiry, R.id.xb_profile_expire, info.flag("remind_expire"))
    val traffic = reminder(R.string.xb_prof_traffic, R.id.xb_profile_traffic, info.flag("remind_traffic"))
    action(getString(R.string.xb_prof_save_reminders)) {
        val params = mapOf("remind_expire" to if (expiry.isChecked) "1" else "0",
            "remind_traffic" to if (traffic.isChecked) "1" else "0")
        mutate {
            check(api("user/update", params, post = true) == true)
            note(R.string.xb_prof_saved)
        }
    }.id = R.id.xb_profile_reminders_save

    if (config.flag("is_telegram")) {
        if (info.optLong("telegram_id") != 0L) {
            label(getString(R.string.xb_prof_telegram_bound))
        } else {
            action(getString(R.string.xb_prof_telegram_bind)) { showTelegramBinding() }.id = R.id.xb_profile_telegram
        }
    }
    telegramUri(config.optString("telegram_discuss_link"))?.let { uri ->
        action(getString(R.string.xb_prof_telegram_group)) { openTelegram(uri) }
    }

    action(getString(R.string.xb_prof_reset)) {
        AlertDialog.Builder(this)
            .setTitle(R.string.xb_prof_reset).setMessage(R.string.xb_prof_reset_confirmation)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.xb_prof_reset_action) { _, _ ->
                mutate {
                    check((api("user/resetSecurity") as? String)?.isNotBlank() == true)
                    note(R.string.xb_prof_reset_done)
                    finish() // Returning to the hub rechecks membership and updates the bound profile.
                }
            }.show()
    }.id = R.id.xb_profile_reset
}

private fun XboardMemberPageActivity.reminder(text: Int, viewId: Int, checked: Boolean): SwitchMaterial =
    SwitchMaterial(this).also {
        it.id = viewId
        it.text = getString(text)
        it.isChecked = checked
        it.minimumHeight = dp(48)
        body.addView(it)
    }

private fun XboardMemberPageActivity.passwordDialog() {
    val form = form()
    val type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    val old = field(form, R.string.xb_prof_old, type, R.id.xb_profile_old_password)
    val new = field(form, R.string.xb_prof_new, type, R.id.xb_profile_new_password)
    val confirm = field(form, R.string.xb_prof_confirm, type, R.id.xb_profile_confirm_password)
    formDialog(R.string.xb_prof_password, form, R.string.xb_prof_save) {
        val value = new.text.toString()
        when {
            old.text.isEmpty() -> invalid(old, R.string.xb_required)
            value.codePointCount(0, value.length) < 8 -> invalid(new, R.string.xb_min_password)
            confirm.text.toString() != value -> invalid(confirm, R.string.xb_password_mismatch)
            else -> {
                val params = mapOf("old_password" to old.text.toString(), "new_password" to value)
                mutate {
                    check(api("user/changePassword", params, post = true) == true)
                    note(R.string.xb_prof_password_done)
                }
                true
            }
        }
    }
}

private fun XboardMemberPageActivity.showTelegramBinding(): Unit = page(getString(R.string.xb_prof_telegram_bind), { showProfile() }) {
    val bot = (api("user/telegram/getBotInfo") as JSONObject).getString("username")
    check(bot.matches(Regex("[A-Za-z0-9_]{4,32}")))
    label(getString(R.string.xb_prof_telegram_instructions, bot))
    action(getString(R.string.xb_prof_copy_command)) {
        mutate {
            val url = (api("user/getSubscribe") as JSONObject).getString("subscribe_url")
            val uri = Uri.parse(url)
            check(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null)
            copy(getString(R.string.xb_prof_copy_command), "/bind $url", sensitive = true)
        }
    }
    action(getString(R.string.xb_prof_open_bot, bot)) { openTelegram(Uri.parse("https://t.me/$bot")) }
}

private fun telegramUri(value: String): Uri? = Uri.parse(value).takeIf {
    it.scheme == "https" && (it.host.equals("t.me", true) || it.host.equals("telegram.me", true)) &&
        it.port in setOf(-1, 443) && it.userInfo == null && !it.path.isNullOrBlank()
}

private fun XboardMemberPageActivity.openTelegram(uri: Uri) {
    try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    catch (_: ActivityNotFoundException) { note(R.string.xb_prof_no_link_handler) }
}

internal fun XboardMemberPageActivity.showInvite(): Unit = page(getString(R.string.xb_member_invite)) {
    val invites = api("user/invite/fetch") as JSONObject
    val config = api("user/comm/config") as JSONObject
    val currency = config.optString("currency_symbol", "¥")
    val stat = invites.getJSONArray("stat")
    val available = stat.getLong(4)
    label(getString(R.string.xb_inv_available, money(available, currency))).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
    }
    label(getString(R.string.xb_inv_summary, stat.getLong(0), stat.get(3).toString(),
        money(stat.get(1), currency), money(stat.get(2), currency)))
    if (config.flag("commission_distribution_enable")) {
        val levels = (1..3).map { config.optString("commission_distribution_l$it", "0") }
        label(getString(R.string.xb_inv_levels, levels[0], levels[1], levels[2]))
    }
    action(getString(R.string.xb_inv_transfer)) { transferDialog(available, currency) }.apply {
        id = R.id.xb_invite_transfer
        isEnabled = available > 0
    }
    val methods = config.optJSONArray("withdraw_methods")?.strings().orEmpty()
    if (!config.flag("withdraw_close") && methods.isNotEmpty()) {
        action(getString(R.string.xb_inv_withdraw)) { withdrawDialog(methods) }.apply {
            id = R.id.xb_invite_withdraw
            isEnabled = available > 0
        }
    }

    label(getString(R.string.xb_inv_codes))
    val codes = invites.getJSONArray("codes")
    if (codes.length() == 0) label(getString(R.string.xb_inv_no_codes))
    for (i in 0 until codes.length()) {
        val item = codes.getJSONObject(i)
        val code = item.getString("code")
        label(getString(R.string.xb_inv_code, code, date(item.optLong("created_at"))))
        action(getString(R.string.xb_inv_copy)) {
            copy(getString(R.string.xb_inv_copy), "https://ikun.iil.im/#/register?code=" + Uri.encode(code))
        }.apply { if (i == 0) id = R.id.xb_invite_copy }
    }
    action(getString(R.string.xb_inv_generate)) {
        mutate {
            check(api("user/invite/save") == true)
            note(R.string.xb_inv_generated)
            showInvite()
        }
    }.id = R.id.xb_invite_generate
    action(getString(R.string.xb_inv_history)) { showCommissionPage(1, currency) }.id = R.id.xb_invite_history
}

private fun XboardMemberPageActivity.showCommissionPage(current: Int, currency: String): Unit =
    page(getString(R.string.xb_inv_history), { showInvite() }) {
        val pageSize = 10
        val result = apiEnvelope("user/invite/details", mapOf("current" to current.toString(), "page_size" to "$pageSize"))
        val rows = result.getJSONArray("data")
        val total = result.getInt("total")
        label(getString(R.string.xb_inv_page, current, ((total.toLong() + pageSize - 1) / pageSize).coerceAtLeast(1L)))
        if (rows.length() == 0) label(getString(R.string.xb_inv_no_records))
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            label(getString(R.string.xb_inv_record, date(row.optLong("created_at")),
                money(row.opt("get_amount"), currency), row.optString("trade_no"), money(row.opt("order_amount"), currency)))
        }
        if (current > 1) action(getString(R.string.xb_inv_previous)) { showCommissionPage(current - 1, currency) }.id = R.id.xb_invite_previous
        if (current.toLong() * pageSize < total) {
            action(getString(R.string.xb_inv_next)) { showCommissionPage(current + 1, currency) }.id = R.id.xb_invite_next
        }
    }

private fun XboardMemberPageActivity.transferDialog(available: Long, currency: String) {
    val form = form()
    val amount = field(form, R.string.xb_inv_amount, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
    formDialog(R.string.xb_inv_transfer, form, R.string.xb_prof_continue) {
        val text = amount.text.toString().trim().replace(DecimalFormatSymbols.getInstance().decimalSeparator, '.')
        val cents = try {
            text.takeIf { it.matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?")) }?.toBigDecimal()?.movePointRight(2)?.longValueExact()
        } catch (_: ArithmeticException) { null }
        if (cents == null || cents !in 1..available) {
            invalid(amount, R.string.xb_inv_invalid_amount)
        } else {
            AlertDialog.Builder(this).setTitle(R.string.xb_inv_transfer)
                .setMessage(getString(R.string.xb_inv_transfer_confirmation, money(cents, currency)))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.xb_inv_transfer) { _, _ ->
                    mutate {
                        check(api("user/transfer", mapOf("transfer_amount" to cents.toString()), post = true) == true)
                        note(R.string.xb_inv_transferred)
                        showInvite()
                    }
                }.show()
            true
        }
    }
}

private fun XboardMemberPageActivity.withdrawDialog(methods: List<String>) {
    val form = form()
    val method = Spinner(this).apply {
        adapter = ArrayAdapter(this@withdrawDialog, android.R.layout.simple_spinner_dropdown_item, methods)
        contentDescription = getString(R.string.xb_inv_method)
    }
    form.addView(method)
    val recipient = field(form, R.string.xb_inv_recipient, InputType.TYPE_CLASS_TEXT)
    formDialog(R.string.xb_inv_withdraw, form, R.string.xb_inv_submit) {
        val destination = recipient.text.toString().trim()
        if (destination.isEmpty()) invalid(recipient, R.string.xb_required)
        else {
            val params = mapOf("withdraw_method" to method.selectedItem.toString(), "withdraw_account" to destination)
            mutate {
                check(api("user/ticket/withdraw", params, post = true) == true)
                note(R.string.xb_inv_withdrawn)
            }
            true
        }
    }
}

private fun XboardMemberPageActivity.form() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(24), dp(8), dp(24), 0)
}

private fun XboardMemberPageActivity.field(form: LinearLayout, hintId: Int, type: Int, viewId: Int = android.view.View.NO_ID) =
    EditText(this).apply {
        id = viewId
        setHint(hintId)
        setSingleLine(true)
        inputType = type
        isSaveEnabled = false
        form.addView(this)
    }

private fun XboardMemberPageActivity.formDialog(title: Int, form: LinearLayout, positive: Int, submit: () -> Boolean) {
    val dialog = AlertDialog.Builder(this).setTitle(title).setView(form)
        .setNegativeButton(android.R.string.cancel, null).setPositiveButton(positive, null).create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { if (submit()) dialog.dismiss() }
    }
    dialog.show()
}

private fun XboardMemberPageActivity.invalid(input: EditText, error: Int): Boolean {
    input.error = getString(error)
    input.requestFocus()
    return false
}

private fun XboardMemberPageActivity.copy(title: String, value: String, sensitive: Boolean = false) {
    val clip = ClipData.newPlainText(title, value)
    if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    }
    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    note(R.string.xb_prof_copied)
}

private fun Context.note(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
private fun Context.dp(value: Int) = (resources.displayMetrics.density * value + 0.5f).toInt()
private fun JSONObject.flag(key: String) = optBoolean(key, false) || optInt(key) == 1
private fun JSONArray.strings() = (0 until length()).mapNotNull { (opt(it) as? String)?.takeIf(String::isNotBlank) }
private fun date(seconds: Long): String = if (seconds > 0) DateFormat.getDateTimeInstance().format(Date(seconds * 1000)) else "—"
private fun money(cents: Any?, symbol: String): String =
    cents?.toString()?.toBigDecimalOrNull()?.let { symbol + it.movePointLeft(2).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() } ?: "—"
