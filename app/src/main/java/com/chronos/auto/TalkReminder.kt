package com.chronos.auto

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.chronos.data.ChronosRepository
import com.chronos.navigator.llm.client.Companion
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * The evening's reminder of the day's conversation (your request of 6 Nov 2026): once a day at the
 * time you choose, and only if you have not talked yet. It opens the conversation directly.
 */
object TalkReminder {
    private const val CHANNEL = "chronos-talk"
    private const val NOTIFY_ID = 4102
    private const val WORK = "chronos-talk"
    private const val LATE_MIN = 120

    private fun prefs(context: Context) = AutoPlanner.prefs(context)

    fun on(context: Context) = prefs(context).getBoolean("talkOn", !com.chronos.BuildConfig.PUBLIC_EDITION)
    fun time(context: Context) = prefs(context).getString("talkTime", "22:00") ?: "22:00"

    fun setOn(context: Context, on: Boolean) { prefs(context).edit().putBoolean("talkOn", on).apply(); schedule(context) }
    fun setTime(context: Context, t: String) {
        // A new time may still be ahead today: it is allowed to ask again.
        prefs(context).edit().putString("talkTime", t).remove("talkFired").apply()
    }

    /** A check every quarter of an hour while on; nothing at all when off. */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        if (!on(context)) { wm.cancelUniqueWork(WORK); return }
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TalkWorker>(15, TimeUnit.MINUTES).build())
    }

    /** Whether to remind now, in minutes since midnight. Pure, so it can be tested. */
    fun decide(now: Int, at: Int, talked: Boolean, firedToday: Boolean): Boolean =
        !talked && !firedToday && now >= at && now - at <= LATE_MIN

    fun check(context: Context) {
        if (!on(context) || !Reminders.allowed(context)) return
        val day = ChronosRepository.logicalToday()
        val t = time(context).split(":")
        val at = (t.getOrNull(0)?.toIntOrNull() ?: 22) * 60 + (t.getOrNull(1)?.toIntOrNull() ?: 0)
        val clock = LocalTime.now()
        val fired = prefs(context).getString("talkFired", null) == day.toString()
        val talked = Companion.read(context, day).turns.any { it.who == "you" }
        if (!decide(clock.hour * 60 + clock.minute, at, talked, fired)) return
        prefs(context).edit().putString("talkFired", day.toString()).apply()
        notify(context)
    }

    private fun notify(context: Context) {
        val fa = prefs(context).getString("lang", null) == "fa"
        if (Build.VERSION.SDK_INT >= 26) context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, if (fa) "گفتگوی امروز" else "Today's conversation", NotificationManager.IMPORTANCE_DEFAULT))
        val i = Intent(context, com.chronos.ui.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open", "today")
        }
        val open = PendingIntent.getActivity(context, 3, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentTitle(if (fa) "گفتگوی امروز" else "Today's conversation")
            .setContentText(if (fa) "امروز چطور گذشت؟ چند دقیقه با هم حرف بزنیم." else "How did today go? A few minutes to talk it over.")
            .setContentIntent(open).setAutoCancel(true)
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFY_ID, b.build()) }
    }
}

class TalkWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        runCatching { TalkReminder.check(applicationContext) }
        return Result.success()
    }
}
