package com.chronos.model;

import com.chronos.model.Enums.LogKind;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * One observation. Which fields are filled depends on the kind, because a
 * lunch break cannot report how much of a weekly target it reached and a goal
 * session cannot report how restoring it was.
 */
public class LogEntry {
    public String id;
    public LogKind kind;
    public LocalDate date;
    public String at;                 // "HH:mm"
    public long ts;

    public String actId;              // SESSION
    public String comId;              // TRAVEL

    public Integer planned;           // minutes
    public Integer actual;

    public Double attain;             // SESSION on a goal activity, percent
    public Integer focus;             // 1..5
    public Integer fatigueAfter;      // 1..5, one overall rating: logs written before fatigue had two kinds
    public Integer mentalAfter;       // 1..5, how mentally tired afterwards
    public Integer physicalAfter;     // 1..5, how physically tired afterwards
    public Integer restored;          // SESSION on an upkeep activity, 1..5
    public Integer energy;            // CHECKIN, 1..5
    public Double units;              // SESSION on a closed goal: units finished, added to progress at close-out

    // SLEEP: `at` is when you went to bed, `restored` how restful the night was (1..5)
    public String endAt;              // "HH:mm" you got up
    public Integer latencyMin;        // minutes it took to fall asleep
    public Integer wakeups;           // times you woke in the night

    // SESSION on leisure: its after-effects, 1..5, from which its stimulation is learned
    public Integer stopHard;          // how hard it was to stop
    public Integer nextStart;         // how hard it was to get into the next thing
    public Integer craving;           // how much you wanted more afterwards

    public List<String> tags = new ArrayList<>();
    public String note;
}
