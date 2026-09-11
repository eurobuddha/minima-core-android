package org.minimarex.minimacore.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Before;

/**
 * The debounce that stopped the hourly tick sampling twice.
 *
 * The Alarm both starts the service and asks for a check, and starting a running service
 * delivers onStartCommand, which asks again - two identical samples milliseconds apart,
 * which is exactly what the field log showed on the first release.
 */
public class NodeHealthMonitorTest {

    /** Static state is shared across tests in one JVM - start each from a clean slate. */
    @Before public void resetDebounce() throws Exception {
        Field f = NodeHealthMonitor.class.getDeclaredField("LAST_CHECK");
        f.setAccessible(true);
        ((AtomicLong) f.get(null)).set(0);
    }

    private static boolean claim(long now) throws Exception {
        Method m = NodeHealthMonitor.class.getDeclaredMethod("claimCheck", long.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, now);
    }

    @Test public void twoCallersAtTheSameTickRunOnlyOneCheck() throws Exception {
        long t = 5_000_000L;
        assertTrue("first caller should win", claim(t));
        assertFalse("the duplicate 7ms later must be dropped", claim(t + 7));
        assertFalse(claim(t + NodeHealthMonitor.MIN_CHECK_INTERVAL_MILLIS - 1));
    }

    /** A clock correction must not silently suppress every check until it catches up. */
    @Test public void aClockJumpBackwardsStillRunsAcheck() throws Exception {
        long t = 5_000_000L;
        assertTrue(claim(t));
        assertTrue("time moved backwards - re-baseline and run",
                claim(t - (60L * 60 * 1000)));
    }

    @Test public void theNextRealTickStillRuns() throws Exception {
        long t = 9_000_000L;
        assertTrue(claim(t));
        assertTrue("an hour later must not be debounced away",
                claim(t + 60L * 60 * 1000));
    }
}
