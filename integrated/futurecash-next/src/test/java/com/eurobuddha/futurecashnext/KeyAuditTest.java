package com.eurobuddha.futurecashnext;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The join + verdict logic, ported from index.html:219-327. */
public class KeyAuditTest {

    private static final String PK1 = "0x3A985DA74FE225B2045C172D6BD390BD855F086E3E9D525B46BFE24511431532";
    private static final String PK2 = "0xA7FFC6F8BF1ED76651C14756A061D662F580FF4DE43B49FA82D80A4B80F8434A";

    private static KeyAudit.Result run(List<KeyAudit.LocalKey> keys,
                                       List<KeyAudit.Usage> usage,
                                       List<KeyAudit.Reuse> reuse) {
        final List<MinimaAddress.Result> derived = new ArrayList<>();
        for (KeyAudit.LocalKey k : keys) derived.add(MinimaAddress.fromPublicKey(k.publicKey));
        return KeyAudit.join(keys, usage, reuse, derived);
    }

    private static String addr(String pk) {
        return MinimaAddress.fromPublicKey(pk).hex;
    }

    @Test
    public void cleanNodeIsOk() {
        final KeyAudit.Result r = run(
                Arrays.asList(new KeyAudit.LocalKey(PK1, 10, 262144),
                              new KeyAudit.LocalKey(PK2, 4, 262144)),
                Arrays.asList(new KeyAudit.Usage(PK1, addr(PK1), 10, 25),
                              new KeyAudit.Usage(PK2, addr(PK2), 3, 3)),
                Collections.<KeyAudit.Reuse>emptyList());

        assertEquals(KeyAudit.Verdict.OK, r.verdict);
        assertEquals(KeyAudit.Status.OK, r.rows.get(0).status());
        assertEquals(KeyAudit.Status.OK, r.rows.get(1).status());
        // recommended = max over keys of max(sigs, local), plus the margin
        assertEquals(10 + KeyAudit.KEYUSES_MARGIN, r.recommendedKeyUses);
        assertFalse(r.derivationMismatch);
        assertFalse(r.exhaustion);
    }

    @Test
    public void chainAheadOfCounterIsAtRisk() {
        final KeyAudit.Result r = run(
                Collections.singletonList(new KeyAudit.LocalKey(PK1, 5, 262144)),
                Collections.singletonList(new KeyAudit.Usage(PK1, addr(PK1), 9, 40)),
                Collections.<KeyAudit.Reuse>emptyList());

        assertEquals(KeyAudit.Verdict.RISK_HEURISTIC, r.verdict);
        assertEquals(KeyAudit.Status.AT_RISK, r.rows.get(0).status());
        assertEquals(9 + KeyAudit.KEYUSES_MARGIN, r.recommendedKeyUses);
    }

    @Test
    public void equalCountsAreNotFlagged() {
        // "treat a key sitting exactly at on-chain == node-uses as 'verify', not provably clear" —
        // the coverage note says verify, but the rule is strictly greater-than.
        final KeyAudit.Result r = run(
                Collections.singletonList(new KeyAudit.LocalKey(PK1, 7, 262144)),
                Collections.singletonList(new KeyAudit.Usage(PK1, addr(PK1), 7, 12)),
                Collections.<KeyAudit.Reuse>emptyList());
        assertEquals(KeyAudit.Verdict.OK, r.verdict);
    }

    @Test
    public void confirmedReuseAtThreeIsAmber() {
        final KeyAudit.Result r = run(
                Collections.singletonList(new KeyAudit.LocalKey(PK1, 50, 262144)),
                Collections.singletonList(new KeyAudit.Usage(PK1, addr(PK1), 40, 90)),
                Collections.singletonList(new KeyAudit.Reuse(addr(PK1), true, 3)));

        assertEquals(KeyAudit.Verdict.REUSE_WARN, r.verdict);
        assertEquals(KeyAudit.Status.REUSED, r.rows.get(0).status());
        assertEquals(3, r.worstReuse);
    }

    @Test
    public void confirmedReuseAboveThreeIsRed() {
        final KeyAudit.Result r = run(
                Collections.singletonList(new KeyAudit.LocalKey(PK1, 50, 262144)),
                Collections.singletonList(new KeyAudit.Usage(PK1, addr(PK1), 40, 90)),
                Collections.singletonList(new KeyAudit.Reuse(addr(PK1), true, 4)));

        assertEquals(KeyAudit.Verdict.REUSE_RISK, r.verdict);
        assertEquals(4, r.worstReuse);
    }

    @Test
    public void confirmedReuseBeatsTheHeuristic() {
        // key 1 trips the count heuristic, key 2 is witness-confirmed: the definitive signal wins
        final KeyAudit.Result r = run(
                Arrays.asList(new KeyAudit.LocalKey(PK1, 2, 262144),
                              new KeyAudit.LocalKey(PK2, 80, 262144)),
                Arrays.asList(new KeyAudit.Usage(PK1, addr(PK1), 9, 20),
                              new KeyAudit.Usage(PK2, addr(PK2), 80, 200)),
                Collections.singletonList(new KeyAudit.Reuse(addr(PK2), true, 2)));

        assertEquals(KeyAudit.Verdict.REUSE_WARN, r.verdict);
        assertEquals(KeyAudit.Status.AT_RISK, r.rows.get(0).status());
        assertEquals(KeyAudit.Status.REUSED, r.rows.get(1).status());
    }

    @Test
    public void reusedFalseIsNotAReuse() {
        final KeyAudit.Result r = run(
                Collections.singletonList(new KeyAudit.LocalKey(PK1, 5, 262144)),
                Collections.singletonList(new KeyAudit.Usage(PK1, addr(PK1), 5, 5)),
                Collections.singletonList(new KeyAudit.Reuse(addr(PK1), false, 0)));
        assertEquals(KeyAudit.Verdict.OK, r.verdict);
    }

    @Test
    public void missingBackendRowsMeanNeverSpent() {
        // a key absent from the archive index has simply never been spent from — not an error
        final KeyAudit.Result r = run(
                Arrays.asList(new KeyAudit.LocalKey(PK1, 0, 262144),
                              new KeyAudit.LocalKey(PK2, 0, 262144)),
                Collections.<KeyAudit.Usage>emptyList(),
                Collections.<KeyAudit.Reuse>emptyList());

        assertEquals(KeyAudit.Verdict.OK, r.verdict);
        assertEquals(2, r.rows.size());
        assertEquals(0, r.rows.get(0).sigs);
        assertEquals(0, r.rows.get(0).coins);
        assertEquals(KeyAudit.KEYUSES_MARGIN, r.recommendedKeyUses);
    }

    @Test
    public void nullBackendListsDoNotCrash() {
        final KeyAudit.Result r = run(
                Collections.singletonList(new KeyAudit.LocalKey(PK1, 3, 262144)), null, null);
        assertEquals(KeyAudit.Verdict.OK, r.verdict);
        assertEquals(1, r.rows.size());
    }

    @Test
    public void caseInsensitiveJoin() {
        // the node returns uppercase hex; the backend echoes it back — don't depend on the casing
        final KeyAudit.Result r = run(
                Collections.singletonList(new KeyAudit.LocalKey(PK1.toLowerCase(), 1, 262144)),
                Collections.singletonList(new KeyAudit.Usage(PK1, addr(PK1).toLowerCase(), 6, 6)),
                Collections.singletonList(new KeyAudit.Reuse(addr(PK1).toLowerCase(), true, 9)));

        assertEquals(KeyAudit.Verdict.REUSE_RISK, r.verdict);
        assertEquals(6, r.rows.get(0).sigs);
    }

    @Test
    public void derivationMismatchIsFlagged() {
        // the server derived a different address for the same key: our /reuse lookup asked about
        // the wrong address, so a clean answer means nothing
        final KeyAudit.Result r = run(
                Collections.singletonList(new KeyAudit.LocalKey(PK1, 1, 262144)),
                Collections.singletonList(new KeyAudit.Usage(PK1, addr(PK2), 0, 0)),
                Collections.<KeyAudit.Reuse>emptyList());
        assertTrue(r.derivationMismatch);
    }

    @Test
    public void exhaustionIsFlagged() {
        // on exhaustion minima-core resets uses to 0 and keeps signing (security-review CORE-VM-2)
        final KeyAudit.Result near = run(
                Collections.singletonList(new KeyAudit.LocalKey(PK1, 262144 - 10, 262144)),
                Collections.<KeyAudit.Usage>emptyList(), Collections.<KeyAudit.Reuse>emptyList());
        assertTrue(near.exhaustion);

        final KeyAudit.Result far = run(
                Collections.singletonList(new KeyAudit.LocalKey(PK1, 1000, 262144)),
                Collections.<KeyAudit.Usage>emptyList(), Collections.<KeyAudit.Reuse>emptyList());
        assertFalse(far.exhaustion);
    }

    @Test
    public void rowOrderAndNumberingFollowTheNode() {
        final KeyAudit.Result r = run(
                Arrays.asList(new KeyAudit.LocalKey(PK1, 0, 262144),
                              new KeyAudit.LocalKey(PK2, 0, 262144)),
                Collections.<KeyAudit.Usage>emptyList(), Collections.<KeyAudit.Reuse>emptyList());
        assertEquals(1, r.rows.get(0).index);
        assertEquals(2, r.rows.get(1).index);
        assertEquals(MinimaAddress.fromPublicKey(PK1).mx, r.rows.get(0).address);
    }

    @Test
    public void recommendationTakesTheMaxAcrossAllKeys() {
        final KeyAudit.Result r = run(
                Arrays.asList(new KeyAudit.LocalKey(PK1, 900, 262144),
                              new KeyAudit.LocalKey(PK2, 12, 262144)),
                Arrays.asList(new KeyAudit.Usage(PK1, addr(PK1), 40, 40),
                              new KeyAudit.Usage(PK2, addr(PK2), 1500, 4000)),
                Collections.<KeyAudit.Reuse>emptyList());
        assertEquals(1500 + KeyAudit.KEYUSES_MARGIN, r.recommendedKeyUses);
    }

    @Test
    public void malformedPublicKeyStillProducesARow() {
        final KeyAudit.Result r = run(
                Collections.singletonList(new KeyAudit.LocalKey("garbage", 0, 262144)),
                Collections.<KeyAudit.Usage>emptyList(), Collections.<KeyAudit.Reuse>emptyList());
        assertEquals(1, r.rows.size());
        assertEquals("(address unavailable)", r.rows.get(0).address);
    }
}
