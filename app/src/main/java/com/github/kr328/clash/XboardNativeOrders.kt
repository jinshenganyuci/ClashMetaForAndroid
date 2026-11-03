package com.github.kr328.clash

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.text.DateFormat
import java.util.Date

internal fun XboardMemberPageActivity.showOrders() {
    page(getString(R.string.xb_member_orders)) {
        val orders = api("user/order/fetch") as JSONArray
        if (orders.length() == 0) label(getString(R.string.xb_orders_empty))
        for (index in 0 until orders.length()) {
            val order = orders.getJSONObject(index)
            val number = order.getString("trade_no")
            val title = order.optJSONObject("plan")?.optString("name").orEmpty()
            action(listOf(title, number, orderStatus(order), orderAmount(order)).filter { it.isNotBlank() }.joinToString("\n")) {
                orderDetail(number)
            }
        }
    }
}

private fun XboardMemberPageActivity.orderDetail(number: String) {
    page(getString(R.string.xb_order_detail), { showOrders() }) {
        val order = api("user/order/detail", mapOf("trade_no" to number)) as JSONObject
        order.optJSONObject("plan")?.optString("name")?.let { label(it) }
        label(getString(R.string.xb_order_number, order.getString("trade_no"))).setTextIsSelectable(true)
        label(getString(R.string.xb_order_status_field, orderStatus(order)))
        val period = order.optString("period")
        val localized = when (period) {
            "month_price", "monthly" -> 0
            "quarter_price", "quarterly" -> 1
            "half_year_price", "half_yearly" -> 2
            "year_price", "yearly" -> 3
            "two_year_price", "two_yearly" -> 4
            "three_year_price", "three_yearly" -> 5
            "onetime_price", "onetime" -> 6
            "reset_price", "reset_traffic" -> 7
            else -> null
        }
        label(getString(R.string.xb_order_period, localized?.let { resources.getStringArray(R.array.xb_order_periods)[it] } ?: period))
        label(getString(R.string.xb_order_amount, orderAmount(order)))
        order.optLong("created_at").takeIf { it > 0 }?.let {
            label(getString(R.string.xb_order_created, DateFormat.getDateTimeInstance().format(Date(it * 1000))))
        }
    }
}

private fun XboardMemberPageActivity.orderStatus(order: JSONObject) =
    resources.getStringArray(R.array.xb_order_states).getOrNull(order.optInt("status", -1)) ?: getString(R.string.xb_order_unknown)

private fun orderAmount(order: JSONObject): String =
    BigDecimal.valueOf(order.getLong("total_amount"), 2).toPlainString()
