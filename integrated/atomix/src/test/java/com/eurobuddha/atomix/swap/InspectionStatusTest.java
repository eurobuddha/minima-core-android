package com.eurobuddha.atomix.swap;
import com.eurobuddha.atomix.eth.EthHtlc;
import org.junit.Test;
import static org.junit.Assert.*;
public class InspectionStatusTest {
    @Test public void openEthereumLegWithoutSecretDoesNotPromiseCollection() {
        String state = SwapEngine.ethClaimStatus(new EthHtlc.Contract(), false);
        assertEquals("locked; waiting for the secret", state); assertFalse(state.contains("claimable"));
    }
    /** A row with no recorded transaction is the phantom-lock case: the report must SAY so, not point at a
     *  line it never prints. Live 2026-09-20 — a 7500 MINIMA leg the 1024-block lookup could not find. */
    @Test public void aRowWithNoRecordedTransactionSaysTheLegWasNeverPosted() {
        String none = SwapEngine.recordedTxnSummary(0);
        assertTrue(none.contains("NONE"));
        assertTrue(none.contains("never posted"));
        assertTrue("must state the funds are safe", none.contains("Nothing is locked"));
    }

    @Test public void recordedTransactionsAreCountedNotSilentlyDropped() {
        assertTrue(SwapEngine.recordedTxnSummary(2).contains("2"));
        assertTrue(SwapEngine.recordedTxnSummary(1).contains("listed above"));
    }

    @Test public void secretAvailabilityDoesNotOverrideTerminalState() {
        EthHtlc.Contract c = new EthHtlc.Contract(); c.refunded = true;
        assertEquals("refunded", SwapEngine.ethClaimStatus(c, true));
        c.refunded = false; c.withdrawn = true;
        assertEquals("withdrawn (complete)", SwapEngine.ethClaimStatus(c, true));
    }
}
