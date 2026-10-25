package com.chronos.navigator.llm.client

import android.content.Context
import com.chronos.data.ChronosRepository
import com.chronos.data.DatabaseWiring
import com.chronos.navigator.bridge.Bridge
import com.chronos.navigator.forecast.Effects
import com.chronos.navigator.llm.EstimatePrior
import com.chronos.navigator.llm.Expand
import com.chronos.navigator.llm.LlmRequest
import com.chronos.navigator.llm.Op
import com.chronos.navigator.llm.ProposalValidator
import com.chronos.navigator.llm.Replan
import com.chronos.navigator.llm.Structure
import com.chronos.navigator.model.Graph
import com.chronos.navigator.model.Issue
import com.chronos.navigator.model.Kinds.Source
import com.chronos.navigator.model.Param
import com.chronos.navigator.store.NavEntities
import com.chronos.navigator.store.NavProposals
import com.chronos.navigator.store.NavStore
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId

/**
 * Every question the Navigator puts to the LLM goes through here, whether you
 * asked it or a trigger did. A question is stored first as a task string
 * (see the builders below) and rebuilt from the graph as it is at the moment
 * it is sent: queued offline, it is never sent stale.
 *
 * Sending checks the monthly budget and the per-provider daily ceiling; an
 * answer is validated for its task, every option it offers is forecast by the
 * app to measure its effect, and the result waits in the inbox.
 */
object NavAsk {

    // ------------------------------------------------------------- task strings

    fun estimateTask(issue: Issue) = EstimatePrior.TASK + "|" + issue.fix().name + "|" + issue.subject()
    fun gateTask(nodeId: String, why: String) = EstimatePrior.TASK + "|GATE_UPDATE|" + nodeId + "|" + why
    fun replanTask(trigger: String, nodes: List<String>, detail: String) =
        Replan.TASK + "|" + trigger + "|" + nodes.joinToString(",") + "|" + detail
    fun expandTask(nodeId: String) = Expand.TASK + "|" + nodeId
    fun reviewTask(trigger: String, detail: String) = Replan.REVIEW + "|" + trigger + "|" + detail
    /** A value the forecast is sensitive to (node.ID.effort, node.ID.success, regime.ID.mix), refined by the model. */
    fun refineTask(key: String, why: String) = EstimatePrior.TASK + "|REFINE|" + key + "|" + why

    /** The trigger a task came from, for the call record ("manual" when you asked). */
    private fun triggerOf(task: String): String {
        val p = task.split("|")
        return when (p[0]) {
            Replan.TASK, Replan.REVIEW -> p.getOrElse(1) { "manual" }
            Expand.TASK -> "expand_due"
            else -> when (p.getOrNull(1)) {
                "GATE_UPDATE" -> "external_window"
                "REFINE" -> "refine"
                else -> "manual"
            }
        }
    }

    // ------------------------------------------------------------------ build

    /** The question a task string stands for, against the graph as it is now. Throws when it no longer applies. */
    fun build(context: Context, g: Graph, task: String): LlmRequest {
        val store = DatabaseWiring.navigator(context)
        val today = ChronosRepository.logicalToday()
        val p = task.split("|", limit = 4)
        return when (p[0]) {
            EstimatePrior.TASK ->
                if (p[1] == "GATE_UPDATE") EstimatePrior.gateUpdate(g, today, p[2], p.getOrElse(3) { "" })
                else if (p[1] == "REFINE") EstimatePrior.refine(g, today, p[2], p.getOrElse(3) { "" })
                else EstimatePrior.forIssue(g, today, Issue(Issue.Severity.BLOCKING, Issue.Fix.valueOf(p[1]), p[2], ""))
            Replan.TASK -> Replan.replan(g, today, p[1], p[2].split(",").filter { g.node(it) != null },
                p.getOrElse(3) { "" }, outlook(store), week(store))
            Replan.REVIEW -> Replan.review(g, today, p[1], p.getOrElse(2) { "" }, outlook(store), week(store),
                InfoCache.read(context).take(5).map { Replan.Question(it.key(), it.text(), it.impact()) })
            Expand.TASK -> {
                val cfg = DatabaseWiring.repository(context).loadConfig()
                val acts = Bridge.nodeActivities(g, cfg, store.links())
                Expand.request(g, today, p[1], (acts[p[1]] ?: emptyList()))
            }
            else -> throw IllegalArgumentException("Unknown task ${p[0]}")
        }
    }

    fun outlook(store: NavStore): Map<String, Replan.Outlook> {
        val f = store.latestForecast() ?: return emptyMap()
        val old = store.weekOldForecast()
        return f.nodes().mapValues { (id, n) ->
            Replan.Outlook(id, n.pByDeadline, n.p50?.let { LocalDate.parse(it) }, old?.nodes()?.get(id)?.pByDeadline,
                n.critical ?: 0.0)
        }
    }

    fun week(store: NavStore): Replan.Week? {
        val rows = store.latestAllocation()
        val meta = rows.firstOrNull { it.nodeId == NavStore.WEEK_META } ?: return null
        return Replan.Week(LocalDate.parse(meta.week), meta.plannedH,
            rows.filter { it.nodeId != NavStore.WEEK_META }.associate { it.nodeId to it.plannedH },
            meta.status != "PROPOSED")
    }

    // ------------------------------------------------------------------- send

    private fun monthStart(): Long = LocalDate.now().withDayOfMonth(1)
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun dayStart(): Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Why this question may not be sent now, or null when it may. */
    fun refusal(context: Context, profile: Profile, req: LlmRequest): String? {
        val inbox = NavProposals(DatabaseWiring.navigator(context).dao())
        val llm = LlmSettings(context)
        val spent = inbox.spentSince(monthStart())
        val estimate = LlmClient.estimateUsd(profile, req)
        if (spent + estimate > llm.monthlyCapUsd)
            return "This would pass your monthly LLM budget: " +
                "%.2f of %.2f dollars spent, this question costs about %.2f.".format(spent, llm.monthlyCapUsd, estimate)
        // The app's own ceiling, counted per provider and only on answered calls: a free
        // provider's limit or a failed call does not use up another provider's turns.
        if (inbox.answeredSince(dayStart(), profile.id) >= llm.dailyCalls)
            return "${profile.label} has answered ${llm.dailyCalls} questions today, the most " +
                "this app allows per provider. Pick another provider, or raise \"Calls per day at most\" in the LLM settings."
        return null
    }

    /**
     * You asked: queue the question and send it now if possible. Returns what
     * happened, in words; throws when it cannot even be queued.
     */
    fun ask(context: Context, task: String): String {
        val store = DatabaseWiring.navigator(context)
        val llm = LlmSettings(context)
        val g = store.load() ?: throw IllegalStateException("Import the goal graph first.")
        val profile = llm.active ?: throw IllegalStateException("Set up a provider in the LLM panel first.")
        val key = KeyVault.load(context, profile.id)
            ?: throw IllegalStateException("Add the API key for ${profile.label} in the LLM panel first.")
        val req = LlmClient.adapt(profile, build(context, g, task))
        refusal(context, profile, req)?.let { throw IllegalStateException(it) }
        val inbox = NavProposals(store.dao())
        val call = inbox.queue(task, triggerOf(task) + "@" + profile.id, profile.label + " · " + profile.model)
        if (!LlmClient.online(context)) return "No network: the question is queued and will be sent when you are online."
        return send(context, profile, key, call, req, g)
    }

    /** A trigger fired: the question is queued (once) and goes out with the rest of the queue. */
    fun enqueue(context: Context, task: String) {
        val store = DatabaseWiring.navigator(context)
        val inbox = NavProposals(store.dao())
        if (inbox.queuedAlready(task)) return
        val profile = LlmSettings(context).active
        inbox.queue(task, triggerOf(task) + "@" + (profile?.id ?: "none"), profile?.let { it.label + " · " + it.model })
    }

    /**
     * Sends what is queued, oldest first, while the network, the budget and the
     * daily ceiling allow; the rest stays queued for the next try. Returns the
     * last thing worth telling, or null.
     */
    fun sendQueued(context: Context): String? {
        val store = DatabaseWiring.navigator(context)
        val inbox = NavProposals(store.dao())
        val queued = inbox.queued()
        if (queued.isEmpty() || !LlmClient.online(context)) return null
        val profile = LlmSettings(context).active ?: return null
        val key = KeyVault.load(context, profile.id) ?: return null
        var said: String? = null
        for (call in queued) {
            val g = store.load() ?: return said
            val req = try {
                LlmClient.adapt(profile, build(context, g, call.task ?: ""))
            } catch (e: Exception) {
                call.status = "FAILED"; call.error = "The question no longer applies: ${e.message}"; inbox.put(call); continue
            }
            refusal(context, profile, req)?.let { return it }
            said = send(context, profile, key, call, req, g)
        }
        return said
    }

    private fun send(context: Context, profile: Profile, key: String, call: NavEntities.LlmCallRow,
                     req: LlmRequest, g: Graph): String {
        val inbox = NavProposals(DatabaseWiring.navigator(context).dao())
        call.status = "SENT"
        call.model = profile.label + " · " + profile.model
        call.trigger = triggerOf(call.task ?: "") + "@" + profile.id
        call.requestHash = MessageDigest.getInstance("SHA-256")
            .digest((req.system() + "\n" + req.user()).toByteArray()).joinToString("") { "%02x".format(it) }
        inbox.put(call)
        val said: String
        try {
            val reply = LlmClient.send(profile, key, req)
            call.model = profile.label + " · " + reply.model
            call.tokensIn = reply.tokensIn.toInt()
            call.tokensOut = reply.tokensOut.toInt()
            call.costUsd = LlmClient.costUsd(profile, reply)
            call.sources = com.google.gson.Gson().toJson(reply.urls)
            call.doneAt = System.currentTimeMillis()
            if (reply.stopReason == "refusal") {
                call.status = "FAILED"; call.error = "The model declined to answer."
                said = "The LLM declined to answer this question."
            } else {
                call.status = "DONE"
                said = file(context, call.id, req, reply, g)
            }
        } catch (e: Exception) {
            call.status = "FAILED"
            call.error = e.message ?: e.javaClass.simpleName
            call.doneAt = System.currentTimeMillis()
            inbox.put(call)
            return "${profile.label} did not answer: ${call.error}"
        }
        inbox.put(call)
        return said
    }

    // ------------------------------------------------------------------- file

    private fun file(context: Context, callId: String, req: LlmRequest, reply: LlmClient.Reply, g: Graph): String {
        val store = DatabaseWiring.navigator(context)
        val inbox = NavProposals(store.dao())
        val today = ChronosRepository.logicalToday()
        val measured = check(context, g, req, reply.text, reply.urls)
        measured.forEach { inbox.file(callId, req, it) }
        val ok = measured.count { it.ok() }
        return when {
            ok == 0 -> "The answer was rejected: ${measured.firstOrNull()?.reason()}"
            ok == 1 -> "A proposal is waiting in the inbox."
            else -> "$ok proposals are waiting in the inbox."
        }
    }

    /** An answer through the checks of its question, then measured. */
    private fun check(context: Context, g: Graph, req: LlmRequest, text: String, urls: List<String>): List<ProposalValidator.Result> {
        val today = ChronosRepository.logicalToday()
        val results: List<ProposalValidator.Result> = when (req.task()) {
            Replan.TASK, Replan.REVIEW -> Replan.validate(g, today, req, text)
            Expand.TASK -> listOf(Expand.validate(g, today, req, text))
            else -> listOf(ProposalValidator.validate(g, today, req, text, urls))
        }
        return runCatching { measure(context, g, req, results) }.getOrElse { results }
    }

    // ------------------------------------------------------------- discussion

    /**
     * You said something about a proposal. The model answers with the original
     * question rebuilt from the graph as it is now, its earlier proposal and the
     * conversation so far; a revision it makes is checked and measured like any
     * answer and filed as the next version, the old one marked revised. Needs
     * the network; counts against the budget like any call.
     */
    fun discuss(context: Context, proposalId: String, message: String): String {
        val store = DatabaseWiring.navigator(context)
        val inbox = NavProposals(store.dao())
        val v = inbox.view(proposalId) ?: throw IllegalStateException("That proposal is gone.")
        if (v.fromCrew()) return CrewRun.discuss(context, proposalId, message)
        val origin = inbox.call(v.callId()) ?: throw IllegalStateException("The question behind this proposal is not on record.")
        val g = store.load() ?: throw IllegalStateException("Import the goal graph first.")
        val llm = LlmSettings(context)
        val profile = llm.active ?: throw IllegalStateException("Set up a provider in the LLM panel first.")
        val key = KeyVault.load(context, profile.id)
            ?: throw IllegalStateException("Add the API key for ${profile.label} in the LLM panel first.")
        if (!LlmClient.online(context)) throw IllegalStateException("No network: a conversation needs it.")
        val original = LlmClient.adapt(profile, build(context, g, origin.task ?: ""))
        val req = com.chronos.navigator.llm.Discuss.request(original, describe(v), v.thread(), message)
        refusal(context, profile, req)?.let { throw IllegalStateException(it) }

        val call = inbox.queue("DISCUSS|" + proposalId, "discussion@" + profile.id, profile.label + " · " + profile.model)
        call.status = "SENT"
        inbox.put(call)
        val reply = try {
            LlmClient.send(profile, key, req)
        } catch (e: Exception) {
            call.status = "FAILED"; call.error = e.message ?: e.javaClass.simpleName; call.doneAt = System.currentTimeMillis()
            inbox.put(call)
            throw IllegalStateException("${profile.label} did not answer: ${call.error}")
        }
        call.model = profile.label + " · " + reply.model
        call.tokensIn = reply.tokensIn.toInt(); call.tokensOut = reply.tokensOut.toInt()
        call.costUsd = LlmClient.costUsd(profile, reply)
        call.doneAt = System.currentTimeMillis()
        call.status = "DONE"
        inbox.put(call)

        val answer = com.chronos.navigator.llm.Discuss.read(reply.text)
            ?: throw IllegalStateException("The model's answer could not be read.")
        val turns = v.thread() + com.chronos.navigator.llm.Discuss.Turn("you", message) +
            com.chronos.navigator.llm.Discuss.Turn("model", answer.reply())
        val revised = answer.revised()
        if (answer.obsolete() && v.status() == "PENDING") {
            inbox.setThread(v.id(), turns)
            inbox.markOutdated(v.id(), "The model: " + answer.reply())
            return "The model says it no longer helps; it left the inbox."
        }
        if (revised == null || answer.involvesOtherPerson() || v.status() != "PENDING") {
            inbox.setThread(v.id(), turns)
            return "The model answered."
        }
        val results = check(context, g, original, revised.toString(), reply.urls)
        val ok = results.filter { it.ok() }
        if (ok.isEmpty()) {
            val why = results.firstOrNull()?.reason() ?: "it did not pass the checks"
            inbox.setThread(v.id(), turns + com.chronos.navigator.llm.Discuss.Turn("model",
                "(The app could not accept the revision: $why.)"))
            return "The model's revision did not pass the checks: $why"
        }
        ok.forEach { inbox.file(call.id, original, it, v.id(), v.version() + 1, turns) }
        inbox.markRevised(v.id())
        return "The model revised its proposal: version ${v.version() + 1} is waiting."
    }

    /** A filed proposal in words, for the model to see what it said before. */
    fun describe(v: NavProposals.View): String = buildString {
        v.title()?.let { append("Title: ").append(it).append('\n') }
        v.summary()?.let { append("Summary: ").append(it).append('\n') }
        v.rationale()?.let { append("Rationale: ").append(it).append('\n') }
        if (v.ops().isNotEmpty()) {
            append("What it would change:\n")
            v.ops().forEach { op ->
                append("- ").append(op.note() ?: op.key())
                if (op.isParam() && op.values().isNotEmpty())
                    append(" [").append(op.key()).append(": ").append(op.values().entries.joinToString { "${it.key}=${"%.3f".format(it.value)}" }).append(']')
                append('\n')
            }
        }
        if (v.effects().isNotEmpty()) {
            append("Its effect as the app measured it:\n")
            v.effects().forEach { append("- P(").append(it.nodeId()).append(" by its deadline): ")
                .append(Math.round(it.before() * 100)).append("% -> ").append(Math.round(it.after() * 100)).append("%\n") }
        }
    }

    /**
     * Each passing option forecast with and without it, same seed and runs: the
     * effect shown beside it is the app's, never the LLM's.
     */
    private fun measure(context: Context, g: Graph, req: LlmRequest,
                        results: List<ProposalValidator.Result>): List<ProposalValidator.Result> {
        val worth = results.filter { r -> r.ok() && r.ops().any { !it.discussOnly() } }
        if (worth.isEmpty()) return results
        val store = DatabaseWiring.navigator(context)
        val repo = DatabaseWiring.repository(context)
        val cfg = repo.loadConfig()
        val today = ChronosRepository.logicalToday()
        val inputs = com.chronos.navigator.bridge.NavEngine.inputs(context, g, cfg)
        val runs = g.params["effects.runs"]?.get("value")?.toInt() ?: 1500
        val seed = today.toEpochDay() * 7919 + 17
        val base = Effects.baseline(g, inputs, today, runs, seed)
        val subject = req.subject()?.split(",")?.filter { g.node(it) != null } ?: emptyList()
        return results.map { r ->
            if (r !in worth) return@map r
            val changed = if (r.ops().any { it.isStructure() })
                store.loadWithNodes(Structure.apply(store.nodeJson(), r.ops()))
            else store.loadWith(r.ops().filter { it.isParam() && !it.discussOnly() }.map { toParam(it) })
            g.params.filterKeys { it.startsWith("post.") }.values.forEach { changed.params[it.key] = it }
            val focus = (subject + r.ops().mapNotNull { op ->
                op.key().removePrefix("node.").substringBeforeLast('.').takeIf { op.key().startsWith("node.") && g.node(it) != null }
            }).distinct()
            val effects = Effects.compare(base, changed, inputs, today, focus, 0.03, 5)
            r.withEffects(effects.map { ProposalValidator.Effect(it.nodeId(), it.before(), it.after()) })
        }
    }

    /**
     * Filed proposals measured again on today's forecast, the same way as when they were
     * filed (same runs, today's seed): for the inbox's re-check. Notes only are skipped.
     */
    fun remeasure(context: Context, g: Graph, items: List<NavProposals.View>): Map<String, List<ProposalValidator.Effect>> {
        val worth = items.filter { v -> v.ops().any { !it.discussOnly() && !it.isChronos() } }
        if (worth.isEmpty()) return emptyMap()
        val store = DatabaseWiring.navigator(context)
        val cfg = DatabaseWiring.repository(context).loadConfig()
        val today = ChronosRepository.logicalToday()
        val inputs = com.chronos.navigator.bridge.NavEngine.inputs(context, g, cfg)
        val runs = g.params["effects.runs"]?.get("value")?.toInt() ?: 1500
        val base = Effects.baseline(g, inputs, today, runs, today.toEpochDay() * 7919 + 17)
        return worth.associate { v ->
            val ops = v.ops()
            val changed = if (ops.any { it.isStructure() }) store.loadWithNodes(Structure.apply(store.nodeJson(), ops))
                else store.loadWith(ops.filter { it.isParam() && !it.discussOnly() }.map { toParam(it) })
            g.params.filterKeys { it.startsWith("post.") }.values.forEach { changed.params[it.key] = it }
            val subject = v.subject()?.split(",")?.filter { g.node(it) != null } ?: emptyList()
            val focus = (subject + ops.mapNotNull { op ->
                op.key().removePrefix("node.").substringBeforeLast('.').takeIf { op.key().startsWith("node.") && g.node(it) != null }
            }).distinct()
            v.id() to Effects.compare(base, changed, inputs, today, focus, 0.03, 5)
                .map { ProposalValidator.Effect(it.nodeId(), it.before(), it.after()) }
        }
    }

    fun toParam(op: Op): Param = Param().apply {
        key = op.key(); dist = op.dist(); values.putAll(op.values()); source = Source.LLM; note = op.note()
        updatedAt = System.currentTimeMillis()
    }
}
