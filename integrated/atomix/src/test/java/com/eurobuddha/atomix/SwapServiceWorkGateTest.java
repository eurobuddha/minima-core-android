package com.eurobuddha.atomix;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.eurobuddha.atomix.swap.PriceOracle;
import com.eurobuddha.atomix.swap.SwapDb;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** The background watcher may only stop when no money is in flight and no market is live. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 33)
public class SwapServiceWorkGateTest {

    private static SwapDb.Swap swap(String hash, String status) {
        SwapDb.Swap s = new SwapDb.Swap();
        s.hash = hash; s.role = "initiator"; s.direction = "sell"; s.status = status;
        s.sellToken = "0x00"; s.sellAmount = "1"; s.buyToken = "0x00"; s.buyAmount = "1";
        s.counterparty = ""; s.myLegIsMinima = true;
        return s;
    }

    @Test public void idleOnlyWithNothingInFlight() {
        Context ctx = RuntimeEnvironment.getApplication();
        assertFalse("fresh install: no watcher", SwapService.hasWork(ctx));

        ctx.getSharedPreferences("atomix", Context.MODE_PRIVATE).edit().putBoolean("auto_publish", true).commit();
        assertTrue("a live market needs the watcher", SwapService.hasWork(ctx));
        ctx.getSharedPreferences("atomix", Context.MODE_PRIVATE).edit().putBoolean("auto_publish", false)
                .putBoolean(PriceOracle.P_WITHDRAWN, true).putBoolean(PriceOracle.P_TOMBSTONED, false).commit();
        assertTrue("an unconfirmed peg withdrawal needs the watcher", SwapService.hasWork(ctx));
        ctx.getSharedPreferences("atomix", Context.MODE_PRIVATE).edit().putBoolean(PriceOracle.P_TOMBSTONED, true).commit();
        assertFalse(SwapService.hasWork(ctx));

        SwapDb db = new SwapDb(ctx);
        String h = "0x" + "ab".repeat(32);
        db.upsertSwap(swap(h, SwapDb.ST_LOCKED));
        assertTrue("a locked swap needs the watcher", SwapService.hasWork(ctx));
        db.setSwapStatus(h, SwapDb.ST_ERROR);
        assertTrue("ERROR may still need a Minima refund", SwapService.hasWork(ctx));
        db.setSwapStatus(h, SwapDb.ST_REFUNDED);
        assertFalse("a refunded swap is settled", SwapService.hasWork(ctx));
        db.setSwapStatus(h, SwapDb.ST_COMPLETE);
        assertFalse("a complete swap is settled", SwapService.hasWork(ctx));
        db.close();
    }
}
