package com.eurobuddha.entropy;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * The node does not see or decide a die face: it is asked for `random` only (0x + 64 hex, never repeated),
 * the device bytes never leave the phone, and the face comes from the SHA3-256 worked out here (checked
 * against the JDK's own SHA3-256). Any node failure discards the roll and reports it.
 */
public class RollEngineTest {

    static final String A = "0x" + "1f".repeat(32), A2 = "0x" + "2e".repeat(32);

    static String jdkSha3(String hex) throws Exception {
        byte[] in = new byte[(hex.length() - 2) / 2];
        for (int i = 0; i < in.length; i++) in[i] = (byte) Integer.parseInt(hex.substring(2 + 2 * i, 4 + 2 * i), 16);
        StringBuilder sb = new StringBuilder("0x");
        for (byte x : MessageDigest.getInstance("SHA3-256").digest(in)) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** A node whose `random` answers come from a list ("ERR" = an error reply); it records every command. */
    static final class FakeNode implements RollEngine.Node {
        final List<String> randoms; int r = 0;
        final List<String> commands = new ArrayList<>();
        FakeNode(List<String> randoms) { this.randoms = randoms; }
        @Override public void cmd(String command, NodeApi.Cb cb) {
            commands.add(command);
            String v = randoms.get(Math.min(r++, randoms.size() - 1));
            if ("ERR".equals(v)) { cb.onError("timeout"); return; }
            try { cb.onResult(new JSONObject().put("status", true).put("response", new JSONObject().put("random", v))); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    static final class Result { Integer face; RollEngine.Audit audit; String error; }

    static Result roll(RollEngine e) {
        Result res = new Result();
        e.rollOne(new RollEngine.RollCb() {
            @Override public void onFace(int face, RollEngine.Audit audit) { res.face = face; res.audit = audit; }
            @Override public void onError(String m) { res.error = m; }
        });
        return res;
    }

    static RollEngine engine(FakeNode n) { RollEngine e = new RollEngine(n); e.setNodeOk(true); return e; }

    static void assertDiscarded(Result r, String why) {
        assertNull("no face may come from a refused answer", r.face);
        assertEquals(why, r.error);
    }

    @Test public void localDigestIsSha3_256OfTheRawBytes() {
        assertEquals("0xa7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a", RollEngine.localHash("0x"));
        assertEquals("0x3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532", RollEngine.localHash("0x616263"));
    }

    @Test public void theNodeIsAskedForRandomOnlyAndNeverSeesTheDeviceBytes() throws Exception {
        FakeNode n = new FakeNode(List.of(A));
        Result r = roll(engine(n));
        assertNull(r.error);
        assertEquals(List.of("random"), n.commands);
        for (String c : n.commands) assertFalse(c.contains(r.audit.b.substring(2)));
        assertEquals(A, r.audit.a);
        String h = jdkSha3(A + r.audit.b.substring(2));
        assertEquals("the audit keeps the local hash", h, r.audit.h);
        long v = Long.parseLong(h.substring(2, 10), 16);
        if (v < RollEngine.REJECT_THRESHOLD) assertEquals(Integer.valueOf((int) (v % 6) + 1), r.face);
        assertEquals(RollEngine.SRC_NODE_DEVICE, r.audit.src);
    }

    @Test public void anEmptyRandomDiscardsTheRoll() {
        assertDiscarded(roll(engine(new FakeNode(List.of("")))), RollEngine.NODE_BAD);
    }

    @Test public void aWrongLengthRandomDiscardsTheRoll() {
        for (String bad : new String[]{"0x" + "1f".repeat(31), "0x" + "1f".repeat(33), "0x1f", "1f".repeat(32) + "ab", "0x" + "zz".repeat(32)})
            assertDiscarded(roll(engine(new FakeNode(List.of(bad)))), RollEngine.NODE_BAD);
    }

    @Test public void aRepeatedRandomDiscardsTheRoll() {
        RollEngine e = engine(new FakeNode(List.of(A, A.toUpperCase().replace("0X", "0x"))));
        assertNull(roll(e).error);
        assertDiscarded(roll(e), RollEngine.NODE_BAD);
    }

    @Test public void freshRandomsKeepRolling() {
        RollEngine e = engine(new FakeNode(List.of(A, A2)));
        assertNull(roll(e).error);
        assertNull(roll(e).error);
    }

    @Test public void aNodeErrorDiscardsTheRollAndIsReported() {
        assertDiscarded(roll(engine(new FakeNode(List.of("ERR")))), RollEngine.NODE_DOWN);
    }

    @Test public void aBadRandomPicksNoLastWord() {
        RollEngine e = engine(new FakeNode(List.of("0x00")));
        String[] err = {null}; boolean[] picked = {false};
        e.randomN(new RollEngine.NCb() {
            @Override public void onN(long n, String src) { picked[0] = true; }
            @Override public void onError(String m) { err[0] = m; }
        });
        assertFalse(picked[0]);
        assertEquals(RollEngine.NODE_BAD, err[0]);
    }

    @Test public void verifiedRestsOnTheSamePath() {
        RollEngine bad = new RollEngine(new FakeNode(List.of("ERR")));
        bad.setNodeOk(true);
        String[] why = {"unset"}; boolean[] ok = {true};
        bad.verify((o, w) -> { ok[0] = o; why[0] = w; });
        assertFalse(ok[0]);
        assertEquals(RollEngine.NODE_DOWN, why[0]);
        assertFalse(bad.nodeOk());

        RollEngine good = new RollEngine(new FakeNode(List.of(A)));
        good.verify((o, w) -> { ok[0] = o; why[0] = w; });
        assertTrue(ok[0]);
        assertTrue(good.nodeOk());
    }
}
