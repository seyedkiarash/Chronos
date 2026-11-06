package com.chronos.navigator.llm.client

import android.content.Context
import android.content.SharedPreferences
import com.chronos.data.ChronosRepository
import com.chronos.data.DatabaseWiring
import com.chronos.navigator.llm.EstimatePrior
import com.chronos.navigator.llm.LlmRequest
import com.chronos.navigator.llm.Replan
import com.chronos.navigator.store.NavProposals
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import java.security.MessageDigest

/**
 * How each key of each provider has been answering (your request of 3 Nov 2026: models were often
 * silent, perhaps an old key). Every request records whether it came back and how fast; the test in
 * the LLM panel sends a one-word question on every key. Quick replies and the fallbacks go to the
 * fastest provider that answered lately, and a key that was refused is left for last.
 */
object LlmHealth {
    data class Check(val profileId: String, val model: String, val tail: String, val ok: Boolean, val ms: Long,
                     val at: Long, val error: String?, val kind: String)

    private val gson = Gson()
    private val checks = mutableListOf<Check>()
    @Volatile private var prefs: SharedPreferences? = null

    /** Remembers across restarts once any part of the app with a context has passed by. */
    @Synchronized fun attach(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("chronos.llm", Context.MODE_PRIVATE)
        prefs = p
        runCatching {
            p.getString("health", null)?.let { checks += gson.fromJson<List<Check>>(it, object : TypeToken<List<Check>>() {}.type) }
        }
    }

    fun tail(key: String) = "…" + key.takeLast(4)

    @Synchronized fun record(p: Profile, key: String, ms: Long, error: String?, kind: String) {
        checks += Check(p.id, p.model, tail(key), error == null, ms, System.currentTimeMillis(), error?.take(160), kind)
        while (checks.size > 120) checks.removeAt(0)
        prefs?.edit()?.putString("health", gson.toJson(checks))?.apply()
    }

    /** The latest result of each key of a provider, by its last four characters. */
    @Synchronized fun latest(profileId: String): Map<String, Check> =
        checks.filter { it.profileId == profileId }.groupBy { it.tail }.mapValues { it.value.last() }

    /** A key refused, or a provider that failed for reasons that do not pass by themselves, in the last six hours. */
    private fun broken(c: Check) = !c.ok && System.currentTimeMillis() - c.at < 6 * 3_600_000L &&
        !NavAsk.passing(c.error ?: "")

    /**
     * Lower is better: the typical time of the recent short answers, in milliseconds. Unknown is
     * 20 seconds; failing on every key puts the provider after all the others.
     */
    @Synchronized fun score(p: Profile): Long {
        val mine = checks.filter { it.profileId == p.id && (it.model == p.model || it.kind == "ping") }.takeLast(8)
        val byKey = mine.groupBy { it.tail }.mapValues { it.value.last() }
        if (byKey.isNotEmpty() && byKey.values.all { broken(it) }) return 10_000_000L
        val fast = mine.filter { it.ok && it.kind != "full" }.map { it.ms }.sorted()
        val failures = mine.takeLast(4).count { !it.ok }
        return (if (fast.isEmpty()) 20_000L else fast[fast.size / 2]) + failures * 15_000L
    }
}

/**
 * Fast, short answers (3 Nov 2026): the Navigator's first reply to a message while the crew works on
 * the full answer, and the daily conversation. One request at a time to the fastest provider that
 * answered lately, with a short wait; when it does not answer, the next one, up to four.
 */
object Quick {
    data class Said(val text: String, val model: String, val ms: Long)

    /** Every provider and model that can be asked: those set up with a key, and the crew's picks. */
    fun routes(context: Context): List<Profile> {
        LlmHealth.attach(context)
        val llm = LlmSettings(context)
        val crew = CrewSettings(context)
        val list = mutableListOf<Profile>()
        llm.profiles.filter { it.model.isNotBlank() && KeyVault.has(context, it.id) }.forEach { list += it }
        listOf(com.chronos.navigator.llm.Crew.Role.COORDINATOR, com.chronos.navigator.llm.Crew.Role.ARCHITECT).forEach { r ->
            val pick = crew.pick(r) ?: return@forEach
            llm.profiles.firstOrNull { it.id == pick.profileId && KeyVault.has(context, it.id) }?.let { list += it.copy(model = pick.model) }
        }
        return list.distinctBy { it.id + "|" + it.model }.sortedBy { LlmHealth.score(it) }
    }

    /** Whether quick replies are wanted; on unless turned off in the LLM panel. */
    fun on(context: Context) = context.getSharedPreferences("chronos.llm", Context.MODE_PRIVATE).getBoolean("quick", true)
    fun setOn(context: Context, on: Boolean) =
        context.getSharedPreferences("chronos.llm", Context.MODE_PRIVATE).edit().putBoolean("quick", on).apply()

    /**
     * One short answer. Each attempt is recorded as a call (its trigger ends in "#chat", so it does
     * not use up the per-provider question ceiling); the monthly budget holds for paid providers.
     * Throws, with every reason, only when none answered.
     */
    fun ask(context: Context, system: String, user: String, task: String, trigger: String,
            maxTokens: Int = 2500, readMs: Int = 45_000, tries: Int = 4): Said {
        val req = LlmRequest(task, JsonObject().apply { addProperty("kind", "CHAT") }, system, user, false)
        val inbox = NavProposals(DatabaseWiring.navigator(context).dao())
        val crew = CrewSettings(context)
        val why = mutableListOf<String>()
        for (p in routes(context).take(tries)) {
            val freeOne = p.preset == "openrouter" && p.free
            if (freeOne && crew.freeLeft <= 0) { why += "OpenRouter's free requests for today are used up"; continue }
            if (!freeOne) NavAsk.refusal(context, p, req)?.takeIf { !it.contains("questions today") }?.let { why += it; continue }
            val keys = KeyVault.keys(context, p.id).filter { LlmClient.keyProblem(p, it) == null }
            val key = KeyRing.take(p.id, keys) ?: run { why += "${p.label}: no key ready"; null } ?: continue
            val call = inbox.queue(task, "$trigger@${p.id}#chat", p.label + " · " + p.model)
            call.status = "SENT"
            call.requestHash = MessageDigest.getInstance("SHA-256")
                .digest((system + "\n" + user).toByteArray()).joinToString("") { "%02x".format(it) }
            inbox.put(call)
            if (freeOne) crew.countFree()
            val t0 = System.currentTimeMillis()
            try {
                val reply = LlmClient.sendShort(p, key, req, maxTokens, readMs, "quick")
                KeyRing.give(p.id, key, null)
                call.model = p.label + " · " + reply.model
                call.tokensIn = reply.tokensIn.toInt(); call.tokensOut = reply.tokensOut.toInt()
                call.costUsd = LlmClient.costUsd(p, reply)
                call.doneAt = System.currentTimeMillis()
                if (reply.text.isBlank() || reply.stopReason == "refusal") {
                    call.status = "FAILED"; call.error = if (reply.text.isBlank()) "Empty answer" else "Declined"
                    inbox.put(call); why += "${p.label}: ${call.error}"; continue
                }
                call.status = "DONE"; inbox.put(call)
                return Said(CrewRun.readable(reply.text).trim(), p.label + " · " + reply.model, System.currentTimeMillis() - t0)
            } catch (e: Exception) {
                val err = e.message ?: e.javaClass.simpleName
                KeyRing.give(p.id, key, err)
                call.status = "FAILED"; call.error = err; call.doneAt = System.currentTimeMillis(); inbox.put(call)
                if (freeOne && (err.contains("HTTP 401") || err.contains("HTTP 403"))) crew.uncount()
                why += "${p.label}: $err"
            }
        }
        throw IllegalStateException(if (why.isEmpty()) "No provider is set up with a key." else why.distinct().joinToString("\n"))
    }

    /** The goals with a deadline still ahead, in a few lines: what a short answer needs to know of them. */
    fun goals(context: Context): String {
        val store = DatabaseWiring.navigator(context)
        val g = store.load() ?: return "(no goal graph yet)"
        val odds = store.latestForecast()?.nodes()
        val today = ChronosRepository.logicalToday()
        val hidden = EstimatePrior.privateNodes(g)
        return g.nodes.values.filter { it.deadline != null && !it.deadline.isBefore(today) && it.id !in hidden && !Replan.done(it) }
            .sortedBy { it.deadline }.take(16).joinToString("\n") { n ->
                "- " + n.shortName() + " (due " + n.deadline + (odds?.get(n.id)?.pByDeadline?.let { ", chance " + Math.round(it * 100) + "%" } ?: "") + ")"
            }.ifBlank { "(no dated goals)" }
    }

    private const val FIRST = "You are the Navigator, a planning companion for a university student. The student just told " +
        "you something; a crew of other models is now working out the full answer (changes to their goal graph and their " +
        "planner, measured by the app), which takes a few minutes. Your job is only the first reply: short (2 to 5 " +
        "sentences), warm and concrete. Show you understood, say what it likely touches among their goals, and ask at most " +
        "one question if something important is missing. Do not invent numbers, do not promise changes — the crew's " +
        "package will propose them. Write in the student's language (fa = Persian, en = English)."

    /**
     * The first reply to a message you sent the crew, shown in the conversation as soon as it comes,
     * marked as quick; the crew's own answer follows as before. Silent when no model answers.
     */
    fun firstReply(context: Context, job: CrewRun.Job) {
        if (!on(context)) return
        val chatId = job.chatId ?: return
        val message = job.message ?: return
        val earlier = CrewRun.chat(context, chatId).filter { it.jobId != job.id }.takeLast(6)
        val lang = if (com.chronos.auto.AutoPlanner.prefs(context).getString("lang", null) == "fa") "fa" else "en"
        val user = "Student language: $lang\nToday: ${ChronosRepository.logicalToday()}\n\nTheir goals now:\n" + goals(context) +
            (if (earlier.isEmpty()) "" else "\n\nEarlier in this conversation:\n" +
                earlier.joinToString("\n") { (if (it.who == "you") "Student: " else "Navigator: ") + it.text.take(600) }) +
            "\n\nThe student just said:\n" + message
        val said = runCatching { ask(context, FIRST, user, "QUICK|" + job.id, "quick", maxTokens = 1800, readMs = 40_000) }
            .getOrNull() ?: return
        // Only while it is still news: once the crew has answered, a first reply would come after the full one.
        if (CrewRun.job(context, job.id)?.status != "RUNNING") return
        CrewRun.say(context, chatId, CrewRun.ChatTurn("model", said.text, System.currentTimeMillis(), job.id, quick = true))
    }

    /** One short question on every key of every provider: whether it answers, and how fast. */
    fun testAll(context: Context): List<String> {
        LlmHealth.attach(context)
        val llm = LlmSettings(context)
        val crew = CrewSettings(context)
        val out = java.util.Collections.synchronizedList(mutableListOf<String>())
        val jobs = mutableListOf<java.util.concurrent.Callable<Unit>>()
        for (p0 in llm.profiles) {
            val model = p0.model.ifBlank { crew.picks.values.firstOrNull { it.profileId == p0.id }?.model ?: "" }
            if (model.isBlank()) { out += "${p0.label}: no model chosen"; continue }
            val p = p0.copy(model = model)
            KeyVault.keys(context, p.id).forEach { key ->
                jobs += java.util.concurrent.Callable {
                    val t0 = System.currentTimeMillis()
                    // A free model's test is one of the day's free requests.
                    if (p.preset == "openrouter" && p.free) crew.countFree()
                    val line = try {
                        LlmClient.ping(p, key)
                        "${p.label} ${LlmHealth.tail(key)}: answered in " + "%.1f".format((System.currentTimeMillis() - t0) / 1000.0) + " s"
                    } catch (e: Exception) {
                        "${p.label} ${LlmHealth.tail(key)}: " + (e.message ?: e.javaClass.simpleName).take(120)
                    }
                    out += line
                }
            }
        }
        if (jobs.isNotEmpty()) {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(6, jobs.size))
            try { pool.invokeAll(jobs) } finally { pool.shutdown() }
        }
        return out.sorted()
    }
}

/**
 * Ways to your goals you may not have considered (your idea of 2 Nov 2026). A model is shown the paths
 * and their branches — never a private node — and suggests others. They stay ideas, in a file of their
 * own, outside the graph and the forecast, until you bring one to the Navigator and approve its package.
 */
object PathIdeas {
    /** @param jobId the message that took it to the Navigator; null until you send it */
    data class Idea(val id: String, val path: String?, val title: String, val why: String, val firstStep: String?, val check: String?,
                    val jobId: String? = null)

    private val gson = com.google.gson.GsonBuilder().disableHtmlEscaping().create()
    private fun file(context: Context) = java.io.File(context.filesDir, "navigator-path-ideas.json")

    fun read(context: Context): List<Idea> = file(context).takeIf { it.exists() }?.let {
        runCatching { gson.fromJson<List<Idea>>(it.readText(), object : TypeToken<List<Idea>>() {}.type) }.getOrNull()
    } ?: emptyList()

    private fun save(context: Context, ideas: List<Idea>) = file(context).writeText(gson.toJson(ideas))

    fun drop(context: Context, id: String): List<Idea> = read(context).filter { it.id != id }.also { save(context, it) }

    fun mark(context: Context, id: String, jobId: String?): List<Idea> =
        read(context).map { if (it.id == id) it.copy(jobId = jobId) else it }.also { save(context, it) }

    private const val SYSTEM = "You help a university student see ways to their goals they may not have considered. You get " +
        "their main paths and branches. Suggest up to 4 realistic alternatives or additions that are NOT already there: other " +
        "programs, routes, orders of steps or ways to gain the same thing. Consider their citizenship and constraints when given. " +
        "Be honest about uncertainty: you cannot look anything up here, so name no date or requirement as a fact, and say in " +
        "`check` what they must verify. Do not suggest more hours of work in general; they are short of hours. Answer in the " +
        "student's language (fa = Persian, en = English) as JSON only: {\"ideas\": [{\"path\": id of the path it belongs to, " +
        "\"title\": short name, \"why\": one or two sentences, \"first_step\": one small step, \"check\": what to verify}]}"

    /** Asks a model for more; what you already have or set aside stays as it is. */
    fun find(context: Context): List<Idea> {
        val store = DatabaseWiring.navigator(context)
        val g = store.load() ?: throw IllegalStateException("Import the goal graph first.")
        val hidden = EstimatePrior.privateNodes(g)
        val lines = com.chronos.navigator.model.Paths.of(g).filter { p -> p.id() != com.chronos.navigator.model.Paths.OTHER && p.goal() !in hidden }
            .joinToString("\n") { p ->
                "- [" + p.id() + "] " + p.title() + (p.goal()?.let { id -> " -> " + g.node(id)?.shortName() } ?: "") +
                    p.branches().filter { b -> b.nodes().none { it in hidden } }.joinToString("") { "\n    branch: " + it.title() }
            }
        val state = g.raw?.getAsJsonObject("user_state")
        val citizenship = state?.get("citizenship")?.toString() ?: "not stated"
        val have = read(context)
        val user = "Student language: " + (if (com.chronos.auto.AutoPlanner.prefs(context).getString("lang", null) == "fa") "fa" else "en") +
            "\nToday: " + ChronosRepository.logicalToday() + "\nCitizenship: " + citizenship +
            "\n\nTheir paths:\n" + lines + "\n\nGoals with dates:\n" + goals(context) +
            (if (have.isEmpty()) "" else "\n\nAlready suggested, do not repeat:\n" + have.joinToString("\n") { "- " + it.title })
        val said = Quick.ask(context, SYSTEM, user, "PATH_IDEAS", "path_ideas", maxTokens = 3000, readMs = 60_000)
        val o = com.chronos.navigator.llm.ProposalValidator.extractJson(said.text)
            ?: throw IllegalStateException("The answer could not be read; try again.")
        fun str(e: JsonObject, k: String) = e.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
        val fresh = o.getAsJsonArray("ideas")?.mapNotNull { el ->
            val e = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val title = str(e, "title") ?: return@mapNotNull null
            Idea(java.util.UUID.randomUUID().toString(), str(e, "path"), title, str(e, "why") ?: "", str(e, "first_step"), str(e, "check"))
        } ?: emptyList()
        return (have + fresh.filter { f -> have.none { it.title == f.title } }).takeLast(12).also { save(context, it) }
    }

    private fun goals(context: Context) = Quick.goals(context)

    fun message(context: Context, i: Idea): String =
        if (com.chronos.auto.AutoPlanner.prefs(context).getString("lang", null) == "fa")
            "یک مسیر که تا حالا در نظر نگرفته بودم: " + i.title + ". " + i.why + (i.check?.let { " باید بررسی شود: $it" } ?: "") +
                " اگر به نظرت ارزشش را دارد، جایش را روی گراف پیشنهاد بده."
        else "A path I had not considered: " + i.title + ". " + i.why + (i.check?.let { " To verify: $it" } ?: "") +
            " If it is worth it, propose its place on the graph."
}
