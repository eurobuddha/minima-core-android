package com.eurobuddha.nftwallet;

import android.widget.Toast;
import java.util.*;
import org.junit.Test;
import org.mockito.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.eurobuddha.nftwallet.BurialEvidenceTest.object;

/** Resume a saved public job; intercept the builder before any signature or post. */
public class DistributeResumeTest {
    static final String COIN = "0x" + "11".repeat(32), ADDRESS = "0x" + "22".repeat(32);
    static class Flow implements AutoCloseable {
        final MainActivity act = mock(MainActivity.class);
        final NodeApi node = mock(NodeApi.class);
        final HistoryDb history = mock(HistoryDb.class);
        final BurialLifecycleTest.Prefs prefs = new BurialLifecycleTest.Prefs();
        final Coin change = new Coin();
        final List<List<?>> builds = new ArrayList<>();
        final List<String> commands = new ArrayList<>();
        final MockedStatic<Toast> toasts = mockStatic(Toast.class);
        final MockedConstruction<TxnBuilder> builders = mockConstruction(TxnBuilder.class,
                (builder,context) -> {builds.add(context.arguments());when(builder.record(any())).thenReturn(builder);});
        Flow(int block, boolean present, boolean confirmed, boolean sendable) {
            when(act.getSharedPreferences(eq("nftwallet_distribute"), anyInt())).thenReturn(prefs.prefs);
            when(act.node()).thenReturn(node); when(act.history()).thenReturn(history);when(history.insertPosting(any())).thenReturn(true);
            when(act.chainBlock()).thenReturn(block);
            toasts.when(() -> Toast.makeText(eq(act), any(CharSequence.class), anyInt())).thenReturn(mock(Toast.class));
            change.coinid = COIN; change.address = ADDRESS; change.miniaddress = "";
            change.amount = change.rawAmount = "10"; change.tokenid = "0x00";
            change.confirmed = confirmed; change.sendable = sendable;
            when(act.coins()).thenReturn(present ? Collections.singletonList(change) : Collections.emptyList());
            DistributeJob job = new DistributeJob(); job.tokenid = "0x00"; job.tokenName = "Minima";
            job.per = "1"; job.remaining.add("0x" + "33".repeat(32)); job.batchesDone = 1;
            job.expectedChangeCoinId = COIN; job.nextChangeAddr = ADDRESS; job.expectedChangeAmt = "10";
            job.waiting = true; job.atBlock = 100; job.currentInternalId = "public-prior-batch"; job.save(act);
            doAnswer(i -> {
                String cmd = i.getArgument(0); commands.add(cmd); assertEquals("getaddress", cmd);
                ((NodeApi.Cb)i.getArgument(1)).onResult(object("status", true, "response", object("address", ADDRESS)));
                return null;
            }).when(node).cmd(anyString(), any(NodeApi.Cb.class));
        }
        DistributeManager resume() { DistributeManager manager = new DistributeManager(act); manager.onCoinsUpdated(); return manager; }
        public void close() { builders.close(); toasts.close(); }
    }
    @Test public void confirmedChangeResumesAfterTwentyBlocksWithoutSendingTheFirstBatchAgain() {
        try (Flow f = new Flow(200, true, true, true)) {
            DistributeManager manager = f.resume(); assertTrue(manager.isActive());
            assertEquals(1, f.builds.size()); assertEquals(Collections.singletonList(f.change), f.builds.get(0).get(1));
            @SuppressWarnings("unchecked") List<TxnBuilder.Out> outputs = (List<TxnBuilder.Out>)f.builds.get(0).get(2);
            assertEquals(2, outputs.size()); assertEquals("1", outputs.get(0).amount); assertEquals("9", outputs.get(1).amount);
            verify(f.builders.constructed().get(0)).run();
            manager.onCoinsUpdated(); assertEquals("No overlap while next batch is in flight", 1, f.builds.size());
            verify(f.history, never()).update(anyString(), eq(HistoryDb.STATUS_ERROR), any(), anyString());
        }
    }
    @Test public void uncertainBatchIsSavedBeforeSigningAndCannotRetryAfterRestart() {
        try (Flow f = new Flow(200,true,true,true)) {
            DistributeManager manager=f.resume(); assertEquals(1,f.builds.size());
            DistributeJob stored=DistributeJob.load(f.act); assertTrue(stored.uncertain); assertTrue(stored.waiting); assertEquals(1,stored.remaining.size()); assertEquals(1,stored.batchesDone);
            TxnBuilder.Done done=(TxnBuilder.Done)f.builds.get(0).get(4); done.onUnknown(TransactionEvidence.UNKNOWN);
            verify(f.history).update(eq(stored.currentInternalId),eq(HistoryDb.STATUS_UNKNOWN),isNull(),eq(TransactionEvidence.UNKNOWN));
            manager.onCoinsUpdated(); new DistributeManager(f.act).onCoinsUpdated();
            assertEquals(1,f.builds.size()); assertTrue(DistributeJob.load(f.act).uncertain); assertEquals(1,DistributeJob.load(f.act).remaining.size());
        }
    }
    @Test public void failedDurableSavePreventsAnyBuilderFromStarting() {
        try (Flow f = new Flow(200,true,true,true)) {
            f.prefs.writable=false; f.resume(); assertTrue(f.builds.isEmpty());
        }
    }
    @Test public void explicitRejectionClearsTheUncertainMarkerForTheExistingRetryPolicy() {
        try (Flow f = new Flow(200,true,true,true)) {
            DistributeManager manager=f.resume(); TxnBuilder.Done done=(TxnBuilder.Done)f.builds.get(0).get(4);done.onFailed("Rejected public fixture");
            assertFalse(DistributeJob.load(f.act).uncertain);assertEquals(1,DistributeJob.load(f.act).retryCount);
            manager.onCoinsUpdated();assertEquals(2,f.builds.size());
        }
    }
    @Test public void absentChangeWaitsAtTheBoundaryAndExpiresOnlyAfterIt() {
        for (int block : new int[]{0, 120, 121}) try (Flow f = new Flow(block, false, true, true)) {
            DistributeManager manager = f.resume(); assertEquals(block <= 120, manager.isActive());
            assertTrue(f.commands.isEmpty()); assertTrue(f.builds.isEmpty());
            if (block > 120) {
                assertNull(DistributeJob.load(f.act));
                verify(f.history).update(eq("public-prior-batch"), eq(HistoryDb.STATUS_ERROR), isNull(), contains("timed out"));
            }
        }
    }
    @Test public void unconfirmedUnspendableOrWrongTokenCoinsCannotResumeAnExpiredJob() {
        for (int invalid = 0; invalid < 3; invalid++) try (Flow f = new Flow(200, true, invalid != 0, invalid != 1)) {
            if (invalid == 2) f.change.tokenid = "0xff";
            assertFalse(f.resume().isActive()); assertTrue(f.commands.isEmpty()); assertTrue(f.builds.isEmpty());
        }
    }
    @Test public void anUnrelatedCoinAtTheSameAddressCannotReplaceTheExpectedCoin() {
        try (Flow f = new Flow(200, true, true, true)) {
            f.change.coinid = "0x" + "44".repeat(32);
            assertFalse(f.resume().isActive()); assertTrue(f.commands.isEmpty()); assertTrue(f.builds.isEmpty());
        }
    }
}
