package com.chronos.calendar;

import android.content.Context;

import com.chronos.data.ChronosRepository;
import com.chronos.engine.DaySequencer;
import com.chronos.engine.Slots;
import com.chronos.model.Block;
import com.chronos.model.Config;
import com.chronos.model.Goal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Seven rolling days, rewritten whenever the plan changes. A month of guessed
 * blocks would only clutter a calendar that the user also lives in.
 */
public final class CalendarSync {
    private CalendarSync() {}

    public static final int ROLLING_DAYS = 7;

    /** How far the pre-write sweep reaches, so no block of an older plan survives. */
    public static final int SWEEP_BACK_DAYS = 30;
    public static final int SWEEP_DAYS = 120;

    public record Report(int days, int blocks, String message) {}

    /**
     * @param bundle the plan the user is looking at. Passing it in, rather than
     *               loading the latest snapshot here, guarantees the calendar
     *               shows exactly the days the Day screen shows — same snapshot,
     *               same seed, same cached day rows.
     */
    public static Report push(Context context, ChronosRepository repo, Config cfg,
                              ChronosRepository.PlanBundle bundle,
                              long calendarId, boolean includeUpkeep, int reminderMinutes) {
        return push(context, repo, cfg, bundle, calendarId, includeUpkeep, reminderMinutes, null);
    }

    /**
     * The night's sleep as a block, starting on {@code date}: from bedtime to
     * waking. A bedtime after midnight belongs to the date it falls on, so the
     * event always starts inside the day it is filed under and is replaced
     * with that day.
     */
    static Block sleepBlock(Config cfg, String title) {
        if (title == null || cfg.params.sleepStart == null || cfg.params.sleepEnd == null) return null;
        int s = Slots.parse(cfg.params.sleepStart), e = Slots.parse(cfg.params.sleepEnd);
        int dur = Math.floorMod(e - s, Slots.PER_DAY);
        if (dur == 0) return null;
        Block b = new Block();
        b.name = title;
        b.start = s;
        b.dur = dur;
        b.fixed = true;
        return b;
    }

    /**
     * One push at a time. The screen and the Navigator's background work can both push at
     * once; interleaved, each one's sweep missed the other's new events, and a day ended up
     * with every block twice (seen on 21 Oct 2026) — two alerts for one block.
     */
    private static final Object LOCK = new Object();

    /** @param sleepTitle the calendar title for sleep, or null to leave sleep out */
    public static Report push(Context context, ChronosRepository repo, Config cfg,
                              ChronosRepository.PlanBundle bundle,
                              long calendarId, boolean includeUpkeep, int reminderMinutes,
                              String sleepTitle) {
        synchronized (LOCK) {
            return pushLocked(context, repo, cfg, bundle, calendarId, includeUpkeep, reminderMinutes, sleepTitle);
        }
    }

    private static Report pushLocked(Context context, ChronosRepository repo, Config cfg,
                                     ChronosRepository.PlanBundle bundle,
                                     long calendarId, boolean includeUpkeep, int reminderMinutes,
                                     String sleepTitle) {
        Block sleep = sleepBlock(cfg, sleepTitle);
        java.util.Set<String> naps = new java.util.HashSet<>();
        for (com.chronos.model.Activity a : cfg.activities) if (a.nap && a.name != null) naps.add(a.name);
        if (bundle == null) bundle = repo.storedPlan(cfg);
        if (bundle == null) bundle = repo.rebuild(cfg);

        List<String> goalIds = new ArrayList<>();
        for (Goal g : cfg.goals) goalIds.add(g.id);
        CalendarWriter.Colours colours = CalendarWriter.coloursFor(context, calendarId, goalIds);

        LocalDate start = repo.today();

        // Sweep this app's own events out of the whole window first, not just
        // the seven days about to be written. A rebuilt plan can move a block
        // off a day entirely, and clearing day by day would leave that block
        // behind; a plan that got shorter would strand the tail of the old one.
        // Anything the user put in that calendar themselves has no marker and
        // is never touched.
        //
        // The sweep starts at the day the user is in, not a month back: the
        // plan is now rebuilt every night, and sweeping backwards would erase
        // yesterday's blocks each time — the record of what the day was meant
        // to hold. Days that have ended keep the events they were given.
        int removed = CalendarWriter.clearRange(context, calendarId,
                                                ChronosRepository.logicalToday(), SWEEP_DAYS);

        int days = 0, blocks = 0;
        for (int i = 0; i < ROLLING_DAYS; i++) {
            LocalDate date = start.plusDays(i);
            DaySequencer.Result result = repo.dayPlan(cfg, bundle, date);
            CalendarWriter.SyncResult r = CalendarWriter.pushDay(
                context, calendarId, date, result.blocks(), sleep, naps, colours, includeUpkeep,
                reminderMinutes);
            blocks += r.written();
            days++;
            repo.markSynced(date);
        }
        repo.setSyncedThrough(start.plusDays(ROLLING_DAYS - 1L));
        return new Report(days, blocks,
            blocks + " blocks across " + days + " days sent to your calendar"
            + (removed > 0 ? ", replacing " + removed + " older ones." : "."));
    }

    public static String remove(Context context, ChronosRepository repo, long calendarId) {
        synchronized (LOCK) { return removeLocked(context, repo, calendarId); }
    }

    private static String removeLocked(Context context, ChronosRepository repo, long calendarId) {
        int n = CalendarWriter.clearRange(context, calendarId, repo.today().minusDays(30), 60);
        return n + " Chronos events removed. Nothing else in that calendar was touched.";
    }
}
