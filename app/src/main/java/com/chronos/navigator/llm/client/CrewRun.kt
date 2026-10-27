package com.chronos.navigator.llm.client

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.chronos.data.ChronosRepository
import com.chronos.data.DatabaseWiring
import com.chronos.model.Config
import com.chronos.navigator.bridge.Bridge
import com.chronos.navigator.bridge.ChronosControl
import com.chronos.navigator.forecast.Bands
import com.chronos.navigator.forecast.Guard
import com.chronos.navigator.llm.ChangeSet
import com.chronos.navigator.llm.Crew
import com.chronos.navigator.llm.Discuss
import com.chronos.navigator.llm.LlmRequest
import com.chronos.navigator.llm.Op
import com.chronos.navigator.llm.ProposalValidator
import com.chronos.navigator.llm.Structure
import com.chronos.navigator.model.Graph
import com.chronos.navigator.store.NavProposals
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.ln
import kotlin.math.max

/**
 * Who plays which role in the crew, and how many free requests a day remain
 * (your answers of 13 Oct 2026: each role gets a model picked automatically,
 * changeable in the LLM panel; you stay on OpenRouter's free tier, 50 requests
 * a day, so a job uses as few as it can).
 */
class CrewSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("chronos.llm", Context.MODE_PRIVATE)
    private val gson = Gson()

    data class Pick(val profileId: String, val model: String)

    var picks: Map<String, Pick>
        get() = prefs.getString("crew.picks", null)?.let {
            gson.fromJson<Map<String, Pick>>(it, object : TypeToken<Map<String, Pick>>() {}.type)
        } ?: emptyMap()
        set(v) = prefs.edit().putString("crew.picks", gson.toJson(v)).apply()

    /** Free models tried, in order, when a role's own model does not answer. */
    var fallbacks: List<String>
        get() = prefs.getString("crew.fallbacks", null)?.let {
            gson.fromJson<List<String>>(it, object : TypeToken<List<String>>() {}.type)
        } ?: emptyList()
        set(v) = prefs.edit().putString("crew.fallbacks", gson.toJson(v)).apply()

    /** Whether messages go to your own chat app (copy and return) instead of the crew's models. */
    var relay: Boolean
        get() = prefs.getBoolean("crew.relay", false)
        set(v) = prefs.edit().putBoolean("crew.relay", v).apply()

    /** OpenRouter's free requests per day: 50, or 1000 once 10 dollars of credit were ever bought. */
    var freeDailyCap: Int
        get() = prefs.getInt("crew.freeDailyCap", 50)
        set(v) = prefs.edit().putInt("crew.freeDailyCap", v).apply()

    /** Free requests sent today (by this app's count; OpenRouter's own is what limits). */
    val freeUsedToday: Int
        get() = if (prefs.getString("crew.freeDay", null) == LocalDate.now().toString()) prefs.getInt("crew.freeCount", 0) else 0

    @Synchronized fun countFree() {
        val today = LocalDate.now().toString()
        val n = if (prefs.getString("crew.freeDay", null) == today) prefs.getInt("crew.freeCount", 0) else 0
        prefs.edit().putString("crew.freeDay", today).putInt("crew.freeCount", n + 1).apply()
    }

    /** A request that was refused before it reached a model: it did not use up the day's allowance. */
    @Synchronized fun uncount() {
        val today = LocalDate.now().toString()
        if (prefs.getString("crew.freeDay", null) != today) return
        prefs.edit().putInt("crew.freeCount", max(0, prefs.getInt("crew.freeCount", 0) - 1)).apply()
    }

    /** OpenRouter said the day's free requests are used up, on every key: none more today. */
    fun exhausted() {
        prefs.edit().putString("crew.freeDay", LocalDate.now().toString())
            .putInt("crew.freeCount", max(freeDailyCap * routerKeys(), freeUsedToday)).apply()
    }

    /** Each OpenRouter key (its own account) has its own free allowance (27 Oct 2026). */
    private fun routerKeys(): Int = LlmSettings(context).profiles.firstOrNull { it.preset == "openrouter" }
        ?.let { KeyVault.keys(context, it.id).size }?.coerceAtLeast(1) ?: 1

    val freeLeft get() = max(0, freeDailyCap * routerKeys() - freeUsedToday)

    fun pick(role: Crew.Role): Pick? = picks[role.name]

    fun set(role: Crew.Role, pick: Pick?) {
        picks = if (pick == null) picks - role.name else picks + (role.name to pick)
    }

    val ready get() = Crew.Role.entries.all { picks[it.name] != null }
}

/** One free model OpenRouter lists, with what the automatic choice looks at. */
data class ModelInfo(val id: String, val context: Int, val created: Long)

/**
 * Runs the crew: a job is your message (INTAKE) or the weekly review (WEEKLY).
 * It runs in the background (WorkManager, needs the network), records every
 * step in a file so the Navigator tab can show who is working on what, and
 * ends with one package in the proposals inbox — or with questions for you.
 */
object CrewRun {

    // ------------------------------------------------------------- the files

    data class Step(val role: String, var model: String? = null, var status: String = "WAITING",
                    var summary: String? = null, var output: String? = null, var error: String? = null)

    data class Job(val id: String, val kind: String, val message: String?, val createdAt: Long,
                   var status: String = "RUNNING", var stage: String? = null,
                   val steps: MutableList<Step> = mutableListOf(), var proposalId: String? = null,
                   var reply: String? = null, var error: String? = null, var requests: Int = 0,
                   /** Asked in your own chat app (copy and return) rather than by the crew's models. */
                   var relay: Boolean = false)

    /** @param failed why your message could not be sent (it stays, to copy, edit or send again); null when it went */
    data class ChatTurn(val who: String, val text: String, val at: Long, val jobId: String? = null,
                        val proposalId: String? = null, val failed: String? = null)

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    /** Called after every change to a job or the conversation, so open screens reload. */
    @Volatile var onChange: (() -> Unit)? = null

    private fun dir(context: Context) = File(context.filesDir, "navigator-crew").apply { mkdirs() }

    fun job(context: Context, id: String): Job? =
        File(dir(context), "$id.json").takeIf { it.exists() }?.let { runCatching { gson.fromJson(it.readText(), Job::class.java) }.getOrNull() }

    @Synchronized fun save(context: Context, job: Job) {
        File(dir(context), "${job.id}.json").writeText(gson.toJson(job))
        onChange?.invoke()
    }

    fun jobs(context: Context): List<Job> = dir(context).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { runCatching { gson.fromJson(it.readText(), Job::class.java) }.getOrNull() }
        .sortedByDescending { it.createdAt }

    private fun chatFile(context: Context) = File(context.filesDir, "navigator-chat.json")

    fun chat(context: Context): List<ChatTurn> = chatFile(context).takeIf { it.exists() }?.let {
        runCatching { gson.fromJson<List<ChatTurn>>(it.readText(), object : TypeToken<List<ChatTurn>>() {}.type) }.getOrNull()
    } ?: emptyList()

    @Synchronized fun say(context: Context, turn: ChatTurn) {
        chatFile(context).writeText(gson.toJson((chat(context) + turn).takeLast(200)))
        onChange?.invoke()
    }

    fun clearChat(context: Context) { chatFile(context).delete(); onChange?.invoke() }

    // ------------------------------------------------------------- starting

    /** You told the Navigator something: it is recorded and a job starts when the network allows. */
    fun tell(context: Context, message: String): Job? {
        // Your words are kept first, whatever happens next: a message that cannot go stays, marked.
        val at = System.currentTimeMillis()
        if (!CrewSettings(context).ready) {
            say(context, ChatTurn("you", message.trim(), at, failed = "Set up the crew's models in the LLM panel first."))
            return null
        }
        val job = Job(java.util.UUID.randomUUID().toString(), "INTAKE", message.trim(), at)
        job.steps += listOf(Step("ARCHITECT"), Step("CRITIC"), Step("COORDINATOR"))
        save(context, job)
        say(context, ChatTurn("you", message.trim(), job.createdAt, job.id))
        enqueue(context, job.id)
        return job
    }

    /**
     * A question about the goal graph itself — why two nodes are linked, where a chance comes from —
     * and, when you ask for one, a correction (your request of 22 Oct 2026). One call to the
     * coordinator's model with the whole graph in front of it; a correction is filed as a package.
     */
    fun askGraph(context: Context, message: String): Job? {
        val at = System.currentTimeMillis()
        if (!CrewSettings(context).ready) {
            say(context, ChatTurn("you", message.trim(), at, failed = "Set up the crew's models in the LLM panel first."))
            return null
        }
        val job = Job(java.util.UUID.randomUUID().toString(), "GRAPH", message.trim(), at)
        job.steps += listOf(Step("COORDINATOR"))
        save(context, job)
        say(context, ChatTurn("you", message.trim(), job.createdAt, job.id))
        enqueue(context, job.id)
        return job
    }

    /** Marks your message as not sent, with why. */
    @Synchronized fun markFailed(context: Context, jobId: String, why: String) {
        val turns = chat(context).map { if (it.who == "you" && it.jobId == jobId) it.copy(failed = why) else it }
        chatFile(context).writeText(gson.toJson(turns))
        onChange?.invoke()
    }

    /** Takes a turn out of the conversation (a failed message being sent again or edited). */
    @Synchronized fun removeTurn(context: Context, at: Long) {
        chatFile(context).writeText(gson.toJson(chat(context).filter { it.at != at }))
        onChange?.invoke()
    }

    /** The weekly review of the levers (and on demand). */
    fun review(context: Context, why: String): Job {
        check(CrewSettings(context).ready) { "Set up the crew's models in the LLM panel first." }
        val job = Job(java.util.UUID.randomUUID().toString(), "WEEKLY", why, System.currentTimeMillis())
        job.steps += listOf(Step("ANALYST"), Step("TUNER"), Step("CRITIC"), Step("COORDINATOR"))
        save(context, job)
        enqueue(context, job.id)
        return job
    }

    // ---------------------------------------------------------- copy and return

    private fun promptFile(context: Context, id: String) = File(dir(context), "$id.prompt.txt")

    /**
     * Copy and return (your choice of 17 Oct 2026): the whole question is made here — the brief, the
     * vocabulary, your words or the weekly review's simulated options — for you to send to a chat app of
     * your own; the answer you bring back is checked and measured like any. No limits, no busy providers.
     * Blocking (it measures the base and, for a review, the options): call it off the main thread.
     */
    fun prepareRelay(context: Context, kind: String, message: String?): Job {
        val at = System.currentTimeMillis()
        val job = Job(java.util.UUID.randomUUID().toString(), kind, message?.trim() ?: "the weekly review", at,
            status = "PREPARING", relay = true)
        job.steps += Step("CHAT")
        save(context, job)
        if (kind != "WEEKLY") say(context, ChatTurn("you", message!!.trim(), at, job.id))
        try {
            val base = base(context)
            val digest = ChronosControl.digest(base.cfg, DatabaseWiring.repository(context).logs(), base.rest, base.g, base.today)
            val brief = Crew.brief(base.g, base.today, base.bands, base.chronos, digest, language(context))
            val earlier = chat(context).filter { it.jobId != job.id }.map { Crew.Turn(it.who, it.text) }
            val options = if (kind == "WEEKLY") candidates(context, base) else null
            promptFile(context, job.id).writeText(if (kind == "GRAPH") Crew.relayGraph(brief, Crew.graph(base.g, base.bands), earlier, message!!)
                else Crew.relay(brief, earlier, message, options))
            job.status = "WAITING_ANSWER"
            save(context, job)
        } catch (e: Exception) {
            job.status = "FAILED"; job.error = e.message ?: e.javaClass.simpleName
            save(context, job)
            if (kind != "WEEKLY") markFailed(context, job.id, job.error ?: "")
        }
        return job
    }

    fun relayPrompt(context: Context, id: String): String? = promptFile(context, id).takeIf { it.exists() }?.readText()

    /** The answer you brought back from your chat app: checked, measured and filed. Returns what happened. */
    fun answerRelay(context: Context, id: String, text: String): String {
        val job = job(context, id) ?: throw IllegalStateException("That question is gone.")
        check(job.status == "WAITING_ANSWER") { "That question is not waiting for an answer." }
        val base = base(context)
        val parsed = ChangeSet.read(base.g, base.today, base.chronos, text)
        // A question about the graph may be answered in words alone: that is an answer, with nothing to approve.
        if (!parsed.ok() && job.kind == "GRAPH") {
            val step = job.steps.first()
            step.status = "DONE"; step.model = "your chat"
            finish(context, job, null, prose(text))
            return "The chat answered."
        }
        if (!parsed.ok()) throw IllegalStateException("The answer could not be used (${parsed.failure()}). " +
            "Copy the chat's whole reply, ending with its JSON, and paste it again.")
        val step = job.steps.first()
        step.status = "DONE"; step.model = "your chat"; step.output = json(text)
        step.summary = parsed.summary() ?: parsed.message()
        job.requests = 0
        if (parsed.empty()) {
            finish(context, job, null, (parsed.message() ?: "Nothing to change.") + questions(parsed.questions()) + refusals(parsed))
            return "The chat's answer changes nothing in your plan."
        }
        job.stage = "measuring"; save(context, job)
        val m = measure(context, base, parsed.ops())
        val proposalId = file(context, job, base, parsed, m, null, null, 1, emptyList())
        finish(context, job, proposalId, (parsed.message() ?: parsed.summary() ?: "") + questions(parsed.questions()) + refusals(parsed))
        return "The package is ready: open it to review and approve."
    }

    fun cancelRelay(context: Context, id: String) {
        val job = job(context, id) ?: return
        job.status = "CANCELLED"
        save(context, job)
        if (job.kind != "WEEKLY") markFailed(context, job.id, "Cancelled before an answer came back")
    }

    // ------------------------------------------------------------- from the log

    /**
     * What your log says about how the plan is set up (your request of 25 Oct 2026), as a package of
     * its own with no model in the way: each finding of the app's rules — allowed hours, days, session
     * lengths, sleep — is one change with the numbers behind it, measured like any package and filed in
     * the inbox for you to approve or not. Returns the proposal's id, or null when the log shows nothing.
     */
    fun habits(context: Context, why: String): String? {
        val base = base(context)
        val fa = language(context) == "fa"
        val found = com.chronos.engine.Habits.find(base.cfg, DatabaseWiring.repository(context).logs(), base.today, fa)
        if (found.isEmpty()) return null
        val changes = JsonArray()
        found.take(8).forEach { f -> changes.add(JsonObject().apply {
            addProperty("title", f.title()); addProperty("why", f.why())
            add("actions", JsonArray().apply { add(JsonParser.parseString(f.action())) })
        }) }
        val summary = if (fa) "پیشنهادهایی از روی لاگ چهار هفتهٔ اخیر" else "What the last four weeks of your log suggest"
        val text = JsonObject().apply {
            addProperty("summary", summary)
            addProperty("rationale", if (fa) "هر تغییر با عددهایی که از لاگ آمده؛ هر کدام را جدا تأیید یا رد کن."
                else "Each change comes with the numbers from your log; approve each on its own, or not.")
            addProperty("message", (if (fa) "از روی لاگت ${found.size} پیشنهاد برای ساعت‌ها، روزها، طول جلسه‌ها یا خواب دارم؛ در صندوق منتظر تأیید توست."
                else "Your log suggests ${found.size} changes to hours, days, session lengths or sleep; they wait in the inbox for you."))
            add("changes", changes)
            add("consent_self_check", JsonObject().apply { addProperty("targets_other_person", false) })
        }
        val parsed = ChangeSet.read(base.g, base.today, base.chronos, text)
        if (!parsed.ok() || parsed.empty()) return null
        val job = Job(java.util.UUID.randomUUID().toString(), "HABITS", why, System.currentTimeMillis())
        save(context, job)
        val measured = measure(context, base, parsed.ops())
        // A newer reading of the log replaces one still waiting, rather than piling up beside it.
        val inbox = NavProposals(DatabaseWiring.navigator(context).dao())
        val older = inbox.proposals().filter { it.status() == "PENDING" && it.crew()?.get("kind")?.asString == "HABITS" }
        val id = file(context, job, base, parsed, measured, null, null, 1, emptyList())
        older.forEach { inbox.markOutdated(it.id(), if (fa) "خواندن تازه‌تری از لاگت جایش را گرفت" else "A newer reading of your log replaced it") }
        finish(context, job, id, parsed.message() ?: summary)
        return id
    }

    /** Whether the log was read for the setup within the last days. */
    fun habitsWithin(context: Context, days: Long): Boolean =
        jobs(context).any { it.kind == "HABITS" && it.createdAt > System.currentTimeMillis() - days * 86_400_000L }

    /** Whether the weekly review already ran this week (seven days). */
    fun reviewedWithin(context: Context, days: Long): Boolean =
        jobs(context).any { it.kind == "WEEKLY" && it.createdAt > System.currentTimeMillis() - days * 86_400_000L }

    private fun enqueue(context: Context, id: String) {
        val req = OneTimeWorkRequestBuilder<CrewWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf("job" to id))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("crew-$id", ExistingWorkPolicy.KEEP, req)
    }

    // ---------------------------------------------------------- the models

    /** OpenRouter's free models, newest list at most a day old. Public: needs no key. */
    fun freeModels(context: Context, refresh: Boolean = false): List<ModelInfo> {
        val prefs = context.getSharedPreferences("chronos.llm", Context.MODE_PRIVATE)
        val cached = prefs.getString("crew.models", null)
        val at = prefs.getLong("crew.modelsAt", 0)
        if (!refresh && cached != null && System.currentTimeMillis() - at < 86_400_000L)
            return gson.fromJson(cached, object : TypeToken<List<ModelInfo>>() {}.type)
        val c = URL("https://openrouter.ai/api/v1/models").openConnection() as HttpURLConnection
        c.connectTimeout = 30_000; c.readTimeout = 60_000
        c.setRequestProperty("X-Title", "Chronos")
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "Chronos/1.0 (Android)")
        // The list is public, but some networks are only let through with the key.
        LlmSettings(context).profiles.firstOrNull { it.preset == "openrouter" }?.let { KeyVault.load(context, it.id) }
            ?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        val body = try {
            val code = c.responseCode
            if (code == 403) throw IllegalStateException("OpenRouter cannot be reached from this network (HTTP 403). Turn on a VPN and try again.")
            if (code !in 200..299) throw IllegalStateException("OpenRouter's model list answered HTTP $code" +
                (c.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }?.take(200)?.let { ": $it" } ?: ""))
            c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally { c.disconnect() }
        val out = JsonParser.parseString(body).asJsonObject.getAsJsonArray("data").mapNotNull { e ->
            val o = e.asJsonObject
            val id = o.get("id")?.asString ?: return@mapNotNull null
            val pr = o.getAsJsonObject("pricing")
            val free = id.endsWith(":free") || (pr != null && pr.get("prompt")?.asString == "0" && pr.get("completion")?.asString == "0")
            if (!free) return@mapNotNull null
            // Only models that answer in text alone: not music or speech, not a safety filter, not a random router.
            val outs = o.getAsJsonObject("architecture")?.getAsJsonArray("output_modalities")?.map { it.asString }
            if (outs != null && outs != listOf("text")) return@mapNotNull null
            if (id.startsWith("openrouter/") || Regex("(safety|guard|embed)", RegexOption.IGNORE_CASE).containsMatchIn(id)) return@mapNotNull null
            ModelInfo(id, o.get("context_length")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
                o.get("created")?.takeIf { !it.isJsonNull }?.asLong ?: 0)
        }
        prefs.edit().putString("crew.models", gson.toJson(out)).putLong("crew.modelsAt", System.currentTimeMillis()).apply()
        return out
    }

    private val REASONING = Regex("(r1|reason|think|qwq|gpt-oss|o\\d|deepseek|kimi|glm)", RegexOption.IGNORE_CASE)
    private val SIZE = Regex("(\\d+(?:\\.\\d+)?)b\\b", RegexOption.IGNORE_CASE)

    /** A rough strength: the size in its name, a reasoning model, a long context, a recent release. */
    fun score(m: ModelInfo, reasoning: Boolean): Double {
        val size = SIZE.findAll(m.id).mapNotNull { it.groupValues[1].toDoubleOrNull() }.maxOrNull() ?: 30.0
        var s = ln(1 + size) + 0.4 * ln(1 + m.context / 8000.0)
        if (REASONING.containsMatchIn(m.id)) s += if (reasoning) 1.5 else 0.5
        s += (m.created - 1_700_000_000L).coerceAtLeast(0) / 3e7
        if (Regex("(vision|vl|embed|guard|tts|audio|image)", RegexOption.IGNORE_CASE).containsMatchIn(m.id)) s -= 5
        return s
    }

    /**
     * Picks a model for every role from the free list: the coordinator is the
     * strongest provider you have set up that is not a free one (if any), else
     * the strongest free model; the critic a different reasoning model, so the
     * one checking is not the one proposing; the analyst the longest context.
     */
    fun autoAssign(context: Context): Map<String, CrewSettings.Pick> {
        val llm = LlmSettings(context)
        val router = llm.profiles.firstOrNull { it.preset == "openrouter" }
            ?: throw IllegalStateException("Add an OpenRouter provider (with its key) first.")
        val free = freeModels(context, refresh = true)
        check(free.isNotEmpty()) { "OpenRouter lists no free models right now." }
        val byReason = free.sortedByDescending { score(it, true) }
        val byAll = free.sortedByDescending { score(it, false) }
        val used = mutableSetOf<String>()
        fun take(list: List<ModelInfo>): String = (list.firstOrNull { it.id !in used } ?: list.first()).id.also { used += it }
        val picks = mutableMapOf<String, CrewSettings.Pick>()
        val paid = llm.profiles.firstOrNull { it.preset != "openrouter" && KeyVault.has(context, it.id) && it.model.isNotBlank() }
        picks[Crew.Role.COORDINATOR.name] = paid?.let { CrewSettings.Pick(it.id, it.model) } ?: CrewSettings.Pick(router.id, take(byReason))
        picks[Crew.Role.ARCHITECT.name] = CrewSettings.Pick(router.id, take(byAll))
        picks[Crew.Role.CRITIC.name] = CrewSettings.Pick(router.id, take(byReason))
        picks[Crew.Role.ANALYST.name] = CrewSettings.Pick(router.id, take(free.sortedByDescending { it.context + score(it, false) }))
        picks[Crew.Role.TUNER.name] = CrewSettings.Pick(router.id, take(byReason))
        picks[Crew.Role.ESTIMATOR.name] = CrewSettings.Pick(router.id, take(byAll))
        val s = CrewSettings(context)
        s.picks = picks
        s.fallbacks = byAll.map { it.id }.take(8)
        return picks
    }

    /** OpenRouter's free models allow 20 requests a minute, per key: requests on one key wait their turn. */
    private object Pace {
        private val sent = HashMap<String, ArrayDeque<Long>>()
        fun await(key: String) {
            while (true) {
                val wait = synchronized(this) {
                    val q = sent.getOrPut(key.takeLast(8)) { ArrayDeque() }
                    val now = System.currentTimeMillis()
                    while (q.isNotEmpty() && now - q.first() > 60_000) q.removeFirst()
                    if (q.size < 18) { q.addLast(now); 0L } else 60_000 - (now - q.first()) + 250
                }
                if (wait == 0L) return
                Thread.sleep(wait)
            }
        }
    }

    data class Answer(val text: String, val model: String)

    /**
     * One role's question, to its own model and, when that fails (a limit, an
     * error, an answer with no JSON), to the next free ones. Every attempt is
     * recorded as a call.
     */
    private fun ask(context: Context, job: Job, role: Crew.Role, req: LlmRequest, needsJson: Boolean = true): Answer {
        rehearsal(context, role, req)?.let { job.requests++; return it }
        val s = CrewSettings(context)
        val llm = LlmSettings(context)
        val pick = s.pick(role) ?: throw IllegalStateException("No model is chosen for the ${role.name.lowercase()}.")
        val home = llm.profiles.firstOrNull { it.id == pick.profileId }
            ?: throw IllegalStateException("The provider chosen for the ${role.name.lowercase()} is gone.")
        val router = llm.profiles.firstOrNull { it.preset == "openrouter" }
        val tries = mutableListOf(home.copy(model = pick.model))
        if (router != null) s.fallbacks.filter { it != pick.model }.take(3).forEach { tries += router.copy(model = it) }
        // Then every other provider you set up with a key and a model (27 Oct 2026): a message is not lost
        // because the crew's own models are busy.
        llm.profiles.filter { it.id != home.id && it.preset != "openrouter" && it.model.isNotBlank() && KeyVault.has(context, it.id) }
            .forEach { tries += it }
        val inbox = NavProposals(DatabaseWiring.navigator(context).dao())
        val why = mutableListOf<String>()
        var badKey = false
        for (p in tries) {
            val freeOne = p.preset == "openrouter" && p.free
            if (freeOne && s.freeLeft <= 0) { why += "OpenRouter's free requests for today are used up"; continue }
            // A key of the wrong kind fails the same way for every model of that provider.
            if (badKey && p.preset == "openrouter") continue
            val all = KeyVault.keys(context, p.id)
            if (all.isEmpty()) { why += "No key for ${p.label}"; continue }
            val keys = all.filter { LlmClient.keyProblem(p, it) == null }
            if (keys.isEmpty()) { why += LlmClient.keyProblem(p, all.first())!!; if (p.preset == "openrouter") badKey = true; continue }
            if (!freeOne) {
                // The monthly budget holds for a paid provider; the per-provider question ceiling is for single questions.
                val no = NavAsk.refusal(context, p, req)
                if (no != null && !no.contains("questions today")) { why += no; continue }
            }
            // A busy provider (503, a dropped connection) is often free again in a few seconds: the same model is
            // tried up to three times before the next one. A key at its limit gives way to the next key at once.
            var attempt = 0
            var swaps = 0
            var waited = 0
            while (attempt < 3) {
                if (attempt > waited) { Thread.sleep(15_000L * attempt); waited = attempt }
                val key = KeyRing.take(p.id, keys)
                if (key == null) { why += "every key of ${p.label} is resting after a limit"; break }
                val call = inbox.queue(Crew.task(job.id, role), "crew:${role.name}@${p.id}#crew", p.label + " · " + p.model)
                call.status = "SENT"
                call.requestHash = MessageDigest.getInstance("SHA-256")
                    .digest((req.system() + "\n" + req.user()).toByteArray()).joinToString("") { "%02x".format(it) }
                inbox.put(call)
                if (freeOne) { Pace.await(key); s.countFree() }
                job.requests++
                try {
                    val reply = LlmClient.send(p, key, req)
                    KeyRing.give(p.id, key, null)
                    call.model = p.label + " · " + reply.model
                    call.tokensIn = reply.tokensIn.toInt(); call.tokensOut = reply.tokensOut.toInt()
                    call.costUsd = LlmClient.costUsd(p, reply)
                    call.doneAt = System.currentTimeMillis()
                    if (reply.stopReason == "refusal" || reply.text.isBlank() ||
                        (needsJson && ProposalValidator.extractJson(reply.text) == null)) {
                        call.status = "FAILED"; call.error = if (reply.text.isBlank()) "Empty answer" else "The answer held no JSON"
                        inbox.put(call); why += "${p.model}: ${call.error}"; break
                    }
                    call.status = "DONE"; inbox.put(call)
                    return Answer(reply.text, p.model)
                } catch (e: Exception) {
                    val err = e.message ?: e.javaClass.simpleName
                    call.status = "FAILED"; call.error = err; call.doneAt = System.currentTimeMillis()
                    inbox.put(call)
                    KeyRing.give(p.id, key, err)
                    if (freeOne && err.contains("HTTP 429") && err.contains("per-day", true)) {
                        KeyRing.spent(p.id, key)
                        if (KeyRing.ready(p.id, keys) == 0) s.exhausted()
                    }
                    why += "${p.model}: $err"
                    // This key's limit, with another key ready: the next key, at once, without using up a try.
                    if (KeyRing.perKey(err) && swaps < keys.size - 1 && KeyRing.ready(p.id, keys) > 0) { swaps++; continue }
                    // The key itself is refused: no other model behind it will do better, and nothing was used up.
                    // 401: the key is refused; 403 from OpenRouter: this network is blocked. Neither gets better with
                    // another model behind the same door, and neither used up the day's allowance.
                    if (err.contains("HTTP 401") || (err.contains("HTTP 403") && p.preset == "openrouter")) {
                        if (freeOne) s.uncount()
                        if (p.preset == "openrouter") badKey = true
                        break
                    }
                    if (!transient(err)) break
                    attempt++
                }
            }
        }
        throw IllegalStateException(if (why.isEmpty()) "No model answered" else why.distinct().joinToString("\n"))
    }

    /**
     * Debug builds only: a role's answer read from `files/navigator-crew-rehearsal/ROLE.txt` instead of a
     * model, so the whole path after the model — checks, measurement, filing, approval — can be tried on
     * the phone without a provider. The question it would have sent is written beside it (ROLE.request.txt).
     * Nothing of this exists in a release build.
     */
    private fun rehearsal(context: Context, role: Crew.Role, req: LlmRequest): Answer? {
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        val dir = File(context.filesDir, "navigator-crew-rehearsal")
        val f = File(dir, role.name + ".txt")
        if (!f.exists()) return null
        File(dir, role.name + ".request.txt").writeText(req.system() + "\n\n=====\n\n" + req.user())
        return Answer(f.readText(), "rehearsal")
    }

    /** An error worth waiting out: the provider is busy, or the connection dropped. */
    private fun transient(err: String) = Regex("HTTP 50[023]|timed? ?out|connection (abort|reset)|unexpected end|unable to resolve host|failed to connect", RegexOption.IGNORE_CASE)
        .containsMatchIn(err)

    // ---------------------------------------------------------- measuring

    /** What a job measures against: the graph, Chronos and the week as they are now. */
    class Base(val g: Graph, val cfg: Config, val rest: Set<LocalDate>, val week: ChronosControl.Week,
               val bands: Bands.Result, val chronos: JsonObject, val today: LocalDate, val worlds: Int, val futures: Int) {
        /** The base measured with fewer runs, for the candidates, once per size. */
        val smaller = mutableMapOf<Pair<Int, Int>, Bands.Result>()
    }

    /** @param locks what Chronos would refuse to plan with after the change (its own readiness check) */
    data class Measured(val graph: Graph, val verdict: Guard.Verdict, val bands: Bands.Result, val week: ChronosControl.Week,
                        val locks: List<String> = emptyList()) {
        fun json(base: Base, parsed: ChangeSet.Parsed): JsonObject = JsonObject().apply {
            add("odds", Crew.odds(verdict))
            if (locks.isNotEmpty()) add("chronos_would_lock_until", gson.toJsonTree(locks))
            add("week_now", base.week.json())
            add("week_with_it", week.json())
            if (parsed.rejected().isNotEmpty()) add("refused_by_the_app", JsonArray().apply {
                parsed.rejected().forEach { r -> add(JsonObject().apply { addProperty("change", r.change()); addProperty("why", r.reason()) }) }
            })
        }
    }

    private fun bandsFor(g: Graph, cfg: Config, week: ChronosControl.Week, context: Context, today: LocalDate,
                         worlds: Int, futures: Int): Bands.Result {
        val store = DatabaseWiring.navigator(context)
        val repo = DatabaseWiring.repository(context)
        val logs = repo.logs()
        val inputs = Bridge.inputs(g, cfg, logs, store.links(), store.dayTypes())
        Bridge.chronosPlan(inputs, g, cfg, week.goalHours, logs, today)
        return Bands.run(g, inputs, today, worlds, futures, today.toEpochDay() * 7919 + 101)
    }

    fun base(context: Context): Base {
        val store = DatabaseWiring.navigator(context)
        val repo = DatabaseWiring.repository(context)
        val g = store.load() ?: throw IllegalStateException("Import the goal graph first.")
        val cfg = repo.loadConfig()
        val today = ChronosRepository.logicalToday()
        val rest = repo.restDays().toSet()
        val week = ChronosControl.week(repo, cfg, rest)
        val worlds = g.params["bands.worlds"]?.get("value")?.toInt() ?: 40
        val futures = g.params["bands.futures"]?.get("value")?.toInt() ?: 40
        val bands = bandsFor(g, cfg, week, context, today, worlds, futures)
        return Base(g, cfg, rest, week, bands, ChronosControl.view(cfg, rest, today), today, worlds, futures)
    }

    /** The change set tried on copies of the graph and of Chronos, measured like the base. */
    fun measure(context: Context, base: Base, ops: List<Op>, worlds: Int = base.worlds, futures: Int = base.futures): Measured {
        val store = DatabaseWiring.navigator(context)
        val repo = DatabaseWiring.repository(context)
        val structure = ops.filter { it.isStructure }
        val params = ops.filter { it.isParam && !it.discussOnly() }.map { NavAsk.toParam(it) }
        val g2 = if (structure.isEmpty() && params.isEmpty()) base.g
            else store.loadWithBoth(if (structure.isEmpty()) store.nodeJson() else Structure.apply(store.nodeJson(), structure), params)
        if (g2 !== base.g) base.g.params.filterKeys { it.startsWith("post.") }.values.forEach { g2.params[it.key] = it }
        val cfg2 = base.cfg.copy()
        val rest2 = base.rest.toMutableSet()
        ChronosControl.apply(cfg2, rest2, ops, base.today)
        val chronosOps = ops.any { it.isChronos }
        val week2 = if (chronosOps) ChronosControl.week(repo, cfg2, rest2) else base.week
        val baseBands = if (worlds == base.worlds && futures == base.futures) base.bands
            else synchronized(base.smaller) {
                base.smaller.getOrPut(worlds to futures) { bandsFor(base.g, base.cfg, base.week, context, base.today, worlds, futures) }
            }
        val bands2 = bandsFor(g2, cfg2, week2, context, base.today, worlds, futures)
        // Chronos's own readiness check on the copy: what the change would leave it unable to plan with.
        val locks = if (!chronosOps) emptyList() else {
            val before = repo.readiness(base.cfg).filter { it.severity() == com.chronos.validate.Readiness.Severity.BLOCKING }.map { it.what() }.toSet()
            repo.readiness(cfg2).filter { it.severity() == com.chronos.validate.Readiness.Severity.BLOCKING }
                .map { it.what() }.filter { it !in before }
        }
        return Measured(g2, Guard.check(g2, baseBands, bands2), bands2, week2, locks)
    }

    // ------------------------------------------------------------- running

    private fun step(job: Job, role: Crew.Role) = job.steps.firstOrNull { it.role == role.name }
        ?: Step(role.name).also { job.steps += it }

    private fun <T> parallel(vararg work: () -> T): List<Result<T>> {
        val pool = Executors.newFixedThreadPool(work.size)
        try {
            return pool.invokeAll(work.map { w -> Callable { runCatching { w() } } }).map { it.get() }
        } finally { pool.shutdown() }
    }

    private fun language(context: Context) =
        if (com.chronos.auto.AutoPlanner.prefs(context).getString("lang", null) == "fa") "fa" else "en"

    /** Runs a job to its end. Blocking: called by [CrewWorker]. */
    fun run(context: Context, id: String) {
        val job = job(context, id) ?: return
        if (job.status != "RUNNING") return
        try {
            when (job.kind) {
                "INTAKE" -> intake(context, job)
                "GRAPH" -> graphQuestion(context, job)
                else -> weekly(context, job)
            }
        } catch (e: Exception) {
            android.util.Log.w("Navigator", "crew job failed", e)
            job.status = "FAILED"
            job.error = e.message ?: e.javaClass.simpleName
            job.steps.filter { it.status == "RUNNING" || it.status == "WAITING" }.forEach { it.status = "SKIPPED" }
            save(context, job)
            if (job.kind != "WEEKLY") markFailed(context, job.id, job.error ?: "")
        }
    }

    /** The answer's words: its `message` when it ended with the JSON, else the reply as written. */
    private fun prose(text: String): String {
        val o = ProposalValidator.extractJson(text)
        val m = o?.get("message")?.takeIf { it.isJsonPrimitive }?.asString
        if (!m.isNullOrBlank()) return m
        val cut = text.lastIndexOf("```json").takeIf { it > 0 } ?: text.lastIndexOf('{').takeIf { it > 0 && o != null } ?: text.length
        return text.substring(0, cut).trim().ifBlank { text.trim() }
    }

    private fun graphQuestion(context: Context, job: Job) {
        needRequests(context, 1)
        job.stage = "measuring"; save(context, job)
        val base = base(context)
        val brief = Crew.brief(base.g, base.today, base.bands, base.chronos,
            ChronosControl.digest(base.cfg, DatabaseWiring.repository(context).logs(), base.rest, base.g, base.today), language(context))
        val earlier = chat(context).filter { it.jobId != job.id }.map { Crew.Turn(it.who, it.text) }
        val co = step(job, Crew.Role.COORDINATOR)
        co.status = "RUNNING"; job.stage = "coordinator"; save(context, job)
        val answer = ask(context, job, Crew.Role.COORDINATOR,
            Crew.graphQuestion(job.id, brief, Crew.graph(base.g, base.bands), earlier, job.message ?: ""))
        val parsed = ChangeSet.read(base.g, base.today, base.chronos, answer.text)
        co.model = answer.model; co.output = json(answer.text); co.status = "DONE"
        co.summary = parsed.summary() ?: parsed.message()
        save(context, job)
        if (!parsed.ok() || parsed.empty()) {
            finish(context, job, null, (if (parsed.ok()) parsed.message() ?: prose(answer.text) else prose(answer.text)) +
                questions(parsed.questions()) + refusals(parsed))
            return
        }
        val m = measured(context, job, base, parsed)
        val proposalId = file(context, job, base, parsed, m, null, null, 1, emptyList())
        finish(context, job, proposalId, (parsed.message() ?: parsed.summary() ?: "") + questions(parsed.questions()) + refusals(parsed))
    }

    private fun needRequests(context: Context, n: Int) {
        val s = CrewSettings(context)
        val freeRoles = Crew.Role.entries.count { r ->
            LlmSettings(context).profiles.firstOrNull { it.id == s.pick(r)?.profileId }?.preset == "openrouter"
        }
        if (freeRoles > 0 && s.freeLeft < n)
            throw IllegalStateException("Only ${s.freeLeft} free OpenRouter requests are left today; this needs about $n. Try tomorrow.")
    }

    private fun intake(context: Context, job: Job) {
        needRequests(context, 3)
        job.stage = "measuring"; save(context, job)
        val base = base(context)
        val brief = Crew.brief(base.g, base.today, base.bands, base.chronos,
            ChronosControl.digest(base.cfg, DatabaseWiring.repository(context).logs(), base.rest, base.g, base.today), language(context))
        val earlier = chat(context).filter { it.jobId != job.id }.map { Crew.Turn(it.who, it.text) }

        // 1. the architect
        val arch = step(job, Crew.Role.ARCHITECT)
        arch.status = "RUNNING"; job.stage = "architect"; save(context, job)
        var answer = ask(context, job, Crew.Role.ARCHITECT, Crew.architect(job.id, brief, earlier, job.message ?: "", null))
        var parsed = ChangeSet.read(base.g, base.today, base.chronos, answer.text)
        arch.model = answer.model; arch.output = json(answer.text); arch.status = "DONE"
        arch.summary = parsed.summary() ?: parsed.message()
        save(context, job)
        if (!parsed.ok()) throw IllegalStateException("The architect's answer could not be used: ${parsed.failure()}")
        if (parsed.empty()) {
            // Nothing to change yet: it needs to know more, there is nothing to do, or the app refused every action.
            job.steps.filter { it.status == "WAITING" }.forEach { it.status = "SKIPPED" }
            finish(context, job, null, (parsed.message() ?: "") + questions(parsed.questions()) + refusals(parsed))
            return
        }
        var measured = measured(context, job, base, parsed)
        finishTeam(context, job, base, brief, "The student said: " + job.message, parsed, measured, Crew.Role.ARCHITECT) { revision ->
            val a = ask(context, job, Crew.Role.ARCHITECT, Crew.architect(job.id, brief, earlier, job.message ?: "", revision))
            ChangeSet.read(base.g, base.today, base.chronos, a.text).also {
                arch.output = json(a.text); arch.summary = (it.summary() ?: it.message()) + " (revised)"
            }
        }
    }

    private fun weekly(context: Context, job: Job) {
        needRequests(context, 4)
        job.stage = "measuring"; save(context, job)
        val base = base(context)
        val digest = ChronosControl.digest(base.cfg, DatabaseWiring.repository(context).logs(), base.rest, base.g, base.today)
        val brief = Crew.brief(base.g, base.today, base.bands, base.chronos, digest, language(context))

        // 1. the analyst reads the log while the app tries the obvious lever moves
        val an = step(job, Crew.Role.ANALYST)
        an.status = "RUNNING"; job.stage = "analyst"; save(context, job)
        val results = parallel(
            { ask(context, job, Crew.Role.ANALYST, Crew.analyst(job.id, brief)) },
            { candidates(context, base) })
        val findingsAnswer = results[0].getOrNull() as Answer?
        val findings = findingsAnswer?.let { Crew.findings(it.text) } ?: emptyList()
        an.status = if (findingsAnswer != null) "DONE" else "FAILED"
        an.model = findingsAnswer?.model
        an.output = findingsAnswer?.let { json(it.text) }
        an.error = results[0].exceptionOrNull()?.message
        an.summary = findings.joinToString("\n") { "• " + it.text() }
        @Suppress("UNCHECKED_CAST")
        val candidates = (results[1].getOrNull() as JsonArray?) ?: JsonArray()
        save(context, job)
        val findingsJson = gson.toJsonTree(findings).asJsonArray

        // 2. the tuner
        val tu = step(job, Crew.Role.TUNER)
        tu.status = "RUNNING"; job.stage = "tuner"; save(context, job)
        val why = job.message ?: "weekly review"
        val a = ask(context, job, Crew.Role.TUNER, Crew.tuner(job.id, brief, findingsJson, candidates, why, null))
        val parsed = ChangeSet.read(base.g, base.today, base.chronos, a.text)
        tu.model = a.model; tu.output = json(a.text); tu.status = "DONE"; tu.summary = parsed.summary() ?: parsed.message()
        save(context, job)
        if (!parsed.ok()) throw IllegalStateException("The tuner's answer could not be used: ${parsed.failure()}")
        if (parsed.empty()) {
            job.steps.filter { it.status == "WAITING" }.forEach { it.status = "SKIPPED" }
            finish(context, job, null, (parsed.message() ?: "The week's levers stay as they are.") + questions(parsed.questions()))
            return
        }
        val measured = measured(context, job, base, parsed)
        finishTeam(context, job, base, brief, "Weekly review of the levers ($why). The analyst found: " +
            findings.joinToString(" ") { it.text() }, parsed, measured, Crew.Role.TUNER) { revision ->
            val b = ask(context, job, Crew.Role.TUNER, Crew.tuner(job.id, brief, findingsJson, candidates, why, revision))
            ChangeSet.read(base.g, base.today, base.chronos, b.text).also {
                tu.output = json(b.text); tu.summary = (it.summary() ?: it.message()) + " (revised)"
            }
        }
    }

    private fun measured(context: Context, job: Job, base: Base, parsed: ChangeSet.Parsed): Measured {
        job.stage = "measuring"; save(context, job)
        return measure(context, base, parsed.ops())
    }

    /**
     * The common end: the critic (and, for new tasks, the estimator) in
     * parallel, then the coordinator, who may ask once for a revision.
     */
    private fun finishTeam(context: Context, job: Job, base: Base, brief: JsonObject, what: String,
                           first: ChangeSet.Parsed, firstMeasured: Measured, proposer: Crew.Role,
                           revise: (String) -> ChangeSet.Parsed) {
        var parsed = first
        var measured = firstMeasured
        var mayRevise = true
        while (true) {
            val outline = ChangeSet.outline(parsed.ops(), parsed.groups())
            val m = measured.json(base, parsed)
            val newTasks = Crew.newTasks(parsed.ops())
            val cr = step(job, Crew.Role.CRITIC)
            cr.status = "RUNNING"
            val es = if (newTasks.size() > 0) step(job, Crew.Role.ESTIMATOR).also { it.status = "RUNNING" } else null
            job.stage = "critic"; save(context, job)
            val work = mutableListOf<() -> Any>({ ask(context, job, Crew.Role.CRITIC, Crew.critic(job.id, brief, what, outline, m)) })
            if (es != null) work += { ask(context, job, Crew.Role.ESTIMATOR, Crew.estimator(job.id, brief, newTasks)) }
            val got = parallel(*work.toTypedArray())
            val critic = got[0].getOrNull() as Answer?
            val review = critic?.let { Crew.review(it.text) }
            cr.status = if (review != null) "DONE" else "FAILED"; cr.model = critic?.model
            cr.output = critic?.let { json(it.text) }; cr.error = got[0].exceptionOrNull()?.message
            cr.summary = review?.let { r -> (r.verdict()?.let { "[$it] " } ?: "") + r.objections().joinToString("\n") { "${it.severity()}: ${it.text()}" } }
            val estimates = if (es != null) (got[1].getOrNull() as Answer?) else null
            es?.let { s -> s.status = if (estimates != null) "DONE" else "FAILED"; s.model = estimates?.model
                s.output = estimates?.let { json(it.text) }; s.error = got[1].exceptionOrNull()?.message
                s.summary = estimates?.let { Crew.estimates(it.text).joinToString("\n") { e -> "${e.node()}: ${e.p50()}–${e.p90()} h — ${e.why()}" } } }
            save(context, job)

            val outputs = JsonObject().apply {
                add(proposer.name.lowercase(), JsonObject().apply {
                    addProperty("summary", parsed.summary()); addProperty("rationale", parsed.rationale())
                    add("questions", gson.toJsonTree(parsed.questions()))
                })
                job.steps.firstOrNull { it.role == "ANALYST" }?.output?.let { add("analyst", JsonParser.parseString(it)) }
                critic?.let { add("critic", JsonParser.parseString(json(it.text) ?: "{}")) } ?: addProperty("critic", "did not answer")
                estimates?.let { add("estimator", JsonParser.parseString(json(it.text) ?: "{}")) }
            }
            val co = step(job, Crew.Role.COORDINATOR)
            co.status = "RUNNING"; job.stage = "coordinator"; save(context, job)
            val ca = ask(context, job, Crew.Role.COORDINATOR,
                Crew.coordinator(job.id, brief, what, outline, m, outputs, mayRevise))
            val decision = Crew.decision(base.g, base.today, base.chronos, ca.text, mayRevise)
            co.model = ca.model; co.output = json(ca.text)
            if (decision.revise() != null && mayRevise) {
                mayRevise = false
                co.summary = "Asked the ${decision.revise()!!.role().name.lowercase()} to revise: " + decision.revise()!!.instructions()
                co.status = "DONE"; job.stage = proposer.name.lowercase(); save(context, job)
                parsed = revise("The coordinator asks you to revise your change set: " + decision.revise()!!.instructions() +
                    "\nYour previous change set:\n" + ChangeSet.describe(parsed.ops(), parsed.groups()) +
                    "\nIts measured effect:\n" + m)
                if (!parsed.ok() || parsed.empty()) {
                    job.steps.filter { it.status == "WAITING" || it.status == "RUNNING" }.forEach { it.status = "SKIPPED" }
                    finish(context, job, null, (parsed.message() ?: parsed.failure() ?: "") + questions(parsed.questions()))
                    return
                }
                measured = measured(context, job, base, parsed)
                continue
            }
            // Changes the coordinator kept by name come from the proposal; an unreadable decision keeps the proposal.
            val final = Crew.reconcile(parsed, decision.set(), ca.text)
            if (!decision.set().ok()) co.error = "Its answer could not be used (${decision.set().failure()}); the proposal is shown as it was"
            co.status = "DONE"; co.summary = final.message() ?: final.summary() ?: co.error
            save(context, job)
            if (final.empty()) {
                finish(context, job, null, (final.message() ?: "Nothing to change.") + questions(final.questions()) + refusals(final))
                return
            }
            val finalMeasured = if (sameOps(final.ops(), parsed.ops())) measured else measured(context, job, base, final)
            // What the app refused along the way (the proposer's changes too), so you see it beside what is left.
            val refused = (first.rejected() + parsed.rejected() + final.rejected()).distinct()
            val shown = ChangeSet.Parsed(final.failure(), final.summary(), final.rationale(), final.message(),
                final.ops(), final.groups(), refused, final.questions())
            val proposalId = file(context, job, base, shown, finalMeasured, decision, null, 1, emptyList())
            finish(context, job, proposalId, (final.message() ?: final.summary() ?: "") + questions(final.questions()))
            return
        }
    }

    private fun sameOps(a: List<Op>, b: List<Op>) = gson.toJson(a) == gson.toJson(b)

    private fun questions(q: List<String>) = if (q.isEmpty()) "" else "\n\n" + q.joinToString("\n") { "? $it" }

    /** What the app refused, said in the conversation when nothing was left to file. */
    private fun refusals(p: ChangeSet.Parsed) = if (p.rejected().isEmpty()) "" else
        "\n\nThe app could not use these changes:\n" + p.rejected().take(6).joinToString("\n") { "✗ " + it.change() + ": " + it.reason() }

    private fun json(text: String): String? = ProposalValidator.extractJson(text)?.toString()

    private fun finish(context: Context, job: Job, proposalId: String?, reply: String) {
        job.status = if (proposalId != null) "DONE" else "ANSWERED"
        job.stage = null
        job.proposalId = proposalId
        job.reply = reply.trim()
        save(context, job)
        if (job.kind != "WEEKLY" || proposalId != null)
            say(context, ChatTurn("navigator", job.reply ?: "", System.currentTimeMillis(), job.id, proposalId))
    }

    /** Files the package in the inbox; returns its id. */
    private fun file(context: Context, job: Job, base: Base, set: ChangeSet.Parsed, measured: Measured, decision: Crew.Decision?,
                     parentId: String?, version: Int, thread: List<Discuss.Turn>, roles: JsonArray? = null): String {
        val inbox = NavProposals(DatabaseWiring.navigator(context).dao())
        val target = JsonObject().apply {
            addProperty("kind", "CREW"); addProperty("subject", job.id)
            addProperty("trigger", when (job.kind) { "WEEKLY" -> "weekly_review"; "HABITS" -> "your_log"; else -> "you" })
        }
        val req = LlmRequest(Crew.TASK, target, "", "", false)
        val effects = measured.verdict.moves().filter { !it.before().p().isNaN() }
            .map { ProposalValidator.Effect(it.nodeId(), it.before().p(), it.after().p()) }
        val result = ProposalValidator.Result(true, set.ops(), set.summary(), set.rationale(), emptyList(), null,
            listOf("shape", "references", "consent", "ownership", "numbers", "measured"),
            when (job.kind) { "WEEKLY" -> "Weekly review"; "HABITS" -> "From your log"; else -> (job.message ?: "").take(60) }, effects)
        val crew = JsonObject().apply {
            addProperty("job", job.id)
            addProperty("kind", job.kind)
            job.message?.let { addProperty("message", it) }
            set.message()?.let { addProperty("reply", it) }
            add("groups", gson.toJsonTree(set.groups()))
            add("rejected", gson.toJsonTree(set.rejected()))
            add("questions", gson.toJsonTree(set.questions()))
            add("odds", Crew.odds(measured.verdict))
            add("week_now", base.week.json())
            add("week_with_it", measured.week.json())
            if (measured.locks.isNotEmpty()) add("locks", gson.toJsonTree(measured.locks))
            add("roles", roles ?: JsonArray().apply {
                val order = listOf("ANALYST", "ARCHITECT", "TUNER", "ESTIMATOR", "CRITIC", "COORDINATOR")
                job.steps.filter { it.status == "DONE" || it.status == "FAILED" }.sortedBy { order.indexOf(it.role) }.forEach { s ->
                    add(JsonObject().apply {
                        addProperty("role", s.role); s.model?.let { addProperty("model", it) }
                        s.summary?.let { addProperty("said", it) }; s.error?.let { addProperty("error", it) }
                        addProperty("status", s.status)
                    })
                }
            })
            decision?.let { add("responses", gson.toJsonTree(it.responses())) }
        }
        val callId = inbox.calls().firstOrNull { it.task == Crew.task(job.id, Crew.Role.COORDINATOR) && it.status == "DONE" }?.id
        return inbox.file(callId, req, result, parentId, version, thread, crew).id
    }

    // ------------------------------------------------------- candidates

    /**
     * The lever moves the tuner may choose from, each tried by the app on a
     * copy: more and less pressure, sleep half an hour earlier and later, and
     * a rest day on the week's most tiring day.
     */
    fun candidates(context: Context, base: Base): JsonArray {
        val out = JsonArray()
        val lev = base.chronos.getAsJsonObject("levers")
        val moves = mutableListOf<Pair<String, String>>()
        lev?.get("slack_ratio")?.asDouble?.let { s ->
            if (s - 0.05 >= 0.05) moves += "More pressure: 5 points less unscheduled time" to """{"type":"pressure","slack_ratio":${"%.2f".format(java.util.Locale.ROOT, s - 0.05)}}"""
            if (s + 0.05 <= 0.5) moves += "Less pressure: 5 points more unscheduled time" to """{"type":"pressure","slack_ratio":${"%.2f".format(java.util.Locale.ROOT, s + 0.05)}}"""
        }
        val bed = ChangeSet.clock(lev?.get("bed")?.asString); val wake = ChangeSet.clock(lev?.get("wake")?.asString)
        if (bed != null && wake != null) {
            moves += "Sleep 30 minutes earlier" to """{"type":"sleep","bed":"${ChangeSet.hhmm(bed - 30)}","wake":"${ChangeSet.hhmm(wake - 30)}"}"""
            moves += "Sleep 30 minutes later" to """{"type":"sleep","bed":"${ChangeSet.hhmm(bed + 30)}","wake":"${ChangeSet.hhmm(wake + 30)}"}"""
        }
        base.week.days.filter { !it.rest && it.date.isAfter(base.today) }.maxByOrNull { it.peakMental + it.peakPhysical }?.let { d ->
            moves += "A rest day on ${d.date}, the most tiring day planned" to """{"type":"rest_day","date":"${d.date}","rest":true}"""
        }
        // What the log says about hours, days, lengths and sleep, each tried like the lever moves.
        com.chronos.engine.Habits.find(base.cfg, DatabaseWiring.repository(context).logs(), base.today).take(4).forEach { f ->
            moves += (f.title() + " — " + f.why()) to f.action()
        }
        for ((title, action) in moves) {
            val text = """{"changes":[{"title":"$title","actions":[$action]}],"consent_self_check":{"targets_other_person":false}}"""
            val parsed = ChangeSet.read(base.g, base.today, base.chronos, text)
            if (parsed.groups().isEmpty()) continue
            val m = runCatching { measure(context, base, parsed.ops(), max(10, base.worlds / 2), max(20, base.futures * 3 / 4)) }.getOrNull() ?: continue
            out.add(JsonObject().apply {
                addProperty("move", title)
                add("actions", JsonParser.parseString("[$action]"))
                add("week_with_it", m.week.json().apply { remove("days") })
                add("odds", Crew.odds(m.verdict))
            })
        }
        return out
    }

    // ------------------------------------------------------- discussion

    /**
     * You said something about a crew package. The coordinator answers with
     * what it decided and why in front of it; a revision is checked and
     * measured like the first answer and filed as the next version.
     */
    fun discuss(context: Context, proposalId: String, message: String): String {
        val store = DatabaseWiring.navigator(context)
        val inbox = NavProposals(store.dao())
        val v = inbox.view(proposalId) ?: throw IllegalStateException("That proposal is gone.")
        val crew = v.crew() ?: throw IllegalStateException("Not a crew proposal.")
        val job = job(context, crew.get("job").asString) ?: throw IllegalStateException("The job behind this proposal is gone.")
        if (!LlmClient.online(context)) throw IllegalStateException("No network: a conversation needs it.")
        val base = base(context)
        val brief = Crew.brief(base.g, base.today, base.bands, base.chronos, null, language(context))
        val groups: List<ChangeSet.Group> = gson.fromJson(crew.get("groups"), object : TypeToken<List<ChangeSet.Group>>() {}.type)
        val outline = ChangeSet.outline(v.ops(), groups)
        val measured = JsonObject().apply { add("odds", crew.get("odds")); add("week_now", crew.get("week_now")); add("week_with_it", crew.get("week_with_it")) }
        val roles = JsonObject().apply { add("what_each_said", crew.get("roles")) }
        val original = Crew.coordinator(job.id, brief, when (job.kind) { "WEEKLY" -> "Weekly review"; "HABITS" -> "Changes the app read in the student's log (hours, days, lengths, sleep)"; else -> "The student said: " + job.message },
            outline, measured, roles, false)
        val req = Discuss.request(original, ChangeSet.describe(v.ops(), groups), v.thread(), message)
        val answer = ask(context, job, Crew.Role.COORDINATOR, req, needsJson = false)
        val read = Discuss.read(answer.text) ?: throw IllegalStateException("The model's answer could not be read.")
        val turns = v.thread() + Discuss.Turn("you", message) + Discuss.Turn("model", read.reply())
        save(context, job)
        val revised = read.revised()
        if (read.obsolete() && v.status() == "PENDING") {
            inbox.setThread(v.id(), turns)
            inbox.markOutdated(v.id(), "The model: " + read.reply())
            return "The model says it no longer helps; it left the inbox."
        }
        if (revised == null || read.involvesOtherPerson() || v.status() != "PENDING") {
            inbox.setThread(v.id(), turns)
            return "The model answered."
        }
        // A revision that names a change of the current version without its actions keeps that change.
        val current = ChangeSet.Parsed(null, v.summary(), v.rationale(), null, v.ops(), groups, emptyList(), emptyList())
        val decided = ChangeSet.read(base.g, base.today, base.chronos, revised)
        val parsed = if (decided.ok()) Crew.reconcile(current, decided, revised.toString()) else decided
        if (!parsed.ok() || parsed.empty()) {
            val why = parsed.failure() ?: parsed.rejected().firstOrNull()?.reason() ?: "it changes nothing"
            inbox.setThread(v.id(), turns + Discuss.Turn("model", "(The app could not accept the revision: $why.)"))
            return "The model's revision did not pass the checks: $why"
        }
        val m = measure(context, base, parsed.ops())
        file(context, job, base, parsed, m, null, v.id(), v.version() + 1, turns, crew.getAsJsonArray("roles"))
        inbox.markRevised(v.id())
        return "The model revised its proposal: version ${v.version() + 1} is waiting."
    }
}

/** Runs one crew job in the background, once the network is there. */
class CrewWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val id = inputData.getString("job") ?: return Result.success()
        CrewRun.run(applicationContext, id)
        return Result.success()
    }
}
