package com.chronos.navigator.bridge

import com.chronos.data.ChronosRepository
import com.chronos.engine.Slots
import com.chronos.model.Activity
import com.chronos.model.Config
import com.chronos.model.Enums
import com.chronos.model.Goal
import com.chronos.model.LogEntry
import com.chronos.navigator.llm.ChangeSet
import com.chronos.navigator.llm.Op
import com.chronos.navigator.model.Graph
import com.chronos.navigator.model.Requirements
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The Navigator's hands on Chronos (your decision of 13 Oct 2026): what it
 * sees of the planner, how an approved change set is written into it, and how
 * a change is tried on a copy first — the week Chronos would plan with it,
 * arranged day by day — so its effect on hours and tiredness is measured, not
 * guessed. Nothing here changes Chronos unless [apply] is given the real
 * configuration after you approved.
 */
object ChronosControl {

    private fun r2(v: Double) = Math.round(v * 100) / 100.0

    // ------------------------------------------------------------------ view

    /** The planner as [ChangeSet.CHRONOS_SHAPE] describes it. */
    fun view(cfg: Config, rest: Set<LocalDate>, today: LocalDate): JsonObject = JsonObject().apply {
        // What is past its deadline or done is left out: nothing about it is to be changed (26 Oct 2026).
        val over = cfg.activities.filter { cfg.over(it, today) }
        val overTargets = over.mapNotNull { it.goalId }.toSet()
        add("goals", JsonArray().apply {
            cfg.goals.filter { it.id !in overTargets }.forEach { g ->
                add(JsonObject().apply {
                    addProperty("name", g.name)
                    g.groupName?.takeIf { it != g.name }?.let { addProperty("goal_it_belongs_to", it) }
                    addProperty("mode", g.mode?.name)
                    g.deadline?.let { addProperty("deadline", it.toString()) }
                    g.totalUnits?.let { addProperty("total_units", it) }
                    g.doneUnits?.let { addProperty("done_units", it) }
                    g.unit?.let { addProperty("unit", it) }
                    g.hoursPerUnit?.let { addProperty("hours_per_unit", it) }
                    g.weeklyFloorH?.let { addProperty("weekly_floor_h", it) }
                    g.maxPerDayH?.let { addProperty("max_per_day_h", it) }
                    if (g.hard) addProperty("hard", true)
                })
            }
        })
        add("activities", JsonArray().apply {
            cfg.activities.filter { it !in over }.forEach { a ->
                add(JsonObject().apply {
                    addProperty("name", a.name)
                    addProperty("kind", if (a.kind == Enums.ActivityKind.GOAL) "goal" else if (a.leisure) "leisure" else "upkeep")
                    if (a.leisure) a.dopamine?.let { addProperty("stimulation_per_hour", r2(it)) }
                    a.goalId?.let { cfg.goal(it) }?.let { addProperty("goal", it.name) }
                    add("days", JsonArray().apply { a.days.forEach { add(it) } })
                    if (a.windows.isNotEmpty()) add("windows", JsonArray().apply {
                        a.windows.forEach { w -> add(JsonObject().apply {
                            addProperty("from", w.from); addProperty("to", w.to)
                            w.days?.let { d -> add("days", JsonArray().apply { d.forEach { add(it) } }) }
                        }) }
                    })
                    if (a.kind == Enums.ActivityKind.MAINTENANCE) a.minBlock?.let { addProperty("minutes", it) }
                    else { a.minBlock?.let { addProperty("min_block", it) }; a.maxBlock?.let { addProperty("max_block", it) } }
                    a.load?.let { addProperty("mental_load", r2(it)) }
                    a.physLoad?.let { addProperty("physical_load", r2(it)) }
                    a.startDate?.let { if (it.isAfter(today)) addProperty("start_date", it.toString()) }
                    if (a.archived) addProperty("archived", true)
                    if (a.pauseUntil != null && !a.pauseUntil.isBefore(today))
                        addProperty("paused", "${a.pauseFrom} to ${a.pauseUntil}")
                    if (a.restOnly) addProperty("rest_days_only", true)
                    if (a.nap) addProperty("nap", true)
                })
            }
        })
        val p = cfg.params
        add("levers", JsonObject().apply {
            p.slackRatio?.let { addProperty("slack_ratio", r2(it)) }
            p.fSafe?.let { addProperty("f_safe", r2(it)) }
            (p.fSafeP ?: p.fSafe)?.let { addProperty("f_safe_p", r2(it)) }
            p.sleepStart?.let { addProperty("bed", it) }
            p.sleepEnd?.let { addProperty("wake", it) }
        })
        add("rest_days", JsonArray().apply { rest.filter { !it.isBefore(today) }.sorted().forEach { add(it.toString()) } })
        add("fixed", JsonArray().apply {
            cfg.commitments.filter { it.kind == Enums.CommitmentKind.FIXED }.forEach { c ->
                add(JsonObject().apply {
                    addProperty("name", c.name)
                    add("sessions", JsonArray().apply { c.sessions.forEach { add("${it.day} ${it.start}-${it.end}") } })
                })
            }
        })
    }

    // ----------------------------------------------------------------- apply

    /** What applying made, for the caller to record: new goals and the goal each was set up like. */
    data class Made(val goal: Goal, val like: Goal?)

    /**
     * Writes the Chronos operations into [cfg] and [rest] (both changed in
     * place: pass copies to try a change). Returns what it did, in words, and
     * the goals it made.
     */
    fun apply(cfg: Config, rest: MutableSet<LocalDate>, ops: List<Op>, today: LocalDate): Pair<List<String>, List<Made>> {
        val said = mutableListOf<String>()
        val made = mutableListOf<Made>()
        fun act(name: String?) = cfg.activities.firstOrNull { Requirements.norm(it.name) == Requirements.norm(name) }
        fun goal(name: String?) = cfg.goals.firstOrNull { Requirements.norm(it.name) == Requirements.norm(name) }
        fun str(o: JsonObject, k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString
        fun num(o: JsonObject, k: String) = o.get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
        fun days(o: JsonObject) = o.getAsJsonArray("days")?.map { it.asInt }?.toSortedSet()
        fun windows(o: JsonObject) = o.getAsJsonArray("windows")?.map {
            Activity.Window(it.asJsonObject.get("from").asString, it.asJsonObject.get("to").asString,
                it.asJsonObject.getAsJsonArray("days")?.map { d -> d.asInt })
        }

        for (op in ops.filter { it.isChronos && it.node() != null }) {
            val x = op.node()
            when (str(x, "type")) {
                "task_goal" -> {
                    val like = act(str(x, "like")) ?: cfg.activities.firstOrNull { it.kind == Enums.ActivityKind.GOAL && !it.archived }
                        ?: continue
                    val likeGoal = like.goalId?.let { cfg.goal(it) }
                    val g = Goal().apply {
                        id = java.util.UUID.randomUUID().toString()
                        name = str(x, "activity") ?: str(x, "goal")
                        // A new task is its own goal, as you would name it; its activity carries the target.
                        groupName = str(x, "goal")
                        mode = Enums.GoalMode.CLOSED
                        hard = likeGoal?.hard ?: true
                        unit = "h"
                        hoursPerUnit = 1.0
                        totalUnits = num(x, "hours") ?: 1.0
                        doneUnits = 0.0
                        deadline = LocalDate.parse(str(x, "deadline"))
                        weight = likeGoal?.weight ?: 0.0
                        // Chronos needs a daily ceiling: the one of the goal it is set up like, but never so low
                        // that the hours cannot fit before the deadline.
                        val days = java.time.temporal.ChronoUnit.DAYS.between(today, deadline).coerceAtLeast(1)
                        maxPerDayH = minOf(8.0, maxOf(likeGoal?.maxPerDayH ?: 2.0, 1.5 * totalUnits!! / days))
                    }
                    var name = str(x, "activity") ?: g.name
                    val base = name
                    var k = 2
                    while (act(name) != null) name = "$base ${k++}"
                    val a = like.copy().apply {
                        id = java.util.UUID.randomUUID().toString()
                        this.name = name
                        goalId = g.id
                        archived = false
                        startDate = str(x, "start_date")?.let { LocalDate.parse(it) }?.takeIf { it.isAfter(today) }
                        days(x)?.let { this.days = java.util.TreeSet(it) }
                    }
                    // A source set up before fatigue had two kinds lends no physical figures: suggest them,
                    // for you to confirm in Setup, rather than leave Chronos unable to plan.
                    com.chronos.engine.FatigueSplit.fill(a)
                    cfg.goals.add(g); cfg.activities.add(a)
                    made.add(Made(g, likeGoal))
                    said.add(op.note())
                }
                "add_activity" -> {
                    val kind = if (str(x, "kind") == "goal") Enums.ActivityKind.GOAL else Enums.ActivityKind.MAINTENANCE
                    val like = act(str(x, "like")) ?: cfg.activities.firstOrNull { it.kind == kind && !it.archived }
                    val a = (like?.copy() ?: Activity().apply {
                        load = 0.3; recovery = 0.0; physLoad = 0.1; physRecovery = 0.0; minBlock = 30; maxBlock = 60
                    }).apply {
                        id = java.util.UUID.randomUUID().toString()
                        name = str(x, "name")
                        this.kind = kind
                        goalId = null
                        archived = false
                        pins = mutableListOf()
                        days(x)?.let { this.days = java.util.TreeSet(it) }
                        windows(x)?.let { this.windows = it.toMutableList() }
                        num(x, "minutes")?.let { if (kind == Enums.ActivityKind.MAINTENANCE) { minBlock = it.toInt(); maxBlock = it.toInt() } }
                        num(x, "min_block")?.let { minBlock = it.toInt() }
                        num(x, "max_block")?.let { maxBlock = it.toInt() }
                        startDate = str(x, "start_date")?.let { LocalDate.parse(it) }?.takeIf { it.isAfter(today) }
                    }
                    if (kind == Enums.ActivityKind.GOAL) {
                        // Every goal activity has its own target: ongoing, under the goal named, paced by the
                        // weekly hours given (or by the weeks the Navigator plans).
                        val under = goal(str(x, "goal")) ?: continue
                        val t = Goal().apply {
                            id = java.util.UUID.randomUUID().toString()
                            name = a.name; groupName = under.groupName ?: under.name
                            mode = Enums.GoalMode.OPEN; hard = false; hoursPerUnit = 1.0; doneUnits = 0.0
                            weeklyFloorH = num(x, "weekly_floor_h") ?: 0.0
                            maxPerDayH = under.maxPerDayH ?: 2.0
                            weight = under.weight
                        }
                        cfg.goals.add(t)
                        a.goalId = t.id
                        made.add(Made(t, under))
                    }
                    com.chronos.engine.FatigueSplit.fill(a)
                    cfg.activities.add(a)
                    said.add(op.note())
                }
                "edit_activity" -> {
                    val a = act(str(x, "activity")) ?: continue
                    days(x)?.let { a.days = java.util.TreeSet(it) }
                    windows(x)?.let { a.windows = it.toMutableList() }
                    num(x, "minutes")?.let { if (a.kind == Enums.ActivityKind.MAINTENANCE) { a.minBlock = it.toInt(); a.maxBlock = it.toInt() } }
                    num(x, "min_block")?.let { a.minBlock = it.toInt() }
                    num(x, "max_block")?.let { a.maxBlock = it.toInt() }
                    said.add(op.note())
                }
                "pause_activity" -> {
                    act(str(x, "activity"))?.let {
                        it.pauseFrom = LocalDate.parse(str(x, "from")); it.pauseUntil = LocalDate.parse(str(x, "until"))
                        said.add(op.note())
                    }
                }
                "archive_activity" -> { act(str(x, "activity"))?.let { it.archived = true; said.add(op.note()) } }
                "restore_activity" -> { act(str(x, "activity"))?.let { it.archived = false; said.add(op.note()) } }
                "edit_goal" -> {
                    val g = goal(str(x, "goal")) ?: continue
                    str(x, "deadline")?.let { g.deadline = LocalDate.parse(it) }
                    num(x, "total_units")?.let { g.totalUnits = it }
                    num(x, "weekly_floor_h")?.let { g.weeklyFloorH = it }
                    num(x, "max_per_day_h")?.let { g.maxPerDayH = it }
                    said.add(op.note())
                }
                "pressure" -> {
                    num(x, "slack_ratio")?.let { cfg.params.slackRatio = it }
                    num(x, "f_safe")?.let { cfg.params.fSafe = it }
                    num(x, "f_safe_p")?.let { cfg.params.fSafeP = it }
                    said.add(op.note())
                }
                "sleep" -> {
                    str(x, "bed")?.let { cfg.params.sleepStart = it }
                    str(x, "wake")?.let { cfg.params.sleepEnd = it }
                    said.add(op.note())
                }
                "rest_day" -> {
                    val d = LocalDate.parse(str(x, "date"))
                    if (x.get("rest")?.asBoolean != false) rest.add(d) else rest.remove(d)
                    said.add(op.note())
                }
            }
        }
        return said to made
    }

    // -------------------------------------------------------------- the week

    /** One simulated day: goal hours by goal name, tiredness peaks, time above the safe lines. */
    data class Day(val date: LocalDate, val rest: Boolean, val goalH: Map<String, Double>, val peakMental: Double,
                   val peakPhysical: Double, val aboveMentalMin: Int, val abovePhysicalMin: Int, val unplacedH: Double)

    data class Week(val days: List<Day>, val shortfalls: Map<String, Double>,
                    /** Per date, goal id → hours: what the forecast follows (Inputs.chronosPlan). */
                    val goalHours: Map<LocalDate, Map<String, Double>>) {
        val goalTotal get() = days.sumOf { d -> d.goalH.values.sum() }
        val unplaced get() = days.sumOf { it.unplacedH }

        fun json(): JsonObject = JsonObject().apply {
            addProperty("goal_hours_total", r1(goalTotal))
            add("goal_hours", JsonObject().apply {
                days.flatMap { it.goalH.entries }.groupBy { it.key }.forEach { (k, v) -> addProperty(k, r1(v.sumOf { it.value })) }
            })
            addProperty("unplaced_hours", r1(unplaced))
            addProperty("minutes_above_mental_line", days.sumOf { it.aboveMentalMin })
            addProperty("minutes_above_physical_line", days.sumOf { it.abovePhysicalMin })
            add("days", JsonArray().apply {
                days.forEach { d ->
                    add(JsonObject().apply {
                        addProperty("date", d.date.toString())
                        if (d.rest) addProperty("rest", true)
                        addProperty("goal_h", r1(d.goalH.values.sum()))
                        addProperty("peak_mental", r2(d.peakMental))
                        addProperty("peak_physical", r2(d.peakPhysical))
                    })
                }
            })
            if (shortfalls.isNotEmpty()) add("goals_short_by_hours", JsonObject().apply {
                shortfalls.forEach { (k, v) -> addProperty(k, r1(v)) }
            })
        }

        private fun r1(v: Double) = Math.round(v * 10) / 10.0
    }

    /** The week Chronos would plan for this configuration and these rest days. Stores nothing. */
    fun week(repo: ChronosRepository, cfg: Config, rest: Set<LocalDate>, days: Int = 7): Week {
        // While Setup still lacks an answer Chronos plans nothing, and neither does its what-if.
        if (!com.chronos.validate.Readiness.canSolve(repo.readiness(cfg))) return Week(emptyList(), emptyMap(), emptyMap())
        val w = repo.whatIf(cfg, rest, days)
        val fSafe = cfg.params.fSafe ?: 1.0
        val fSafeP = cfg.params.fSafeP ?: fSafe
        val out = w.days().mapIndexed { i, r ->
            val goalH = mutableMapOf<String, Double>()
            r.blocks().filter { !it.fixed && !it.gap && it.start >= 0 && it.goalId != null }.forEach { b ->
                val name = cfg.goal(b.goalId)?.name ?: b.goalId
                goalH[name] = (goalH[name] ?: 0.0) + b.dur * Slots.SLOT_MIN / 60.0
            }
            val m = r.score()?.fatigue() ?: DoubleArray(0)
            val ph = r.score()?.physical() ?: DoubleArray(0)
            Day(w.start().plusDays(i.toLong()), w.rest()[i], goalH, m.maxOrNull() ?: 0.0, ph.maxOrNull() ?: 0.0,
                m.count { it > fSafe } * Slots.SLOT_MIN, ph.count { it > fSafeP } * Slots.SLOT_MIN,
                r.undelivered().values.sum())
        }
        val hours = (0 until minOf(14, w.plan().allocation().size)).associate { k ->
            w.start().plusDays(k.toLong()) to w.plan().allocation()[k]
        }
        return Week(out, w.plan().shortfalls().associate { it.goal() to it.missingH() }, hours)
    }

    // ------------------------------------------------------------ the digest

    /**
     * A summary of the last four weeks of the log, computed here so the
     * models see figures, never the log itself: per activity hours planned and
     * worked and how tired it left you; per weekday the same; sleep; mornings
     * and evenings; rest days; the speed and adherence the Navigator learned.
     */
    fun digest(cfg: Config, logs: List<LogEntry>, rest: Set<LocalDate>, g: Graph?, today: LocalDate): JsonObject {
        val from = today.minusDays(28)
        val recent = logs.filter { it.date != null && !it.date.isBefore(from) && it.date.isBefore(today.plusDays(1)) }
        fun avg(v: List<Number>) = if (v.isEmpty()) null else Math.round(v.sumOf { it.toDouble() } / v.size * 100) / 100.0
        fun mental(l: LogEntry) = l.mentalAfter ?: l.fatigueAfter
        val sessions = recent.filter { it.kind == Enums.LogKind.SESSION }
        return JsonObject().apply {
            addProperty("from", from.toString())
            addProperty("to", today.toString())
            add("activities", JsonArray().apply {
                sessions.groupBy { it.actId }.forEach { (id, ls) ->
                    // Work whose deadline is behind it is not reviewed; its tiredness stays in the weekdays below.
                    val a = cfg.activity(id)?.takeIf { !cfg.over(it, today) } ?: return@forEach
                    add(JsonObject().apply {
                        addProperty("name", a.name)
                        addProperty("kind", if (a.kind == Enums.ActivityKind.GOAL) "goal" else if (a.leisure) "leisure" else "upkeep")
                        avg(ls.mapNotNull { it.stopHard })?.let { addProperty("hard_to_stop_1to5", it) }
                        avg(ls.mapNotNull { it.nextStart })?.let { addProperty("hard_to_start_next_1to5", it) }
                        avg(ls.mapNotNull { it.craving })?.let { addProperty("wanted_more_1to5", it) }
                        addProperty("planned_h", Math.round(ls.sumOf { it.planned ?: 0 } / 6.0) / 10.0)
                        addProperty("worked_h", Math.round(ls.filter { it.at != null }.sumOf { it.actual ?: 0 } / 6.0) / 10.0)
                        addProperty("sessions", ls.count { it.at != null })
                        addProperty("skipped", ls.count { it.at == null })
                        avg(ls.mapNotNull { mental(it) })?.let { addProperty("mental_tired_after_1to5", it) }
                        avg(ls.mapNotNull { it.physicalAfter })?.let { addProperty("physical_tired_after_1to5", it) }
                        avg(ls.mapNotNull { it.focus })?.let { addProperty("focus_1to5", it) }
                    })
                }
            })
            val names = arrayOf("Sat", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri")
            add("weekdays", JsonArray().apply {
                for (d in 0..6) {
                    val ls = sessions.filter { it.at != null && Slots.dow(it.date) == d }
                    val goalMin = ls.filter { cfg.activity(it.actId)?.kind == Enums.ActivityKind.GOAL }.sumOf { it.actual ?: 0 }
                    val weeks = max(1, ChronoUnit.WEEKS.between(from, today).toInt())
                    add(JsonObject().apply {
                        addProperty("day", names[d])
                        addProperty("goal_h_per_week", Math.round(goalMin / 6.0 / weeks) / 10.0)
                        avg(ls.mapNotNull { mental(it) })?.let { addProperty("mental_tired_1to5", it) }
                        avg(ls.mapNotNull { it.physicalAfter })?.let { addProperty("physical_tired_1to5", it) }
                    })
                }
            })
            val nights = recent.filter { it.kind == Enums.LogKind.SLEEP }
            if (nights.isNotEmpty()) add("sleep", JsonObject().apply {
                fun clockAvg(times: List<String>, shift: Int): String? {
                    val m = times.mapNotNull { ChangeSet.clock(it) }.map { (it + shift) % 1440 }
                    return if (m.isEmpty()) null else ChangeSet.hhmm(m.average().roundToInt() - shift)
                }
                addProperty("nights", nights.size)
                clockAvg(nights.mapNotNull { n -> n.at?.let { ChangeSet.hhmm(com.chronos.engine.Habits.bedMinutes(it, n.endAt)) } }, 720)
                    ?.let { addProperty("avg_bed", it) }
                clockAvg(nights.mapNotNull { it.endAt }, 0)?.let { addProperty("avg_up", it) }
                avg(nights.mapNotNull { it.restored })?.let { addProperty("restful_1to5", it) }
                avg(nights.mapNotNull { it.latencyMin })?.let { addProperty("minutes_to_fall_asleep", it) }
                avg(nights.mapNotNull { it.wakeups })?.let { addProperty("wakeups", it) }
            })
            val checkins = recent.filter { it.kind == Enums.LogKind.CHECKIN && it.energy != null }
            add("checkins", JsonObject().apply {
                avg(checkins.filter { it.id?.startsWith("morning|") == true }.map { it.energy!! })?.let { addProperty("morning_energy_1to5", it) }
                avg(checkins.filter { it.id?.startsWith("evening|") == true }.map { it.energy!! })?.let { addProperty("evening_energy_1to5", it) }
                add("last_mornings", JsonArray().apply {
                    checkins.filter { it.id?.startsWith("morning|") == true }.sortedByDescending { it.date }.take(7)
                        .forEach { add("${it.date}: ${it.energy}") }
                })
            })
            addProperty("rest_days_taken", rest.count { !it.isBefore(from) && it.isBefore(today) })
            if (g != null) add("speed", JsonObject().apply {
                g.params.values.filter { it.key.startsWith("post.bias.") }.forEach { p ->
                    add(p.key.removePrefix("post.bias."), JsonObject().apply {
                        p.get("median")?.let { addProperty("actual_over_planned", r2(it)) }
                        p.get("slope")?.let { addProperty("trend_per_day", Math.round(it * 10000) / 10000.0) }
                        p.get("n")?.let { addProperty("days_seen", it.toInt()) }
                    })
                }
            })
            if (g != null) add("share_of_plan_kept", JsonObject().apply {
                g.params.values.filter { it.key.startsWith("post.adherence.") }.forEach { p ->
                    val a = p.get("a"); val b = p.get("b")
                    if (a != null && b != null && a + b > 0) addProperty(p.key.removePrefix("post.adherence."), r2(a / (a + b)))
                }
            })
            // What the app's own rules read in the log about hours, days, lengths and sleep (25 Oct 2026),
            // each with a change it would make; the tuner may take, adjust or leave them.
            val patterns = com.chronos.engine.Habits.find(cfg, logs, today)
            if (patterns.isNotEmpty()) add("patterns_in_the_log", JsonArray().apply {
                patterns.forEach { f -> add(JsonObject().apply {
                    addProperty("about", f.subject()); addProperty("pattern", f.why())
                    add("suggested_action", com.google.gson.JsonParser.parseString(f.action()))
                }) }
            })
            addProperty("note", "Tiredness is 1 (fresh) to 5 (spent). Days are " + names.joinToString(" ") + ".")
        }
    }

    /** Lower-case English, for the brief. */
    fun lang(fa: Boolean) = if (fa) "fa" else "en".lowercase(Locale.ROOT)
}
