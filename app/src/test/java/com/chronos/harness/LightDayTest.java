package com.chronos.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.chronos.engine.LightDay;

import org.junit.Test;

/** What is kept of a heavy day: your word first, moved by the log, and near deadlines protected. */
public class LightDayTest {

    @Test
    public void heavierWordsKeepLess() {
        int a = LightDay.suggest(LightDay.A_LITTLE, null, null, null, null, null, 0, 6, 0).keepPercent();
        int b = LightDay.suggest(LightDay.HEAVY, null, null, null, null, null, 0, 6, 0).keepPercent();
        int c = LightDay.suggest(LightDay.VERY, null, null, null, null, null, 0, 6, 0).keepPercent();
        assertTrue(a > b && b > c);
    }

    @Test
    public void theLogMovesItAndSaysWhy() {
        LightDay.Suggestion s = LightDay.suggest(LightDay.HEAVY, 2, 300, 450, 2.0, 3.5, 0, 6, 0);
        assertEquals(30, s.keepPercent());
        assertEquals(3, s.reasons().size());
    }

    @Test
    public void nearDeadlinesKeepTheirTimeUnlessTheDayIsVeryHeavy() {
        LightDay.Suggestion heavy = LightDay.suggest(LightDay.HEAVY, null, null, null, null, null, 0, 6, 4.5);
        assertEquals(80, heavy.keepPercent());
        assertFalse(heavy.reasons().isEmpty());
        LightDay.Suggestion very = LightDay.suggest(LightDay.VERY, null, null, null, null, null, 0, 6, 4.5);
        assertEquals("your word stands, and you are told", 40, very.keepPercent());
        assertEquals(75, very.firmPercent());
        assertFalse(very.reasons().isEmpty());
    }

    @Test
    public void neverNothingAndNeverAll() {
        assertEquals(20, LightDay.suggest(LightDay.VERY, 1, 200, 450, 1.0, 4.0, 9, 6, 0).keepPercent());
        assertTrue(LightDay.suggest(LightDay.A_LITTLE, null, null, null, null, null, 0, 6, 0).keepPercent() < 100);
    }
}
