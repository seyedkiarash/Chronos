package com.chronos.harness;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Runs the two desktop harnesses as JVM unit tests so `gradlew test` (or the
 * Run gutter in Android Studio) exercises the engine and the storage encoding
 * without a device. Both harnesses print their report to stdout; DataRoundTrip
 * also counts failures, which is what this asserts on.
 */
public class EngineHarnessTest {

    @Test
    public void parityHarnessSolvesAFullWeek() {
        ParityHarness.main(new String[0]);
    }

    @Test
    public void storageEncodingRoundTrips() {
        assertEquals("DataRoundTrip reported failures (see stdout)", 0, DataRoundTrip.run());
    }
}
