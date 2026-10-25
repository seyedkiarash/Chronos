package com.chronos.navigator.bridge

import android.content.Context
import com.chronos.auto.AutoPlanner
import com.chronos.data.ChronosRepository
import com.chronos.data.DatabaseWiring
import com.chronos.navigator.allocate.Allocator
import com.chronos.navigator.estimate.Estimator
import com.chronos.navigator.forecast.Forecaster
import com.chronos.navigator.llm.Op
import com.chronos.navigator.llm.client.InfoCache
import com.chronos.navigator.llm.client.LlmSettings
import com.chronos.navigator.llm.client.NavAsk
import com.chronos.navigator.model.Requirements
import com.chronos.navigator.store.NavProposals
import com.chronos.navigator.trigger.Triggers
import com.chronos.validate.Readiness
import java.time.LocalDate

/**
 * Runs the Navigator's layers on the phone, without the network: the
 * estimate and the forecast every night (spec, "a nightly job"), the weekly
 * allocation on its night (Thursday unless changed), and on demand from the
 * Navigator tab. Applying a week to Chronos happens only when the user
 * accepts it.
 */
object NavEngine {
    private val lock = Any()

    data class Outcome(val ran: Boolean, val why: String?, val forecastMillis: Long?, val allocated: Boolean)

    /** 0 = Saturday … 6 = Friday, the week Chronos uses. */
    fun weekdayIndex(d: LocalDate) = (d.dayOfWeek.value + 1) % 7

    /**
     * Estimate, forecast, and if asked, the coming week's allocation. Does
     * nothing (and says why) while the Navigator tab still has questions open.
     */
    fun run(context: Context, allocate: Boolean): Outcome = synchronized(lock) {
        val store = DatabaseWiring.navigator(context)
        val repo = DatabaseWiring.repository(context)
        val g = store.load() ?: return Outcome(false, "No goal graph yet.", null, false)
        val cfg = repo.loadConfig()
        val today = ChronosRepository.logicalToday()
        val names = cfg.activities.filter { !it.name.isNullOrBlank() }.map { it.name.trim() }
        if (!Requirements.canRun(store.issues(g, today, names)))
            return Outcome(false, "Some answers are still missing in the Navigator tab.", null, false)

        val inputs = inputs(context, g, cfg)
        val now = System.currentTimeMillis()
        val learned = Estimator.run(g, inputs, today, now)
        store.savePosteriors(learned.posteriors())
        learned.posteriors().forEach { g.params[it.key] = it }

        val runs = g.params["forecast.runs_nightly"]?.get("value")?.toInt() ?: 5000
        val seed = today.toEpochDay() * 7919 + (store.info()?.importedAt ?: 0L) % 100_003
        val budget = g.params["forecast.budget_s"]?.get("value") ?: 5.0
        val forecast = Forecaster.runWithin(g, inputs, today, runs, seed, budget)
        store.saveForecast(forecast)

        var allocated = false
        if (allocate) {
            val week = Allocator.run(g, inputs, today, Allocator.weekStart(today), seed)
            store.saveAllocation(week)
            allocated = true
        }
        Outcome(true, null, forecast.millis(), allocated)
    }

    /**
     * Everything the layers read from Chronos: the log, the goals' units and
     * weights, the ticked day types, and what Chronos itself plans for goals
     * with a deadline over the next two weeks.
     */
    fun inputs(context: Context, g: com.chronos.navigator.model.Graph, cfg: com.chronos.model.Config): com.chronos.navigator.estimate.Inputs {
        val store = DatabaseWiring.navigator(context)
        val repo = DatabaseWiring.repository(context)
        val logs = repo.logs()
        val inputs = Bridge.inputs(g, cfg, logs, store.links(), store.dayTypes())
        val today = ChronosRepository.logicalToday()
        runCatching {
            val bundle = repo.storedPlan(cfg) ?: return@runCatching
            val hours = (0 until 14).associate { k -> today.plusDays(k.toLong()) to repo.allocationFor(bundle, today.plusDays(k.toLong())) }
            Bridge.chronosPlan(inputs, g, cfg, hours, logs, today)
        }
        return inputs
    }

    /**
     * The night's run: always the forecast; the allocation on its weekday, once
     * per week; then the triggers, whose questions go out if the network and
     * the budget allow (stage 5).
     */
    fun nightly(context: Context): Outcome {
        val store = DatabaseWiring.navigator(context)
        val g = store.load() ?: return Outcome(false, "No goal graph yet.", null, false)
        val today = ChronosRepository.logicalToday()
        val day = g.params["allocate.weekday"]?.get("value")?.toInt() ?: 5
        val due = weekdayIndex(today) == day &&
            store.allocation(Allocator.weekStart(today).toString()).isEmpty()
        val out = run(context, due)
        if (out.ran && LlmSettings(context).autoTriggers)
            runCatching { triggers(context) }.onFailure { android.util.Log.w("Navigator", "triggers failed", it) }
        // The crew's weekly review of the levers, on the same night as the week's allocation.
        if (out.ran && LlmSettings(context).autoTriggers && weekdayIndex(today) == day &&
            com.chronos.navigator.llm.client.CrewSettings(context).let { it.ready || it.relay } &&
            !com.chronos.navigator.llm.client.CrewRun.reviewedWithin(context, 6))
            runCatching {
                // With copy and return the question is made ready overnight, waiting for you in the chat.
                if (com.chronos.navigator.llm.client.CrewSettings(context).relay)
                    com.chronos.navigator.llm.client.CrewRun.prepareRelay(context, "WEEKLY", null)
                else com.chronos.navigator.llm.client.CrewRun.review(context, "the weekly review")
            }
                .onFailure { android.util.Log.w("Navigator", "weekly review not started", it) }
        // What the log says about hours, days, lengths and sleep: the app's own rules, no model needed.
        if (out.ran && weekdayIndex(today) == day && !com.chronos.navigator.llm.client.CrewRun.habitsWithin(context, 6))
            runCatching { com.chronos.navigator.llm.client.CrewRun.habits(context, "the weekly look at your log") }
                .onFailure { android.util.Log.w("Navigator", "log patterns not read", it) }
        return out
    }

    /**
     * Evaluates every trigger against the newest forecast and queues a question
     * for each that fired; then sends the queue. Returns what fired. Runs
     * without the network up to the sending.
     */
    fun triggers(context: Context): List<Triggers.Fired> = synchronized(lock) {
        val store = DatabaseWiring.navigator(context)
        val repo = DatabaseWiring.repository(context)
        val g = store.load() ?: return emptyList()
        val f = store.latestForecast() ?: return emptyList()
        val today = ChronosRepository.logicalToday()
        val now = System.currentTimeMillis()
        val days = g.params["trigger.prob_jump"]?.get("days") ?: 7.0
        val pNow = f.nodes().mapNotNull { (id, n) -> n.pByDeadline?.let { id to it } }.toMap()
        val low = store.lowestSince(now - (days * 86_400_000L).toLong())
        val (hash, detail) = fingerprint(context, store, repo.loadConfig())
        val state = store.triggers()
        val inbox = NavProposals(store.dao())
        val fired = Triggers.evaluate(g, Triggers.Night(today, now, pNow, low, hash, detail, inbox.expansionsInPlay()), state)
        store.saveTriggers(state)
        for (x in fired) {
            val task = when (x.kind()) {
                Triggers.PROB_DROP, Triggers.PROB_JUMP -> NavAsk.replanTask(x.kind(), x.nodes(), x.detail())
                Triggers.EXPAND_DUE -> NavAsk.expandTask(x.nodes()[0])
                Triggers.EXTERNAL_WINDOW -> NavAsk.gateTask(x.nodes()[0], x.detail())
                Triggers.MANUAL_CHANGE, Triggers.MONTHLY_REVIEW -> {
                    // The review asks about the inputs that matter most: rank them first.
                    if (x.kind() == Triggers.MONTHLY_REVIEW) runCatching { InfoCache.compute(context) }
                    NavAsk.reviewTask(x.kind(), x.detail())
                }
                else -> null
            } ?: continue
            NavAsk.enqueue(context, task)
        }
        NavAsk.sendQueued(context)
        fired
    }

    /**
     * What manual_change watches — the graph you imported, the answers you gave,
     * your Chronos goals and commitments — as one fingerprint, and what changed
     * since the last look in words. Goals the Navigator made itself, the floors
     * the bridge writes and what the log teaches are left out: they are not your edits.
     */
    private fun fingerprint(context: Context, store: com.chronos.navigator.store.NavStore,
                            cfg: com.chronos.model.Config): Pair<String, String?> {
        val made = store.params().filter { it.key.startsWith(MADE) }.map { it.key.removePrefix(MADE) }.toSet()
        val items = sortedSetOf<String>()
        items.add("graph imported " + (store.info()?.importedAt ?: 0))
        store.params().filter { it.source == com.chronos.navigator.model.Kinds.Source.USER && !it.key.startsWith("bridge.") && !it.key.startsWith(MADE) }
            .forEach { p -> items.add("answer " + p.key + " = " + p.values.entries.joinToString { "${it.key}=${it.value}" }) }
        cfg.goals.filter { it.id !in made }.forEach { gl ->
            items.add("goal ${gl.name}: ${gl.mode}, deadline ${gl.deadline}, total ${gl.totalUnits}, hard ${gl.hard}")
        }
        cfg.commitments.forEach { c ->
            items.add("commitment ${c.name}: " + c.sessions.joinToString { "${it.day} ${it.start}-${it.end}" })
        }
        val prefs = context.getSharedPreferences("chronos.navigator", Context.MODE_PRIVATE)
        val before = prefs.getStringSet("fingerprint", null)
        prefs.edit().putStringSet("fingerprint", items).apply()
        val detail = before?.let { b ->
            val added = items - b
            val gone = b - items
            (added.map { "now: $it" } + gone.map { "before: $it" }).take(8).joinToString("; ").ifBlank { null }
        }
        return items.joinToString("\n") to detail
    }

    /** Parameter-key prefix marking a Chronos goal the Navigator made for a fine task. */
    const val MADE = "made.goal."

    /**
     * Approves a proposal. A breakdown also makes each new task a goal and an
     * activity in Chronos, set up like [copyFrom] (an existing activity whose
     * windows, block lengths and load suit the work), waiting with no hours until
     * a week you accept gives it some. Any approved change to hours, pauses or
     * deadlines plans the coming week again, for you to accept.
     */
    fun approve(context: Context, proposalId: String, copyFrom: String?): String = synchronized(lock) {
        val store = DatabaseWiring.navigator(context)
        val repo = DatabaseWiring.repository(context)
        val inbox = NavProposals(store.dao())
        val v = inbox.view(proposalId) ?: return "That proposal is gone."
        if (!v.expansion()) {
            inbox.decide(proposalId, true, store)
            // An approved deadline moves the Chronos goals the node is worked through, too.
            val moved = v.ops().filter { it.isParam() && !it.discussOnly() && it.key().endsWith(".deadline") }
            if (moved.isNotEmpty()) {
                val cfg = repo.loadConfig()
                val g = store.load()
                val names = moved.flatMap { op ->
                    val id = op.key().removePrefix("node.").removeSuffix(".deadline")
                    if (g == null) emptyList() else
                        Sync.deadlineToChronos(cfg, g, store.links(), id, LocalDate.ofEpochDay(op.values()["value"]!!.toLong()))
                }
                if (names.isNotEmpty()) { repo.saveConfig(cfg); afterConfigChange(context, cfg) }
            }
            val changesPlan = v.ops().any { it.isParam() && !it.discussOnly() &&
                (it.key().endsWith(".min_weekly") || it.key().endsWith(".pause") || it.key().endsWith(".deadline")) }
            if (changesPlan) {
                run(context, allocate = true)
                return "Approved. The coming week was planned again with it: check it and accept."
            }
            return if (v.noteOnly()) "Noted." else "Approved: the values are saved with the LLM as their source."
        }

        val cfg = repo.loadConfig()
        val src = cfg.activities.firstOrNull { Requirements.norm(it.name) == Requirements.norm(copyFrom) && it.goalId != null }
            ?: throw IllegalStateException("Pick the Chronos activity the new tasks are set up like.")
        val srcGoal = cfg.goal(src.goalId) ?: throw IllegalStateException("That activity has no goal.")
        val names = cfg.activities.mapNotNull { it.name?.let { n -> Requirements.norm(n) } }.toMutableSet()
        val made = mutableListOf<com.chronos.model.Goal>()
        val ops = v.ops().map { op ->
            if (op.kind() != Op.NODE_ADD) return@map op
            val node = op.node().deepCopy()
            var name = node.getAsJsonObject("chronos")?.get("activity")?.asString ?: node.get("title").asString
            val base = name
            var k = 2
            while (Requirements.norm(name) in names) name = "$base ${k++}"
            names.add(Requirements.norm(name))
            node.getAsJsonObject("chronos").addProperty("activity", name)

            val goal = com.chronos.model.Goal().apply {
                id = java.util.UUID.randomUUID().toString()
                this.name = node.get("title").asString.take(60)
                mode = com.chronos.model.Enums.GoalMode.OPEN
                hard = srcGoal.hard
                hoursPerUnit = srcGoal.hoursPerUnit
                unit = srcGoal.unit
                weeklyFloorH = 0.0
                maxPerDayH = 0.0          // waits until an accepted week gives it hours
                weight = srcGoal.weight
            }
            val act = src.copy().apply {
                id = java.util.UUID.randomUUID().toString()
                this.name = name
                goalId = goal.id
                val earliest = node.getAsJsonObject("window")?.get("earliest")?.asString?.let { LocalDate.parse(it) }
                startDate = earliest?.takeIf { it.isAfter(ChronosRepository.logicalToday()) }
            }
            cfg.goals.add(goal)
            cfg.activities.add(act)
            made.add(goal)
            Op(op.key(), op.dist(), op.values(), op.before(), op.note(), op.discussOnly(), op.kind(), node)
        }
        inbox.decide(proposalId, true, store, ops)
        made.forEach { gl ->
            repo.putComparison(gl.id, srcGoal.id, 1.0)
            store.putParam(com.chronos.navigator.model.Param(MADE + gl.id, com.chronos.navigator.model.Kinds.Dist.POINT,
                com.chronos.navigator.model.Kinds.Source.USER,
                "Chronos goal \"${gl.name}\", made for a task of ${v.subject()} when you approved its breakdown", "value", 1)
                .also { it.updatedAt = System.currentTimeMillis() })
        }
        repo.saveConfig(cfg)
        afterConfigChange(context, cfg)
        run(context, allocate = true)
        "Approved: ${made.size} tasks are now goals and activities in Chronos. The coming week was planned again " +
            "with them — accept it to send their hours to your days."
    }

    /**
     * Approves the changes you ticked in a package from the crew: the graph's
     * part as any approval writes it, then Chronos's — new goals and
     * activities, edits, archives, the levers and rest days — and the plan is
     * rebuilt. Changes left unticked are dropped with the rest of the package.
     */
    fun approveCrew(context: Context, proposalId: String, groups: Set<Int>): String = synchronized(lock) {
        val store = DatabaseWiring.navigator(context)
        val repo = DatabaseWiring.repository(context)
        val inbox = NavProposals(store.dao())
        val v = inbox.view(proposalId) ?: return "That proposal is gone."
        val crew = v.crew() ?: return "Not a crew proposal."
        val all: List<com.chronos.navigator.llm.ChangeSet.Group> = com.google.gson.Gson().fromJson(crew.get("groups"),
            object : com.google.gson.reflect.TypeToken<List<com.chronos.navigator.llm.ChangeSet.Group>>() {}.type)
        val keep = all.filterIndexed { i, _ -> i in groups }.flatMap { it.ops() }.toSortedSet()
        if (keep.isEmpty()) return "Tick at least one change."
        val ops = v.ops().filterIndexed { i, _ -> i in keep }
        inbox.decide(proposalId, true, store, ops)
        val today = ChronosRepository.logicalToday()
        val cfg = repo.loadConfig()
        val restBefore = repo.restDays().toSet()
        val rest = restBefore.toMutableSet()
        val (said, made) = ChronosControl.apply(cfg, rest, ops, today)
        // An approved deadline move of a node moves its Chronos goals too.
        val g = store.load()
        ops.filter { it.isParam && !it.discussOnly() && it.key().endsWith(".deadline") }.forEach { op ->
            if (g != null) Sync.deadlineToChronos(cfg, g, store.links(), op.key().removePrefix("node.").removeSuffix(".deadline"),
                LocalDate.ofEpochDay(op.values()["value"]!!.toLong()))
        }
        (rest - restBefore).forEach { repo.setRestDay(it, true) }
        (restBefore - rest).forEach { repo.setRestDay(it, false) }
        repo.saveConfig(cfg)
        made.forEach { m ->
            m.like?.let { repo.putComparison(m.goal.id, it.id, 1.0) }
            store.putParam(com.chronos.navigator.model.Param(MADE + m.goal.id, com.chronos.navigator.model.Kinds.Dist.POINT,
                com.chronos.navigator.model.Kinds.Source.USER,
                "Chronos goal \"${m.goal.name}\", made by the Navigator when you approved a package", "value", 1)
                .also { it.updatedAt = System.currentTimeMillis() })
        }
        afterConfigChange(context, cfg)
        val locked = repo.readiness(cfg).filter { it.severity() == Readiness.Severity.BLOCKING }.map { it.what() }
        val replans = ops.any { it.isParam && !it.discussOnly() &&
            (it.key().endsWith(".min_weekly") || it.key().endsWith(".pause") || it.key().endsWith(".deadline")) }
        run(context, allocate = replans)
        val n = all.size
        "Approved ${groups.size} of $n changes" + (if (said.isNotEmpty()) "; Chronos was changed (${said.size})" else "") +
            (if (replans) ". The coming week was planned again: check it and accept." else ".") +
            (if (locked.isNotEmpty()) " Chronos cannot plan until this is fixed in Setup: " + locked.joinToString("; ") else "")
    }

    /**
     * Marks a node done (or open again). A task the Navigator made a Chronos goal
     * for stops getting time at once; the forecast counts it finished from tonight.
     */
    fun markDone(context: Context, nodeId: String, done: Boolean): Unit = synchronized(lock) {
        val store = DatabaseWiring.navigator(context)
        val repo = DatabaseWiring.repository(context)
        store.setStatus(nodeId, if (done) "done" else null)
        if (!done) return
        val g = store.load() ?: return
        val cfg = repo.loadConfig()
        val made = store.params().filter { it.key.startsWith(MADE) }.map { it.key.removePrefix(MADE) }.toSet()
        var changed = false
        for (act in Bridge.nodeActivities(g, cfg, store.links())[nodeId] ?: emptyList()) {
            val a = cfg.activities.firstOrNull { Requirements.norm(it.name) == act } ?: continue
            val goal = cfg.goal(a.goalId ?: continue) ?: continue
            if (goal.id !in made) continue
            goal.weeklyFloorH = 0.0; goal.maxPerDayH = 0.0
            changed = true
        }
        if (changed) { repo.saveConfig(cfg); afterConfigChange(context, cfg) }
    }

    /**
     * The user accepted the week (with any hours they changed): its hours go
     * into Chronos's weekly floors, the plan is rebuilt and sent to the
     * calendar, and the open screens reload.
     */
    fun applyWeek(context: Context, week: String, edits: Map<String, Double>): List<Bridge.Change> = synchronized(lock) {
        val store = DatabaseWiring.navigator(context)
        val repo = DatabaseWiring.repository(context)
        val rows = store.accept(week, edits)
        val g = store.load() ?: return emptyList()
        val cfg = repo.loadConfig()
        val nodeActs = Bridge.nodeActivities(g, cfg, store.links())
        val backups = store.backups().toMutableMap()
        val changes = Bridge.apply(g, cfg, nodeActs, rows, backups)
        store.saveBackups(backups)
        repo.saveConfig(cfg)
        afterConfigChange(context, cfg)
        changes
    }

    /** Stops following the Navigator: every goal it changed gets its own floor and ceiling back. */
    fun stopFollowing(context: Context): List<Bridge.Change> = synchronized(lock) {
        val store = DatabaseWiring.navigator(context)
        val repo = DatabaseWiring.repository(context)
        val cfg = repo.loadConfig()
        val changes = Bridge.restore(cfg, store.backups())
        store.dropBackups()
        repo.saveConfig(cfg)
        afterConfigChange(context, cfg)
        changes
    }

    /** After the Navigator wrote into Chronos with your approval: the plan is rebuilt and the forecast follows. */
    fun afterChronosChange(context: Context, cfg: com.chronos.model.Config) {
        afterConfigChange(context, cfg)
        run(context, allocate = false)
    }

    private fun afterConfigChange(context: Context, cfg: com.chronos.model.Config) {
        val repo = DatabaseWiring.repository(context)
        if (Readiness.canSolve(repo.readiness(cfg))) {
            val bundle = repo.rebuild(cfg)
            runCatching { AutoPlanner.pushCalendar(context, repo, cfg, bundle) }
        }
        AutoPlanner.onClosedInBackground?.invoke()
    }
}
