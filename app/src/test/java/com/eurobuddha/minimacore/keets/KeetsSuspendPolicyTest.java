package com.eurobuddha.minimacore.keets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eurobuddha.minimacore.service.BatteryPolicy.Profile;

import org.junit.Test;

public class KeetsSuspendPolicyTest {

    @Test public void onlySaverSuspendsAndOnlyAfterFiveMinutesScreenOff() {
        assertTrue(KeetsWorklet.shouldSuspend(Profile.SAVER, false, false, 5 * 60_000));
        assertFalse(KeetsWorklet.shouldSuspend(Profile.SAVER, false, false, 4 * 60_000));
        assertFalse(KeetsWorklet.shouldSuspend(Profile.SAVER, true, false, 60 * 60_000));
        assertFalse(KeetsWorklet.shouldSuspend(Profile.SAVER, false, true, 60 * 60_000));
        assertFalse(KeetsWorklet.shouldSuspend(Profile.BALANCED, false, false, 60 * 60_000));
        assertFalse(KeetsWorklet.shouldSuspend(Profile.INSTANT, false, false, 60 * 60_000));
    }
}
