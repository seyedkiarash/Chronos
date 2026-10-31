package com.chronos.ui

import com.chronos.ui.common.*
import com.chronos.ui.setup.*
import com.chronos.ui.horizon.*
import com.chronos.ui.day.*
import com.chronos.ui.log.*
import com.chronos.ui.navigator.*

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chronos.auto.AutoPlanner
import com.chronos.auto.LogExport
import com.chronos.calendar.CalendarSync
import com.chronos.calendar.CalendarWriter
import com.chronos.data.ChronosRepository
import com.chronos.data.DatabaseWiring
import com.chronos.engine.Calibrator
import com.chronos.engine.DaySequencer
import com.chronos.engine.Feasibility
import com.chronos.model.Config
import com.chronos.model.LogEntry
import com.chronos.validate.Readiness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/**
 * Holds the whole app state. The engine is synchronous and fast enough to run
 * on demand, so the only work worth moving off the main thread is the database,
 * the solver itself, and the calendar provider.
 */
class ChronosViewModel(private val app: Application) : AndroidViewModel(app) {

    companion object {
        /** Raised when the way days are arranged changes, so stored plans are solved again. */
        private const val ENGINE_VERSION = 10
        /** How far ahead the Horizon screen looks for things that did not fit. */
        private const val UPCOMING_DAYS = 7
    }

    private val repo: ChronosRepository = DatabaseWiring.repository(app)
    /** The Navigator's tables, for the one field it adds to the evening check-in. */
    private val nav = DatabaseWiring.navigator(app)

    /**
     * Config is a mutable Java object. Every edit bumps [revision], and each screen reads that
     * value once to subscribe; and since 29 Oct 2026 every edit also hands the screens a fresh
     * copy, so a card given the configuration (or one of its goals or activities) is drawn again
     * at once — before, an unchanged reference let Compose skip it until you changed tabs.
     */
    var config: Config by mutableStateOf(Config()); private set
    var revision by mutableIntStateOf(0); private set

    /**
     * Call this at the top of any composable lambda that draws from [config]
     * (typically a Panel's content). Config and its lists keep their identity
     * across edits, so Compose would otherwise memoise the lambda and skip it;
     * reading [revision] inside the lambda subscribes that lambda to edits.
     */
    fun observeConfig(): Int = revision

    var loading by mutableStateOf(true); private set

    /** Which Setup step is open; kept here so switching tabs does not reset the wizard. */
    var setupStep by mutableIntStateOf(0)

    /**
     * Interface language. A display preference rather than planning data, so it
     * lives in preferences and the database schema stays where it is — that
     * schema holds the only copy of the log.
     */
    private val prefs = app.getSharedPreferences("chronos.ui", android.content.Context.MODE_PRIVATE)

    var lang by mutableStateOf(
        Lang.entries.firstOrNull { it.code == prefs.getString("lang", null) } ?: Lang.English
    ); private set

    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)

    var issues by mutableStateOf<List<Readiness.Issue>>(emptyList()); private set
    var bundle by mutableStateOf<ChronosRepository.PlanBundle?>(null); private set
    /** The first day of the plan. Usually the day you are in; later if you chose a later start. */
    var today by mutableStateOf<LocalDate>(LocalDate.now()); private set

    /**
     * The day you are living in, which is the day feedback is written against.
     * It rolls over at [dayEnd], not at midnight, and differs from [today] only
     * when the plan was set to start later.
     */
    var logDay by mutableStateOf<LocalDate>(LocalDate.now()); private set

    /** When the day closes itself; see [AutoPlanner]. */
    var dayEnd by mutableStateOf(AutoPlanner.dayEnd(app)); private set

    /** What the last automatic close-out changed. */
    var nightly by mutableStateOf(AutoPlanner.lastReport(app)); private set
    var logs by mutableStateOf<List<LogEntry>>(emptyList()); private set
    var comparisons by mutableStateOf<Map<String, Double>>(emptyMap()); private set
    var calibration by mutableStateOf<Calibrator.Outcome?>(null); private set

    /** Which dated goals do not finish, and what would let them. Recomputed on every edit. */
    var feasibility by mutableStateOf<Feasibility.Report?>(null); private set

    /**
     * What each dated goal actually receives once the days are arranged. Unlike
     * [feasibility], which estimates from the capacity model, this is measured
     * from the real timetable — so it is only meaningful once a plan exists, and
     * it is what the Horizon screen's verdict is allowed to rely on.
     */
    var coverage by mutableStateOf<List<Feasibility.Coverage>>(emptyList()); private set

    /** What the coming week's arranged days left out or had to bend, with why. */
    var upcoming by mutableStateOf<List<ChronosRepository.DayProblems>>(emptyList()); private set

    var calendars by mutableStateOf<List<CalendarWriter.CalendarChoice>>(emptyList()); private set
    var calendarId by mutableStateOf<Long?>(null); private set
    // Calendar preferences. They survive a restart, and the setter persists on
    // assignment so the screens keep writing to them as plain properties.
    private var reminderState by mutableStateOf(prefs.getInt("reminder", 10).takeIf { it >= 0 })
    private var upkeepState by mutableStateOf(prefs.getBoolean("upkeepInCalendar", true))

    /** Null is no reminder at all, which is what an empty box reads as. */
    var reminderMinutes: Int?
        get() = reminderState
        set(value) {
            reminderState = value
            // Preferences hold no nulls, so "no reminder" is stored as a negative.
            prefs.edit().putInt("reminder", value ?: -1).apply()
        }

    var includeUpkeepInCalendar: Boolean
        get() = upkeepState
        set(value) {
            upkeepState = value
            prefs.edit().putBoolean("upkeepInCalendar", value).apply()
        }

    private var sleepState by mutableStateOf(prefs.getBoolean("sleepInCalendar", true))

    /** Sleep, and the nap if there is one, written to the calendar as blocks. */
    var sleepInCalendar: Boolean
        get() = sleepState
        set(value) {
            sleepState = value
            prefs.edit().putBoolean("sleepInCalendar", value).apply()
        }

    // ------------------------------------------------------------- wellbeing

    /** This week's load against a normal one. */
    var weekly by mutableStateOf<com.chronos.engine.WeeklyLoad.Report?>(null); private set

    /** Whether the day you are in has been given over to rest. */
    var restToday by mutableStateOf(false); private set

    /** What this morning's check-in, last night's, and the week add up to. */
    var restVerdict by mutableStateOf<com.chronos.engine.RestAdvisor.Verdict?>(null); private set

    private var autoRestState by mutableStateOf(prefs.getBoolean("autoRest", true))

    /** Switch to a rest day by itself when the signs are clear, rather than only suggest it. */
    var autoRest: Boolean
        get() = autoRestState
        set(value) {
            autoRestState = value
            prefs.edit().putBoolean("autoRest", value).apply()
        }

    private fun checkin(kind: String, date: LocalDate) = logs.firstOrNull { it.id == "$kind|$date" }

    /**
     * Sleep boxes being filled in, by day. Held here rather than in the screen,
     * so switching tabs halfway through does not throw the answers away.
     */
    val sleepDrafts = mutableStateMapOf<LocalDate, LogEntry>()

    /** Minutes asleep in a logged night: time in bed less the time it took to fall asleep. */
    private fun sleptMinutes(night: LogEntry): Int? {
        val bed = night.at ?: return null
        val up = night.endAt ?: return null
        fun m(t: String) = t.split(":").let { it[0].toInt() * 60 + it[1].toInt() }
        return ((m(up) - m(bed)) % 1440 + 1440) % 1440 - (night.latencyMin ?: 0)
    }

    /** Recomputes the week and the verdict from what is stored. Call off the main thread. */
    private fun refreshWellbeing() {
        weekly = repo.weeklyLoad(config)
        restToday = repo.isRestDay(logDay)
        val morning = checkin("morning", logDay)
        val evening = checkin("evening", logDay.minusDays(1))
        val night = logs.firstOrNull { it.id == "sleep|$logDay" }
        // A short or poor night logged in the sleep box is the morning's "Slept badly".
        val badNight = night != null && ((night.restored ?: 5) <= 2 || sleptMinutes(night)?.let { it < 360 } == true)
        val morningTags = morning?.tags.orEmpty().let { if (badNight && "Slept badly" !in it) it + "Slept badly" else it }
        val recent = (1..3).mapNotNull { checkin("morning", logDay.minusDays(it.toLong()))?.energy }
        restVerdict = if (morning == null && evening == null) null else
            com.chronos.engine.RestAdvisor.assess(
                morning?.energy, morningTags,
                evening?.energy, evening?.fatigueAfter, evening?.tags.orEmpty(),
                weekly?.ratio, recent)
    }

    /**
     * Saves the morning or evening check-in. A morning that adds up to a clear
     * case for rest switches the day over by itself when [autoRest] is on.
     *
     * @param heavy evening only: how heavy the day felt, 1..5
     */
    // ------------------------------------------------------------ day type

    /** The day types ticked so far, by day: (type, unusual day). Any day the Log can show, not only today. */
    var dayTypes by mutableStateOf<Map<LocalDate, Pair<String, Boolean>>>(emptyMap()); private set

    /** The day type ticked for the day being lived, if any. */
    val dayType: Pair<String, Boolean>? get() = dayTypes[logDay]

    private fun readDayType() {
        dayTypes = nav.dayTypes().associate { LocalDate.parse(it.date) to ((it.type ?: "") to it.anomaly) }
    }

    /**
     * The Navigator's suggestion for a day's type, from that day's check-ins
     * and the hours of fixed commitments (see navigator DayTypes).
     */
    fun suggestedDayType(day: LocalDate = logDay): String {
        val evening = logs.firstOrNull { it.id == "evening|$day" }
        val morning = logs.firstOrNull { it.id == "morning|$day" }
        val signs = evening?.tags.orEmpty() + morning?.tags.orEmpty()
        val fixed = com.chronos.navigator.bridge.Bridge.fixedHours(config, com.chronos.engine.Slots.dow(day))
        return com.chronos.navigator.estimate.DayTypes.suggest(evening?.fatigueAfter, signs, fixed).name
    }

    /** Whether a day was closed already, so a change to it reaches what the model learned. */
    fun isClosed(day: LocalDate) = day.isBefore(today)

    /**
     * What kind of day it was, and whether it was too unusual to learn from, for
     * any day the Log shows. Marking a closed day unusual (or taking it back)
     * replays the learning since then without it (with it) — your decision of 20 Oct 2026.
     */
    fun saveDayType(type: String, anomaly: Boolean, day: LocalDate = logDay) {
        val suggested = suggestedDayType(day)
        val before = dayTypes[day]?.second ?: false
        dayTypes = dayTypes + (day to (type to anomaly))
        work {
            nav.putDayType(day, type, anomaly, suggested)
            if (anomaly != before && isClosed(day)) {
                val changes = repo.relearnAround(config, day, anomaly)
                config = repo.loadConfig()
                LogExport.record(app, if (anomaly) "day left out of learning" else "day taken back into learning",
                    listOf(day), changes, config)
                issues = repo.readiness(config)
                val stale = repo.storedPlan(config)?.stale() == true
                bundle?.let { if (stale) bundle = it.markStale() }
                modelChanges = if (stale) changes else emptyList()
                dayCache.clear(); requested.clear()
                revision++
                message = when {
                    changes.isEmpty() -> "Saved. Nothing the model learned depended on that day."
                    anomaly -> "That day is no longer learned from; what it taught was taken back. The Horizon tab offers a rebuild if the plan moved."
                    else -> "That day is learned from again. The Horizon tab offers a rebuild if the plan moved."
                }
            }
        }
    }

    fun saveCheckin(kind: String, energy: Int, heavyMind: Int?, heavyBody: Int?, signs: List<String>,
                    day: LocalDate = logDay) {
        val now = java.time.LocalTime.now()
        val entry = LogEntry().apply {
            id = "$kind|$day"
            this.kind = com.chronos.model.Enums.LogKind.CHECKIN
            date = day
            at = String.format("%02d:%02d", now.hour, now.minute)
            ts = System.currentTimeMillis()
            this.energy = energy
            mentalAfter = heavyMind
            physicalAfter = heavyBody
            // The rest advisor and the day-type suggestion read one "how heavy": the heavier of the two.
            fatigueAfter = listOfNotNull(heavyMind, heavyBody).maxOrNull()
            tags = signs
        }
        logs = logs.filter { it.id != entry.id } + entry
        work {
            repo.addLog(entry)
            logs = repo.logs()
            lateEntries = repo.pendingLateEntries()
            refreshWellbeing()
            val verdict = restVerdict
            if (day == logDay && kind == "morning" && autoRest && !restToday &&
                verdict?.level() == com.chronos.engine.RestAdvisor.Level.REST) {
                switchRest(true)
                message = "The signs are clear, so today is now a rest day. Goal work is paused; " +
                    "tomorrow morning decides again."
            } else {
                message = "Saved."
            }
        }
    }

    /** Makes today a rest day, or takes it back, and re-solves from today. */
    fun chooseRestToday(on: Boolean) = work {
        switchRest(on)
        message = if (on) "Today is a rest day. Goal work is paused and the plan was rebuilt."
                  else "Back to the normal plan for today."
    }

    private fun switchRest(on: Boolean) {
        repo.setRestDay(logDay, on)
        dayCache.clear(); requested.clear()
        if (Readiness.canSolve(issues)) {
            bundle = repo.rebuild(config)
            coverage = repo.coverage(config, bundle)
            upcoming = repo.dayProblems(config, bundle, UPCOMING_DAYS)
            syncCalendarIfConfigured()
        }
        refreshWellbeing()
        revision++
    }

    /** Entries for closed days that the model has not taken in yet; see [applyLateEntries]. */
    var lateEntries by mutableIntStateOf(0); private set

    /**
     * What the last Apply changed in the model, kept until the plan is rebuilt
     * so the Horizon screen can say why the plan has gone out of date.
     */
    var modelChanges by mutableStateOf<List<Calibrator.Change>>(emptyList()); private set

    fun setLanguage(value: Lang) {
        lang = value
        prefs.edit().putString("lang", value.code).apply()
    }

    /** Light, dark, or as the phone is set (29 Oct 2026). A display preference, like the language. */
    var themeMode by mutableStateOf(
        com.chronos.ui.common.ThemeMode.entries.firstOrNull { it.code == prefs.getString("theme", null) }
            ?: com.chronos.ui.common.ThemeMode.SYSTEM
    ); private set

    fun setTheme(value: com.chronos.ui.common.ThemeMode) {
        themeMode = value
        prefs.edit().putString("theme", value.code).apply()
    }

    /** The optional lock (31 Oct 2026): the phone's own fingerprint or screen lock, asked on opening and after a minute away. */
    var lockOn by mutableStateOf(prefs.getBoolean("lock", false)); private set
    /** Whether the app is locked now: its screens are not drawn until the phone's lock is given. */
    var locked by mutableStateOf(prefs.getBoolean("lock", false))
    /** Screens kept out of screenshots and the recent-apps view; on unless you turn it off. */
    var hideScreens by mutableStateOf(prefs.getBoolean("hideScreens", true)); private set

    fun setLock(on: Boolean) { lockOn = on; prefs.edit().putBoolean("lock", on).apply() }
    fun hideScreensOn(on: Boolean) { hideScreens = on; prefs.edit().putBoolean("hideScreens", on).apply() }

    private val dayCache = mutableStateMapOf<LocalDate, DaySequencer.Result>()
    private val requested = mutableSetOf<LocalDate>()

    val ready: Boolean get() = Readiness.canSolve(issues)

    init {
        AutoPlanner.applyDayEnd(app)
        AutoPlanner.schedule(app)
        AutoPlanner.onClosedInBackground = {
            viewModelScope.launch {
                dayCache.clear(); requested.clear()
                reload()
            }
        }
        reload()
    }

    override fun onCleared() {
        AutoPlanner.onClosedInBackground = null
    }

    // ------------------------------------------------------------- lifecycle

    private fun reload() = viewModelScope.launch {
        loading = true
        withContext(Dispatchers.IO) {
            // Close whatever days ended while the app was shut, before reading
            // anything: the configuration and plan below are the ones it leaves.
            runCatching { AutoPlanner.catchUp(app) }
            config = repo.loadConfig()
            val repaired = repairConfig()
            issues = repo.readiness(config)
            feasibility = repo.feasibility(config)
            logs = repo.logs()
            lateEntries = repo.pendingLateEntries()
            comparisons = repo.comparisons()
            logDay = ChronosRepository.logicalToday()
            refreshWellbeing()
            runCatching { readDayType() }
            calendarId = repo.state().calendarId
            today = repo.today()
            logDay = ChronosRepository.logicalToday()
            nightly = AutoPlanner.lastReport(app)
            if (Readiness.canSolve(issues)) {
                // A repair changes only how the settings are stored, not what they
                // mean, so the plan is re-solved at once rather than reported stale.
                val stored = repo.storedPlan(config)
                // A plan solved by an older engine is solved again once, so a fix
                // in how days are arranged reaches today instead of waiting for tonight.
                val outdated = prefs.getInt("engine", 1) < ENGINE_VERSION
                bundle = if (stored == null || outdated || (repaired && stored.stale())) repo.rebuild(config) else stored
                if (outdated) {
                    prefs.edit().putInt("engine", ENGINE_VERSION).apply()
                    syncCalendarIfConfigured()
                }
                coverage = repo.coverage(config, bundle)
                upcoming = repo.dayProblems(config, bundle, UPCOMING_DAYS)
            }
        }
        revision++
        loading = false
    }

    /**
     * Data written by earlier builds: an upkeep activity could keep the goalId
     * it was created with, which the engine then treated as goal work. Clear it
     * once on load and persist, so the stored configuration is what the model
     * expects.
     */
    private fun repairConfig(): Boolean {
        var dirty = false
        config.activities.forEach { a ->
            if (a.kind == com.chronos.model.Enums.ActivityKind.MAINTENANCE && a.goalId != null) {
                a.goalId = null; dirty = true
            }
        }
        // Fixed commitments no longer have a course-wide load and recovery; each
        // session has its own. Sessions that were relying on the course-wide
        // figure take it over once, so nothing about the plan changes.
        config.commitments.forEach { c ->
            if (c.kind == com.chronos.model.Enums.CommitmentKind.FIXED &&
                (c.load != null || c.recovery != null)) {
                c.sessions.forEach { s ->
                    if (s.load == null) s.load = c.load
                    if (s.recovery == null) s.recovery = c.recovery
                }
                c.load = null; c.recovery = null; dirty = true
            }
        }
        // Fatigue has two kinds now. Whatever was set up with one number gets a suggested
        // split once — used as it is until confirmed in Setup — and the new parameters
        // start from their mental counterparts.
        config.activities.forEach { a -> if (com.chronos.engine.FatigueSplit.fill(a)) dirty = true }
        config.commitments.forEach { c -> if (com.chronos.engine.FatigueSplit.fill(c)) dirty = true }
        if (config.params != null && com.chronos.engine.FatigueSplit.fill(config.params)) dirty = true
        if (dirty) repo.saveConfig(config)
        return dirty
    }

    private fun work(done: String? = null, block: () -> Unit) {
        viewModelScope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { block() }
                if (done != null) message = done
            } catch (e: Exception) {
                message = e.message ?: "Something went wrong."
            } finally {
                busy = false
            }
        }
    }

    // ------------------------------------------------------------ configuring

    /** Every configuration change goes through here. */
    fun edit(block: (Config) -> Unit) {
        val next = config.copy()
        block(next)
        // Weights follow the priority levels; a new target is placed in one (26 Oct 2026).
        com.chronos.engine.Priorities.settle(next, com.chronos.data.ChronosRepository.logicalToday())
        config = next
        revision++
        dayCache.clear()
        requested.clear()
        work {
            repo.saveConfig(config)
            issues = repo.readiness(config)
            feasibility = repo.feasibility(config)
            // The stored plan no longer matches the settings. Say so rather
            // than re-solving: a week that rearranges itself while you edit a
            // field is worse than a week you are told is out of date.
            bundle?.let { bundle = it.markStale() }
        }
    }

    /**
     * Called when the app comes back to the foreground. If a day ended in the
     * meantime and the nightly job has not closed it yet, close it now; if the
     * job already did, pick up what it changed.
     */
    fun onResume() {
        if (loading) return
        viewModelScope.launch {
            val before = today
            val run = withContext(Dispatchers.IO) { runCatching { AutoPlanner.catchUp(app) }.getOrNull() }
            val rolled = withContext(Dispatchers.IO) { repo.today() } != before ||
                ChronosRepository.logicalToday() != logDay
            if (run?.closed?.isNotEmpty() == true || rolled) {
                dayCache.clear(); requested.clear()
                reload()
                if (run?.closed?.isNotEmpty() == true)
                    message = "The day was closed and the plan rebuilt from what you logged."
            }
        }
    }

    fun changeDayEnd(hhmm: String) {
        AutoPlanner.setDayEnd(app, hhmm)
        dayEnd = hhmm
        logDay = ChronosRepository.logicalToday()
        // An earlier end can mean a day has just ended.
        onResume()
    }

    /**
     * Solve the horizon from [start], or from the current start when null. A
     * date in the past is read as today.
     */
    /** @param time "HH:MM" on the first day, or null for the whole day (only with a [start]). */
    fun rebuild(start: LocalDate? = null, time: String? = null) = work {
        dayCache.clear(); requested.clear()
        modelChanges = emptyList()
        if (start != null) { repo.setPlanStart(start, time); config.planFrom = repo.planFrom() }
        today = repo.today()
        issues = repo.readiness(config)
        // Setup still lacks an answer: the planner waits for it rather than solving with a hole in it.
        if (!Readiness.canSolve(issues)) { message = "Finish Setup first: some answers are still missing."; return@work }
        bundle = repo.rebuild(config)
        coverage = repo.coverage(config, bundle)
        upcoming = repo.dayProblems(config, bundle, UPCOMING_DAYS)
        feasibility = repo.feasibility(config)
        message = "Plan rebuilt." + syncCalendarIfConfigured()
    }

    fun closeOutDay() = work {
        dayCache.clear(); requested.clear()
        bundle = repo.closeOutDay(config)
        coverage = repo.coverage(config, bundle)
        today = repo.today()
        message = "Day closed. The remaining horizon was re-solved." + syncCalendarIfConfigured()
    }

    // ------------------------------------------------------------------ a day

    /** Null until the day has been solved; the screen shows a spinner meanwhile. */
    fun dayResult(date: LocalDate): DaySequencer.Result? = dayCache[date]

    /** Past days whose plan was not kept: their Log shows only the free-form entry. */
    val noPlanKept = mutableStateMapOf<LocalDate, Boolean>()

    /**
     * Days that have begun read the plan they were actually given, which later
     * rebuilds leave alone; the rest are arranged from the current plan.
     */
    fun requestDay(date: LocalDate) {
        val current = bundle ?: return
        if (dayCache.containsKey(date) || !requested.add(date)) return
        // Arranged one at a time (a week asked at once ran seven arrangements side by side), and the
        // result handed to the screen on the main thread: state written from several background
        // threads at once was what Compose refused ("a state created after the snapshot", 23 Oct 2026).
        viewModelScope.launch {
            busy = true
            try {
                val lived = date.isBefore(logDay)
                val result = withContext(Dispatchers.IO) {
                    dayLock.withLock { if (lived) repo.pastDay(config, date) else repo.dayPlan(config, current, date) }
                }
                if (result != null) dayCache[date] = result else noPlanKept[date] = true
            } catch (e: Exception) {
                requested.remove(date)
                message = e.message ?: "Something went wrong."
            } finally { busy = false }
        }
    }

    private val dayLock = kotlinx.coroutines.sync.Mutex()

    // -------------------------------------------------------------- reminders

    /** A tab another part of the app asks to show (a reminder opens the Log). */
    var openTab by mutableStateOf<String?>(null)

    /** A fix to open in Setup, as Feasibility.Remedy.fix names it; Setup's board reads and clears it. */
    var pendingFix by mutableStateOf<String?>(null)

    /** Goes to where a suggested fix is made: the Scheduler step, or the board with the item open. */
    fun goFix(fix: String) {
        setupStep = if (fix == "scheduler") 1 else 2
        if (fix != "scheduler" && fix != "upkeep") pendingFix = fix
        openTab = "setup"
    }

    var remindersOn by mutableStateOf(com.chronos.auto.Reminders.on(app)); private set
    var reminderTimes by mutableStateOf(com.chronos.auto.Reminders.times(app)); private set
    var reminderGapH by mutableStateOf(com.chronos.auto.Reminders.gapHours(app)); private set
    var reminderViaModel by mutableStateOf(com.chronos.auto.Reminders.viaModel(app)); private set
    var notificationsAllowed by mutableStateOf(com.chronos.auto.Reminders.allowed(app))

    fun changeReminders(on: Boolean) { com.chronos.auto.Reminders.setOn(app, on); remindersOn = on }
    fun changeReminderTimes(times: List<String>) { com.chronos.auto.Reminders.setTimes(app, times); reminderTimes = com.chronos.auto.Reminders.times(app) }
    fun changeReminderGap(h: Double) { com.chronos.auto.Reminders.setGapHours(app, h); reminderGapH = h }
    fun changeReminderViaModel(on: Boolean) { com.chronos.auto.Reminders.setViaModel(app, on); reminderViaModel = on }
    fun testReminder() = work { com.chronos.auto.Reminders.check(app, force = true) }

    /** Answers to reminders, as the model read them, waiting for you to confirm. */
    var quickDrafts by mutableStateOf(com.chronos.auto.QuickLog.drafts(app)); private set

    fun refreshQuick() { quickDrafts = com.chronos.auto.QuickLog.drafts(app) }

    /** The entries you kept from one answer go into the log, as if typed into their boxes. */
    fun confirmQuick(id: String, entries: List<com.chronos.auto.QuickLog.Entry>) = work("Saved.") {
        val d = quickDrafts.firstOrNull { it.id == id } ?: return@work
        val day = LocalDate.parse(d.day)
        val blocks = runCatching { repo.dayPlan(config, bundle, day)?.blocks().orEmpty() }.getOrDefault(emptyList())
        val list = com.chronos.auto.QuickLog.toLogs(config, day, blocks, entries)
        list.forEach { repo.addLog(it) }
        logs = repo.logs()
        lateEntries = repo.pendingLateEntries()
        com.chronos.auto.QuickLog.markDone(app, id)
        refreshQuick()
        dayCache.remove(day); requested.remove(day)
        revision++
    }

    /** Your answer to the model's question, or a correction: read again with the conversation so far. */
    fun answerQuick(id: String, text: String) = work { com.chronos.auto.QuickLog.receive(app, text, id); refreshQuick() }

    /** A line typed in the Log instead of the notification. */
    fun quickAnswer(text: String) = work { com.chronos.auto.QuickLog.receive(app, text); refreshQuick() }

    fun dropQuick(id: String) = work { com.chronos.auto.QuickLog.drop(app, id); refreshQuick() }

    // ------------------------------------------------------------- priorities

    /**
     * Why a target needs no priority now, or null while it is in play: its deadline has passed,
     * its amount is done, or its activity is archived. Nothing is stored — extend the deadline
     * and it is compared again.
     */
    fun outOfPlay(g: com.chronos.model.Goal, today: LocalDate = LocalDate.now()): String? {
        val a = com.chronos.engine.Umbrellas.activityOf(config, g)
        return when {
            a?.archived == true -> "archived"
            g.mode == com.chronos.model.Enums.GoalMode.CLOSED && g.deadline?.isBefore(today) == true -> "deadline passed"
            g.mode == com.chronos.model.Enums.GoalMode.CLOSED && (g.totalUnits ?: 0.0) > 0 && g.remainingUnits() <= 0 -> "finished"
            else -> null
        }
    }

    /**
     * Activities still in play on a day, for the lists you pick from (your request of 23 Oct 2026):
     * not archived, its period not over, and — for goal work — its target not finished or past its
     * deadline. [keep] stays in the list whatever it is (an entry already saved with it).
     */
    fun activitiesOn(day: LocalDate, keep: Collection<String?> = emptyList()): List<com.chronos.model.Activity> =
        config.activities.filter { a ->
            a.id in keep || (!a.archived && !a.endedBy(day) &&
                (a.kind != com.chronos.model.Enums.ActivityKind.GOAL ||
                    config.goal(a.goalId)?.let { outOfPlay(it, day) == null } != false))
        }

    /**
     * What a day's log may name (your request of 25 Oct 2026): as [activitiesOn], except that a target
     * whose units are all marked done stays while its deadline has not passed — a deadline you extended
     * means the work goes on, and its sessions must be loggable. Past deadlines, archived activities and
     * ended upkeep stay out.
     */
    fun loggableOn(day: LocalDate, keep: Collection<String?> = emptyList()): List<com.chronos.model.Activity> =
        config.activities.filter { a ->
            a.id in keep || (!a.archived && !a.endedBy(day) &&
                (a.kind != com.chronos.model.Enums.ActivityKind.GOAL ||
                    config.goal(a.goalId)?.let { outOfPlay(it, day).let { why -> why == null || why == "finished" } } != false))
        }

    /** Targets in play: the ones the plan still works on. */
    fun targetsInPlay(today: LocalDate = LocalDate.now()) = config.goals.filter { outOfPlay(it, today) == null }

    /** A goal just named: its first activity's sheet opens next (Setup reads and clears this). */
    var pendingNewActivity by mutableStateOf<String?>(null)

    /**
     * Priorities as levels and an order (your choice of 26 Oct 2026). [order] is every target shown,
     * top to bottom, with the level it now sits in; targets not shown keep their places after them.
     * [confirmed] are the ones you touched: no longer "chosen by the app".
     */
    fun reorderPriorities(order: List<Pair<String, Int>>, confirmed: Collection<String>) = edit { cfg ->
        val next = IntArray(4)
        val shown = order.map { it.first }.toSet()
        order.forEach { (id, tier) ->
            cfg.goal(id)?.let { g -> g.tier = tier; g.rank = next[tier]++; if (id in confirmed) g.tierAuto = false }
        }
        for (t in 0..3) com.chronos.engine.Priorities.level(cfg, t).filter { it.id !in shown }.forEach { it.rank = next[t]++ }
    }

    /** A target's level from its editor or its row: at the end of that level, and confirmed. */
    fun setTier(goalId: String, tier: Int) = edit { cfg ->
        cfg.goal(goalId)?.let { g ->
            if (g.tier != tier) { g.tier = tier; g.rank = Int.MAX_VALUE }
            g.tierAuto = false
        }
    }

    /** Keeps what the app chose for these targets. */
    fun confirmPriorities(ids: Collection<String>) = edit { cfg -> ids.forEach { cfg.goal(it)?.tierAuto = false } }

    fun forgetGoal(goalId: String) {
        comparisons = comparisons.filterKeys { !it.split("|").contains(goalId) }
        work { repo.dropComparisonsFor(goalId) }
    }

    // -------------------------------------------------------------------- log

    fun addLog(entry: LogEntry) = work("Saved.") {
        repo.addLog(entry)
        logs = repo.logs()
    }

    /**
     * Feedback written from the day's lists. Quiet, because it is saved a tap
     * at a time; entries keep a stable id per hour or per block, so a changed
     * answer replaces the old one rather than adding a second.
     */
    fun putLogs(entries: List<LogEntry>) {
        if (entries.isEmpty()) return
        val byId = entries.associateBy { it.id }
        logs = logs.filter { it.id !in byId } + entries
        work {
            entries.forEach { repo.addLog(it) }
            logs = repo.logs()
            lateEntries = repo.pendingLateEntries()
            // A night logged in the sleep box can change this morning's rest check.
            if (entries.any { it.kind == com.chronos.model.Enums.LogKind.SLEEP }) refreshWellbeing()
        }
    }

    fun dropLogs(ids: Collection<String>) {
        if (ids.isEmpty()) return
        logs = logs.filter { it.id !in ids }
        work {
            var progressMoved = false
            ids.forEach { if (repo.removeLog(it)) progressMoved = true }
            afterRemoval(progressMoved)
        }
    }

    fun removeLog(id: String) = work { afterRemoval(repo.removeLog(id)) }

    /** A deleted session whose units were already counted took them back from its goal. */
    private fun afterRemoval(progressMoved: Boolean) {
        logs = repo.logs()
        lateEntries = repo.pendingLateEntries()
        if (progressMoved) {
            config = repo.loadConfig()
            issues = repo.readiness(config)
            bundle?.let { if (repo.storedPlan(config)?.stale() == true) bundle = it.markStale() }
            revision++
        }
    }

    /**
     * Takes in what was filled in or corrected for days already closed: the
     * units go into progress and the entries into the model, now rather than
     * tonight. The plan is not rebuilt; if the model moved, the Horizon screen
     * says so and offers the rebuild.
     */
    fun applyLateEntries() = work {
        val changes = repo.applyLateEntries(config)
        config = repo.loadConfig()
        LogExport.record(app, "late entries applied", emptyList(), changes, config)
        issues = repo.readiness(config)
        logs = repo.logs()
        lateEntries = repo.pendingLateEntries()
        val stale = repo.storedPlan(config)?.stale() == true
        bundle?.let { if (stale) bundle = it.markStale() }
        modelChanges = if (stale) changes else emptyList()
        dayCache.clear(); requested.clear()
        revision++
        message = if (stale) "Your entries changed the model. The Horizon tab says what, and offers a rebuild."
                  else "Your entries were taken in. The model did not move enough to change the plan."
    }

    fun calibrate() = work {
        val outcome = repo.calibrate(config)
        calibration = outcome
        config = repo.loadConfig()
        issues = repo.readiness(config)
        dayCache.clear(); requested.clear()
        revision++
        message = "Recalibrated on ${outcome.cleanObservations()} clean observations."
    }

    /**
     * Writes every log entry, the coefficients as they stand and their history
     * to the file the user picked. The system file picker hands over the place;
     * the app never chooses one itself.
     */
    fun exportLog(uri: android.net.Uri) = work {
        val json = LogExport.build(app, config, repo.logs(), repo.comparisons(), repo.restDays()).toString(2)
        val out = app.contentResolver.openOutputStream(uri, "wt")
            ?: throw IllegalStateException("That location cannot be written to.")
        out.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        message = "Saved: every goal, activity and log entry, and how the model changed."
    }

    // --------------------------------------------------------------- calendar

    fun refreshCalendars() = work { calendars = CalendarWriter.available(app) }

    fun chooseCalendar(id: Long) = work { calendarId = id; repo.setCalendarId(id) }

    fun createLocalCalendar() = work("A phone-only Chronos calendar was created.") {
        val id = CalendarWriter.ensureLocalCalendar(app, "Chronos")
        if (id > 0) { calendarId = id; repo.setCalendarId(id) }
        calendars = CalendarWriter.available(app)
    }

    fun pushToCalendar() = work {
        val id = calendarId
        if (id == null) { message = "Choose a calendar first."; return@work }
        val report = CalendarSync.push(app, repo, config, bundle, id,
            includeUpkeepInCalendar, reminderMinutes ?: -1, AutoPlanner.sleepTitle(app))
        message = report.message()
    }

    private fun hasCalendarPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Once a calendar has been chosen, every new plan is pushed to it. Without
     * this the calendar quietly kept the previous week — the plan the user saw
     * on the Day screen and the events in Google Calendar drifted apart after
     * the first rebuild.
     */
    private fun syncCalendarIfConfigured(): String {
        val id = calendarId ?: return ""
        if (!hasCalendarPermission()) return ""
        return try {
            val report = CalendarSync.push(app, repo, config, bundle, id,
                includeUpkeepInCalendar, reminderMinutes ?: -1, AutoPlanner.sleepTitle(app))
            " Calendar updated: ${report.blocks()} blocks."
        } catch (e: Exception) {
            " Calendar not updated: ${e.message ?: "unknown error"}."
        }
    }

    fun clearCalendar() = work {
        val id = calendarId ?: return@work
        message = CalendarSync.remove(app, repo, id)
    }
}
