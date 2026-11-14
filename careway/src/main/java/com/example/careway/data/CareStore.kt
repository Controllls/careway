package com.example.careway.data

import android.content.Context
import com.example.careway.core.*
import org.json.JSONArray
import org.json.JSONObject

data class ReactionRecord(val atMs: Long, val durationMs: Long?, val delayed: Boolean, val demo: Boolean)

class CareStore(context: Context) {
    private val prefs = context.getSharedPreferences("careway", Context.MODE_PRIVATE)
    init {
        if (!prefs.getBoolean("v2_events_migrated", false)) {
            val newer = runCatching { JSONArray(context.getSharedPreferences("careway_state_v2", Context.MODE_PRIVATE).getString("events", "[]")) }.getOrDefault(JSONArray())
            val combined = JSONArray(prefs.getString("events", "[]"))
            val seen = (0 until combined.length()).map { combined.getJSONObject(it).toString() }.toMutableSet()
            for (i in 0 until newer.length()) if (seen.add(newer.getJSONObject(i).toString())) combined.put(newer.getJSONObject(i))
            prefs.edit().putString("events", combined.toString()).putBoolean("v2_events_migrated", true).apply()
        }
    }
    private fun recent(at: Long) = at >= System.currentTimeMillis() - 31L * 86_400_000
    fun liveInputPreferred() = prefs.getBoolean("live_input", false)
    fun saveInputMode(demo: Boolean) { prefs.edit().putBoolean("live_input", !demo).apply() }
    fun settings() = CareSettings(
        night = prefs.getBoolean("night", true), rain = prefs.getBoolean("rain", true),
        tunnel = prefs.getBoolean("tunnel", true), voice = prefs.getBoolean("voice", true),
        glassLevel = null, // Reconfirm per connection; do not reuse a tint calibration on another vehicle.
    )
    fun saveSettings(s: CareSettings) {
        prefs.edit().putBoolean("night", s.night).putBoolean("rain", s.rain)
            .putBoolean("tunnel", s.tunnel).putBoolean("voice", s.voice).apply {
                remove("glass")
            }.apply()
    }
    fun reactions(): List<ReactionRecord> = runCatching {
        val a = JSONArray(prefs.getString("reactions", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let {
            ReactionRecord(it.getLong("at"), if (it.isNull("ms")) null else it.getLong("ms"), it.getBoolean("delayed"), it.getBoolean("demo"))
        } }
    }.getOrDefault(emptyList())
    @Synchronized fun addReaction(r: ReactionRecord) {
        val a = JSONArray()
        (reactions() + r).filter { recent(it.atMs) }.takeLast(2_000).forEach { a.put(JSONObject().put("at", it.atMs)
            .put("ms", it.durationMs ?: JSONObject.NULL).put("delayed", it.delayed).put("demo", it.demo)) }
        prefs.edit().putString("reactions", a.toString()).apply()
    }
    fun events(): List<CareEvent> = runCatching {
        val a = JSONArray(prefs.getString("events", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let {
            CareEvent(it.getLong("at"), it.getString("title"), it.getString("detail"), it.getBoolean("demo"))
        } }
    }.getOrDefault(emptyList())
    @Synchronized fun addEvent(e: CareEvent) {
        val a = JSONArray()
        (events() + e).filter { recent(it.atMs) }.takeLast(20_000).forEach { a.put(JSONObject().put("at", it.atMs).put("title", it.title).put("detail", it.detail).put("demo", it.demo)) }
        prefs.edit().putString("events", a.toString()).apply()
    }
    @Synchronized fun clearHistory(demo: Boolean) {
        val remainingEvents = JSONArray(prefs.getString("events", "[]"))
        val remainingReactions = JSONArray(prefs.getString("reactions", "[]"))
        fun keep(a: JSONArray) = JSONArray().apply { for (i in 0 until a.length()) {
            val entry = a.getJSONObject(i); if (entry.getBoolean("demo") != demo) put(entry)
        } }
        prefs.edit().putString("events", keep(remainingEvents).toString())
            .putString("reactions", keep(remainingReactions).toString()).apply()
    }
    private fun selected(key: String, demo: Boolean): JSONArray {
        val source = JSONArray(prefs.getString(key, "[]"))
        return JSONArray().apply { for (i in 0 until source.length()) {
            val item = source.getJSONObject(i)
            if (item.getBoolean("demo") == demo && recent(item.getLong("at"))) put(item)
        } }
    }
    fun exportCsv(demo: Boolean) = CareReports.csv(CareReports.summarize(reactions(), events(), demo), demo)
    fun export(demo: Boolean): String = JSONObject().put("app", "꼼짝마차").put("schema_version", 3).put("demo", demo)
        .put("exported_at", System.currentTimeMillis()).put("reactions", selected("reactions", demo))
        .put("events", selected("events", demo))
        .put("last_30_days", JSONArray().apply {
            listOf(demo).forEach { mode ->
                val report = CareReports.summarize(reactions(), events(), mode)
                put(JSONObject().put("demo", mode).put("attempts", report.attempts)
                    .put("average_ms", report.averageMs ?: JSONObject.NULL).put("headlight_warnings", report.headlightWarnings)
                    .put("days", JSONArray().apply { report.days.forEach {
                        put(JSONObject().put("date", it.day.toString()).put("attempts", it.attempts)
                            .put("average_ms", it.averageMs ?: JSONObject.NULL).put("headlight_warnings", it.headlightWarnings))
                    } }))
            }
        })
        .put("notice", "운전 적합성·질병 진단 자료가 아닙니다. demo=true는 시뮬레이션입니다. 최근 30일 통계이며 최대 반응 2,000건·이벤트 20,000건 범위입니다. 페달 신호 전달 지연이 포함됩니다.")
        .toString(2)

    fun ownership(demo: Boolean): OwnershipStore = object : OwnershipStore {
        private val key = if (demo) "demo_ownership" else "live_ownership"
        override fun load(): Map<Control, Ownership> {
            if (demo) return emptyMap()
            return runCatching {
                val json = JSONObject(prefs.getString(key, "{}")!!)
                Control.entries.mapNotNull { c -> json.optJSONObject(c.name)?.let {
                    c to Ownership(it.getInt("original"), it.getInt("applied"), it.optBoolean("confirmed", false))
                } }.toMap()
            }.getOrDefault(emptyMap())
        }
        override fun save(value: Map<Control, Ownership>) {
            if (demo) return
            val json = JSONObject()
            value.forEach { (c, o) -> json.put(c.name, JSONObject().put("original", o.original).put("applied", o.applied).put("confirmed", o.confirmed)) }
            check(prefs.edit().putString(key, json.toString()).commit()) { "복원 기록 저장 실패" }
        }
    }
}
