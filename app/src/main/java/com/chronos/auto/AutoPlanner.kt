package com.chronos.auto

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.chronos.calendar.CalendarSync
import com.chronos.data.ChronosRepository
import com.chronos.data.DatabaseWiring
import com.chronos.engine.Calibrator
import com.chronos.validate.Readiness
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * The end of the day, done without being asked.
 *
 * When the day ends (03:00 unless the user moved it) every day that has ended
 * is closed: the units its sessions finished are added to their goals, its
 * log refines the model, the horizon is re-solved from the next day, and the
 * result goes straight to the calendar. The same catch-up runs whenever the
 * app opens, so a job the system delayed or skipped costs nothing.
 */
object AutoPlanner {
    private const val PREFS = "chronos.ui"
    private const val WORK = "chronos-nightly"
    private const val KEY_DAY_END = "dayEnd"
    private const val KEY_STARTED = "autoCloseStarted"
    private const val KEY_REPORT = "nightlyReport"
    private const val KEY_REPORT_DAYS = "nightlyDays"
    private const val KEY_REPORT_NOTE = "nightlyNote"

    /** The worker and the app can both wake at the day boundary; only one closes it. */
    private val lock = Any()

    /**
     * Set by a live screen. When the job closes a day while the app is open,
     * the screen's copy of the configuration is out of date, and its next edit
     * would write the old estimates back over the new ones.
     */
    @Volatile var onClosedInBackground: (() -> Unit)? = null

    data class Run(
        val closed: List<LocalDate>,
        val report: List<Calibrator.Change>,
        val rebuilt: Boolean,
        val calendarNote: String?
    )

    /** What the last close-out did, kept for the Log screen. */
    data class LastReport(val days: String, val lines: List<Pair<String, String>>, val note: String)

    internal fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun dayEnd(context: Context): String = prefs(context).getString(KEY_DAY_END, "03:00") ?: "03:00"

    fun setDayEnd(context: Context, hhmm: String) {
        prefs(context).edit().putString(KEY_DAY_END, hhmm).apply()
        applyDayEnd(context)
        schedule(context)
    }

    private fun minutesOf(hhmm: String): Int {
        val parts = hhmm.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 3
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return (h * 60 + m).coerceIn(0, 1439)
    }

    /** Hands the preference to the repository, which owns the notion of "today". */
    fun applyDayEnd(context: Context) {
        ChronosRepository.dayEndMinutes = minutesOf(dayEnd(context))
    }

    /**
     * Close every day that has ended. Returns what happened; an empty
     * [Run.closed] means there was nothing to do.
     */
    fun catchUp(context: Context): Run = synchronized(lock) {
        applyDayEnd(context)
        val repo = DatabaseWiring.repository(context)
        val p = prefs(context)
        if (!p.getBoolean(KEY_STARTED, false)) {
            repo.startAutoCloseFromToday()
            p.edit().putBoolean(KEY_STARTED, true).apply()
        }

        val cfg = repo.loadConfig()
        val result = repo.closeElapsedDays(cfg)
        if (result.closed().isEmpty()) return Run(emptyList(), emptyList(), false, null)

        var rebuilt = false
        var note: String? = null
        if (Readiness.canSolve(repo.readiness(cfg))) {
            val bundle = repo.rebuild(cfg)
            rebuilt = true
            note = pushCalendar(context, repo, cfg, bundle)
        }
        saveReport(context, result, rebuilt, note)
        LogExport.record(context, "day closed", result.closed(), result.report(), cfg)
        Run(result.closed(), result.report(), rebuilt, note)
    }

    /** Sends the plan to the chosen calendar; null when no calendar is set up. */
    fun pushCalendar(
        context: Context, repo: ChronosRepository,
        cfg: com.chronos.model.Config, bundle: ChronosRepository.PlanBundle?
    ): String? {
        val id = repo.state().calendarId() ?: return null
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return "Calendar not updated: permission is off."
        val p = prefs(context)
        return try {
            val r = CalendarSync.push(context, repo, cfg, bundle, id,
                p.getBoolean("upkeepInCalendar", true), p.getInt("reminder", 10), sleepTitle(context))
            "Calendar updated: ${r.blocks()} blocks."
        } catch (e: Exception) {
            "Calendar not updated: ${e.message ?: "unknown error"}."
        }
    }

    /** "Sleep" in the interface language, or null when sleep is kept out of the calendar. */
    fun sleepTitle(context: Context): String? {
        val p = prefs(context)
        if (!p.getBoolean("sleepInCalendar", true)) return null
        val lang = com.chronos.ui.Lang.entries.firstOrNull { it.code == p.getString("lang", null) }
            ?: com.chronos.ui.Lang.English
        return com.chronos.ui.translate(lang, "Sleep")
    }

    private fun saveReport(
        context: Context, result: ChronosRepository.NightlyResult, rebuilt: Boolean, note: String?
    ) {
        // Tab and newline never occur in a goal or activity name typed on a phone keyboard.
        val lines = result.report().joinToString("\n") {
            "${it.subject().replace('\t', ' ')}\t${it.detail().replace('\n', ' ')}"
        }
        val days = result.closed().joinToString(",")
        val summary = buildString {
            append(if (rebuilt) "Plan rebuilt from the next day." else "Plan not rebuilt: setup is incomplete.")
            if (note != null) append(" ").append(note)
        }
        prefs(context).edit()
            .putString(KEY_REPORT, lines)
            .putString(KEY_REPORT_DAYS, days)
            .putString(KEY_REPORT_NOTE, summary)
            .apply()
    }

    fun lastReport(context: Context): LastReport? {
        val p = prefs(context)
        val days = p.getString(KEY_REPORT_DAYS, null) ?: return null
        val lines = (p.getString(KEY_REPORT, "") ?: "").split("\n").filter { it.isNotBlank() }.map {
            val cut = it.indexOf('\t')
            if (cut < 0) it to "" else it.substring(0, cut) to it.substring(cut + 1)
        }
        return LastReport(days, lines, p.getString(KEY_REPORT_NOTE, "") ?: "")
    }

    /**
     * One job, at the next day boundary plus a few minutes. It reschedules
     * itself when it runs, so the chain survives without a periodic request
     * drifting away from the hour the user chose.
     */
    fun schedule(context: Context, fromWorker: Boolean = false) {
        val end = minutesOf(dayEnd(context))
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atStartOfDay().plusMinutes(end.toLong() + 5)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMillis()

        val request = OneTimeWorkRequestBuilder<NightlyWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        // From inside the running job, append: replacing would cancel the job
        // that is scheduling its own successor. From the app, replace, so a
        // changed hour takes effect at once.
        val policy = if (fromWorker) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, policy, request)
    }
}

/** The scheduled half of [AutoPlanner]. */
class NightlyWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        // A failed night is not retried in a loop: the app runs the same
        // catch-up when it next opens, and tomorrow's job tries again.
        try {
            val run = AutoPlanner.catchUp(applicationContext)
            if (run.closed.isNotEmpty()) AutoPlanner.onClosedInBackground?.invoke()
        } catch (_: Exception) {
        }
        // The Navigator learns from the day just closed and forecasts again; on its weekday it also
        // proposes the coming week, which waits in the Navigator tab for the user to accept.
        try {
            com.chronos.navigator.bridge.NavEngine.nightly(applicationContext)
        } catch (e: Exception) {
            android.util.Log.w("Navigator", "nightly run failed", e)
        }
        AutoPlanner.schedule(applicationContext, fromWorker = true)
        return Result.success()
    }
}
