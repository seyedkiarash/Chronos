package com.chronos.engine;

import com.chronos.model.Activity;
import com.chronos.model.Config;
import com.chronos.model.Enums.ActivityKind;
import com.chronos.model.Enums.LogKind;
import com.chronos.model.LogEntry;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * What the log says about how the plan is set up (your request of 25 Oct 2026): the hours an
 * activity is allowed, the days it is planned on, how long its sessions are, and when you sleep.
 * Each finding is a change in the Navigator's vocabulary with the numbers that call for it, so it
 * can be measured like any package and approved, or not, by you. Nothing here changes anything.
 *
 * The rules are deliberately cautious: each needs several observations in the last four weeks and
 * a clear difference, because a habit seen twice is not a habit.
 */
public final class Habits {
    private Habits() {}

    /** @param action one Navigator action as JSON; @param kind times | focus | days | extra_days | length | sleep */
    public record Finding(String subject, String kind, String title, String why, String action) {}

    public static final int LOOKBACK_DAYS = 28;
    static final int LEAST_SESSIONS = 4;
    private static final String[] DOW = {"Saturday", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday"};
    private static final String[] DOW_FA = {"شنبه", "یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه"};

    /** Whether the findings are worded in Persian; set by [find]. */
    private static final ThreadLocal<Boolean> FA = ThreadLocal.withInitial(() -> false);

    private static String say(String en, String fa, Object... args) {
        return String.format(Locale.ROOT, FA.get() ? fa : en, args);
    }

    private static String day(int d) { return FA.get() ? DOW_FA[d] : DOW[d] + "s"; }

    public static List<Finding> find(Config cfg, List<LogEntry> logs, LocalDate today) {
        return find(cfg, logs, today, false);
    }

    /** @param fa word the titles and reasons in Persian */
    public static List<Finding> find(Config cfg, List<LogEntry> logs, LocalDate today, boolean fa) {
        FA.set(fa);
        try { return findAll(cfg, logs, today); } finally { FA.remove(); }
    }

    private static List<Finding> findAll(Config cfg, List<LogEntry> logs, LocalDate today) {
        List<LogEntry> recent = new ArrayList<>();
        LocalDate from = today.minusDays(LOOKBACK_DAYS);
        for (LogEntry l : logs)
            if (l.date != null && !l.date.isBefore(from) && !l.date.isAfter(today)) recent.add(l);
        List<Finding> out = new ArrayList<>();
        for (Activity a : cfg.activities) {
            // Past its deadline, done, or its period over: nothing to change about it (26 Oct 2026).
            if (a.archived || a.nap || cfg.over(a, today) || a.name == null) continue;
            List<LogEntry> mine = new ArrayList<>();
            for (LogEntry l : recent)
                if (l.kind == LogKind.SESSION && a.id.equals(l.actId) && l.at != null && l.actual != null && l.actual > 0) mine.add(l);
            Finding wider = times(a, mine);
            Activity widened = a;
            if (wider != null) {
                widened = a.copy();
                widened.windows.clear();
                for (com.google.gson.JsonElement w : com.google.gson.JsonParser.parseString(wider.action())
                        .getAsJsonObject().getAsJsonArray("windows"))
                    widened.windows.add(new Activity.Window(w.getAsJsonObject().get("from").getAsString(),
                        w.getAsJsonObject().get("to").getAsString()));
            }
            Finding sharper = focus(widened, mine);
            // Both are about its hours: one change, or the second would undo the first.
            if (wider != null && sharper != null)
                out.add(new Finding(a.name, "times", sharper.title(), wider.why() + " " + sharper.why(), sharper.action()));
            else if (wider != null) out.add(wider);
            else if (sharper != null) out.add(sharper);
            Finding f;
            f = days(a, recent);
            if (f != null) out.add(f);
            f = extraDays(a, mine);
            if (f != null) out.add(f);
            f = length(a, mine);
            if (f != null) out.add(f);
        }
        Finding s = sleep(cfg, recent);
        if (s != null) out.add(s);
        return out;
    }

    // ------------------------------------------------------------ the rules

    /** Sessions mostly outside the allowed hours: the hours where you actually do it join the windows. */
    static Finding times(Activity a, List<LogEntry> mine) {
        // Hours set apart for each weekday are left as you set them: one window for all days would undo them.
        if (a.windows.isEmpty() || a.windowsByDay() || mine.size() < LEAST_SESSIONS) return null;
        List<int[]> outside = new ArrayList<>();
        for (LogEntry l : mine) {
            int s = minutes(l.at), e = s + l.actual;
            int in = 0;
            for (int m = s; m < e; m++) if (inWindows(a, m % 1440)) in++;
            if (in * 2 < (e - s)) outside.add(new int[]{s, e});
        }
        if (outside.size() < 3 || outside.size() * 10 < mine.size() * 4) return null;
        List<Integer> starts = new ArrayList<>(), ends = new ArrayList<>();
        for (int[] o : outside) { starts.add(o[0]); ends.add(o[1]); }
        int lo = floor15(percentile(starts, 0.1)), hi = ceil15(percentile(ends, 0.9));
        if (hi > 1440) hi = 1440;
        if (hi - lo < (a.minBlock == null ? 30 : a.minBlock)) hi = Math.min(1440, lo + (a.minBlock == null ? 30 : a.minBlock));
        List<int[]> windows = new ArrayList<>();
        for (Activity.Window w : a.windows) {
            int[] sp = Slots.span(w.from, w.to);
            if (sp != null) windows.add(new int[]{sp[0] * Slots.SLOT_MIN, sp[1] * Slots.SLOT_MIN});
        }
        windows.add(new int[]{lo, hi});
        String json = "{\"type\":\"edit_activity\",\"activity\":" + q(a.name) + ",\"windows\":" + windowsJson(merge(windows)) + "}";
        return new Finding(a.name, "times", say("%s: allow the hours you actually use", "%s: ساعت‌هایی که واقعاً انجامش می‌دهی مجاز شود", a.name),
            say("%d of your %d sessions in the last four weeks were mostly outside its allowed hours, "
                + "most of them between %s and %s. Allowing that time lets the plan put it where you really do it.",
                "%d جلسه از %d جلسهٔ چهار هفتهٔ اخیر بیشترش بیرون از ساعت‌های مجازش بود، بیشترشان بین %s و %s. "
                + "اگر این بازه مجاز شود، برنامه آن را همان‌جایی می‌گذارد که واقعاً انجامش می‌دهی.",
                outside.size(), mine.size(), Slots.formatMinutes(lo), Slots.formatMinutes(hi)), json);
    }

    /** Goal work clearly sharper in one part of the day: the weaker part leaves its windows, if enough room stays. */
    static Finding focus(Activity a, List<LogEntry> mine) {
        if (a.kind != ActivityKind.GOAL || a.windows.isEmpty() || a.windowsByDay()) return null;
        int[][] parts = {{0, 720}, {720, 1020}, {1020, 1440}};
        String[] names = FA.get() ? new String[]{"صبح", "بعدازظهر", "عصر و شب"} : new String[]{"morning", "afternoon", "evening"};
        double[] sum = new double[3];
        int[] n = new int[3];
        for (LogEntry l : mine) {
            if (l.focus == null) continue;
            if (l.tags != null && l.tags.stream().anyMatch(Calibrator.CONFOUNDERS::contains)) continue;
            int m = minutes(l.at);
            for (int i = 0; i < 3; i++) if (m >= parts[i][0] && m < parts[i][1]) { sum[i] += l.focus; n[i]++; }
        }
        int best = -1, worst = -1;
        for (int i = 0; i < 3; i++) {
            if (n[i] < 3) continue;
            if (best < 0 || sum[i] / n[i] > sum[best] / n[best]) best = i;
            if (worst < 0 || sum[i] / n[i] < sum[worst] / n[worst]) worst = i;
        }
        if (best < 0 || best == worst) return null;
        double gap = sum[best] / n[best] - sum[worst] / n[worst];
        if (gap < 0.8) return null;
        List<int[]> kept = new ArrayList<>();
        int room = 0;
        for (Activity.Window w : a.windows) {
            int[] sp = Slots.span(w.from, w.to);
            if (sp == null) continue;
            int s = sp[0] * Slots.SLOT_MIN, e = sp[1] * Slots.SLOT_MIN;
            // what of this window lies outside the weaker part of the day
            if (s < parts[worst][0]) kept.add(new int[]{s, Math.min(e, parts[worst][0])});
            if (e > parts[worst][1]) kept.add(new int[]{Math.max(s, parts[worst][1]), e});
        }
        kept.removeIf(k -> k[1] - k[0] < (a.minBlock == null ? 30 : a.minBlock));
        for (int[] k : kept) room += k[1] - k[0];
        if (kept.isEmpty() || room < 2 * (a.maxBlock == null ? 60 : a.maxBlock)) return null;
        String json = "{\"type\":\"edit_activity\",\"activity\":" + q(a.name) + ",\"windows\":" + windowsJson(merge(kept)) + "}";
        return new Finding(a.name, "focus", say("%s: keep it out of the %s", "%s: در %s برنامه‌ریزی نشود", a.name, names[worst]),
            say("Your focus in it averages %.1f/5 in the %s (%d sessions) but %.1f/5 in the %s (%d sessions). "
                + "Leaving the %s out of its hours puts it where you work on it best.",
                "میانگین تمرکزت در آن هنگام %2$s %1$.1f از ۵ است (%3$d جلسه) ولی هنگام %5$s %4$.1f از ۵ (%6$d جلسه). "
                + "اگر %7$s از ساعت‌هایش حذف شود، جایی قرار می‌گیرد که بهتر رویش کار می‌کنی.",
                sum[best] / n[best], names[best], n[best], sum[worst] / n[worst], names[worst], n[worst], names[worst]), json);
    }

    /** A weekday where it is planned but nearly always skipped, while the other days hold. */
    static Finding days(Activity a, List<LogEntry> recent) {
        int[] planned = new int[7], kept = new int[7];
        for (LogEntry l : recent) {
            if (l.kind != LogKind.SESSION || !a.id.equals(l.actId) || l.id == null || !l.id.startsWith("session|")) continue;
            int d = Slots.dow(l.date);
            planned[d]++;
            if (l.at != null && l.actual != null && l.actual > 0) kept[d]++;
        }
        int bad = -1;
        int otherP = 0, otherK = 0;
        for (int d = 0; d < 7; d++) {
            if (!a.days.contains(d)) continue;
            if (planned[d] >= 3 && kept[d] * 4 <= planned[d] && (bad < 0 || kept[d] * planned[bad] < kept[bad] * planned[d])) bad = d;
        }
        if (bad < 0 || a.days.size() <= 1) return null;
        for (int d = 0; d < 7; d++) if (d != bad) { otherP += planned[d]; otherK += kept[d]; }
        if (otherP < 3 || otherK * 10 < otherP * 6) return null;
        TreeSet<Integer> days = new TreeSet<>(a.days);
        days.remove(bad);
        return new Finding(a.name, "days", say("%s: not on %s", "%s: نه در روزهای %s", a.name, day(bad)),
            say("On %s you kept %d of %d planned sessions; on the other days %d of %d. "
                + "Planning it where it actually happens keeps the plan honest.",
                "در روزهای %s از %d جلسهٔ برنامه‌ریزی‌شده %d را انجام دادی؛ در روزهای دیگر %d از %d. "
                + "اگر فقط در روزهایی برنامه‌ریزی شود که واقعاً انجام می‌شود، برنامه واقع‌بینانه‌تر می‌شود.",
                day(bad), FA.get() ? planned[bad] : kept[bad], FA.get() ? kept[bad] : planned[bad],
                otherK, otherP),
            "{\"type\":\"edit_activity\",\"activity\":" + q(a.name) + ",\"days\":" + days + "}");
    }

    /** Done again and again on a weekday it is not planned on. */
    static Finding extraDays(Activity a, List<LogEntry> mine) {
        int[] extra = new int[7];
        for (LogEntry l : mine) {
            int d = Slots.dow(l.date);
            if (!a.days.contains(d)) extra[d]++;
        }
        int best = -1;
        for (int d = 0; d < 7; d++) if (extra[d] >= 3 && (best < 0 || extra[d] > extra[best])) best = d;
        if (best < 0) return null;
        TreeSet<Integer> days = new TreeSet<>(a.days);
        days.add(best);
        return new Finding(a.name, "extra_days", say("%s: also on %s", "%s: در روزهای %s هم", a.name, day(best)),
            say("You did it on %d %s in the last four weeks, a day it is not planned on. "
                + "Adding the day lets the plan count on it.",
                "در چهار هفتهٔ اخیر %d بار در روزهای %s انجامش دادی، روزی که برایش برنامه‌ریزی نمی‌شود. "
                + "اگر این روز اضافه شود، برنامه رویش حساب می‌کند.", extra[best], day(best)),
            "{\"type\":\"edit_activity\",\"activity\":" + q(a.name) + ",\"days\":" + days + "}");
    }

    /** Sessions that keep running well past the longest block, or stop well short of the shortest. */
    static Finding length(Activity a, List<LogEntry> mine) {
        if (a.minBlock == null || a.maxBlock == null || mine.size() < 5) return null;
        List<Integer> lens = new ArrayList<>();
        for (LogEntry l : mine) lens.add(l.actual);
        int median = (int) Math.round(percentile(lens, 0.5));
        boolean upkeep = a.kind == ActivityKind.MAINTENANCE && !a.leisure;
        if (median > a.maxBlock * 1.25) {
            int to = Math.min(300, ceil15(median));
            String json = upkeep ? "{\"type\":\"edit_activity\",\"activity\":" + q(a.name) + ",\"minutes\":" + to + "}"
                : "{\"type\":\"edit_activity\",\"activity\":" + q(a.name) + ",\"max_block\":" + to + "}";
            return new Finding(a.name, "length", say("%s: longer sessions", "%s: جلسه‌های بلندتر", a.name),
                say("Your sessions of it last %d minutes in the middle (%d sessions), while the plan "
                    + "gives it at most %d. Planning %d leaves room for what you actually spend.",
                    "میانهٔ طول جلسه‌هایت %d دقیقه است (%d جلسه)، ولی برنامه حداکثر %d دقیقه به آن می‌دهد. "
                    + "با %d دقیقه، برای زمانی که واقعاً صرف می‌کنی جا می‌ماند.", median, lens.size(), a.maxBlock, to), json);
        }
        if (median < a.minBlock * 0.75) {
            int to = Math.max(15, floor15(median));
            String json = upkeep ? "{\"type\":\"edit_activity\",\"activity\":" + q(a.name) + ",\"minutes\":" + to + "}"
                : "{\"type\":\"edit_activity\",\"activity\":" + q(a.name) + ",\"min_block\":" + Math.max(10, to) + "}";
            return new Finding(a.name, "length", say("%s: shorter sessions", "%s: جلسه‌های کوتاه‌تر", a.name),
                say("Your sessions of it last %d minutes in the middle (%d sessions), while the plan "
                    + "asks for at least %d. Shorter blocks fit the gaps you really have.",
                    "میانهٔ طول جلسه‌هایت %d دقیقه است (%d جلسه)، ولی برنامه دست‌کم %d دقیقه می‌خواهد. "
                    + "بلوک‌های کوتاه‌تر در فاصله‌هایی که واقعاً داری جا می‌شوند.", median, lens.size(), a.minBlock), json);
        }
        return null;
    }

    /** Going to bed and getting up at other times than the plan assumes, night after night. */
    static Finding sleep(Config cfg, List<LogEntry> recent) {
        if (cfg.params.sleepStart == null || cfg.params.sleepEnd == null) return null;
        List<Integer> beds = new ArrayList<>(), ups = new ArrayList<>();
        for (LogEntry l : recent) {
            if (l.kind != LogKind.SLEEP) continue;
            if (l.at != null) beds.add(Math.floorMod(bedMinutes(l.at, l.endAt) - 720, 1440));   // noon-based, so midnight does not split it
            if (l.endAt != null) ups.add(minutes(l.endAt));
        }
        if (beds.size() < 7 || ups.size() < 7) return null;
        int bed = Math.floorMod((int) Math.round(percentile(beds, 0.5)) + 720, 1440), up = (int) Math.round(percentile(ups, 0.5));
        int bedNow = minutes(cfg.params.sleepStart), upNow = minutes(cfg.params.sleepEnd);
        int dBed = circular(bed, bedNow), dUp = circular(up, upNow);
        if (Math.abs(dBed) < 30 && Math.abs(dUp) < 30) return null;
        // One step at a time: the Navigator moves sleep at most half an hour a proposal.
        int newBed = Math.floorMod(bedNow + clamp15(dBed), 1440), newUp = Math.floorMod(upNow + clamp15(dUp), 1440);
        int asleep = Math.floorMod(newUp - newBed, 1440);
        if (asleep < 360 || asleep > 600) return null;
        return new Finding("sleep", "sleep", say("Sleep closer to when you really do", "ساعت خواب نزدیک‌تر به خواب واقعی‌ات"),
            say("Over %d nights you went to bed around %s and got up around %s; the plan assumes %s and %s. "
                + "Moving it half an hour at most towards that keeps the morning plan from starting before you are up.",
                "در %d شب حدود %s خوابیدی و حدود %s بیدار شدی؛ برنامه %s و %s را فرض می‌کند. "
                + "اگر حداکثر نیم ساعت به آن سمت جابه‌جا شود، برنامهٔ صبح پیش از بیدار شدنت شروع نمی‌شود.",
                beds.size(), Slots.formatMinutes(bed), Slots.formatMinutes(up), cfg.params.sleepStart, cfg.params.sleepEnd),
            "{\"type\":\"sleep\",\"bed\":\"" + Slots.formatMinutes(newBed) + "\",\"wake\":\"" + Slots.formatMinutes(newUp) + "\"}");
    }

    // ------------------------------------------------------------ helpers

    private static int clamp15(int d) { return Math.max(-30, Math.min(30, Math.round(d / 15f) * 15)); }

    /** Signed minutes from b to a, the short way round the clock. */
    private static int circular(int a, int b) {
        int d = Math.floorMod(a - b, 1440);
        return d > 720 ? d - 1440 : d;
    }

    /**
     * When you went to bed, in minutes, with the slip of the 24-hour clock taken back: 12:40 with a
     * 06:15 rise is 00:40 (12 and 00 sit on two rings of the dial), 11:10 with 08:05 is 23:10. A
     * bedtime between nine and three by day counts as the night's only when the time in bed is
     * between 4 and 14 hours.
     */
    public static int bedMinutes(String at, String endAt) {
        int m = minutes(at);
        if (endAt == null || m < 9 * 60 || m >= 15 * 60) return m;
        int inBed = Math.floorMod(minutes(endAt) - m, 1440);
        return inBed < 240 || inBed > 840 ? (m + 720) % 1440 : m;
    }

    static int minutes(String hhmm) {
        String[] p = hhmm.split(":");
        return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
    }

    private static boolean inWindows(Activity a, int minute) {
        for (Activity.Window w : a.windows) {
            int[] sp = Slots.span(w.from, w.to);
            if (sp != null && minute >= sp[0] * Slots.SLOT_MIN && minute < sp[1] * Slots.SLOT_MIN) return true;
        }
        return false;
    }

    private static double percentile(List<Integer> v, double p) {
        List<Integer> s = new ArrayList<>(v);
        Collections.sort(s);
        double idx = p * (s.size() - 1);
        int lo = (int) Math.floor(idx), hi = (int) Math.ceil(idx);
        return s.get(lo) + (s.get(hi) - s.get(lo)) * (idx - lo);
    }

    private static int floor15(double m) { return (int) Math.floor(m / 15) * 15; }
    private static int ceil15(double m) { return (int) Math.ceil(m / 15) * 15; }

    private static List<int[]> merge(List<int[]> spans) {
        List<int[]> s = new ArrayList<>(spans);
        s.sort((x, y) -> Integer.compare(x[0], y[0]));
        List<int[]> out = new ArrayList<>();
        for (int[] w : s) {
            if (!out.isEmpty() && w[0] <= out.get(out.size() - 1)[1]) out.get(out.size() - 1)[1] = Math.max(out.get(out.size() - 1)[1], w[1]);
            else out.add(new int[]{w[0], w[1]});
        }
        return out;
    }

    private static String windowsJson(List<int[]> spans) {
        StringBuilder sb = new StringBuilder("[");
        for (int[] w : spans) {
            if (sb.length() > 1) sb.append(',');
            sb.append("{\"from\":\"").append(Slots.formatMinutes(w[0])).append("\",\"to\":\"")
              .append(w[1] >= 1440 ? "24:00" : Slots.formatMinutes(w[1])).append("\"}");
        }
        return sb.append(']').toString();
    }

    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
