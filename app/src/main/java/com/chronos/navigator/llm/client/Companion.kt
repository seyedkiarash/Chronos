package com.chronos.navigator.llm.client

import android.content.Context
import com.chronos.data.ChronosRepository
import com.chronos.data.DatabaseWiring
import com.chronos.model.Enums
import com.chronos.navigator.llm.ProposalValidator
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.time.LocalDate

/**
 * The day's conversation and its journal (your idea of 2 Nov 2026, built 3 Nov): in the evening — or
 * whenever you like — the Navigator asks how the day went, and talks with you about how you felt, what
 * happened, what you thought and what you want, the way a companion would. Each day is kept on this
 * phone in its own file. When you wrap the day up, its signals — mood and pressure as you set them,
 * what happened, ideas, worries, new constraints — are written down; the crew sees only those
 * summaries, never the conversation, and anything meant to change your plans goes to the Navigator
 * only when you send it, as a message you can read first.
 */
object Companion {
    data class Turn(val who: String, val text: String, val at: Long,
                    /** The app's own fixed note when something said sounds like distress. */
                    val safety: Boolean = false,
                    /** Why your message got no answer (it stays, to send again). */
                    val failed: String? = null)

    data class Signals(val summary: String? = null, val events: List<String>? = null, val feelings: List<String>? = null,
                       val ideas: List<String>? = null, val worries: List<String>? = null,
                       val constraints: List<String>? = null, val goalNotes: List<String>? = null,
                       val forNavigator: String? = null)

    data class Day(val date: String, val turns: List<Turn> = emptyList(), val mood: Int? = null, val stress: Int? = null,
                   val signals: Signals? = null, val sentToNavigator: Boolean = false)

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    private fun dir(context: Context) = File(context.filesDir, "navigator-journal").apply { mkdirs() }
    private fun file(context: Context, day: LocalDate) = File(dir(context), "$day.json")

    fun read(context: Context, day: LocalDate): Day =
        file(context, day).takeIf { it.exists() }?.let { runCatching { gson.fromJson(it.readText(), Day::class.java) }.getOrNull() }
            ?.let { it.copy(turns = it.turns ?: emptyList()) } ?: Day(day.toString())

    @Synchronized fun write(context: Context, d: Day) { file(context, LocalDate.parse(d.date)).writeText(gson.toJson(d)) }

    /** The days kept, the latest first. */
    fun days(context: Context, limit: Int = 30): List<Day> = dir(context).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .sortedByDescending { it.name }.take(limit)
        .mapNotNull { f -> runCatching { gson.fromJson(f.readText(), Day::class.java) }.getOrNull() }
        .map { it.copy(turns = it.turns ?: emptyList()) }

    private fun persian(context: Context) = com.chronos.auto.AutoPlanner.prefs(context).getString("lang", null) == "fa"

    // ---------------------------------------------------------------- the day in brief

    /** What the day held, from the log: hours per activity, the energy ratings, the morning check. */
    fun dayFacts(context: Context, day: LocalDate): String {
        val repo = DatabaseWiring.repository(context)
        val cfg = repo.loadConfig()
        val logs = repo.logs().filter { it.date == day }
        val sessions = logs.filter { it.kind == Enums.LogKind.SESSION && (it.actual ?: 0) > 0 }
        val byAct = sessions.groupBy { cfg.activity(it.actId)?.name ?: "?" }
            .mapValues { (_, ls) -> ls.sumOf { it.actual ?: 0 } / 60.0 }.toList().sortedByDescending { it.second }
        val energy = logs.filter { it.kind == Enums.LogKind.CHECKIN && it.id.startsWith("energy|") }.mapNotNull { it.energy }
        val morning = logs.firstOrNull { it.id == "morning|$day" }
        return buildString {
            append("Date: $day\n")
            if (byAct.isEmpty()) append("Logged work: none yet\n")
            else append("Logged work: " + byAct.joinToString(", ") { "${it.first} ${"%.1f".format(it.second)} h" } + "\n")
            if (energy.isNotEmpty()) append("Energy through the day (1 foggy – 5 sharp): average ${"%.1f".format(energy.average())} over ${energy.size} hours\n")
            morning?.energy?.let { append("Morning check: energy $it/5\n") }
            val tags = logs.flatMap { it.tags.orEmpty() }.distinct().take(12)
            if (tags.isNotEmpty()) append("Tags in the log: " + tags.joinToString(", ") + "\n")
            // The day against its plan and the week around it, so a talk about the day can see both.
            val planned = logs.filter { it.kind == Enums.LogKind.SESSION }.sumOf { it.planned ?: 0 } / 60.0
            val skipped = logs.count { it.kind == Enums.LogKind.SESSION && it.at == null }
            if (planned > 0) append("Of the blocks logged today: ${"%.1f".format(planned)} h were planned, $skipped skipped\n")
            val week = repo.logs().filter { it.kind == Enums.LogKind.SESSION && it.date != null && !it.date.isAfter(day) && it.date.isAfter(day.minusDays(7)) && (it.actual ?: 0) > 0 }
            val goalWeek = week.filter { cfg.activity(it.actId)?.goalId != null }
            if (goalWeek.isNotEmpty()) append("Last seven days: ${"%.1f".format(goalWeek.sumOf { it.actual ?: 0 } / 60.0)} h of goal work: " +
                goalWeek.groupBy { cfg.activity(it.actId)?.name?.trim() ?: "?" }.mapValues { (_, l) -> l.sumOf { it.actual ?: 0 } / 60.0 }
                    .toList().sortedByDescending { it.second }.take(6).joinToString(", ") { "${it.first} ${"%.1f".format(it.second)} h" } + "\n")
        }
    }

    /** The first words, from the app, so the conversation starts at once and offline. */
    fun opening(context: Context, day: LocalDate): String {
        val repo = DatabaseWiring.repository(context)
        val cfg = repo.loadConfig()
        val sessions = repo.logs().filter { it.date == day && it.kind == Enums.LogKind.SESSION && (it.actual ?: 0) > 0 }
        val hours = sessions.sumOf { it.actual ?: 0 } / 60.0
        val top = sessions.groupBy { cfg.activity(it.actId)?.name ?: "" }.mapValues { (_, l) -> l.sumOf { it.actual ?: 0 } }
            .entries.sortedByDescending { it.value }.take(3).map { it.key }.filter { it.isNotBlank() }
        val fa = persian(context)
        return if (fa) {
            (if (sessions.isEmpty()) "سلام. امروز هنوز کاری در لاگ ثبت نشده. "
             else "سلام. امروز حدود ${"%.1f".format(hours)} ساعت کار ثبت شده" + (if (top.isNotEmpty()) "، بیشترش " + top.joinToString("، ") else "") + ". ") +
                "روزت چطور گذشت؟ از هر جا دوست داری شروع کن — حالت، اتفاقی که افتاد، فکری که توی ذهنت است، یا چیزی که می‌خواهی."
        } else {
            (if (sessions.isEmpty()) "Hi. Nothing is logged for today yet. "
             else "Hi. About ${"%.1f".format(hours)} hours of work are logged today" + (if (top.isNotEmpty()) ", mostly " + top.joinToString(", ") else "") + ". ") +
                "How did the day go? Start wherever you like — how you feel, something that happened, a thought on your mind, or something you want."
        }
    }

    /** The last week's wrapped-up days, as summaries only: what the companion and the crew may know of them. */
    fun recent(context: Context, before: LocalDate, days: Int = 7): JsonArray = JsonArray().apply {
        days(context, 40).filter { val d = LocalDate.parse(it.date); d.isBefore(before) && !d.isBefore(before.minusDays(days.toLong())) }
            .filter { it.signals != null || it.mood != null || it.stress != null }
            .sortedBy { it.date }.forEach { d ->
                add(JsonObject().apply {
                    addProperty("date", d.date)
                    d.mood?.let { addProperty("mood_1_to_5", it) }
                    d.stress?.let { addProperty("pressure_1_to_5", it) }
                    d.signals?.summary?.let { addProperty("summary", it) }
                    d.signals?.worries?.takeIf { it.isNotEmpty() }?.let { add("worries", gson.toJsonTree(it)) }
                    d.signals?.constraints?.takeIf { it.isNotEmpty() }?.let { add("constraints", gson.toJsonTree(it)) }
                })
            }
    }

    /** The crew's picture of you, with the last week's reflections in it (summaries only). */
    fun addTo(context: Context, brief: JsonObject) {
        val r = runCatching { recent(context, ChronosRepository.logicalToday().plusDays(1)) }.getOrNull() ?: return
        if (r.size() > 0) brief.add("recent_reflections", r)
    }

    // ---------------------------------------------------------------- talking

    // Half-space, space or nothing between the parts of a word: written all three ways.
    private const val J = "[\u200c ]?"
    private val DISTRESS = Regex(
        "خودکشی|خود(م|مو)${J}(را|رو)? ?بکشم|نمی${J}خوا(ه)?م زنده|آسیب به خودم|به خودم آسیب|دیگه نمی${J}کشم|دیگر نمی${J}کشم|" +
        "suicid|kill myself|end my life|self.?harm|hurt myself|don'?t want to (live|be alive)",
        RegexOption.IGNORE_CASE)

    fun distress(text: String) = DISTRESS.containsMatchIn(text)

    fun safetyNote(fa: Boolean) = if (fa)
        "اگر حالت خیلی بد است یا فکر آسیب زدن به خودت را داری، لطفاً همین حالا با کسی که به او اعتماد داری حرف بزن، " +
            "یا تماس بگیر: صدای مشاور بهزیستی ۱۴۸۰، اورژانس اجتماعی ۱۲۳، و در خطر فوری اورژانس ۱۱۵. " +
            "این برنامه جای کمک تخصصی نیست، ولی تو تنها نیستی."
    else "If you feel very low or are thinking of hurting yourself, please talk now to someone you trust, or call your local " +
        "emergency number or a crisis line. This app is no substitute for professional help — but you are not alone."

    private const val SYSTEM = "You are the Navigator's companion side: in a short daily conversation you talk with a " +
        "university student about their day — how they felt, what happened, what they thought, what they want — the way " +
        "a caring, curious friend who also knows their goals would. Rules: answer in 1 to 4 sentences; reflect what you " +
        "heard before anything else; ask at most one open question at a time; be warm and honest, never preachy; link to " +
        "their goals only when it helps, and never push work when they are tired or upset. You are not a therapist and do " +
        "not diagnose. If they mention hurting themselves or not wanting to live, respond with care, urge them to reach " +
        "someone they trust and professional help now (for a student writing in Persian in Iran: Welfare counselling line 1480, " +
        "social emergency 123, emergency 115; anyone else: their local emergency number or a crisis line), and leave planning aside. Do not propose changes to their plans yourself: if they decide " +
        "something, say it will be in the day's summary they can send to the Navigator. Write in the student's language " +
        "(fa = Persian, en = English), plain text, no lists or JSON."

    private fun transcript(turns: List<Turn>) = turns.filter { !it.safety }.joinToString("\n") {
        (if (it.who == "you") "Student: " else "You: ") + it.text
    }

    private fun context(context: Context, day: LocalDate): String {
        val lang = if (persian(context)) "fa" else "en"
        val week = recent(context, day)
        return "Student language: $lang\n\nToday, from their log (the app's summary):\n" + dayFacts(context, day) +
            "\nTheir goals now, with the chance the app's forecast gives each:\n" + Quick.goals(context) +
            paths(context) +
            (if (week.size() > 0) "\n\nThe last days, as they summed them up:\n" + week.toString() else "")
    }

    /** The main paths, private ones left out, so it can see what a day's choices serve. */
    private fun paths(context: Context): String {
        val g = DatabaseWiring.navigator(context).load() ?: return ""
        val hidden = com.chronos.navigator.llm.EstimatePrior.privateNodes(g)
        val lines = com.chronos.navigator.model.Paths.of(g).filter { it.id() != com.chronos.navigator.model.Paths.OTHER && it.goal() !in hidden }
            .joinToString("\n") { "- " + it.title() }
        return if (lines.isBlank()) "" else "\n\nTheir main paths:\n$lines"
    }

    /** Opens the day's conversation with the app's own first words, once. */
    fun open(context: Context, day: LocalDate): Day {
        val d = read(context, day)
        if (d.turns.isNotEmpty()) return d
        return d.copy(turns = listOf(Turn("model", opening(context, day), System.currentTimeMillis()))).also { write(context, it) }
    }

    /** You said something: it is kept first, then answered. Throws only when no model answered; your words stay. */
    fun say(context: Context, day: LocalDate, text: String): Day {
        var d = open(context, day)
        val mine = Turn("you", text.trim(), System.currentTimeMillis())
        d = d.copy(turns = d.turns + mine)
        if (distress(text)) d = d.copy(turns = d.turns + Turn("app", safetyNote(persian(context)), System.currentTimeMillis(), safety = true))
        write(context, d)
        return answer(context, day)
    }

    /** The model's answer to the conversation as it stands (also: your last message sent again). */
    fun answer(context: Context, day: LocalDate): Day {
        var d = read(context, day)
        val last = d.turns.lastOrNull { it.who == "you" } ?: return d
        val user = context(context, day) + "\n\nThe conversation so far:\n" + transcript(d.turns) + "\n\nYour reply:"
        return try {
            val said = Quick.ask(context, SYSTEM, user, "COMPANION|$day", "companion", maxTokens = 2000, readMs = 60_000)
            d = read(context, day)
            d = d.copy(turns = d.turns.map { if (it.at == last.at && it.who == "you") it.copy(failed = null) else it } +
                Turn("model", said.text, System.currentTimeMillis()))
            write(context, d); d
        } catch (e: Exception) {
            d = read(context, day)
            d = d.copy(turns = d.turns.map { if (it.at == last.at && it.who == "you") it.copy(failed = e.message ?: "no answer") else it })
            write(context, d)
            throw e
        }
    }

    fun setMood(context: Context, day: LocalDate, mood: Int?, stress: Int?): Day =
        open(context, day).copy(mood = mood, stress = stress).also { write(context, it) }

    private const val WRAP = "From a student's daily conversation with their planning companion, write down the day's " +
        "signals, in the student's language, as JSON only: {\"summary\": one or two sentences, \"events\": [...], " +
        "\"feelings\": [...], \"ideas\": [...], \"worries\": [...], \"constraints\": [new limits on their time or energy, " +
        "with dates when said], \"goal_notes\": [what they said about their goals or plans], \"for_navigator\": a short " +
        "message in the student's voice for their goal planner with only what should change or be considered in their " +
        "plans, or \"\" if nothing}. Use only what the student said; keep each item short; empty lists are fine."

    /** The day wrapped up: its signals written down from the conversation. */
    fun wrap(context: Context, day: LocalDate): Day {
        val d = open(context, day)
        if (d.turns.none { it.who == "you" }) throw IllegalStateException("Say something about the day first.")
        val user = "Student language: " + (if (persian(context)) "fa" else "en") +
            (d.mood?.let { "\nMood they set: $it/5" } ?: "") + (d.stress?.let { "\nPressure they set: $it/5" } ?: "") +
            "\n\nThe conversation:\n" + transcript(d.turns)
        val said = Quick.ask(context, WRAP, user, "COMPANION_WRAP|$day", "companion", maxTokens = 3000, readMs = 60_000)
        val o = ProposalValidator.extractJson(said.text) ?: throw IllegalStateException("The summary could not be read; try again.")
        fun list(k: String) = o.getAsJsonArray(k)?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() } }
        fun str(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
        val s = Signals(str("summary"), list("events"), list("feelings"), list("ideas"), list("worries"), list("constraints"),
            list("goal_notes"), str("for_navigator"))
        return read(context, day).copy(signals = s).also { write(context, it) }
    }

    fun markSent(context: Context, day: LocalDate) = write(context, read(context, day).copy(sentToNavigator = true))
}
