package com.eurobuddha.nftwallet;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * The token definition (icon + name + description + external URL + script
 * scaffolding) is IMMUTABLE after tokencreate, and past ~10.5K estimated chars
 * it can no longer be split under the 64KB TxPoW cap — every token-carrying
 * output AND the input embed the full definition, plus a multi-KB signature.
 * Calibrated against Atelier's measured failure: an 18.4KB definition
 * (estimate ~11.9K) never split; estimate-to-actual runs ~1.55x.
 */
public class DefBudgetTest {

    /* The gate reproduces Atelier's measured failure: 'Math' passed every
     * estimator at ~10K visible metadata, then the ~8.4KB creator signature
     * landed in the record AFTER tokencreate — 18.4K, past the split bound. */

    @Test public void lightCollectionSignsAndPasses() {
        assertEquals("sign", StateNft.jointGate(3000, 5500));   // 5.5K image in the 7.6K room
    }

    /* ALWAYS SIGNED: the nosign branch is deleted project-wide. Anything
     * that cannot carry the signature is REFUSED with the reason. */
    @Test public void mathWeightRecordIsRefusedNeverUnsigned() {
        String g = StateNft.jointGate(9554, 5500);
        assertNotEquals("sign", g);
        assertNotEquals("nosign", g);
    }

    @Test public void overJointPairIsRefused() {
        String g = StateNft.jointGate(3000, 7700);
        assertNotEquals("sign", g);
        assertTrue(g.contains("image budget"));
    }

    @Test public void unsplittableRecordIsRefusedOutright() {
        String g = StateNft.jointGate(18000, 0);
        assertNotEquals("sign", g);
    }

    @Test public void envelopeMath() {
        assertEquals(8367, StateNft.META_MAX);
        assertEquals(19500 - 533 - 8400 - 3000, StateNft.imageBudget(3000));
        assertEquals(-1, StateNft.imageBudget(9000));
    }

    @Test public void exactDefLenUsesTheRealMetadata() {
        StateNft.Meta m = new StateNft.Meta();
        m.name = "X";
        m.mode = "embed";
        assertEquals(StateNft.tokenMetadata(m).toString().length()
                + StateNft.DEF_WRAPPER + StateNft.DEF_SIGN_WEIGHT,
                StateNft.defActualLen(m));
    }

    /* (k units + change + input) full token definitions must fit ~40KB under
     * the 64KB TxPoW cap — a fixed 3-unit batch silently stalled heavy mints. */
    @Test public void splitBatchSizesToTokenDefinition() {
        assertEquals(3, MintEngine.splitBatch(7000));    // legacy: proven batch
        assertEquals(1, MintEngine.splitBatch(11079));   // heavy: unit + change
        assertEquals(3, MintEngine.splitBatch(500));     // tiny: capped
        assertEquals(2, MintEngine.splitBatch(10000));
        assertEquals(1, MintEngine.splitBatch(90000));   // degenerate: floor 1
    }
}
