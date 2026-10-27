package com.chronos.auto

import android.content.Context
import com.chronos.engine.Calibrator
import com.chronos.engine.Slots
import com.chronos.model.Activity
import com.chronos.model.Config
import com.chronos.model.Enums
import com.chronos.model.LogEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The model's own history, and the file the user can take away.
 *
 * Every time the model learns — each night's close-out and each Apply of late
 * entries — one line is appended to a file in the app's private storage: when,
 * why, what moved, and every coefficient as it stood afterwards. The log
 * database holds what the user said; this holds what the model made of it.
 * Nothing was recorded before this existed, so the history starts with it; the
 * current coefficients and every log entry are always complete.
 */
/** A keyed value, null written as JSON null; always the Object overload, never a primitive one. */
private fun JSONObject.v(name: String, value: Any?): JSONObject = put(name, value ?: JSONObject.NULL)

object LogExport {
    private const val HISTORY = "coefficient-history.jsonl"

    private fun file(context: Context) = File(context.filesDir, HISTORY)

    /** Appends one learning step. Quietly does nothing if storage fails: this is a record, not a gate. */
    fun record(context: Context, source: String, days: List<LocalDate>,
               changes: List<Calibrator.Change>, cfg: Config) {
        runCatching {
            val line = JSONObject()
                .v("at", LocalDateTime.now().toString())
                .v("source", source)
                .v("days", JSONArray(days.map { it.toString() }))
                .v("changes", JSONArray(changes.map {
                    JSONObject().v("subject", it.subject()).v("detail", it.detail())
                }))
                .v("coefficientsAfter", coefficients(cfg))
            file(context).appendText(line.toString() + "\n")
        }
    }

    private fun history(context: Context): JSONArray {
        val out = JSONArray()
        val f = file(context)
        if (!f.exists()) return out
        f.readLines().filter { it.isNotBlank() }.forEach { line ->
            runCatching { out.put(JSONObject(line)) }
        }
        return out
    }

    /** Every number the model learns or plans with, as it stands. */
    fun coefficients(cfg: Config): JSONObject {
        val p = cfg.params
        val body = JSONObject()
            .v("sleepStart", p.sleepStart).v("sleepEnd", p.sleepEnd)
            .v("sharpestMinute", p.phi)
            .v("baseline_a0", p.a0).v("timeOfDay_aC", p.aC)
            .v("sleepPressure_aH", p.aH).v("workFatigue_aF", p.aF)
            .v("safeFatigue", p.fSafe).v("idleRecovery", p.idleRecovery)
            .v("slackRatio", p.slackRatio).v("pessimism", p.lcbBeta)
        val activities = JSONArray()
        cfg.activities.forEach { a ->
            activities.put(JSONObject()
                .v("name", a.name).v("kind", a.kind?.name)
                .v("goal", a.goalId?.let { cfg.goal(it)?.name })
                .v("quality_q", a.q).v("qualityUncertainty", a.qSigma)
                .v("fatigueLoad", a.load).v("recovery", a.recovery)
                .v("physicalLoad", a.physLoad).v("physicalRecovery", a.physRecovery).v("splitConfirmed", a.splitConfirmed)
                .v("minutes", JSONArray(listOf(a.minBlock, a.maxBlock))))
        }
        val trips = JSONArray()
        cfg.commitments.filter { it.kind == Enums.CommitmentKind.VARIABLE }.forEach { c ->
            trips.put(JSONObject()
                .v("name", c.name).v("typicalMinutes", c.medianMin).v("variability", c.sigma)
                .v("fatigueLoad", c.load).v("recovery", c.recovery)
                    .v("physicalLoad", c.physLoad).v("physicalRecovery", c.physRecovery))
        }
        val goals = JSONArray()
        cfg.goals.forEach { g ->
            goals.put(JSONObject()
                .v("name", g.name).v("mode", g.mode?.name)
                .v("done", g.doneUnits).v("total", g.totalUnits).v("unit", g.unit)
                .v("deadline", g.deadline?.toString()).v("weight", g.weight))
        }
        return JSONObject().v("bodyClock", body).v("activities", activities)
            .v("trips", trips).v("goals", goals)
    }

    private fun entry(l: LogEntry, cfg: Config): JSONObject {
        val o = JSONObject()
            .v("id", l.id).v("kind", l.kind?.name).v("date", l.date?.toString())
            .v("at", l.at).v("savedAt", l.ts)
        l.actId?.let { o.v("activity", cfg.activity(it)?.name ?: it) }
        l.comId?.let { id -> o.v("trip", cfg.commitments.firstOrNull { it.id == id }?.name ?: id) }
        o.v("plannedMinutes", l.planned).v("actualMinutes", l.actual)
            .v("targetReachedPercent", l.attain).v("unitsFinished", l.units)
            .v("focus", l.focus).v("drained", l.fatigueAfter).v("mentallyTired", l.mentalAfter)
            .v("physicallyTired", l.physicalAfter).v("restored", l.restored)
            .v("energy", l.energy)
            .v("wokeAt", l.endAt).v("minutesToFallAsleep", l.latencyMin).v("timesWoken", l.wakeups)
            .v("tags", JSONArray(l.tags.orEmpty())).v("note", l.note)
        return o
    }

    private fun windows(list: List<Activity.Window>) =
        JSONArray(list.map { w -> JSONObject().v("from", w.from).v("to", w.to).also { o -> w.days?.let { o.v("days", days(it)) } } })

    private fun days(set: Collection<Int>) = JSONArray(set.sorted().map { Slots.DOW[it] })

    /** Two decimals: a summary is read by a person, and 3.0599999999999996 is not 3.06 to them. */
    private fun r2(x: Double) = Math.round(x * 100) / 100.0

    private fun avg(values: List<Double>): Double? = if (values.isEmpty()) null else r2(values.average())

    /** Everything a goal is and has been: its settings, its progress, its sessions, its timeline. */
    private fun goalSection(cfg: Config, logs: List<LogEntry>, hist: JSONArray,
                            comparisons: Map<String, Double>): JSONArray {
        val out = JSONArray()
        cfg.goals.forEach { g ->
            val acts = cfg.activitiesFor(g.id)
            val sessions = logs.filter { l -> l.kind == Enums.LogKind.SESSION && acts.any { it.id == l.actId } }
            val done = sessions.filter { it.at != null }
            val settings = JSONObject()
                .v("mode", g.mode?.name).v("hard", g.hard)
                .v("totalUnits", g.totalUnits).v("doneUnits", g.doneUnits).v("unit", g.unit)
                .v("hoursPerUnit", g.hoursPerUnit).v("deadline", g.deadline?.toString()).v("deadlineAt", g.deadlineTime)
                .v("weeklyFloorHours", g.weeklyFloorH).v("maxHoursPerDay", g.maxPerDayH)
                .v("weight", g.weight)
                .v("activities", JSONArray(acts.map { it.name }))
            val judgements = JSONArray()
            comparisons.forEach { (key, ratio) ->
                val ids = key.split("|")
                if (ids.size == 2 && g.id in ids) {
                    val other = cfg.goal(if (ids[0] == g.id) ids[1] else ids[0])?.name ?: "?"
                    // Stored as "a|b" = how much more a matters than b; said from this goal's side.
                    judgements.put(JSONObject().v("against", other)
                        .v("timesAsImportant", if (ids[0] == g.id) ratio else 1.0 / ratio))
                }
            }
            val summary = JSONObject()
                .v("sessionsLogged", done.size).v("sessionsSkipped", sessions.size - done.size)
                .v("minutesLogged", done.sumOf { it.actual ?: 0 })
                .v("unitsLogged", r2(done.sumOf { it.units ?: 0.0 }))
                .v("averageTargetReachedPercent", avg(done.mapNotNull { it.attain }))
                .v("averageFocus", avg(done.mapNotNull { it.focus?.toDouble() }))
                .v("firstLogged", done.mapNotNull { it.date }.minOrNull()?.toString())
                .v("lastLogged", done.mapNotNull { it.date }.maxOrNull()?.toString())
            out.put(JSONObject().v("name", g.name).v("settings", settings)
                .v("priorityJudgements", judgements).v("fromTheLog", summary)
                .v("timeline", timeline(hist, "goals", g.name)))
        }
        return out
    }

    /** Everything an activity is and has been. */
    private fun activitySection(cfg: Config, logs: List<LogEntry>, hist: JSONArray): JSONArray {
        val out = JSONArray()
        cfg.activities.forEach { a ->
            val mine = logs.filter { it.kind == Enums.LogKind.SESSION && it.actId == a.id }
            val done = mine.filter { it.at != null }
            val sessionsPerDay: Any? =
                if (a.blocksMode == Enums.BlocksMode.AUTO && a.kind == Enums.ActivityKind.GOAL) "chosen by the model"
                else a.maxBlocks
            val cutRule = when (a.sacrificeRank()) {
                Activity.SACRIFICE_FIRST -> "cut first"
                Activity.SACRIFICE_LAST -> "cut as a last resort"
                else -> "never cut"
            }
            val settings = JSONObject()
                .v("kind", a.kind?.name).v("goal", a.goalId?.let { cfg.goal(it)?.name })
                .v("nap", a.nap).v("onlyOnRestDays", a.restOnly).v("startsOn", a.startDate?.toString()).v("startsAt", a.startTime).v("lastDay", a.endDate?.toString()).v("days", days(a.days))
                .v("shortestMinutes", a.minBlock).v("longestMinutes", a.maxBlock)
                .v("sessionsPerDay", sessionsPerDay)
                .v("windows", windows(a.windows))
                .v("pins", JSONArray(a.pins.map { JSONObject().v("day", Slots.DOW[it.day]).v("at", it.start) }))
                .v("changeoverMinutes", a.changeoverMin)
                .v("outOfTheHouse", a.away).v("needsHighFocus", a.needsHighEnergy)
                .v("whenDeadlineAtRisk", cutRule)
                .v("keepMinutesWhenCut", a.keepMin)
            val learned = JSONObject()
                .v("quality_q", a.q).v("qualityUncertainty", a.qSigma)
                .v("fatigueLoad", a.load).v("recovery", a.recovery)
                .v("physicalLoad", a.physLoad).v("physicalRecovery", a.physRecovery).v("splitConfirmed", a.splitConfirmed)
                .v("warmUpSharpness_k", a.k).v("warmUpMidpointMinutes_u0", a.u0)
                .v("attentionDecayMinutes_theta", a.theta)
            val summary = JSONObject()
                .v("sessionsLogged", done.size).v("sessionsSkipped", mine.size - done.size)
                .v("minutesLogged", done.sumOf { it.actual ?: 0 })
                .v("averageFocus", avg(done.mapNotNull { it.focus?.toDouble() }))
                .v("averageDrained", avg(done.mapNotNull { it.fatigueAfter?.toDouble() }))
                .v("averageMentallyTired", avg(done.mapNotNull { it.mentalAfter?.toDouble() }))
                .v("averagePhysicallyTired", avg(done.mapNotNull { it.physicalAfter?.toDouble() }))
                .v("averageRestored", avg(done.mapNotNull { it.restored?.toDouble() }))
                .v("firstLogged", done.mapNotNull { it.date }.minOrNull()?.toString())
                .v("lastLogged", done.mapNotNull { it.date }.maxOrNull()?.toString())
            out.put(JSONObject().v("name", a.name).v("settings", settings).v("learnedNow", learned)
                .v("fromTheLog", summary).v("timeline", timeline(hist, "activities", a.name)))
        }
        return out
    }

    /** Fixed classes and trips, as set up and as learned. */
    private fun commitmentSection(cfg: Config): JSONArray {
        val out = JSONArray()
        cfg.commitments.forEach { c ->
            val o = JSONObject().v("name", c.name).v("kind", c.kind?.name).v("outOfTheHouse", c.away)
            if (c.kind == Enums.CommitmentKind.FIXED) {
                o.v("sessions", JSONArray(c.sessions.map { s ->
                    JSONObject().v("day", Slots.DOW[s.day]).v("from", s.start).v("to", s.end)
                        .v("fatigueLoad", s.load).v("recovery", s.recovery)
                        .v("physicalLoad", s.physLoad).v("physicalRecovery", s.physRecovery)
                }))
            } else {
                o.v("typicalMinutes", c.medianMin).v("variability", c.sigma).v("planAtQuantile", c.quantile)
                    .v("fatigueLoad", c.load).v("recovery", c.recovery)
                    .v("physicalLoad", c.physLoad).v("physicalRecovery", c.physRecovery)
                    .v("outbound", c.outbound?.let { l -> JSONObject().v("mode", l.mode?.name).v("from", l.from).v("to", l.to) })
                    .v("inbound", c.inbound?.let { l -> JSONObject().v("mode", l.mode?.name).v("from", l.from).v("to", l.to) })
            }
            out.put(o)
        }
        return out
    }

    /** Every scheduler setting, not only the ones the model learns. */
    private var restDays: Collection<LocalDate> = emptyList()

    private fun settings(cfg: Config): JSONObject {
        val p = cfg.params
        return JSONObject()
            .v("sleepStart", p.sleepStart).v("sleepEnd", p.sleepEnd).v("sharpestMinute", p.phi)
            .v("baseline_a0", p.a0).v("timeOfDay_aC", p.aC).v("sleepPressure_aH", p.aH)
            .v("workFatigue_aF", p.aF).v("sleepPressureRiseHours", p.tauR)
            .v("sleepPressureDecayHours", p.tauD).v("maxFatigue", p.fMax)
            .v("safeFatigue", p.fSafe).v("idleRecovery", p.idleRecovery).v("slackRatio", p.slackRatio)
            .v("pessimism", p.lcbBeta).v("costOfStartingABlock", p.switchPenalty)
            .v("costOfOverworking", p.fatiguePenalty).v("horizonDays", p.horizonDays)
            .v("restDays", JSONArray(restDays.map { it.toString() }))
    }

    /** One goal's or activity's entries in the coefficient history, oldest first. */
    private fun timeline(hist: JSONArray, section: String, name: String?): JSONArray {
        val out = JSONArray()
        for (i in 0 until hist.length()) {
            val step = hist.optJSONObject(i) ?: continue
            val after = step.optJSONObject("coefficientsAfter")?.optJSONArray(section) ?: continue
            var state: JSONObject? = null
            for (j in 0 until after.length())
                if (after.optJSONObject(j)?.optString("name") == name) state = after.optJSONObject(j)
            if (state == null) continue
            val changes = JSONArray()
            step.optJSONArray("changes")?.let { cs ->
                for (j in 0 until cs.length())
                    if (cs.optJSONObject(j)?.optString("subject")?.trim() == name?.trim())
                        changes.put(cs.optJSONObject(j).optString("detail"))
            }
            out.put(JSONObject().v("at", step.optString("at")).v("source", step.optString("source"))
                .v("changes", changes).v("after", state))
        }
        return out
    }

    /**
     * The whole file: what was said, what the model is now, how it got there,
     * and every goal and activity in full — settings, learned values, what the
     * log says about it, and its own timeline.
     */
    fun build(context: Context, cfg: Config, logs: List<LogEntry>,
              comparisons: Map<String, Double> = emptyMap(),
              rest: Collection<LocalDate> = emptyList()): JSONObject {
        restDays = rest
        val hist = history(context)
        return JSONObject()
            .v("app", "Chronos")
            .v("format", 2)
            .v("exportedAt", LocalDateTime.now().toString())
            .v("settings", settings(cfg))
            .v("goals", goalSection(cfg, logs, hist, comparisons))
            .v("activities", activitySection(cfg, logs, hist))
            .v("commitments", commitmentSection(cfg))
            .v("coefficientsNow", coefficients(cfg))
            .v("coefficientHistory", hist)
            .v("logs", JSONArray(logs.sortedWith(compareBy({ it.date }, { it.at ?: "" }, { it.ts }))
                .map { entry(it, cfg) }))
    }
}
