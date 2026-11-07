package com.eurobuddha.atomix.swap;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;

import com.eurobuddha.atomix.eth.EthNet;
import com.eurobuddha.atomix.eth.EthRpc;
import com.eurobuddha.atomix.eth.EthWallet;
import com.eurobuddha.atomix.integratedcomms.NodeApi;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

/**
 * Security review 7 Nov 2026.
 *  (1) CRITICAL: a counterparty-chosen hashlock that is not exactly 32 bytes is never answered — KISS compares the
 *      bytes exactly, but the ETH counter-leg coerced it to 32 (EthHtlc.b32), so 0x00‖H let the taker withdraw my
 *      USDT with a secret my Minima claim could never match.
 *  (2) CRITICAL: the initiator never reveals its secret into a counter-leg whose refund opens within the reveal margin
 *      (the owner would race it with a refund and use the secret on the initiator's first leg). A RESPONDER collects
 *      with an already-public secret and is never blocked.
 */
public class RevealWindowTest {
    private static final String HASH = "0x" + "44".repeat(32), MY_MPK = "0xAAAA";
    private SwapEngine engine;
    private SwapDb db;
    private EthRpc rpc;
    private SwapDb.Swap sw;
    private MinimaHtlc minima;

    @Before public void setUp() throws Exception {
        SwapEngine.clearRetryMarkersForTest();
        com.eurobuddha.atomix.IdentityWatch.resetForTest();   // Pandamonium copy: other suites in this JVM leave these set
        SwapEngine.ETH_FIRST_PAUSED = true;
        EthWallet wallet = mock(EthWallet.class);
        when(wallet.address()).thenReturn("0x1111111111111111111111111111111111111111");
        db = mock(SwapDb.class);
        minima = mock(MinimaHtlc.class);
        engine = new SwapEngine(mock(NodeApi.class), minima, db, wallet, mock(Handler.class), mock(SwapEngine.Notifier.class));
        rpc = mock(EthRpc.class);
        engine.setNetwork(rpc, EthNet.MAINNET);
        engine.setMyMinimaPk(MY_MPK);
        when(db.getSecret(HASH)).thenReturn("0x" + "33".repeat(32));
        sw = new SwapDb.Swap(); sw.hash = HASH; sw.buyToken = "mxUSDT"; sw.status = SwapDb.ST_LOCKED; sw.role = "INITIATOR";
        when(db.getSwap(HASH)).thenReturn(sw);
        when(db.getRequest(HASH)).thenReturn(new String[]{"4.95", "minima"});
    }

    private static JSONObject coin(String hash, int timelock) throws Exception {
        return new JSONObject().put("tokenid", MinimaHtlc.USDT_TOKENID).put("tokenamount", "4.95")
                .put("state", new JSONObject().put("0", "0x0B0B").put("1", "1").put("2", "[minima]").put("6", "0x01").put("3", String.valueOf(timelock))
                        .put("4", MY_MPK).put("5", hash));
    }

    @Test public void nonThirtyTwoByteHashlockIsIgnored() throws Exception {
        engine.checkCanSwapCoin(coin("0x00" + HASH.substring(2), 999999), 100);
        verify(db, never()).getSecret(anyString());
        verify(db, never()).setSwapStatus(anyString(), anyString());
    }

    @Test public void initiatorDoesNotRevealIntoAClosingMinimaWindow() throws Exception {
        engine.checkCanSwapCoin(coin(HASH, 100 + SwapEngine.REVEAL_MIN_BLOCKS - 1), 100);
        verify(db, never()).setSwapStatus(eq(HASH), eq(SwapDb.ST_CLAIMING));
    }

    @Test public void initiatorClaimsWithEnoughWindow() throws Exception {
        try { engine.checkCanSwapCoin(coin(HASH, 100 + SwapEngine.CP_BLOCKS), 100); } catch (RuntimeException androidLogStub) { }
        verify(db).setSwapStatus(HASH, SwapDb.ST_CLAIMING);
    }

    @Test public void aRetryAfterMySecretWentOutIsNeverBlocked() throws Exception {
        // R2-1: my first claim carried the secret but did not mine — the secret is public now; a retry must go out
        when(db.hasEvent(HASH, SwapDb.EV_SECRET_OUT)).thenReturn(true);
        try { engine.checkCanSwapCoin(coin(HASH, 101), 100); } catch (RuntimeException androidLogStub) { }
        verify(db).setSwapStatus(HASH, SwapDb.ST_CLAIMING);
        long now = System.currentTimeMillis() / 1000;
        when(rpc.latestBlockTimestamp()).thenReturn(now);
        assertTrue(engine.revealWindowOk(HASH, now + 15));
    }

    @Test public void theMarkerIsWrittenOnlyOnceTheClaimWasPosted() throws Exception {
        // R3-1: nothing may lift the margin before a post really happened
        try { engine.checkCanSwapCoin(coin(HASH, 100 + SwapEngine.CP_BLOCKS), 100); } catch (RuntimeException androidLogStub) { }
        verify(db, never()).logEvent(eq(HASH), eq(SwapDb.EV_SECRET_OUT), anyString(), anyString(), anyString());
        org.mockito.ArgumentCaptor<MinimaHtlc.PostCb> cb = org.mockito.ArgumentCaptor.forClass(MinimaHtlc.PostCb.class);
        verify(minima).claim(org.mockito.ArgumentMatchers.any(), eq(HASH), anyString(), cb.capture());
        try { cb.getValue().ok("0x" + "ab".repeat(32)); } catch (RuntimeException androidLogStub) { }
        verify(db).logEvent(HASH, SwapDb.EV_SECRET_OUT, "", "", "");
    }

    private static com.eurobuddha.atomix.eth.EthHtlc.Contract contract(String receiver, long timelock) {
        com.eurobuddha.atomix.eth.EthHtlc.Contract c = new com.eurobuddha.atomix.eth.EthHtlc.Contract();
        c.contractId = "0x" + "cd".repeat(32); c.owner = "0x" + "11".repeat(20); c.receiver = receiver;
        c.tokenContract = "0xdac17f958d2ee523a2206206994597c13d831ec7"; c.hashlock = HASH;
        c.amount = java.math.BigInteger.valueOf(1_000_000); c.requestAmount = java.math.BigInteger.TEN; c.timelock = timelock;
        return c;
    }

    private void respondToUsdtLock(com.eurobuddha.atomix.eth.EthHtlc eth) throws Exception {
        when(db.getSecret(HASH)).thenReturn(null);
        when(db.getSwap(HASH)).thenReturn(null);
        java.lang.reflect.Method m = SwapEngine.class.getDeclaredMethod("checkCanCollectEth",
                com.eurobuddha.atomix.eth.EthHtlc.class, com.eurobuddha.atomix.eth.EthHtlc.Contract.class, int.class);
        m.setAccessible(true);
        try { m.invoke(engine, eth, contract("0x1111111111111111111111111111111111111111", System.currentTimeMillis() / 1000 + 7200), 100); }
        catch (java.lang.reflect.InvocationTargetException androidLogStub) { if (!(androidLogStub.getCause() instanceof RuntimeException)) throw androidLogStub; }
    }

    @Test public void aUsdtPaidTakeFromAnOldTakerIsDeclined() throws Exception {
        // HTLC v1.3: no v1.3 capability in the handshake = no counter-leg, and not even an endpoint read
        SwapEngine.HTLC13_TAKERS.clear();
        com.eurobuddha.atomix.eth.EthHtlc eth = mock(com.eurobuddha.atomix.eth.EthHtlc.class);
        respondToUsdtLock(eth);
        verify(db).logEvent(eq(MinimaHtlc.normKey(HASH)), eq(SwapDb.EV_DECLINED), eq(""), eq(""), org.mockito.ArgumentMatchers.contains("HTLC v1.3"));
        verify(eth, never()).getContractOn(anyString(), anyString());
    }

    @Test public void aUsdtPaidTakeFromAV13TakerGoesOnToTheEndpointCheck() throws Exception {
        SwapEngine.HTLC13_TAKERS.clear();
        SwapEngine.takerUnderstands13(HASH);
        try {
            com.eurobuddha.atomix.eth.EthHtlc eth = mock(com.eurobuddha.atomix.eth.EthHtlc.class);
            respondToUsdtLock(eth);
            verify(db, never()).logEvent(eq(MinimaHtlc.normKey(HASH)), eq(SwapDb.EV_DECLINED), anyString(), anyString(), org.mockito.ArgumentMatchers.contains("HTLC v1.3"));
            verify(eth, org.mockito.Mockito.atLeastOnce()).getContractOn(anyString(), anyString());
        } finally { SwapEngine.HTLC13_TAKERS.clear(); }
    }

    @Test public void oneRpcCannotDecideAReveal() throws Exception {
        // review 7 Nov: the same contract must read identically on a second, independent endpoint
        com.eurobuddha.atomix.eth.EthHtlc eth = mock(com.eurobuddha.atomix.eth.EthHtlc.class);
        com.eurobuddha.atomix.eth.EthHtlc.Contract seen = contract("0x" + "22".repeat(20), 5000);
        when(eth.getContractOn(anyString(), eq(seen.contractId))).thenReturn(contract("0x" + "22".repeat(20), 5000));
        assertTrue(engine.secondEndpointAgrees(eth, seen.contractId, seen));
        // R4-3: one stale endpoint (first in the list) is outvoted by the next two
        java.util.List<String> eps = com.eurobuddha.atomix.eth.EthRpc.fallbacks();
        when(eth.getContractOn(eq(eps.get(0)), eq(seen.contractId))).thenReturn(null);
        assertTrue(engine.secondEndpointAgrees(eth, seen.contractId, seen));
        when(eth.getContractOn(anyString(), eq(seen.contractId))).thenReturn(contract("0x" + "33".repeat(20), 5000));
        try { assertFalse(engine.secondEndpointAgrees(eth, seen.contractId, seen)); } catch (RuntimeException androidLogStub) { }
        when(eth.getContractOn(anyString(), eq(seen.contractId))).thenReturn(null);
        try { assertFalse(engine.secondEndpointAgrees(eth, seen.contractId, seen)); } catch (RuntimeException androidLogStub) { }
        assertFalse("timelock differs", SwapEngine.sameContract(seen, contract("0x" + "22".repeat(20), 5001)));
    }

    @Test public void ethFirstShipsPaused() {
        assertTrue("HTLC v1.2 cannot require a 32-byte secret: makers ship with USDT-paid buys paused", SwapEngine.ETH_FIRST_PAUSED);
    }

    @Test public void responderCollectIsNeverBlocked() throws Exception {
        sw.role = "RESPONDER";
        try { engine.checkCanSwapCoin(coin(HASH, 101), 100); } catch (RuntimeException androidLogStub) { }
        verify(db).setSwapStatus(HASH, SwapDb.ST_CLAIMING);
    }

    @Test public void ethRevealNeedsTheMarginForTheInitiatorOnly() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        when(rpc.latestBlockTimestamp()).thenReturn(now);
        assertFalse(engine.revealWindowOk(HASH, now + 15));
        assertTrue(engine.revealWindowOk(HASH, now + SwapEngine.CP_SECS - 100));
        sw.role = "RESPONDER";
        assertTrue("a responder's secret is already public", engine.revealWindowOk(HASH, now + 15));
    }
}
