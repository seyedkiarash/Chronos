package com.chronos.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.chronos.data.ChronosRepository
import com.chronos.data.DatabaseWiring
import com.chronos.engine.Slots
import com.chronos.model.Config
import com.chronos.model.Enums
import com.chronos.model.LogEntry
import com.chronos.navigator.llm.LlmRequest
import com.chronos.navigator.llm.ProposalValidator
import com.chronos.navigator.llm.client.KeyVault
import com.chronos.navigator.llm.client.LlmClient
import com.chronos.navigator.llm.client.LlmSettings
import com.chronos.navigator.llm.client.NavAsk
import com.chronos.navigator.store.NavProposals
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * A one-line answer to a reminder ("read convex from 10 to 12, tired"), read by
 * the model into log entries. Nothing is saved until you confirm the entries in
 * the Log; when something essential is unclear the model asks one question, and
 * your answer goes back to it with the conversation so far.
 */
object QuickLog {
    /** One answer and what became of it. status: WAITING (to be read), READ (entries to confirm), FAILED, DONE. */
    data class Draft(val id: String, val day: String, val at: Long, val turns: List<Turn>, val status: String,
                     val entries: List<Entry> = emptyList(), val reply: String? = null, val question: String? = null,
                     val error: String? = null)

    data class Turn(val who: String, val text: String)

    /** One entry as the model read it, before it becomes a [LogEntry]. */
    data class Entry(val kind: String, val activity: String? = null, val start: String? = null, val minutes: Int? = null,
                     val donePercent: Double? = null, val units: Double? = null, val focus: Int? = null,
                     val mind: Int? = null, val body: Int? = null, val restored: Int? = null,
                     val hour: Int? = null, val rating: Int? = null)

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private fun file(context: Context) = File(context.filesDir, "quick-log.json")

    @Volatile var onChange: (() -> Unit)? = null

    fun drafts(context: Context): List<Draft> = file(context).takeIf { it.exists() }?.let { f ->
        runCatching { gson.fromJson<List<Draft>>(f.readText(), object : TypeToken<List<Draft>>() {}.type) }.getOrNull()
    }.orEmpty()

    @Synchronized private fun save(context: Context, list: List<Draft>) {
        // A week of answers is plenty; older ones are of no use for confirming anything.
        val keep = list.sortedByDescending { it.at }.take(30)
        file(context).writeText(gson.toJson(keep))
        onChange?.invoke()
    }

    @Synchronized private fun put(context: Context, d: Draft) = save(context, drafts(context).filter { it.id != d.id } + d)

    @Synchronized fun drop(context: Context, id: String) = save(context, drafts(context).filter { it.id != id })

    fun markDone(context: Context, id: String) { drafts(context).firstOrNull { it.id == id }?.let { put(context, it.copy(status = "DONE")) } }

    /** An answer from the notification or the Log: kept at once, read by the model when it can be. */
    fun receive(context: Context, text: String, draftId: String? = null) {
        val old = draftId?.let { id -> drafts(context).firstOrNull { it.id == id } }
        val d = old?.copy(turns = old.turns + Turn("you", text), status = "WAITING", error = null, question = null)
            ?: Draft(java.util.UUID.randomUUID().toString(), ChronosRepository.logicalToday().toString(),
                System.currentTimeMillis(), listOf(Turn("you", text)), "WAITING")
        put(context, d)
        if (!Reminders.viaModel(context)) return
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<QuickLogWorker>()
            .setInputData(workDataOf("draft" to d.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }

    // ------------------------------------------------------------ reading

    private const val SYSTEM = """You turn a student's short message about their day into entries for their planning app's log.
Use only the activity names in the list, exactly as written. Times are today's, never after now. If they say they did a planned block, use its planned start.
Never invent a rating they did not express: leave it null. Ratings are 1 to 5. "mind" and "body" are how tired afterwards (1 fresh, 5 spent); "focus" how absorbed (1 scattered, 5 absorbed); "restored" for upkeep such as meals and walks (1 no change, 5 fully reset); an energy rating is how sharp they felt that hour (1 foggy, 5 razor sharp).
If something essential is unclear (which activity, or roughly when), ask one short question instead of guessing; entries you are sure of can still be given.
Answer with exactly one JSON object and nothing else:
{"reply":"one short sentence in the student's language","question":null,"entries":[{"kind":"session","activity":"…","start":"HH:MM","minutes":90,"done_percent":null,"units":null,"focus":null,"mind":null,"body":null,"restored":null},{"kind":"energy","hour":14,"rating":3}]}"""

    private fun prompt(cfg: Config, day: LocalDate, blocks: List<com.chronos.model.Block>, logs: List<LogEntry>, d: Draft): String = buildString {
        val now = LocalTime.now()
        append("Now: ").append(day).append(' ').append("%02d:%02d".format(now.hour, now.minute)).append('\n')
        append("Activities: ").append(cfg.activities.filter { !it.archived && !it.nap }.mapNotNull { it.name }.joinToString(" | ")).append('\n')
        if (blocks.isNotEmpty()) {
            append("Today's plan:\n")
            blocks.filter { it.start >= 0 && it.act != null && !it.fixed }.forEach { b ->
                append("- ").append(Slots.format(b.start)).append('–').append(Slots.format(b.start + b.dur)).append(' ').append(b.act.name).append('\n')
            }
        }
        val already = logs.filter { it.date == day && it.kind == Enums.LogKind.SESSION }
        if (already.isNotEmpty()) {
            append("Already logged today:\n")
            already.forEach { l -> append("- ").append(l.at ?: "?").append(' ').append(cfg.activity(l.actId)?.name ?: "?")
                .append(' ').append(l.actual ?: 0).append(" min\n") }
        }
        append("\nThe conversation:\n")
        d.turns.forEach { t -> append(if (t.who == "you") "Student: " else "You: ").append(t.text).append('\n') }
    }

    /** Sends a waiting answer to the model and files what it read. Blocking: the worker calls it. */
    fun read(context: Context, id: String) {
        val d = drafts(context).firstOrNull { it.id == id } ?: return
        if (d.status != "WAITING") return
        val fa = AutoPlanner.prefs(context).getString("lang", null) == "fa"
        fun fail(why: String) {
            put(context, d.copy(status = "FAILED", error = why))
            Reminders.status(context, if (fa) "پاسخت نگه داشته شد؛ در لاگ کاملش کن. ($why)" else "Your answer is kept; finish it in the Log. ($why)")
        }
        val llm = LlmSettings(context)
        val profile = llm.active ?: return fail("No model is set up")
        val key = KeyVault.load(context, profile.id) ?: return fail("No API key for ${profile.label}")
        val repo = DatabaseWiring.repository(context)
        val cfg = repo.loadConfig()
        val day = LocalDate.parse(d.day)
        val blocks = runCatching { repo.dayPlan(cfg, repo.storedPlan(cfg), day)?.blocks().orEmpty() }.getOrDefault(emptyList())
        val logs = repo.logs()
        val target = JsonObject().apply { addProperty("kind", "QUICK_LOG"); addProperty("subject", d.id) }
        val req = LlmClient.adapt(profile, LlmRequest("QUICK_LOG|" + d.id, target, SYSTEM, prompt(cfg, day, blocks, logs, d), false))
        NavAsk.refusal(context, profile, req)?.let { return fail(it) }
        val inbox = NavProposals(DatabaseWiring.navigator(context).dao())
        val call = inbox.queue("QUICK_LOG|" + d.id, "quick_log@" + profile.id, profile.label + " · " + profile.model)
        call.status = "SENT"; inbox.put(call)
        val reply = try { LlmClient.send(profile, key, req) } catch (e: Exception) {
            call.status = "FAILED"; call.error = e.message ?: e.javaClass.simpleName; call.doneAt = System.currentTimeMillis(); inbox.put(call)
            return fail(call.error ?: "no answer")
        }
        call.model = profile.label + " · " + reply.model
        call.tokensIn = reply.tokensIn.toInt(); call.tokensOut = reply.tokensOut.toInt()
        call.costUsd = LlmClient.costUsd(profile, reply); call.doneAt = System.currentTimeMillis(); call.status = "DONE"
        inbox.put(call)

        val o = ProposalValidator.extractJson(reply.text) ?: return fail(if (fa) "پاسخ مدل خوانا نبود" else "The model's answer could not be read")
        val entries = o.getAsJsonArray("entries")?.mapNotNull { e ->
            if (!e.isJsonObject) return@mapNotNull null
            val x = e.asJsonObject
            fun s(k: String) = x.get(k)?.takeIf { it.isJsonPrimitive }?.asString
            fun n(k: String) = x.get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
            fun r(k: String) = n(k)?.toInt()?.takeIf { it in 1..5 }
            Entry(s("kind") ?: return@mapNotNull null, s("activity"), s("start"), n("minutes")?.toInt(), n("done_percent"),
                n("units"), r("focus"), r("mind"), r("body"), r("restored"), n("hour")?.toInt(), r("rating"))
        }.orEmpty().filter { usable(cfg, it) }
        fun str(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString
        val question = str("question")?.takeIf { it.isNotBlank() && it != "null" }
        val said = str("reply")
        val turns = d.turns + listOfNotNull((question ?: said)?.let { Turn("model", it) })
        put(context, d.copy(turns = turns, status = "READ", entries = entries, reply = said, question = question))
        Reminders.status(context, when {
            question != null -> (if (fa) "یک سؤال: " else "One question: ") + question
            fa -> "${entries.size} مورد از پاسخت خوانده شد؛ برای تأیید بزن."
            else -> "${entries.size} entries read from your answer; tap to confirm."
        })
    }

    /** Whether an entry names what it needs: a known activity and a time, or an hour and a rating. */
    private fun usable(cfg: Config, e: Entry): Boolean = when (e.kind) {
        "session" -> activityOf(cfg, e.activity) != null && e.start != null &&
            runCatching { Slots.parse(e.start) }.isSuccess && (e.minutes ?: 0) > 0
        "energy" -> e.hour in 0..23 && e.rating != null
        else -> false
    }

    private fun activityOf(cfg: Config, name: String?) =
        name?.trim()?.lowercase()?.let { n -> cfg.activities.firstOrNull { !it.archived && it.name?.trim()?.lowercase() == n } }

    /**
     * The entries as the Log stores them. A session close to a planned block of the same
     * activity fills that block's box; anything else is something done outside the plan.
     */
    fun toLogs(cfg: Config, day: LocalDate, blocks: List<com.chronos.model.Block>, entries: List<Entry>): List<LogEntry> =
        entries.mapNotNull { e ->
            when (e.kind) {
                "session" -> {
                    val act = activityOf(cfg, e.activity) ?: return@mapNotNull null
                    val start = Slots.parse(e.start!!)
                    val block = blocks.filter { it.act?.id == act.id && it.start >= 0 }
                        .minByOrNull { Math.abs(it.start - start) }?.takeIf { Math.abs(it.start - start) * Slots.SLOT_MIN <= 45 }
                    LogEntry().apply {
                        id = if (block != null) "session|$day|${act.id}|${block.start}" else "extra|$day|" + java.util.UUID.randomUUID()
                        kind = Enums.LogKind.SESSION
                        date = day
                        ts = System.currentTimeMillis()
                        actId = act.id
                        at = Slots.format(start)
                        actual = e.minutes!!.coerceAtLeast(1)
                        planned = block?.let { it.dur * Slots.SLOT_MIN } ?: actual
                        tags = emptyList()
                        if (act.kind == Enums.ActivityKind.GOAL) {
                            attain = e.donePercent ?: 100.0
                            focus = e.focus
                            mentalAfter = e.mind
                            units = e.units
                        } else restored = e.restored
                        physicalAfter = e.body
                    }
                }
                "energy" -> LogEntry().apply {
                    id = "energy|$day|${e.hour}"
                    kind = Enums.LogKind.CHECKIN
                    date = day
                    at = "%02d:00".format(e.hour)
                    ts = System.currentTimeMillis()
                    energy = e.rating
                    tags = emptyList()
                }
                else -> null
            }
        }
}

/** The notification's quick answer. */
class QuickReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Reminders.REPLY_KEY)?.toString()?.trim()
        if (text.isNullOrEmpty()) return
        val pending = goAsync()
        Thread {
            try {
                QuickLog.receive(context, text)
                val fa = AutoPlanner.prefs(context).getString("lang", null) == "fa"
                // The notification must change, or Android keeps showing the reply as being sent.
                Reminders.status(context, if (!Reminders.viaModel(context))
                    (if (fa) "پاسخت نگه داشته شد؛ در لاگ کاملش کن." else "Your answer is kept; finish it in the Log.")
                    else if (fa) "پاسخت رسید؛ در حال خواندن…" else "Got it; reading your answer…")
            } finally { pending.finish() }
        }.start()
    }
}

class QuickLogWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val id = inputData.getString("draft") ?: return Result.success()
        runCatching { QuickLog.read(applicationContext, id) }
        return Result.success()
    }
}
