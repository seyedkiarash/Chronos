package com.chronos.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.chronos.engine.Habits;
import com.chronos.engine.Slots;
import com.chronos.model.Activity;
import com.chronos.model.Config;
import com.chronos.model.Enums.LogKind;
import com.chronos.model.LogEntry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** What the log says about the setup: each pattern needs several observations and a clear difference. */
public class HabitsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);

    private static LogEntry done(String act, LocalDate day, String at, int minutes) {
        LogEntry l = new LogEntry();
        l.id = "session|" + day + "|" + act + "|" + Slots.parse(at);
        l.kind = LogKind.SESSION;
        l.date = day; l.at = at; l.actId = act;
        l.planned = minutes; l.actual = minutes;
        return l;
    }

    private static LogEntry skipped(String act, LocalDate day) {
        LogEntry l = done(act, day, "10:00", 60);
        l.at = null; l.actual = 0;
        return l;
    }

    private static Habits.Finding of(List<Habits.Finding> all, String subject, String kind) {
        for (Habits.Finding f : all) if (f.subject().equals(subject) && f.kind().equals(kind)) return f;
        return null;
    }

    @Test
    public void sessionsOutsideTheirHoursWidenThem() {
        Config cfg = ParityHarness.build(TODAY);
        Activity english = cfg.activity("english");
        english.windows.clear();
        english.windows.add(new Activity.Window("08:00", "12:00"));
        List<LogEntry> logs = new ArrayList<>();
        for (int i = 1; i <= 4; i++) logs.add(done("english", TODAY.minusDays(i), "21:00", 45));
        logs.add(done("english", TODAY.minusDays(5), "09:00", 45));
        Habits.Finding f = of(Habits.find(cfg, logs, TODAY), english.name, "times");
        assertNotNull(f);
        JsonObject a = JsonParser.parseString(f.action()).getAsJsonObject();
        assertEquals("edit_activity", a.get("type").getAsString());
        String windows = a.get("windows").toString();
        assertTrue(windows, windows.contains("08:00") && windows.contains("21:00"));
        assertTrue(f.why(), f.why().contains("4 of your 5"));
    }

    @Test
    public void aWeekdayAlwaysSkippedLeavesItsDays() {
        Config cfg = ParityHarness.build(TODAY);
        List<LogEntry> logs = new ArrayList<>();
        // Tuesdays (Slots.dow 3) skipped four times; the other days kept.
        LocalDate tue = TODAY;
        while (Slots.dow(tue) != 3) tue = tue.minusDays(1);
        for (int w = 0; w < 4; w++) logs.add(skipped("english", tue.minusWeeks(w)));
        for (int i = 0; i < 20; i++) {
            LocalDate d = TODAY.minusDays(i);
            if (Slots.dow(d) != 3) logs.add(done("english", d, "10:00", 30));
        }
        Habits.Finding f = of(Habits.find(cfg, logs, TODAY), cfg.activity("english").name, "days");
        assertNotNull(f);
        String days = JsonParser.parseString(f.action()).getAsJsonObject().get("days").toString();
        assertFalse(days, days.contains("3"));
        assertTrue(days, days.contains("2") && days.contains("4"));
    }

    @Test
    public void sessionsRunningLongLengthenTheBlock() {
        Config cfg = ParityHarness.build(TODAY);
        List<LogEntry> logs = new ArrayList<>();
        for (int i = 1; i <= 5; i++) logs.add(done("coursework", TODAY.minusDays(i), "15:00", 150));
        Habits.Finding f = of(Habits.find(cfg, logs, TODAY), cfg.activity("coursework").name, "length");
        assertNotNull(f);
        assertEquals(150, JsonParser.parseString(f.action()).getAsJsonObject().get("max_block").getAsInt());
    }

    @Test
    public void laterNightsMoveSleepHalfAnHourAtMost() {
        Config cfg = ParityHarness.build(TODAY);          // planned 23:30–07:00
        List<LogEntry> logs = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            LogEntry l = new LogEntry();
            l.id = "sleep|" + TODAY.minusDays(i);
            l.kind = LogKind.SLEEP;
            l.date = TODAY.minusDays(i);
            l.at = "01:00"; l.endAt = "08:30";
            logs.add(l);
        }
        Habits.Finding f = of(Habits.find(cfg, logs, TODAY), "sleep", "sleep");
        assertNotNull(f);
        JsonObject a = JsonParser.parseString(f.action()).getAsJsonObject();
        assertEquals("00:00", a.get("bed").getAsString());
        assertEquals("07:30", a.get("wake").getAsString());
    }

    @Test
    public void aFewObservationsAreNotAHabit() {
        Config cfg = ParityHarness.build(TODAY);
        List<LogEntry> logs = new ArrayList<>();
        logs.add(done("english", TODAY.minusDays(1), "23:00", 30));
        logs.add(done("english", TODAY.minusDays(2), "23:00", 30));
        assertTrue(Habits.find(cfg, logs, TODAY).isEmpty());
    }

    @Test
    public void workPastItsDeadlineIsNotReviewed() {
        Config cfg = ParityHarness.build(TODAY);
        cfg.goal("courses").deadline = TODAY.minusDays(1);
        List<LogEntry> logs = new ArrayList<>();
        for (int i = 1; i <= 5; i++) logs.add(done("coursework", TODAY.minusDays(i + 1), "15:00", 150));
        assertTrue(Habits.find(cfg, logs, TODAY).stream().noneMatch(f -> f.subject().equals(cfg.activity("coursework").name)));
        cfg.goal("courses").deadline = TODAY.plusDays(3);
        assertNotNull(of(Habits.find(cfg, logs, TODAY), cfg.activity("coursework").name, "length"));
    }

    @Test
    public void inPersianTheReasonIsInPersian() {
        Config cfg = ParityHarness.build(TODAY);
        List<LogEntry> logs = new ArrayList<>();
        for (int i = 1; i <= 5; i++) logs.add(done("coursework", TODAY.minusDays(i), "15:00", 150));
        Habits.Finding f = of(Habits.find(cfg, logs, TODAY, true), cfg.activity("coursework").name, "length");
        assertNotNull(f);
        assertTrue(f.why(), f.why().contains("جلسه"));
        assertTrue(f.title(), f.title().contains("بلندتر"));
    }
}
