package com.eurobuddha.minimacore.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BatteryPolicyTest {

    @Test public void chargerLiftsAnyProfileToInstantOnlyWhenEnabled() {
        for (BatteryPolicy.Profile p : BatteryPolicy.Profile.values()) {
            assertEquals(BatteryPolicy.Profile.INSTANT, BatteryPolicy.effective(p, true, true));
            assertEquals(p, BatteryPolicy.effective(p, false, true));
            assertEquals(p, BatteryPolicy.effective(p, true, false));
        }
    }

    @Test public void unknownSavedProfileFallsBackToBalanced() {
        assertEquals(BatteryPolicy.Profile.BALANCED, BatteryPolicy.parse("bogus"));
        assertEquals(BatteryPolicy.Profile.SAVER, BatteryPolicy.parse("SAVER"));
    }

    @Test public void screenOffClockRunsOnlyWhileOff() throws Exception {
        BatteryPolicy.setScreenOn(true);
        assertEquals(0, BatteryPolicy.screenOffMillis());
        BatteryPolicy.setScreenOn(false);
        Thread.sleep(20);
        assertTrue(BatteryPolicy.screenOffMillis() >= 15);
        BatteryPolicy.setScreenOn(true);
        assertEquals(0, BatteryPolicy.screenOffMillis());
    }

    @Test public void dhtRoutesUnlessRelayWhenPluggedIsOnBattery() {
        assertEquals("routing", BatteryPolicy.dhtMode(BatteryPolicy.DhtRelay.ALWAYS, false));
        assertEquals("routing", BatteryPolicy.dhtMode(BatteryPolicy.DhtRelay.ALWAYS, true));
        assertEquals("routing", BatteryPolicy.dhtMode(BatteryPolicy.DhtRelay.PLUGGED, true));
        assertEquals("client", BatteryPolicy.dhtMode(BatteryPolicy.DhtRelay.PLUGGED, false));
    }

}
