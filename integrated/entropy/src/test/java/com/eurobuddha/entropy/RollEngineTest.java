package com.eurobuddha.entropy;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The node does not decide a die face: its `hash` answer is used only when it equals the SHA3-256
 * worked out on the device, and its `random` only when it is 0x + 64 hex not seen before.
 * The fake node answers synchronously; the "truthful" answers come from the JDK's own SHA3-256.
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

    static JSONObject ok(String key, String value) throws Exception {
        return new JSONObject().put("status", true).put("response", new JSONObject().put(key, value));
    }

    /** A node whose `random` answers come from a list and whose `hash` answer is hashOf(data). */
    static final class FakeNode implements RollEngine.Node {
        final List<String> randoms; final Function<String, String> hashOf; int r = 0;
        final List<String> commands = new ArrayList<>();
        FakeNode(List<String> randoms, Function<String, String> hashOf) { this.randoms = randoms; this.hashOf = hashOf; }
        @Override public void cmd(String command, NodeApi.Cb cb) {
            commands.add(command);
            try {
                if (command.equals("random")) cb.onResult(ok("random", randoms.get(Math.min(r++, randoms.size() - 1))));
                else if (command.startsWith("hash data:")) cb.onResult(ok("hash", hashOf.apply(command.substring(10))));
                else fail("unexpected command " + command);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    static Function<String, String> truthful() {
        return d -> { try { return jdkSha3(d); } catch (Exception e) { throw new RuntimeException(e); } };
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

    static void assertDiscarded(Result r) {
        assertNull("no face may come from a refused answer", r.face);
        assertEquals(RollEngine.NODE_BAD, r.error);
    }

    @Test public void localDigestIsSha3_256OfTheRawBytes() {
        assertEquals("0xa7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a", RollEngine.localHash("0x"));
        assertEquals("0x3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532", RollEngine.localHash("0x616263"));
    }

    @Test public void aTruthfulNodeGivesTheFaceOfItsHash() throws Exception {
        FakeNode n = new FakeNode(List.of(A), truthful());
        Result r = roll(engine(n));
        assertNull(r.error);
        assertEquals(A, r.audit.a);
        assertEquals("hash data:" + A + r.audit.b.substring(2), n.commands.get(1));
        long v = Long.parseLong(jdkSha3(A + r.audit.b.substring(2)).substring(2, 10), 16);
        if (v < RollEngine.REJECT_THRESHOLD) assertEquals(Integer.valueOf((int) (v % 6) + 1), r.face);
        assertEquals(RollEngine.SRC_NODE_DEVICE, r.audit.src);
    }

    @Test public void anUpperCaseTruthfulHashIsAccepted() {
        Result r = roll(engine(new FakeNode(List.of(A), d -> truthful().apply(d).toUpperCase().replace("0X", "0x"))));
        assertNull(r.error);
        assertNotNull(r.face);
    }

    @Test public void aWrongHashDiscardsTheRoll() {
        assertDiscarded(roll(engine(new FakeNode(List.of(A), d -> truthful().apply(d + "00")))));
    }

    @Test public void aConstantHashDiscardsTheRoll() {
        assertDiscarded(roll(engine(new FakeNode(List.of(A), d -> "0x" + "00".repeat(32)))));
    }

    @Test public void anEmptyOrShortHashDiscardsTheRoll() {
        assertDiscarded(roll(engine(new FakeNode(List.of(A), d -> ""))));
        assertDiscarded(roll(engine(new FakeNode(List.of(A), d -> truthful().apply(d).substring(0, 10)))));
    }

    @Test public void anEmptyRandomDiscardsTheRollAndAsksNoHash() {
        FakeNode n = new FakeNode(List.of(""), truthful());
        assertDiscarded(roll(engine(n)));
        assertEquals(List.of("random"), n.commands);
    }

    @Test public void aWrongLengthRandomDiscardsTheRoll() {
        for (String bad : new String[]{"0x" + "1f".repeat(31), "0x" + "1f".repeat(33), "0x1f", "1f".repeat(32) + "ab", "0x" + "zz".repeat(32)}) {
            FakeNode n = new FakeNode(List.of(bad), truthful());
            assertDiscarded(roll(engine(n)));
            assertEquals(bad, List.of("random"), n.commands);
        }
    }

    @Test public void aRepeatedRandomDiscardsTheRoll() {
        RollEngine e = engine(new FakeNode(List.of(A, A.toUpperCase().replace("0X", "0x")), truthful()));
        assertNull(roll(e).error);
        assertDiscarded(roll(e));
    }

    @Test public void freshRandomsKeepRolling() {
        RollEngine e = engine(new FakeNode(List.of(A, A2), truthful()));
        assertNull(roll(e).error);
        assertNull(roll(e).error);
    }

    @Test public void aWrongHashPicksNoLastWord() {
        RollEngine e = engine(new FakeNode(List.of(A), d -> "0x" + "ff".repeat(32)));
        String[] err = {null}; boolean[] picked = {false};
        e.randomN(new RollEngine.NCb() {
            @Override public void onN(long n, String src) { picked[0] = true; }
            @Override public void onError(String m) { err[0] = m; }
        });
        assertFalse(picked[0]);
        assertEquals(RollEngine.NODE_BAD, err[0]);
    }

    @Test public void verifiedRestsOnTheSameCheck() {
        RollEngine bad = new RollEngine(new FakeNode(List.of(A), d -> "0x" + "00".repeat(32)));
        bad.setNodeOk(true);
        String[] why = {"unset"}; boolean[] ok = {true};
        bad.verify((o, w) -> { ok[0] = o; why[0] = w; });
        assertFalse(ok[0]);
        assertEquals(RollEngine.NODE_BAD, why[0]);
        assertFalse(bad.nodeOk());

        RollEngine good = new RollEngine(new FakeNode(List.of(A), truthful()));
        good.verify((o, w) -> { ok[0] = o; why[0] = w; });
        assertTrue(ok[0]);
        assertTrue(good.nodeOk());
    }
}
