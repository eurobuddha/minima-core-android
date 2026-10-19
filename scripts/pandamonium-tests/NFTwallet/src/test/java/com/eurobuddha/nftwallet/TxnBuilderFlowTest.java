package com.eurobuddha.nftwallet;

import android.os.Handler;
import com.eurobuddha.minimaapi.MinimaAPIListener;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;
import java.math.BigDecimal;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.mockito.MockedConstruction;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.eurobuddha.nftwallet.BurialEvidenceTest.object;

/** Public fixtures through the real NodeApi gate. Never runs a node or signs. */
public class TxnBuilderFlowTest {
    static final String DEST = "0x" + "aa".repeat(32), CHANGE = "0x" + "bb".repeat(32);
    static class Flow implements AutoCloseable {
        final MainActivity act = mock(MainActivity.class);
        final TxnBuilder.Done done = mock(TxnBuilder.Done.class);
        final HistoryDb history = mock(HistoryDb.class);
        final List<String> commands = new ArrayList<>();
        String reject = "";
        boolean holdSigning;
        JSONObject signReply;
        MinimaAPIListener lateReply;
        final List<Runnable> timeouts = new ArrayList<>();
        JSONObject rejection = object("status", false, "error", "Rejected public fixture");
        final MockedConstruction<Handler> handlers = mockConstruction(Handler.class, (h,c) -> {
            when(h.post(any())).thenAnswer(i -> { ((Runnable)i.getArgument(0)).run(); return true; });
            when(h.postDelayed(any(Runnable.class),anyLong())).thenAnswer(i -> { timeouts.add(i.getArgument(0)); return true; });
            doAnswer(i -> { timeouts.remove(i.getArgument(0)); return null; }).when(h).removeCallbacks(any(Runnable.class));
        });
        final MockedConstruction<DirectNodeApi> clients = mockConstruction(DirectNodeApi.class, (api,c) -> {
            doAnswer(i -> {
                String command = i.getArgument(0); commands.add(command);
                if (holdSigning && command.startsWith("txnsign ")) { lateReply=i.getArgument(1); return null; }
                JSONObject reply = !reject.isEmpty() && command.startsWith(reject)
                        ? rejection : command.startsWith("txnsign ") && signReply != null ? signReply : object("status", true);
                ((MinimaAPIListener)i.getArgument(1)).response(reply); return null;
            }).when(api).Command(anyString(), any());
        });
        final NodeApi node = new NodeApi(act, null);
        Flow() { when(act.node()).thenReturn(node);when(act.history()).thenReturn(history);
            when(history.prepareAttempt(anyString(),anyString(),any())).thenReturn(true);
            when(history.markAttempt(anyString(),anyString(),any())).thenReturn(true); }
        void run(String token, String input, String recipient, String change, String burn) {
            Coin coin = new Coin(); coin.coinid = "0x" + "11".repeat(32);
            coin.tokenid = token; coin.amount = coin.rawAmount = input; coin.confirmed = coin.sendable = true;
            List<TxnBuilder.Out> outputs = new ArrayList<>(); outputs.add(new TxnBuilder.Out(DEST, recipient));
            if (change != null) outputs.add(new TxnBuilder.Out(CHANGE, change));
            new TxnBuilder(act, Collections.singletonList(coin), outputs, token, burn, done).record("public-attempt").run();
        }
        void assertSingleBurn(String input, String expected) {
            BigDecimal output = BigDecimal.ZERO;
            for (String command : commands) if (command.startsWith("txnoutput ")) {
                for (String part : command.split(" ")) if (part.startsWith("amount:"))
                    output = output.add(new BigDecimal(part.substring(7)));
            }
            assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(input).subtract(output)));
            assertEquals(1, commands.stream().filter(c -> c.startsWith("txnsign ")).count());
            assertFalse("Must not charge another burn from an unselected wallet coin",
                    commands.stream().anyMatch(c -> c.contains("txnpostburn:")));
        }
        public void close() { node.onDestroy(); clients.close(); handlers.close(); }
    }
    @Test public void incompleteSuccessAndTransportLossAreUnknownWithoutCleanupOrSuccess() {
        for (JSONObject reply : new JSONObject[]{object("status",true), object(), object("transporterror","Node result is unavailable. Outcome remains unknown."), object("status",false,"response","Result too long!")}) try (Flow f = new Flow()) {
            f.reject="txnsign "; f.rejection=reply; f.run("0x00","10","3","7",null);
            verify(f.done).onUnknown(TransactionEvidence.UNKNOWN); verify(f.done,never()).onFailed(anyString()); verify(f.done,never()).onPosted(any(),any());
            assertFalse(f.commands.stream().anyMatch(c -> c.startsWith("txndelete ")));
        }
    }
    @Test public void aTimeoutCannotDeleteOrRetryAnExecutingPostAndItsLateReplyCannotDoubleComplete() {
        try (Flow f = new Flow()) {
            f.holdSigning=true; f.run("0x00","10","3","7",null); assertEquals(1,f.timeouts.size());
            f.timeouts.get(0).run(); verify(f.done).onUnknown(TransactionEvidence.UNKNOWN);
            f.lateReply.response(TransactionEvidenceTest.receipt(TransactionEvidenceTest.intent("0x00")));
            verify(f.done,never()).onFailed(anyString()); verify(f.done,never()).onPosted(any(),any());
            assertEquals(1,f.commands.stream().filter(c -> c.startsWith("txnsign ")).count());
            assertFalse(f.commands.stream().anyMatch(c -> c.startsWith("txndelete ")));
        }
    }
    @Test public void aCompleteMatchingReceiptReportsPostedOnce() {
        try (Flow f = new Flow()) {
            f.signReply=TransactionEvidenceTest.receipt(TransactionEvidenceTest.intent("0x00")); f.run("0x00","10","3","7",null);
            verify(f.done).onPosted(eq(TransactionEvidenceTest.id('f')),argThat(v -> v.size()==2));
            verify(f.done,never()).onUnknown(anyString()); verify(f.done,never()).onFailed(anyString());
        }
    }
    @Test public void explicitLocalNotSentRepliesAreFailuresAndCanBeRetriedSafely() {
        for (String message : new String[]{"The embedded node is not running. No command was sent.","Too many pending node requests. Nothing was sent.","Node request cancelled before execution. Nothing was sent.","Node connection is closed."}) try (Flow f = new Flow()) {
            f.reject="txnsign "; f.rejection=object("transporterror",message); f.run("0x00","10","3","7",null);
            verify(f.done).onFailed(message); verify(f.done,never()).onUnknown(anyString()); verify(f.done,never()).onPosted(any(),any());
        }
    }
    @Test public void uncertainConstructionBeforeAnySignatureIsARecoverableBuildFailure() {
        try (Flow f = new Flow()) {
            f.reject="txninput "; f.rejection=object("transporterror","Node result unavailable. Outcome unknown."); f.run("0x00","10","3","7",null);
            verify(f.done).onFailed(anyString()); verify(f.done,never()).onUnknown(anyString());
            assertFalse(f.commands.stream().anyMatch(c -> c.startsWith("txnsign ")));
        }
    }
    @Test public void expertMinimaSendChargesOnlyTheReservedBurnAndReturnsChange() {
        try (Flow f = new Flow()) {
            f.run("0x00", "10", "3", "6.75", "0.25"); f.assertSingleBurn("10", "0.25");
            assertTrue(f.commands.stream().anyMatch(c -> c.contains("amount:6.75 address:" + CHANGE)));
        }
    }
    @Test public void maximumSendStillChargesOnlyOneBurnWithoutAChangeOutput() {
        try (Flow f = new Flow()) {
            f.run("0x00", "10", "9.75", null, "0.25"); f.assertSingleBurn("10", "0.25");
            assertEquals(1, f.commands.stream().filter(c -> c.startsWith("txnoutput ")).count());
        }
    }
    @Test public void zeroBurnAndTokenOutputsRemainUnchanged() {
        for (String token : new String[]{"0x00", "0x" + "cc".repeat(32)})
            for (String burn : new String[]{null, "0"}) try (Flow f = new Flow()) {
                f.run(token, "10", "3", "7", burn); f.assertSingleBurn("10", "0");
                for (String command : f.commands) if (command.startsWith("txnoutput "))
                    assertEquals(!"0x00".equals(token), command.endsWith(" tokenid:" + token));
            }
    }
    @Test public void rejectedBuildStagesStopBeforeSigningAndNeverReportPosted() {
        for (String stage : new String[]{"txncreate ", "txninput ", "txnoutput "}) try (Flow f = new Flow()) {
            f.reject = stage; f.run("0x00", "10", "3", "7", null);
            verify(f.done).onFailed("Rejected public fixture"); verify(f.done, never()).onPosted(any(), any());
            assertFalse(f.commands.stream().anyMatch(c -> c.startsWith("txnsign ")));
            assertEquals(1, f.commands.stream().filter(c -> c.startsWith("txndelete ")).count());
        }
    }
    @Test public void rejectedSigningReplyCannotBeReportedAsPosted() {
        try (Flow f = new Flow()) {
            f.reject = "txnsign "; f.run("0x00", "10", "3", "7", null);
            verify(f.done).onFailed("Rejected public fixture"); verify(f.done, never()).onPosted(any(), any());
        }
    }
    @Test public void noCommandCanPrecedeTheSavedIntentAndNoSignatureCanPrecedePostingCheckpoint() {
        try(Flow f=new Flow()) {
            when(f.history.prepareAttempt(anyString(),anyString(),any())).thenAnswer(i->{assertTrue(f.commands.isEmpty());return true;});
            when(f.history.markAttempt(anyString(),eq(HistoryDb.POSTING),any())).thenAnswer(i->{assertFalse(f.commands.stream().anyMatch(c->c.startsWith("txnsign ")));return true;});
            f.signReply=TransactionEvidenceTest.receipt(TransactionEvidenceTest.intent("0x00"));f.run("0x00","10","3","7",null);
            verify(f.history).prepareAttempt(eq("public-attempt"),anyString(),any());verify(f.history).markAttempt(eq("public-attempt"),eq(HistoryDb.POSTING),isNull());
            verify(f.history).markAttempt(eq("public-attempt"),eq(HistoryDb.POSTED),same(f.signReply));verify(f.done).onPosted(anyString(),any());
        }
    }
    @Test public void reservationFailureSendsNoNodeCommand() {
        try(Flow f=new Flow()){when(f.history.prepareAttempt(anyString(),anyString(),any())).thenReturn(false);f.run("0x00","10","3","7",null);assertTrue(f.commands.isEmpty());verify(f.done).onNotStarted(TransactionEvidence.RECORD_FAILED);}
    }
    @Test public void postingCheckpointFailureCannotSignOrReleaseItsReservation() {
        try(Flow f=new Flow()){when(f.history.markAttempt(anyString(),eq(HistoryDb.POSTING),any())).thenReturn(false);f.run("0x00","10","3","7",null);
            assertFalse(f.commands.stream().anyMatch(c->c.startsWith("txnsign ")||c.startsWith("txndelete ")));verify(f.done).onUnknown(TransactionEvidence.UNKNOWN);verify(f.history,never()).markAttempt(anyString(),eq(HistoryDb.NOT_SUBMITTED),any());}
    }
    @Test public void failedReceiptOrRejectionWriteRetainsUnknownInsteadOfClaimingAnOutcome() {
        for(boolean accepted:new boolean[]{true,false})try(Flow f=new Flow()) {
            if(accepted){f.signReply=TransactionEvidenceTest.receipt(TransactionEvidenceTest.intent("0x00"));when(f.history.markAttempt(anyString(),eq(HistoryDb.POSTED),any())).thenReturn(false);}
            else{f.reject="txnsign ";when(f.history.markAttempt(anyString(),eq(HistoryDb.NOT_SUBMITTED),any())).thenReturn(false);}
            f.run("0x00","10","3","7",null);verify(f.done).onUnknown(TransactionEvidence.UNKNOWN);verify(f.done,never()).onFailed(anyString());verify(f.done,never()).onPosted(anyString(),any());
            assertFalse(f.commands.stream().anyMatch(c->c.startsWith("txndelete ")));
        }
    }
}
