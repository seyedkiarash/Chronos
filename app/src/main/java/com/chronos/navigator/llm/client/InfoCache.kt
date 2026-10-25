package com.chronos.navigator.llm.client

import android.content.Context
import com.chronos.data.ChronosRepository
import com.chronos.data.DatabaseWiring
import com.chronos.navigator.bridge.Bridge
import com.chronos.navigator.forecast.InfoValue
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * The questions whose answers would move the forecast most (stage 6,
 * onboarding), ranked on the phone and kept in a small file: ranking takes
 * a few dozen forecasts, so it runs at the monthly review or when you ask,
 * not every time the tab opens.
 */
object InfoCache {
    private const val FILE = "navigator-questions.json"

    data class Saved(val at: Long, val asOf: String, val items: List<InfoValue.Ranked>)

    fun read(context: Context): List<InfoValue.Ranked> = saved(context)?.items ?: emptyList()

    fun saved(context: Context): Saved? = runCatching {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) null else Gson().fromJson<Saved>(f.readText(), object : TypeToken<Saved>() {}.type)
    }.getOrNull()

    /** Ranks again and saves. Blocks for up to info.budget_s seconds plus one variant. */
    fun compute(context: Context): List<InfoValue.Ranked> {
        val store = DatabaseWiring.navigator(context)
        val repo = DatabaseWiring.repository(context)
        val g = store.load() ?: return emptyList()
        val today = ChronosRepository.logicalToday()
        val inputs = com.chronos.navigator.bridge.NavEngine.inputs(context, g, repo.loadConfig())
        val runs = g.params["info.runs"]?.get("value")?.toInt() ?: 400
        val budget = g.params["info.budget_s"]?.get("value") ?: 40.0
        val ranked = InfoValue.rank(g, { extra -> store.loadWith(extra) }, inputs, today, runs,
            today.toEpochDay() * 31 + 5, budget).take(8)
        File(context.filesDir, FILE).writeText(Gson().toJson(Saved(System.currentTimeMillis(), today.toString(), ranked)))
        return ranked
    }
}
