package com.eurobuddha.futurecashnext;

import static org.junit.Assert.*;

import org.junit.Test;

/** A real node's `coins coinid:` answers [] for a spent coin (never a row with spent:true): "no row" (GONE) is how
 *  every successful collect and sweep looks, and also how a reorg looks. Only our output arriving proves it. */
public class SpentCoinReadTest {
    private static final Node.CoinState GONE = Node.CoinState.GONE, SPENT = Node.CoinState.SPENT,
            UNSPENT = Node.CoinState.UNSPENT, UNKNOWN = Node.CoinState.UNKNOWN;

    @Test public void aSweepWithNoRowIsSweptOnceItsOutputIsAtTheSafe() {
        assertEquals(Guardian.Sweep.SWEPT, Guardian.sweepVerdict(GONE, true, true, true, 1));
        for (long age : new long[]{0, Guardian.VERIFY_GRACE, Guardian.VERIFY_MAX - 1})
            for (boolean canVerify : new boolean[]{true, false})
                assertEquals(Guardian.Sweep.WAIT, Guardian.sweepVerdict(GONE, canVerify, true, false, age));
    }

    /** No row and no output is never SWEPT: the three unproven endings are told apart (2.2.120). */
    @Test public void anUnprovenSweepIsNeverSweptAndSaysWhichKindItIs() {
        // own safe, the lookup worked, nothing arrived: said plainly
        assertEquals(Guardian.Sweep.NOT_ARRIVED, Guardian.sweepVerdict(GONE, true, true, false, Guardian.VERIFY_MAX));
        // own safe, the lookup itself failed
        assertEquals(Guardian.Sweep.UNVERIFIED, Guardian.sweepVerdict(GONE, true, false, false, Guardian.VERIFY_MAX));
        // external safe: this node can never see it - sent, not verifiable, no alarm
        for (boolean reliable : new boolean[]{true, false}) {
            assertEquals(Guardian.Sweep.UNVERIFIABLE, Guardian.sweepVerdict(GONE, false, reliable, false, Guardian.VERIFY_MAX));
            assertEquals(Guardian.Sweep.UNVERIFIABLE, Guardian.sweepVerdict(SPENT, false, reliable, false, 0));
        }
        for (Node.CoinState st : new Node.CoinState[]{GONE, SPENT, UNSPENT, UNKNOWN})
            for (boolean canVerify : new boolean[]{true, false}) for (boolean reliable : new boolean[]{true, false})
                for (long age = 0; age <= Guardian.VERIFY_MAX + 1; age++)
                    assertNotEquals("SWEPT without the output at the safe", Guardian.Sweep.SWEPT,
                            Guardian.sweepVerdict(st, canVerify, reliable, false, age));
    }

    @Test public void aNodeThatReportsSpentKeepsTheOldRules() {
        assertEquals(Guardian.Sweep.SWEPT, Guardian.sweepVerdict(SPENT, true, true, true, 0));
        assertEquals(Guardian.Sweep.WAIT, Guardian.sweepVerdict(SPENT, true, true, false, Guardian.VERIFY_GRACE - 1));
        assertEquals(Guardian.Sweep.TAKEN, Guardian.sweepVerdict(SPENT, true, true, false, Guardian.VERIFY_GRACE));
        assertEquals(Guardian.Sweep.UNVERIFIED, Guardian.sweepVerdict(SPENT, true, false, false, Guardian.VERIFY_MAX));
        for (Node.CoinState st : new Node.CoinState[]{UNSPENT, UNKNOWN}) {
            assertEquals(Guardian.Sweep.WAIT, Guardian.sweepVerdict(st, true, true, false, Guardian.RETRY_BLOCKS - 1));
            assertEquals(Guardian.Sweep.RETRY, Guardian.sweepVerdict(st, true, true, false, Guardian.RETRY_BLOCKS));
        }
    }

    @Test public void aCollectWithNoRowIsDoneOnlyWhenItsOutputArrived() {
        assertTrue(Guardian.collectDone(GONE, true));
        assertFalse(Guardian.collectDone(GONE, false));
        assertTrue(Guardian.collectDone(SPENT, false));
        assertFalse(Guardian.collectDone(UNSPENT, true));
        assertFalse(Guardian.collectDone(UNKNOWN, true));
    }
}
