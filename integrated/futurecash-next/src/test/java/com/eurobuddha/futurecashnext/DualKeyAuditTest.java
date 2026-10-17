package com.eurobuddha.futurecashnext;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

/** Disposable public-key fixtures; no node, seed, signatures or network requests. */
public class DualKeyAuditTest {
    private static final String CLASSIC = "0x3A985DA74FE225B2045C172D6BD390BD855F086E3E9D525B46BFE24511431532";
    private static final String BLOCK = "0xA7FFC6F8BF1ED76651C14756A061D662F580FF4DE43B49FA82D80A4B80F8434A";
    private static MinimaAddress.Result address(String key) { return MinimaAddress.fromPublicKey(key); }
    private static KeyAudit.LocalKey classic(int uses) {
        return new KeyAudit.LocalKey(CLASSIC, uses, 262144, 64, 3);
    }
    private static KeyAudit.LocalKey block(int uses) {
        return new KeyAudit.LocalKey(BLOCK, uses, 268435456, 128, 4);
    }
    private static KeyAudit.Usage usage(String key, long signatures) {
        return new KeyAudit.Usage(key, address(key).hex, signatures, signatures);
    }
    private static KeyAudit.Reuse reused(String key, boolean yes) {
        return new KeyAudit.Reuse(address(key).hex, yes, yes ? 4 : 0);
    }
    private static KeyAudit.Result join(List<KeyAudit.LocalKey> keys, List<KeyAudit.Usage> usage,
                                        List<KeyAudit.Reuse> reuse) {
        java.util.ArrayList<MinimaAddress.Result> derived = new java.util.ArrayList<>();
        for (KeyAudit.LocalKey key : keys) derived.add(address(key.publicKey));
        return KeyAudit.join(keys, usage, reuse, derived);
    }
    @Test public void mixedFamiliesAreBothAuditedWithoutMixingRecoveryCounts() {
        KeyAudit.Result result = join(Arrays.asList(classic(12), block(2350000)),
                Arrays.asList(usage(CLASSIC, 10), usage(BLOCK, 2)),
                Arrays.asList(reused(CLASSIC, false), reused(BLOCK, false)));
        assertEquals(1, result.classicKeys);
        assertEquals(1, result.blockKeys);
        assertEquals(0, result.unknownKeys);
        assertTrue(result.complete);
        assertFalse(result.exhaustion);
        assertEquals(12 + KeyAudit.KEYUSES_MARGIN, result.recommendedKeyUses);
        assertEquals(KeyAudit.Family.BLOCK, result.rows.get(1).family);
    }
    @Test public void knownReuseStillFlagsABlockKeyWithMillionsOfLeafPositions() {
        KeyAudit.Result result = join(Collections.singletonList(block(2350000)),
                Collections.singletonList(usage(BLOCK, 2)), Collections.singletonList(reused(BLOCK, true)));
        assertTrue(result.rows.get(0).reused);
        assertEquals(KeyAudit.Verdict.REUSE_RISK, result.verdict);
        assertEquals(0, result.recommendedKeyUses);
    }
    @Test public void blockLeafPositionIsNotComparedToSignatureCount() {
        KeyAudit.Result result = join(Collections.singletonList(block(0)),
                Collections.singletonList(usage(BLOCK, 12)), Collections.singletonList(reused(BLOCK, false)));
        assertFalse(result.rows.get(0).risk);
        assertTrue(result.complete);
    }
    @Test public void classicHeuristicStillFlagsCounterBehindChain() {
        KeyAudit.Result result = join(Arrays.asList(classic(2), block(2350000)),
                Arrays.asList(usage(CLASSIC, 10), usage(BLOCK, 1)),
                Arrays.asList(reused(CLASSIC, false), reused(BLOCK, false)));
        assertTrue(result.rows.get(0).risk);
        assertEquals(KeyAudit.Verdict.RISK_HEURISTIC, result.verdict);
    }
    @Test public void missingBlockResultCannotLicenseTheWholeWallet() {
        KeyAudit.Result result = join(Arrays.asList(classic(12), block(2350000)),
                Arrays.asList(usage(CLASSIC, 10), usage(BLOCK, 2)),
                Collections.singletonList(reused(CLASSIC, false)));
        assertFalse(result.complete);
        assertEquals(KeyAudit.Status.UNKNOWN, result.rows.get(1).status());
    }
    @Test public void unknownShapeAndInconsistentCapacityStayUnchecked() {
        for (KeyAudit.LocalKey key : Arrays.asList(
                new KeyAudit.LocalKey(BLOCK, 2350000, 268435456, 0, 0),
                new KeyAudit.LocalKey(BLOCK, 2350000, 262144, 128, 4),
                new KeyAudit.LocalKey(BLOCK, -1, 268435456, 128, 4))) {
            KeyAudit.Result result = join(Collections.singletonList(key),
                    Collections.singletonList(usage(BLOCK, 2)), Collections.singletonList(reused(BLOCK, false)));
            assertFalse(result.complete);
            assertEquals(KeyAudit.Status.UNKNOWN, result.rows.get(0).status());
        }
    }
    @Test public void eachFamilyUsesItsOwnExhaustionCapacity() {
        KeyAudit.Result result = join(Collections.singletonList(block(268435450)),
                Collections.singletonList(usage(BLOCK, 2)), Collections.singletonList(reused(BLOCK, false)));
        assertTrue(result.exhaustion);
        assertFalse(join(Collections.singletonList(block(2350000)),
                Collections.singletonList(usage(BLOCK, 2)), Collections.singletonList(reused(BLOCK, false))).exhaustion);
    }
    @Test public void changedKeyShapeInvalidatesOfflineAuditFingerprint() throws Exception {
        JSONObject row = new JSONObject().put("publickey", CLASSIC).put("uses", 10)
                .put("size", 64).put("depth", 3).put("maxuses", 262144);
        String before = Audit.usesFingerprint(new JSONArray().put(row));
        row.put("size", 128).put("depth", 4).put("maxuses", 268435456);
        assertNotEquals(before, Audit.usesFingerprint(new JSONArray().put(row)));
    }
    @Test public void invalidClassicCounterIsUnknownRatherThanEvidenceOfReuse() {
        KeyAudit.Result result = join(Collections.singletonList(classic(-1)),
                Collections.singletonList(usage(CLASSIC, 0)), Collections.singletonList(reused(CLASSIC, false)));
        assertFalse(result.complete);
        assertFalse(result.rows.get(0).risk);
        assertEquals(KeyAudit.Status.UNKNOWN, result.rows.get(0).status());
    }
    @Test public void mismatchedDerivedAddressCannotDisplayAsChecked() {
        KeyAudit.Result result = join(Collections.singletonList(classic(12)),
                Collections.singletonList(new KeyAudit.Usage(CLASSIC, address(BLOCK).hex, 10, 10)),
                Collections.singletonList(reused(CLASSIC, false)));
        assertTrue(result.derivationMismatch);
        assertFalse(result.complete);
        assertEquals(KeyAudit.Status.UNKNOWN, result.rows.get(0).status());
    }
}
