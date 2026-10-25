package com.chronos.navigator.bridge

import android.content.Context
import com.chronos.data.DatabaseWiring
import com.chronos.model.Config
import com.chronos.model.Enums
import com.chronos.model.Goal
import com.chronos.navigator.estimate.Estimator
import com.chronos.navigator.model.Graph
import com.chronos.navigator.model.Kinds
import com.chronos.navigator.model.Node
import com.chronos.navigator.model.Overrides
import com.chronos.navigator.model.Param
import com.chronos.navigator.model.Requirements
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.exp

/**
 * Chronos and the Navigator, kept in step both ways — never silently. Where a
 * node and the Chronos goal it is worked through disagree (a deadline, a
 * number of units, the hours a unit takes, whether it is finished), the
 * difference is listed with what each side says; you choose which side is
 * right, or keep both as they are. Nothing changes on either side until you do.
 *
 * Read from the newest state each time: a change you make in Setup shows up
 * here at once, and so does what the log has taught the Navigator.
 */
object Sync {

    enum class Kind { DEADLINE, UNITS, HOURS_PER_UNIT, FINISHED_IN_CHRONOS, FINISHED_IN_GRAPH }

    /**
     * One disagreement.
     *
     * @param toChronos  the graph's value may be written into Chronos
     * @param toGraph    Chronos's value may be written into the graph
     */
    data class Diff(
        val kind: Kind, val nodeId: String, val nodeTitle: String, val goalId: String, val goalName: String,
        val graph: String, val chronos: String, val toChronos: Boolean, val toGraph: Boolean, val note: String?,
        val unit: String? = null, val trendPerWeek: Double? = null
    ) {
        val id get() = "${kind.name}.$nodeId.$goalId"
    }

    private const val IGNORE = "sync.ignore."

    /** The Chronos goals with a deadline each node is worked through. */
    private fun closedGoals(g: Graph, cfg: Config, links: Map<String, String>): Map<String, List<Goal>> {
        val out = mutableMapOf<String, List<Goal>>()
        for ((nodeId, acts) in Bridge.nodeActivities(g, cfg, links)) {
            val goals = cfg.activities.filter { it.name != null && it.goalId != null && Requirements.norm(it.name) in acts }
                .mapNotNull { cfg.goal(it.goalId) }.filter { it.mode == Enums.GoalMode.CLOSED }.distinctBy { it.id }
            if (goals.isNotEmpty()) out[nodeId] = goals
        }
        return out
    }

    private fun done(n: Node) = n.status?.lowercase()?.let { it.startsWith("done") || it.startsWith("reached") } == true

    private fun num(v: Double) = if (v == Math.rint(v)) v.toLong().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')

    fun diffs(context: Context): List<Diff> {
        val store = DatabaseWiring.navigator(context)
        val g = store.load() ?: return emptyList()
        val cfg = DatabaseWiring.repository(context).loadConfig()
        val ignored = store.params().filter { it.key.startsWith(IGNORE) }.associateBy { it.key.removePrefix(IGNORE) }
        val out = mutableListOf<Diff>()
        for ((nodeId, goals) in closedGoals(g, cfg, store.links())) {
            val n = g.node(nodeId) ?: continue
            val title = n.title ?: n.id
            for (goal in goals) {
                val name = goal.name?.trim() ?: ""
                val advisor = n.owner == Kinds.Owner.ADVISOR
                if (n.deadline != null && goal.deadline != null && n.deadline != goal.deadline)
                    out += Diff(Kind.DEADLINE, n.id, title, goal.id, name, n.deadline.toString(), goal.deadline.toString(),
                        toChronos = true, toGraph = true,
                        note = if (advisor) "Your advisor set this deadline: move it in the graph only after talking to them." else null)
                if (n.unitsTotal != null && goal.totalUnits != null && abs(n.unitsTotal - goal.totalUnits) > 1e-9)
                    out += Diff(Kind.UNITS, n.id, title, goal.id, name, num(n.unitsTotal), num(goal.totalUnits),
                        toChronos = true, toGraph = true, note = null)
                val act = cfg.activities.firstOrNull { it.goalId == goal.id && it.name != null }?.name?.let { Requirements.norm(it) }
                val bias = act?.let { g.params[Estimator.POST + "bias." + it] }
                val hpu = goal.hoursPerUnit
                if (bias != null && hpu != null && hpu > 0 && (bias.get("n") ?: 0.0) >= 3) {
                    val learned = Math.round(hpu * (bias.get("median") ?: 1.0) * 20) / 20.0
                    if (abs(learned - hpu) / hpu > 0.15) {
                        val week = bias.get("slope")?.let { (exp(it * 7) - 1) * 100 }
                        out += Diff(Kind.HOURS_PER_UNIT, n.id, title, goal.id, name, num(learned), num(hpu),
                            toChronos = true, toGraph = false,
                            note = null, unit = goal.unit, trendPerWeek = week?.div(100))
                    }
                }
                val finishedInChronos = goal.totalUnits != null && goal.remainingUnits() <= 1e-9
                if (finishedInChronos && !done(n))
                    out += Diff(Kind.FINISHED_IN_CHRONOS, n.id, title, goal.id, name, "open", "finished",
                        toChronos = false, toGraph = true, note = null)
                if (done(n) && goal.totalUnits != null && goal.remainingUnits() > 1e-9)
                    out += Diff(Kind.FINISHED_IN_GRAPH, n.id, title, goal.id, name, "finished",
                        num(goal.doneUnits ?: 0.0) + " / " + num(goal.totalUnits), toChronos = true, toGraph = false, note = null)
            }
        }
        // A difference you chose to keep is not shown again — until either side changes.
        return out.filter { d -> ignored[d.id]?.let { it.note != d.graph + "|" + d.chronos } ?: true }
    }

    /**
     * Writes one side's value into the other. Returns what changed, in words.
     * The caller re-runs the forecast (graph changed) or rebuilds the day plan (Chronos changed).
     */
    fun apply(context: Context, d: Diff, toChronos: Boolean): String {
        val store = DatabaseWiring.navigator(context)
        val repo = DatabaseWiring.repository(context)
        val g = store.load() ?: return "No goal graph."
        val n = g.node(d.nodeId) ?: return "The node is gone."
        if (toChronos) {
            val cfg = repo.loadConfig()
            val goal = cfg.goal(d.goalId) ?: return "The Chronos goal is gone."
            when (d.kind) {
                Kind.DEADLINE -> goal.deadline = n.deadline
                Kind.UNITS -> goal.totalUnits = n.unitsTotal
                Kind.HOURS_PER_UNIT -> goal.hoursPerUnit = d.graph.toDouble()
                Kind.FINISHED_IN_GRAPH -> goal.doneUnits = goal.totalUnits
                Kind.FINISHED_IN_CHRONOS -> return "Nothing to write into Chronos."
            }
            repo.saveConfig(cfg)
            NavEngine.afterChronosChange(context, cfg)
            return "Chronos updated: ${d.goalName}."
        }
        val now = System.currentTimeMillis()
        when (d.kind) {
            Kind.DEADLINE -> {
                val v = linkedMapOf("value" to LocalDate.parse(d.chronos).toEpochDay().toDouble())
                v.putAll(wasOf(g, n, "deadline"))
                store.putParam(Param(Overrides.nodeKey(n.id, "deadline"), Kinds.Dist.POINT, Kinds.Source.USER,
                    "Taken from Chronos (${d.goalName}) with your approval").also { it.values.putAll(v); it.updatedAt = now })
            }
            Kind.UNITS -> {
                val v = linkedMapOf("total" to d.chronos.toDouble(), "was_total" to (n.unitsTotal ?: -1.0))
                store.putParam(Param(Overrides.nodeKey(n.id, "units"), Kinds.Dist.POINT, Kinds.Source.USER,
                    "Taken from Chronos (${d.goalName}) with your approval").also { it.values.putAll(v); it.updatedAt = now })
            }
            Kind.FINISHED_IN_CHRONOS -> store.setStatus(n.id, "done")
            else -> return "Nothing to write into the graph."
        }
        return "Graph updated: ${n.id}."
    }

    /** What a decision about a node's value replaces: carried over from an earlier one, so it always names the file's. */
    private fun wasOf(g: Graph, n: Node, what: String): Map<String, Double> {
        val old = g.params[Overrides.nodeKey(n.id, what)]?.values?.filterKeys { it.startsWith("was") }
        return if (!old.isNullOrEmpty()) old else Overrides.was(n, what)
    }

    /** Keeps both sides as they are; asked again only if either changes. */
    fun keepBoth(context: Context, d: Diff) {
        DatabaseWiring.navigator(context).putParam(Param(IGNORE + d.id, Kinds.Dist.POINT, Kinds.Source.USER,
            d.graph + "|" + d.chronos).also { it.updatedAt = System.currentTimeMillis() })
    }

    /**
     * When the Navigator moves a deadline you approved, the Chronos goals that
     * node is worked through move with it — the approval covered both.
     */
    fun deadlineToChronos(cfg: Config, g: Graph, links: Map<String, String>, nodeId: String, date: LocalDate): List<String> {
        val changed = mutableListOf<String>()
        for (goal in closedGoals(g, cfg, links)[nodeId] ?: emptyList()) {
            if (goal.deadline == date) continue
            goal.deadline = date
            changed += goal.name ?: goal.id
        }
        return changed
    }
}
