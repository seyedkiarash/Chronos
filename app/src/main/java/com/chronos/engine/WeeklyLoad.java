package com.chronos.engine;

import com.chronos.model.Activity;
import com.chronos.model.Commitment;
import com.chronos.model.Config;
import com.chronos.model.Enums.CommitmentKind;
import com.chronos.model.Enums.LogKind;
import com.chronos.model.LogEntry;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * How much the last week has taken out of you, against what a normal week does.
 *
 * A day's load is its hours weighted by how draining each was, mind and body
 * together: an hour of an activity with mental load 0.6 and physical load 0.2
 * counts 0.8, an hour of lunch counts nothing. It is read
 * from what was logged — sessions, trips — plus the day's fixed classes, which
 * happen whether or not anyone logs them. Days with nothing logged are left out
 * rather than counted as empty, because an unlogged day is unknown, not idle.
 *
 * The comparison is the acute:chronic ratio used for training load: this
 * week's daily average over the average of the last four weeks. Around 1 is a
 * week like the others; 1.3 is a noticeably heavy one; 1.5 and above is the
 * kind that ends in a crash. It needs two weeks of logged days to mean
 * anything, and says so until then.
 */
public final class WeeklyLoad {
    private WeeklyLoad() {}

    public static final int MIN_DAYS_FOR_RATIO = 14;

    public record DayLoad(LocalDate date, double loadH, double hours, boolean logged) {}

    /**
     * @param weekLoadH  load over the last seven days, scaled to a full week
     *                   from the days that were logged
     * @param typicalWeekLoadH the same over the last 28 days, or null with too little data
     * @param ratio      weekLoadH / typicalWeekLoadH, or null
     */
    public record Report(List<DayLoad> lastWeek, double weekLoadH, Double typicalWeekLoadH,
                         Double ratio, int daysLogged) {}

    /** One day's load from its log and its fixed classes. */
    public static DayLoad of(Config cfg, LocalDate date, List<LogEntry> dayLogs) {
        double load = 0, hours = 0;
        boolean logged = false;
        for (LogEntry l : dayLogs) {
            if (l.at == null || l.actual == null || l.actual <= 0) continue;
            double h = l.actual / 60.0;
            if (l.kind == LogKind.SESSION) {
                Activity a = cfg.activity(l.actId);
                if (a == null || a.load == null) continue;
                load += h * (a.load + (a.physLoad == null ? 0 : a.physLoad));
                hours += h;
                logged = true;
            } else if (l.kind == LogKind.TRAVEL) {
                Commitment c = null;
                for (Commitment k : cfg.commitments) if (k.id.equals(l.comId)) c = k;
                if (c == null) continue;
                load += h * ((c.load == null ? 0 : c.load) + (c.physLoad == null ? 0 : c.physLoad));
                hours += h;
                logged = true;
            }
        }
        int dow = Slots.dow(date);
        for (Commitment c : cfg.commitments) {
            if (c.kind != CommitmentKind.FIXED) continue;
            for (Commitment.Session s : c.sessions) {
                if (s.day != dow) continue;
                int[] span = Slots.span(s.start, s.end);
                if (span == null) continue;
                double h = (span[1] - span[0]) * Slots.SLOT_MIN / 60.0;
                load += h * (s.loadOr(c.load) + s.physLoadOr(c.physLoad));
                hours += h;
            }
        }
        return new DayLoad(date, load, hours, logged);
    }

    /** The week up to and including {@code today}, against the four weeks before it. */
    public static Report report(Config cfg, LocalDate today, List<LogEntry> logs) {
        List<DayLoad> week = new ArrayList<>();
        double weekSum = 0, monthSum = 0;
        int weekDays = 0, monthDays = 0;
        for (int i = 27; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            List<LogEntry> mine = new ArrayList<>();
            for (LogEntry l : logs) if (d.equals(l.date)) mine.add(l);
            DayLoad dl = of(cfg, d, mine);
            if (i < 7) week.add(dl);
            if (!dl.logged()) continue;
            monthSum += dl.loadH();
            monthDays++;
            if (i < 7) { weekSum += dl.loadH(); weekDays++; }
        }
        double weekLoad = weekDays == 0 ? 0 : weekSum / weekDays * 7;
        Double typical = monthDays >= MIN_DAYS_FOR_RATIO ? monthSum / monthDays * 7 : null;
        Double ratio = typical != null && typical > 0 && weekDays >= 3 ? weekLoad / typical : null;
        return new Report(week, weekLoad, typical, ratio, monthDays);
    }
}
