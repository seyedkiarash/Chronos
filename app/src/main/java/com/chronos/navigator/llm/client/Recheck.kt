package com.chronos.navigator.llm.client

import android.content.Context
import com.chronos.data.ChronosRepository
import com.chronos.data.DatabaseWiring
import com.chronos.navigator.llm.Discuss
import com.chronos.navigator.llm.ProposalValidator
import com.chronos.navigator.llm.Staleness
import com.chronos.navigator.store.NavProposals
import com.google.gson.Gson
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The inbox's re-check, run whenever you ask (your decision of 20 Oct 2026:
 * the app's own rules first, then the model for what is left).
 *
 * 1. The app, free: every proposal waiting for you is held against the graph
 *    and Chronos as they are now ([Staleness]). One none of whose changes still
 *    applies leaves the inbox as outdated, with why; you can put it back.
 * 2. What stays is measured again on today's forecast, so the effect beside it
 *    is today's, not the one from the day it was filed.
 * 3. The model, a few calls at most: proposals that are old, only partly still
 *    true, or whose effect moved are put back to it with today's snapshot. It
 *    says it no longer helps (outdated), revises it (a new version), or keeps it.
 */
object Recheck {
    /** At most this many proposals go to the model in one re-check. */
    const val MODEL_MAX = 3
    /** A proposal not looked at for this long is worth asking about again. */
    const val AGE_DAYS = 7L
    /** A measured effect that moved this much (in probability) is news for the model. */
    private const val MOVED = 0.10

    data class Report(val outdated: List<String>, val remeasured: Int, val asked: Int, val revised: Int,
                      val keptByModel: Int, val droppedByModel: Int, val waiting: Int, val notes: List<String>) {
        /** One line for the snackbar and the conversation, in the app's language. */
        fun line(fa: Boolean): String {
            val parts = mutableListOf<String>()
            if (fa) {
                if (outdated.isNotEmpty()) parts += "${outdated.size} پیشنهاد دیگر کاربرد نداشت و از صندوق بیرون رفت"
                if (remeasured > 0) parts += "اثر $remeasured پیشنهاد با پیش‌بینی امروز دوباره سنجیده شد"
                if (asked > 0) parts += "$asked پیشنهاد به مدل داده شد ($revised اصلاح، $droppedByModel حذف، $keptByModel بی‌تغییر)"
                if (parts.isEmpty()) parts += "هیچ پیشنهاد منتظری نیاز به تغییر نداشت"
                return "بررسی مجدد: " + parts.joinToString("؛ ") + "." +
                    (if (waiting > 0) " $waiting پیشنهاد دیگر را می‌شود دفعهٔ بعد به مدل داد." else "")
            }
            if (outdated.isNotEmpty()) parts += "${outdated.size} no longer applied and left the inbox"
            if (remeasured > 0) parts += "$remeasured measured again on today's forecast"
            if (asked > 0) parts += "$asked put to the model ($revised revised, $droppedByModel dropped, $keptByModel kept)"
            if (parts.isEmpty()) parts += "nothing waiting needed a change"
            return "Re-check: " + parts.joinToString("; ") + "." +
                (if (waiting > 0) " $waiting more could be put to the model next time." else "")
        }
    }

    private val gson = Gson()

    fun run(context: Context, withModel: Boolean): Report {
        val store = DatabaseWiring.navigator(context)
        val inbox = NavProposals(store.dao())
        val g = store.load() ?: throw IllegalStateException("Import the goal graph first.")
        val cfg = DatabaseWiring.repository(context).loadConfig()
        val today = ChronosRepository.logicalToday()
        val activities = cfg.activities.filter { !it.archived }.mapNotNull { it.name }.toSet()
        val goals = (cfg.goals.mapNotNull { it.name } + cfg.goals.mapNotNull { it.groupName }).toSet()

        val outdated = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val kept = mutableListOf<NavProposals.View>()
        val news = mutableMapOf<String, MutableList<String>>()
        for (v in inbox.proposals().filter { it.status() == "PENDING" }) {
            val verdict = Staleness.check(g, today, v.ops(), if (v.fromCrew()) null else v.subject(), activities, goals)
            if (verdict.outdated() != null) {
                inbox.markOutdated(v.id(), verdict.outdated())
                outdated += (v.title() ?: v.summary() ?: v.id()) + ": " + verdict.outdated()
                continue
            }
            kept += v
            if (verdict.partly()) news.getOrPut(v.id()) { mutableListOf() } +=
                "Part of it no longer applies: " + verdict.gone().joinToString("; ") + "."
        }

        // Today's effect beside each one that stays.
        var remeasured = 0
        runCatching {
            val plain = kept.filter { !it.fromCrew() }
            NavAsk.remeasure(context, g, plain).forEach { (id, effects) ->
                val before = plain.first { it.id() == id }.effects()
                inbox.update(id) { it.add("effects", gson.toJsonTree(effects)) }
                remeasured++
                moved(before, effects)?.let { news.getOrPut(id) { mutableListOf() } += it }
            }
            val crew = kept.filter { it.fromCrew() && it.ops().isNotEmpty() }
            if (crew.isNotEmpty()) {
                val base = CrewRun.base(context)
                crew.forEach { v -> runCatching {
                    val m = CrewRun.measure(context, base, v.ops())
                    val effects = m.verdict.moves().filter { !it.before().p().isNaN() }
                        .map { ProposalValidator.Effect(it.nodeId(), it.before().p(), it.after().p()) }
                    inbox.update(v.id()) { o ->
                        o.add("effects", gson.toJsonTree(effects))
                        o.getAsJsonObject("crew")?.let { c ->
                            c.add("odds", com.chronos.navigator.llm.Crew.odds(m.verdict))
                            c.add("week_now", base.week.json())
                            c.add("week_with_it", m.week.json())
                        }
                    }
                    remeasured++
                    moved(v.effects(), effects)?.let { news.getOrPut(v.id()) { mutableListOf() } += it }
                }.onFailure { notes += (v.title() ?: "A package") + ": " + (it.message ?: it.javaClass.simpleName) } }
            }
        }.onFailure { notes += "Measuring again failed: " + (it.message ?: it.javaClass.simpleName) }

        // The model, for those worth a second look.
        val now = System.currentTimeMillis()
        val worth = kept.filter { v ->
            news.containsKey(v.id()) || ChronoUnit.DAYS.between(day(inbox.lastLooked(v.id())), today) >= AGE_DAYS
        }.sortedWith(compareBy({ !news.containsKey(it.id()) }, { inbox.lastLooked(it.id()) }))
        var asked = 0; var revised = 0; var keptByModel = 0; var dropped = 0
        if (withModel) for (v in worth.take(MODEL_MAX)) {
            val message = Discuss.recheckMessage(today, day(v.createdAt()), news[v.id()]?.joinToString(" "))
            try {
                NavAsk.discuss(context, v.id(), message)
                asked++
                when (inbox.view(v.id())?.status()) {
                    "OUTDATED" -> { dropped++; outdated += (v.title() ?: v.summary() ?: v.id()) + ": the model says it no longer helps" }
                    "REVISED" -> revised++
                    else -> { keptByModel++; inbox.update(v.id()) { it.addProperty("rechecked", now) } }
                }
            } catch (e: Exception) {
                notes += (v.title() ?: v.summary() ?: "A proposal") + ": " + (e.message ?: e.javaClass.simpleName)
                break   // the next call would fail the same way: no network, no key, the budget spent
            }
        }
        val report = Report(outdated, remeasured, asked, revised, keptByModel, dropped,
            if (withModel) (worth.size - MODEL_MAX).coerceAtLeast(0) else worth.size, notes)
        // The conversation hears about it too, where the packages were announced.
        if (outdated.isNotEmpty() || asked > 0)
            CrewRun.say(context, CrewRun.APP_CHAT, CrewRun.ChatTurn("navigator", report.line(persian(context)), now, null, null))
        return report
    }

    fun persian(context: Context) = com.chronos.auto.AutoPlanner.prefs(context).getString("lang", null) == "fa"

    private fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

    /** In words, when a measured effect moved enough to be news; null when it did not. */
    private fun moved(before: List<ProposalValidator.Effect>, after: List<ProposalValidator.Effect>): String? {
        val was = before.associateBy { it.nodeId() }
        val shifts = after.mapNotNull { e ->
            val b = was[e.nodeId()] ?: return@mapNotNull null
            val d = (e.after() - e.before()) - (b.after() - b.before())
            if (Math.abs(d) < MOVED) null else
                "P(${e.nodeId()}) ${pct(e.before())}% -> ${pct(e.after())}% today (it was ${pct(b.before())}% -> ${pct(b.after())}%)"
        }
        return if (shifts.isEmpty()) null else "Its effect as the app measures it today: " + shifts.joinToString("; ") + "."
    }

    private fun pct(p: Double) = Math.round(p * 100)
}
