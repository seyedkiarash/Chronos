package com.chronos.auto

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.chronos.data.ChronosRepository
import com.chronos.data.DatabaseWiring
import com.chronos.model.Enums
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Asking how it is going when the log has gone quiet (your request of 20 Oct 2026):
 * at the times you set, and whenever several waking hours pass with nothing
 * logged — never while you sleep or sit in a class. The notification opens the
 * Log, or takes a one-line answer that the model turns into log entries for you
 * to confirm ([QuickLog]).
 */
object Reminders {
    const val CHANNEL = "chronos-reminders"
    const val NOTIFY_ID = 4101
    const val REPLY_KEY = "quick_reply"
    private const val WORK = "chronos-reminders"

    private const val KEY_ON = "remindOn"
    private const val KEY_TIMES = "remindTimes"
    private const val KEY_GAP = "remindGapH"
    private const val KEY_MODEL = "remindModel"
    private const val KEY_FIRED = "remindFired"      // "date|HH:MM,HH:MM" fixed times already asked today
    private const val KEY_NUDGED = "remindNudgedAt"  // epoch ms of the last reminder of any kind

    // ------------------------------------------------------------ settings

    private fun prefs(context: Context) = AutoPlanner.prefs(context)

    fun on(context: Context) = prefs(context).getBoolean(KEY_ON, false)
    fun times(context: Context): List<String> =
        (prefs(context).getString(KEY_TIMES, "13:00,18:00,22:00") ?: "").split(",").map { it.trim() }.filter { it.matches(Regex("\\d\\d:\\d\\d")) }.sorted()
    /** Hours awake with nothing logged before asking; 0 turns that kind off. */
    fun gapHours(context: Context) = prefs(context).getFloat(KEY_GAP, 3f).toDouble()
    /** Whether a quick answer goes to the model to be read into entries. */
    fun viaModel(context: Context) = prefs(context).getBoolean(KEY_MODEL, true)

    fun setOn(context: Context, on: Boolean) { prefs(context).edit().putBoolean(KEY_ON, on).apply(); schedule(context) }
    fun setTimes(context: Context, times: List<String>) { prefs(context).edit().putString(KEY_TIMES, times.distinct().sorted().joinToString(",")).apply() }
    fun setGapHours(context: Context, h: Double) { prefs(context).edit().putFloat(KEY_GAP, h.toFloat()).apply() }
    fun setViaModel(context: Context, on: Boolean) { prefs(context).edit().putBoolean(KEY_MODEL, on).apply() }

    fun allowed(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** A check every quarter of an hour while reminders are on; nothing at all when off. */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        if (!on(context)) { wm.cancelUniqueWork(WORK); return }
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderWorker>(15, TimeUnit.MINUTES).build())
    }

    // ------------------------------------------------------------ deciding

    /** Why to ask now: one of the fixed times, or a long quiet stretch. */
    sealed class Ask {
        data class AtTime(val time: String) : Ask()
        data class Quiet(val sinceMin: Int) : Ask()
    }

    /**
     * Whether to ask now, from minutes since midnight. Pure, so it can be tested.
     *
     * @param wake/bed      the waking window (bed may be past midnight, then above 1440)
     * @param busy          inside a class or other fixed session right now
     * @param fired         fixed times already asked about today
     * @param lastLog       the latest minute anything was logged today, or null
     * @param lastNudge     the minute of the last reminder today, or null
     */
    fun decide(now: Int, wake: Int, bed: Int, busy: Boolean, times: List<String>, fired: Set<String>,
               lastLog: Int?, lastNudge: Int?, gapHours: Double): Ask? {
        // bed is past midnight when above 1440: the small hours before it still count as awake.
        val awake = now in wake until minOf(bed, 1440) || (bed > 1440 && now < bed - 1440)
        if (!awake || busy) return null
        // A fixed time, within an hour and a half after it, unless something was logged in the 45 minutes before now.
        for (t in times) {
            if (t in fired) continue
            val m = t.substring(0, 2).toInt() * 60 + t.substring(3).toInt()
            if (now >= m && now - m <= 90 && (lastLog == null || now - lastLog > 45)) return Ask.AtTime(t)
        }
        if (gapHours <= 0) return null
        val since = maxOf(lastLog ?: wake, lastNudge ?: wake, wake)
        val gap = (gapHours * 60).toInt()
        return if (now - since >= gap) Ask.Quiet(since) else null
    }

    /** Fixed times that have passed and so will not be asked about any more today, from those already asked. */
    private fun fired(context: Context, today: LocalDate): Set<String> {
        val raw = prefs(context).getString(KEY_FIRED, "") ?: ""
        val (date, list) = raw.split("|").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        return if (date == today.toString()) list.split(",").filter { it.isNotBlank() }.toSet() else emptySet()
    }

    private fun minutesOf(t: String?) = t?.split(":")?.takeIf { it.size == 2 }?.let { it[0].toInt() * 60 + it[1].toInt() }

    /** Runs the check: asks if [decide] says so. Called by the worker; [force] asks regardless (the test button). */
    fun check(context: Context, force: Boolean = false) {
        if (!force && !on(context)) return
        if (!allowed(context)) return
        val repo = DatabaseWiring.repository(context)
        val cfg = repo.loadConfig()
        val today = ChronosRepository.logicalToday()
        val clock = LocalTime.now()
        val now = clock.hour * 60 + clock.minute
        val wake = minutesOf(cfg.params?.sleepEnd) ?: 7 * 60
        val bed = (minutesOf(cfg.params?.sleepStart) ?: 23 * 60).let { if (it <= wake) it + 1440 else it }
        val dow = com.chronos.engine.Slots.dow(LocalDate.now())
        val busy = cfg.commitments.filter { it.kind == Enums.CommitmentKind.FIXED }.flatMap { it.sessions }
            .any { s -> s.day == dow && (minutesOf(s.start) ?: 0) <= now && now < (minutesOf(s.end) ?: 0).let { if (it == 0) 1440 else it } }
        val logs = repo.logs().filter { it.date == today }
        val zone = java.time.ZoneId.systemDefault()
        fun minuteOf(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime().let { dt ->
            if (dt.toLocalDate().isAfter(LocalDate.now()) || dt.toLocalDate().isBefore(LocalDate.now())) null else dt.hour * 60 + dt.minute
        }
        val lastLog = logs.mapNotNull { minuteOf(it.ts) }.maxOrNull()
        val lastNudge = prefs(context).getLong(KEY_NUDGED, 0L).takeIf { it > 0 }?.let { minuteOf(it) }
        val fired = fired(context, today)
        val ask = if (force) Ask.Quiet(lastLog ?: wake)
            else decide(now, wake, bed, busy, times(context), fired, lastLog, lastNudge, gapHours(context)) ?: return
        val e = prefs(context).edit().putLong(KEY_NUDGED, System.currentTimeMillis())
        if (ask is Ask.AtTime) e.putString(KEY_FIRED, today.toString() + "|" + (fired + ask.time).joinToString(","))
        e.apply()
        notify(context, ask, lastLog)
    }

    // ------------------------------------------------------------ asking

    private fun persian(context: Context) = prefs(context).getString("lang", null) == "fa"

    private fun channel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val fa = persian(context)
        val ch = NotificationChannel(CHANNEL, if (fa) "یادآور ثبت لاگ" else "Log reminders", NotificationManager.IMPORTANCE_DEFAULT)
        ch.description = if (fa) "وقتی مدتی چیزی ثبت نشده، حالت را می‌پرسد." else "Asks how it is going when nothing has been logged for a while."
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    /** Opens the Log tab. */
    fun openLog(context: Context): PendingIntent {
        val i = Intent(context, com.chronos.ui.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open", "log")
        }
        return PendingIntent.getActivity(context, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun notify(context: Context, ask: Ask, lastLog: Int?) {
        channel(context)
        val fa = persian(context)
        fun hm(m: Int) = "%02d:%02d".format((m / 60) % 24, m % 60)
        val title = if (fa) "حالت چطور است؟" else "How is it going?"
        val body = when {
            ask is Ask.AtTime && fa -> "ساعت ${ask.time} است. از " + (lastLog?.let { "ساعت " + hm(it) } ?: "صبح") + " چیزی ثبت نشده: چه کردی و چه حالی داری؟"
            ask is Ask.AtTime -> "It is ${ask.time}. Nothing logged since " + (lastLog?.let { hm(it) } ?: "this morning") + ": what have you done, and how do you feel?"
            fa -> "چند ساعت است چیزی ثبت نشده" + (lastLog?.let { " (آخرین: " + hm(it) + ")" } ?: "") + ". یک خط بنویس چه کردی و حالت چطور است."
            else -> "Nothing logged for a few hours" + (lastLog?.let { " (last at " + hm(it) + ")" } ?: "") + ". One line: what you did and how you feel."
        }
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openLog(context)).setAutoCancel(true)
            .addAction(0, if (fa) "باز کردن لاگ" else "Open the log", openLog(context))
        val input = RemoteInput.Builder(REPLY_KEY)
            .setLabel(if (fa) "مثلاً: ۱۰ تا ۱۲ فصل ۳ را خواندم، خسته‌ام" else "e.g. read chapter 3 from 10 to 12, tired").build()
        val reply = PendingIntent.getBroadcast(context, 2, Intent(context, QuickReplyReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        b.addAction(NotificationCompat.Action.Builder(0, if (fa) "پاسخ کوتاه" else "Quick answer", reply)
            .addRemoteInput(input).setAllowGeneratedReplies(false).build())
        if (allowed(context)) runCatching { NotificationManagerCompat.from(context).notify(NOTIFY_ID, b.build()) }
    }

    /** Replaces the reminder with a short line (after a quick answer, or once it has been read). */
    fun status(context: Context, text: String, open: Boolean = true) {
        channel(context)
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentTitle("Chronos").setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true).setOnlyAlertOnce(true)
        if (open) b.setContentIntent(openLog(context))
        if (allowed(context)) runCatching { NotificationManagerCompat.from(context).notify(NOTIFY_ID, b.build()) }
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        runCatching { Reminders.check(applicationContext) }
        return Result.success()
    }
}
