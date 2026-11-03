package com.github.kr328.clash.xboard

import org.json.JSONException
import org.json.JSONObject

data class XboardTrafficRecord(
    val recordedAt: Long,
    val rate: Double?,
    val billedUpload: Long,
    val billedDownload: Long,
) {
    // Xboard stores byte counters after applying the rate. A free-rate record
    // has zero billed bytes regardless of the actual traffic on the wire.
    val billedBytes: Double get() = billedUpload.toDouble() + billedDownload
    val actualUpload: Double? get() = rate?.takeIf { it > 0 }?.let { billedUpload / it }
    val actualDownload: Double? get() = rate?.takeIf { it > 0 }?.let { billedDownload / it }

    internal companion object {
        fun fromJson(row: JSONObject): XboardTrafficRecord {
            fun nonnegativeLong(key: String): Long = row.get(key).toString().toLongOrNull()
                ?.takeIf { it >= 0 } ?: throw JSONException("Invalid traffic record")
            val seconds = nonnegativeLong("record_at")
            if (seconds > Long.MAX_VALUE / 1000) throw JSONException("Invalid record date")
            return XboardTrafficRecord(
                recordedAt = seconds * 1000,
                rate = row.opt("server_rate")?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 },
                billedUpload = nonnegativeLong("u"),
                billedDownload = nonnegativeLong("d"),
            )
        }
    }
}
