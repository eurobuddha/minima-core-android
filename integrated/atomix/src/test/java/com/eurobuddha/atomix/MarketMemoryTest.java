package com.eurobuddha.atomix;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.SharedPreferences;

import com.eurobuddha.atomix.swap.PriceOracle;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-currency market memory (MainActivity.snapshotMarket / restoreMarket, used by doSwitchCurrency).
 * A switch parks the leaving currency's CONFIG under its own key and restores the arriving currency's, so a flick
 * back keeps your ladder. FUND-RELEVANT invariant: the snapshot carries CONFIG only — never the runtime/price
 * state — so a restored market re-fetches the target currency's own price (no cross-currency price bleed).
 */
public class MarketMemoryTest {

    /** A map-backed SharedPreferences so snapshot/restore actually mutate state (only the methods they call). */
    private SharedPreferences prefs(final Map<String, Object> m) {
        SharedPreferences p = mock(SharedPreferences.class);
        SharedPreferences.Editor ed = mock(SharedPreferences.Editor.class);
        when(p.getString(anyString(), nullable(String.class))).thenAnswer(i -> {
            Object o = m.get(i.<String>getArgument(0)); return o instanceof String ? o : i.getArgument(1); });
        when(p.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> {
            Object o = m.get(i.<String>getArgument(0)); return o instanceof Boolean ? o : i.getArgument(1); });
        when(p.edit()).thenReturn(ed);
        when(ed.putString(anyString(), nullable(String.class))).thenAnswer(i -> { m.put(i.getArgument(0), i.getArgument(1)); return ed; });
        when(ed.putBoolean(anyString(), anyBoolean())).thenAnswer(i -> { m.put(i.getArgument(0), i.getArgument(1)); return ed; });
        when(ed.remove(anyString())).thenAnswer(i -> { m.remove(i.<String>getArgument(0)); return ed; });
        return p;
    }

    @Test public void snapshotThenRestoreRoundTripsTheMarketConfig() {
        Map<String, Object> m = new HashMap<>();
        m.put("order_config", "{\"USDT\":{\"en\":true}}");
        m.put("auto_publish", true);
        m.put(PriceOracle.P_ENABLE, true);
        m.put(PriceOracle.P_STEP, "1");
        m.put(PriceOracle.P_SIZE, "30");
        m.put(PriceOracle.P_LEVELS, "1");
        m.put(PriceOracle.P_BIAS, "0.5");
        m.put(PriceOracle.P_REPRICE, "1");
        SharedPreferences p = prefs(m);

        MainActivity.snapshotMarket(p, "mxusdt");

        // the switch reset wipes the ACTIVE keys before we go to the other currency
        m.remove("order_config"); m.put("auto_publish", false); m.put(PriceOracle.P_ENABLE, false);
        m.remove(PriceOracle.P_STEP); m.remove(PriceOracle.P_SIZE); m.remove(PriceOracle.P_LEVELS);

        MainActivity.restoreMarket(p, "mxusdt");   // flick back

        assertEquals("ladder restored", "{\"USDT\":{\"en\":true}}", m.get("order_config"));
        assertEquals(Boolean.TRUE, m.get("auto_publish"));
        assertEquals(Boolean.TRUE, m.get(PriceOracle.P_ENABLE));
        assertEquals("1", m.get(PriceOracle.P_STEP));
        assertEquals("30", m.get(PriceOracle.P_SIZE));
        assertEquals("1", m.get(PriceOracle.P_LEVELS));
        assertEquals("0.5", m.get(PriceOracle.P_BIAS));
        assertEquals("1", m.get(PriceOracle.P_REPRICE));
        // a restored market must be live-able, not carried in as withdrawn/tombstoned
        assertEquals(Boolean.FALSE, m.get(PriceOracle.P_WITHDRAWN));
        assertEquals(Boolean.FALSE, m.get(PriceOracle.P_TOMBSTONED));
    }

    @Test public void snapshotExcludesRuntimeAndPriceState() {
        Map<String, Object> m = new HashMap<>();
        m.put(PriceOracle.P_STEP, "1");                         // config — carried
        m.put(PriceOracle.P_LAST_PRICE, "1.0");                 // runtime/price — must NOT be carried
        m.put(PriceOracle.P_LAST_OK, "1700000000000");
        m.put(PriceOracle.P_LAST_MID, "1.0");
        m.put(PriceOracle.P_WITHDRAWN, true);
        m.put("last_publish_ok", true);
        SharedPreferences p = prefs(m);

        MainActivity.snapshotMarket(p, "minima");
        String snap = (String) m.get("market_minima");
        assertNotNull("snapshot written", snap);
        assertTrue("config IS carried", snap.contains(PriceOracle.P_STEP));
        // cross-currency price-bleed guard: the parked blob must not carry stale price/runtime keys
        assertFalse(snap.contains(PriceOracle.P_LAST_PRICE));
        assertFalse(snap.contains(PriceOracle.P_LAST_OK));
        assertFalse(snap.contains(PriceOracle.P_LAST_MID));
        assertFalse(snap.contains("last_publish_ok"));
    }

    @Test public void restoringOneCurrencyNeverBleedsAnothersLadder() {
        Map<String, Object> m = new HashMap<>();
        // a live MINIMA market
        m.put("order_config", "{\"MINIMA-ladder\":true}");
        m.put("auto_publish", true);
        m.put(PriceOracle.P_STEP, "2");
        SharedPreferences p = prefs(m);

        MainActivity.snapshotMarket(p, "minima");     // park MINIMA under its own key

        // the switch reset wipes the active keys, then we restore the OTHER currency (mxUSDT — never saved)
        m.remove("order_config"); m.put("auto_publish", false); m.remove(PriceOracle.P_STEP);
        MainActivity.restoreMarket(p, "mxusdt");

        // MINIMA's ladder/config must NOT bleed into the active (now-mxUSDT) keys
        assertNull("no MINIMA order_config bleed", m.get("order_config"));
        assertEquals(Boolean.FALSE, m.get("auto_publish"));
        assertNull("no MINIMA peg step bleed", m.get(PriceOracle.P_STEP));
        // MINIMA's parked blob is untouched and still recoverable
        assertNotNull(m.get("market_minima"));
    }

    @Test public void restoreOfUnsavedCurrencyIsANoOp() {
        Map<String, Object> m = new HashMap<>();
        m.put("order_config", "already-here");
        SharedPreferences p = prefs(m);

        MainActivity.restoreMarket(p, "never-saved");

        assertEquals("nothing saved → active keys untouched", "already-here", m.get("order_config"));
    }
}
