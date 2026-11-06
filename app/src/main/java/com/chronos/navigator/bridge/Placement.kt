package com.chronos.navigator.bridge

import android.content.Context
import com.chronos.data.ChronosRepository
import com.chronos.data.DatabaseWiring
import com.chronos.model.Config
import com.chronos.model.Enums
import com.chronos.navigator.model.Graph
import com.chronos.navigator.model.Kinds
import com.chronos.navigator.model.Param
import com.chronos.navigator.model.Requirements
import java.time.LocalDate

/**
 * Nothing in Chronos without a place on the graph (your rule of 6 Nov 2026). A goal counts as placed
 * when its activity is a node's, its group is one a node names ("groups" in the node's chronos part),
 * or you put it under a node. A new deadline in a group already placed needs nothing more. Anything
 * else is not planned yet: it waits, paused, until you place it or the Navigator takes it in through
 * a proposal you approve. Only goal work; upkeep and leisure have no place on the graph.
 */
object Placement {
    /** The pause of something waiting for its place, told apart from one the Navigator set for a time. */
    val HOLD: LocalDate = LocalDate.of(2099, 12, 31)
    private const val PLACED = "placed."

    /** @param key what a placement is remembered under: the goal's group, or the activity's name without one */
    data class Waiting(val activityId: String, val name: String, val group: String?, val key: String)

    private fun prefs(context: Context) = context.getSharedPreferences("chronos.llm", Context.MODE_PRIVATE)
    fun on(context: Context) = prefs(context).getBoolean("placement", !com.chronos.BuildConfig.PUBLIC_EDITION)
    fun setOn(context: Context, on: Boolean) = prefs(context).edit().putBoolean("placement", on).apply()

    /** The Chronos goal groups the graph's nodes name. */
    fun namedGroups(g: Graph): Set<String> = g.nodes.values.flatMap { n ->
        n.raw?.getAsJsonObject("chronos")?.takeIf { it.has("groups") && it.get("groups").isJsonArray }
            ?.getAsJsonArray("groups")?.filter { it.isJsonPrimitive }?.map { Requirements.norm(it.asString) } ?: emptyList()
    }.toSet()

    private fun waitingIn(context: Context, g: Graph, cfg: Config, today: LocalDate): List<Waiting> {
        val store = DatabaseWiring.navigator(context)
        val onNodes = Bridge.nodeActivities(g, cfg, store.links()).values.flatten().toSet()
        val groups = namedGroups(g)
        val chosen = store.params().filter { it.key.startsWith(PLACED) }.map { it.key.removePrefix(PLACED) }.toSet()
        return cfg.activities.mapNotNull { a ->
            if (a.kind != Enums.ActivityKind.GOAL || a.archived || a.goalId == null || a.name.isNullOrBlank() || cfg.over(a, today))
                return@mapNotNull null
            val group = cfg.goal(a.goalId)?.groupName?.trim()?.takeIf { it.isNotEmpty() }
            val key = Requirements.norm(group ?: a.name)
            val placed = Requirements.norm(a.name) in onNodes || key in groups || key in chosen
            if (placed) null else Waiting(a.id, a.name.trim(), group, key)
        }
    }

    fun waiting(context: Context): List<Waiting> {
        if (!on(context)) return emptyList()
        val g = DatabaseWiring.navigator(context).load() ?: return emptyList()
        return waitingIn(context, g, DatabaseWiring.repository(context).loadConfig(), ChronosRepository.logicalToday())
    }

    /**
     * Pauses what has no place and lifts the pause of what has one now. Changes [cfg] and says whether
     * it did; the caller saves it. With the rule off or no graph, everything waiting is let go.
     */
    fun reconcile(context: Context, cfg: Config): Boolean {
        val today = ChronosRepository.logicalToday()
        val g = if (on(context)) DatabaseWiring.navigator(context).load() else null
        val waiting = g?.let { waitingIn(context, it, cfg, today).map { w -> w.activityId }.toSet() } ?: emptySet()
        var changed = false
        for (a in cfg.activities) {
            val held = a.pauseUntil == HOLD
            if (a.id in waiting && !held) { a.pauseFrom = today; a.pauseUntil = HOLD; changed = true }
            else if (a.id !in waiting && held) { a.pauseFrom = null; a.pauseUntil = null; changed = true }
        }
        return changed
    }

    /** You put it under a node: from now on its group, or the activity, counts as placed. */
    fun place(context: Context, w: Waiting, nodeId: String) {
        val cfg = DatabaseWiring.repository(context).loadConfig()
        DatabaseWiring.navigator(context).putParam(Param(PLACED + w.key, Kinds.Dist.POINT, Kinds.Source.USER,
            "Put under $nodeId by you").also { it.updatedAt = System.currentTimeMillis() })
        if (reconcile(context, cfg)) {
            DatabaseWiring.repository(context).saveConfig(cfg)
            NavEngine.afterChronosChange(context, cfg)
        }
    }

    /** What the Navigator is told when you ask it to decide. */
    fun question(w: Waiting, persian: Boolean) = if (persian)
        "«${w.name}» را به Chronos اضافه کرده‌ام" + (w.group?.let { " (گروه $it)" } ?: "") +
            ". کجای گراف قرار می‌گیرد و به کدام مسیر کمک می‌کند؟ اگر به هیچ مسیری کمک نمی‌کند هم بگو."
    else "I added \"${w.name}\" to Chronos" + (w.group?.let { " (group $it)" } ?: "") +
        ". Where does it belong on my goal graph, and which path does it serve? If it serves none, say so."
}
