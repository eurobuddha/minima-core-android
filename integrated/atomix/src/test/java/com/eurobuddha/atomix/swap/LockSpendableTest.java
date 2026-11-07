package com.eurobuddha.atomix.swap;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** 0.2.9: a counterparty lock whose own HTLC script cannot run (non-HEX owner, non-NUMBER timelock, a missing port) is
 *  never answered or claimed — verified with runscript: such a lock can be neither claimed nor refunded. */
public class LockSpendableTest {
    static final String K = "0x" + "11".repeat(32), C = "0x" + "22".repeat(32), H = "0x" + "AB".repeat(32);
    static JSONObject coin(String p0, String p3, boolean withPort6) throws Exception {
        JSONArray st = new JSONArray();
        String[] v = {p0, "1", "0xdAC17F958D2ee523a2206206994597C13D831ec7", p3, C, H, "0x01"};
        for (int i = 0; i < 7; i++) if (i != 6 || withPort6) st.put(new JSONObject().put("port", i).put("data", v[i]));
        return new JSONObject().put("state", st);
    }
    @Test public void wellFormedLockIsSpendable() throws Exception { assertTrue(MinimaHtlc.lockSpendable(coin(K, "2000000", true))); }
    @Test public void numberOwnerIsRefused() throws Exception { assertFalse(MinimaHtlc.lockSpendable(coin("5", "2000000", true))); }
    @Test public void stringOwnerIsRefused() throws Exception { assertFalse(MinimaHtlc.lockSpendable(coin("[" + K + "]", "2000000", true))); }
    @Test public void hexTimelockIsRefused() throws Exception { assertFalse(MinimaHtlc.lockSpendable(coin(K, "0x1E8480", true))); }
    @Test public void missingPortIsRefused() throws Exception { assertFalse(MinimaHtlc.lockSpendable(coin(K, "2000000", false))); }
}
