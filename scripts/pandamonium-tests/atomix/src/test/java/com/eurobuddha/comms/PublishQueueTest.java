package com.eurobuddha.atomix.integratedcomms;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.eurobuddha.atomix.TradingContext;
import com.eurobuddha.atomix.swap.*;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;
import com.goterl.lazysodium.LazySodium;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.junit.*;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real publication, signing queue, NodeApi and DirectNodeApi with synthetic host work and preferences. */
public class PublishQueueTest {
    private TradingContext saved;
    @Before public void before() { saved = TradingContext.active(); SignGate.resetForTest(); WriteSafety.resetForTest(); }
    @After public void after() { TradingContext.setActive(saved, null); SignGate.resetForTest(); WriteSafety.resetForTest(); }
    private static JSONObject sent() throws Exception { return new JSONObject().put("status", true).put("response", new JSONObject().put("txpowid", "0x" + "12".repeat(32))); }
    private static final class Host implements AutoCloseable {
        final List<DirectNodeApi.Request> requests = new ArrayList<>();
        final List<String> commands = new ArrayList<>();
        final Map<String,Object> persisted = new HashMap<>();
        final List<Runnable> timeouts = new ArrayList<>();
        final MockedStatic<Looper> loopers = mockStatic(Looper.class);
        final MockedStatic<Log> logs = mockStatic(Log.class);
        final MockedConstruction<Handler> handlers;
        final NodeApi node;
        Host() {
            Context context = mock(Context.class); when(context.getPackageName()).thenReturn("com.eurobuddha.pandamonium");
            SharedPreferences prefs = mock(SharedPreferences.class); SharedPreferences.Editor edit = mock(SharedPreferences.Editor.class);
            when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs); when(prefs.edit()).thenReturn(edit);
            when(prefs.getString(anyString(), anyString())).thenAnswer(i -> persisted.getOrDefault(i.getArgument(0), i.getArgument(1)));
            when(prefs.getLong(anyString(), anyLong())).thenAnswer(i -> persisted.getOrDefault(i.getArgument(0), i.getArgument(1)));
            when(edit.putString(anyString(), anyString())).thenAnswer(i -> { persisted.put(i.getArgument(0), i.getArgument(1)); return edit; });
            when(edit.putLong(anyString(), anyLong())).thenAnswer(i -> { persisted.put(i.getArgument(0), i.getArgument(1)); return edit; });
            when(edit.remove(anyString())).thenAnswer(i -> { persisted.remove(i.getArgument(0)); return edit; }); when(edit.commit()).thenReturn(true);
            handlers = mockConstruction(Handler.class, (h,c) -> {
                when(h.post(any())).thenAnswer(i -> { ((Runnable)i.getArgument(0)).run(); return true; });
                when(h.postDelayed(any(), anyLong())).thenAnswer(i -> { if ((long)i.getArgument(1) == 180000L) timeouts.add(i.getArgument(0)); return true; });
            });
            DirectNodeApi.install(new DirectNodeApi.Backend() {
                public boolean ready() { return true; }
                public void command(String command, DirectNodeApi.Request reply) { commands.add(command); requests.add(reply); }
                public void file(String a,String p,String n,android.net.Uri u,DirectNodeApi.Request reply) { fail("No file actions expected"); }
            });
            node = new NodeApi(context, null);
        }
        public void close() { node.onDestroy(); DirectNodeApi.install(null); handlers.close(); logs.close(); loopers.close(); }
    }
    private static CommsIdentity identity() { return CommsIdentity.fromKeys(new byte[32], new byte[32], new byte[32], new byte[64]); }
    private static LazySodium signer() {
        LazySodium sodium = mock(LazySodium.class);
        when(sodium.cryptoSignDetached(any(byte[].class), any(byte[].class), anyLong(), any(byte[].class))).thenReturn(true); return sodium;
    }
    private static void publish(Host host, boolean otc, LazySodium sodium, CommsTransport.SendCb cb) {
        if (otc) {
            OtcOffer offer = new OtcOffer(); offer.enable = true; offer.sellSize = 5;
            OtcBook.publish(host.node, sodium, identity(), offer, cb);
        } else {
            Order order = new Order(); order.minimaPublicKey = "0x1234"; order.ethAddress = "0x" + "34".repeat(20);
            SwapOrderBook.publish(host.node, sodium, identity(), order, cb);
        }
    }
    @Test public void supersededPublicationsNeverLeaveTheSigningQueue() {
        for (TradingContext market : TradingContext.values()) for (boolean otc : new boolean[]{true,false}) for (boolean currency : new boolean[]{true,false}) {
            TradingContext.setActive(market, null);
            try (Host host = new Host()) {
                List<SignGate.Release> hold = new ArrayList<>(); SignGate.submit(hold::add);
                CommsTransport.SendCb cb = mock(CommsTransport.SendCb.class); publish(host, otc, signer(), cb);
                assertTrue(host.commands.isEmpty()); assertTrue(host.persisted.isEmpty());
                if (currency) TradingContext.setActive(market.other(), null); else PublishGate.bumpGen();
                hold.get(0).free(); assertTrue(host.commands.isEmpty()); assertTrue(host.persisted.isEmpty());
                verify(cb).onFailed(NodeApi.ERR_COMMAND_CHANGED); verify(cb, never()).onSent(anyString());
                List<Boolean> next = new ArrayList<>(); SignGate.submit(r -> { next.add(true); r.free(); }); assertEquals(1, next.size());
            }
        }
    }
    @Test public void supersededPublicationsCannotBeginInTheSharedNodeQueue() {
        for (TradingContext market : TradingContext.values()) for (boolean otc : new boolean[]{true,false}) for (boolean currency : new boolean[]{true,false}) {
            TradingContext.setActive(market, null); WriteSafety.resetForTest();
            try (Host host = new Host()) {
                CommsTransport.SendCb cb = mock(CommsTransport.SendCb.class); publish(host, otc, signer(), cb);
                assertEquals(1, host.requests.size()); assertTrue(host.persisted.containsKey("pending"));
                if (currency) TradingContext.setActive(market.other(), null); else PublishGate.bumpGen();
                assertFalse(host.requests.get(0).begin()); verify(cb, never()).onSent(anyString()); verify(cb).onFailed(NodeApi.ERR_WRITE_UNCERTAIN);
                // Existing transport contract intentionally retains incomplete-reply evidence, even for queue cancellation.
                assertTrue(host.node.hasInterruptedWrite());
            }
        }
    }
    @Test public void unchangedPublicationsUseBothOriginalBoardsAndReleaseTheGate() throws Exception {
        for (TradingContext market : TradingContext.values()) for (boolean otc : new boolean[]{true,false}) {
            TradingContext.setActive(market, null);
            try (Host host = new Host()) {
                CommsTransport.SendCb cb = mock(CommsTransport.SendCb.class); publish(host, otc, signer(), cb);
                assertTrue(host.commands.get(0).contains("address:" + (otc ? market.otcBoardAddr : market.orderBookAddr)));
                assertTrue(host.requests.get(0).begin()); host.requests.get(0).accept(sent());
                verify(cb).onSent("0x" + "12".repeat(32)); verify(cb, never()).onFailed(anyString()); assertFalse(host.node.hasInterruptedWrite());
                assertFalse(host.persisted.containsKey("pending"));
                List<Boolean> next = new ArrayList<>(); SignGate.submit(r -> { next.add(true); r.free(); }); assertEquals(1, next.size());
            }
        }
    }
    @Test public void startedWritesKeepTheirReplyButCannotRestampAnObsoleteOffer() throws Exception {
        for (boolean otc : new boolean[]{true,false}) {
            TradingContext.setActive(TradingContext.MINIMA, null);
            try (Host host = new Host()) {
                CommsTransport.SendCb cb = mock(CommsTransport.SendCb.class); publish(host, otc, signer(), cb);
                DirectNodeApi.Request request = host.requests.get(0); assertTrue(request.begin()); PublishGate.bumpGen();
                TradingContext.setActive(TradingContext.MXUSDT, null); request.accept(sent());
                assertFalse(host.persisted.containsKey("pending")); verify(cb).onFailed(NodeApi.ERR_COMMAND_CHANGED); verify(cb, never()).onSent(anyString());
                request.accept(sent()); verify(cb, times(1)).onFailed(anyString());
            }
        }
    }
    @Test public void otcChangeDuringSigningCannotSendToEitherBoard() {
        TradingContext.setActive(TradingContext.MINIMA, null);
        try (Host host = new Host()) {
            LazySodium sodium = signer(); when(sodium.cryptoSignDetached(any(byte[].class), any(byte[].class), anyLong(), any(byte[].class)))
                .thenAnswer(i -> { TradingContext.setActive(TradingContext.MXUSDT, null); return true; });
            CommsTransport.SendCb cb = mock(CommsTransport.SendCb.class); publish(host, true, sodium, cb);
            assertTrue(host.commands.isEmpty()); verify(cb).onFailed(NodeApi.ERR_COMMAND_CHANGED);
        }
    }
    @Test public void missingAndThrowingGuardsFailBeforeDurableWriteAdmission() {
        for (BooleanSupplier guard : Arrays.<BooleanSupplier>asList(null, () -> false, () -> { throw new IllegalStateException("fixture"); })) {
            try (Host host = new Host()) {
                NodeApi.Cb cb = mock(NodeApi.Cb.class); host.node.cmd("send amount:1", guard, cb);
                assertTrue(host.commands.isEmpty()); assertTrue(host.persisted.isEmpty()); verify(cb).onError(NodeApi.ERR_COMMAND_CHANGED);
                CommsTransport.SendCb send = mock(CommsTransport.SendCb.class);
                CommsTransport.postBlob(host.node, "0x12", CommsTransport.MESSAGE_AMOUNT, "0x00", "abcd", null, guard, send);
                verify(send).onFailed(NodeApi.ERR_COMMAND_CHANGED); assertTrue(host.commands.isEmpty());
            }
        }
    }
    @Test public void queuedAndIncompleteRepliesRetainTheExistingWritePause() throws Exception {
        for (JSONObject reply : Arrays.asList(new JSONObject().put("status", true).put("pending", true), new JSONObject().put("response", "incomplete"))) {
            WriteSafety.resetForTest();
            try (Host host = new Host()) {
                CommsTransport.SendCb cb = mock(CommsTransport.SendCb.class); publish(host, true, signer(), cb);
                assertTrue(host.requests.get(0).begin()); host.requests.get(0).accept(reply);
                verify(cb).onFailed(NodeApi.ERR_WRITE_UNCERTAIN); verify(cb, never()).onSent(anyString()); assertTrue(host.node.hasInterruptedWrite());
                host.node.cmd("send amount:1", mock(NodeApi.Cb.class)); assertEquals(1, host.requests.size());
            }
        }
    }
    @Test public void timeoutRetainsEvidenceAndLateCompleteReplyDoesNotRedeliver() throws Exception {
        try (Host host = new Host()) {
            CommsTransport.SendCb cb = mock(CommsTransport.SendCb.class); publish(host, true, signer(), cb);
            assertTrue(host.requests.get(0).begin()); assertEquals(1, host.timeouts.size()); host.timeouts.get(0).run();
            assertTrue(host.node.hasInterruptedWrite()); verify(cb).onFailed(NodeApi.ERR_WRITE_UNCERTAIN);
            host.requests.get(0).accept(sent()); assertFalse(host.node.hasInterruptedWrite()); verify(cb, never()).onSent(anyString()); verify(cb, times(1)).onFailed(anyString());
        }
    }
    @Test public void ordinaryMessagesRemainIndependentOfPublicationGeneration() throws Exception {
        try (Host host = new Host()) {
            List<SignGate.Release> hold = new ArrayList<>(); SignGate.submit(hold::add);
            CommsTransport.SendCb cb = mock(CommsTransport.SendCb.class);
            CommsTransport.postBlob(host.node, "0x12", CommsTransport.MESSAGE_AMOUNT, "0x00", "abcd", null, cb);
            PublishGate.bumpGen(); hold.get(0).free(); assertEquals(1, host.requests.size());
            assertTrue(host.requests.get(0).begin()); host.requests.get(0).accept(sent()); verify(cb).onSent(anyString());
        }
    }
    @Test public void completeNodeRejectionAndValidationErrorsRemainFailures() throws Exception {
        try (Host host = new Host()) {
            CommsTransport.SendCb rejected = mock(CommsTransport.SendCb.class); publish(host, false, signer(), rejected);
            assertTrue(host.requests.get(0).begin()); host.requests.get(0).accept(new JSONObject().put("status", false).put("error", "fixture rejected"));
            verify(rejected).onFailed("fixture rejected"); assertFalse(host.node.hasInterruptedWrite());
            CommsTransport.SendCb invalid = mock(CommsTransport.SendCb.class);
            CommsTransport.postBlob(host.node, "0x12 tokenid:evil", "1", "0x00", "abcd", null, () -> true, invalid);
            verify(invalid).onFailed("bad post address"); assertEquals(1, host.requests.size());
        }
    }

    @Test public void malformedPositiveSendRepliesKeepDurablePauseAndNeverReportSent() throws Exception {
        List<JSONObject> replies = new ArrayList<>();
        replies.add(new JSONObject().put("status", true));
        for (Object response : new Object[]{JSONObject.NULL, "missing", 7, new org.json.JSONArray(), new JSONObject()})
            replies.add(new JSONObject().put("status", true).put("response", response));
        for (Object id : new Object[]{JSONObject.NULL, "", false, 12, "0x12", "0x" + "00".repeat(32),
                "0x" + "ab".repeat(33), "0x" + "zz".repeat(32), "0x" + "ab".repeat(32) + "\n"})
            replies.add(new JSONObject().put("status", true).put("response", new JSONObject().put("txpowid", id)));
        for (String flag : new String[]{"pending", "dryrun"}) for (Object value : new Object[]{true, "true", "false", JSONObject.NULL, 0})
            replies.add(sent().put(flag, value));
        for (boolean otc : new boolean[]{false, true}) for (JSONObject reply : replies) {
            WriteSafety.resetForTest();
            try (Host host = new Host()) {
                CommsTransport.SendCb cb = mock(CommsTransport.SendCb.class); publish(host, otc, signer(), cb);
                assertTrue(host.requests.get(0).begin()); String marker = (String) host.persisted.get("pending");
                host.requests.get(0).accept(reply);
                verify(cb).onFailed(NodeApi.ERR_WRITE_UNCERTAIN); verify(cb, never()).onSent(anyString());
                assertEquals(marker, host.persisted.get("pending")); assertEquals(WriteSafety.WHY_INCOMPLETE, host.persisted.get("pending_why"));
                WriteSafety.resetForTest(); assertTrue(host.node.hasInterruptedWrite());
                NodeApi.Cb blocked = mock(NodeApi.Cb.class); host.node.cmd("send amount:1", blocked);
                verify(blocked).onError(NodeApi.ERR_WRITE_UNCERTAIN); assertEquals(1, host.requests.size());
            }
        }
    }
    @Test public void completeSendReceiptKeepsTheExactIdAndPreviewContractsStaySeparate() throws Exception {
        String id = "0x" + "aB".repeat(32);
        try (Host host = new Host()) {
            CommsTransport.SendCb cb = mock(CommsTransport.SendCb.class); publish(host, false, signer(), cb);
            JSONObject reply = sent().put("pending", false).put("dryrun", false);
            reply.getJSONObject("response").put("txpowid", id);
            assertTrue(host.requests.get(0).begin()); host.requests.get(0).accept(reply);
            verify(cb).onSent(id); assertFalse(host.node.hasInterruptedWrite());
            assertFalse(host.persisted.containsKey("pending"));
        }
        JSONObject preview = new JSONObject().put("status", true).put("dryrun", true);
        assertTrue(WriteSafety.completeReply("send amount:1 dryrun:true", preview));
        assertFalse(WriteSafety.completeReply("send amount:1", preview));
        assertTrue(WriteSafety.completeReply("status", new JSONObject().put("status", true)));
        assertTrue(WriteSafety.completeReply("txnsign id:fixture", new JSONObject().put("status", true)));
    }
    @Test public void lateMalformedSendReceiptDoesNotResolveTimedOutEvidence() throws Exception {
        try (Host host = new Host()) {
            CommsTransport.SendCb cb = mock(CommsTransport.SendCb.class); publish(host, true, signer(), cb);
            assertTrue(host.requests.get(0).begin()); host.timeouts.get(0).run();
            String marker = (String) host.persisted.get("pending");
            host.requests.get(0).accept(new JSONObject().put("status", true));
            assertEquals(marker, host.persisted.get("pending")); assertTrue(host.node.hasInterruptedWrite());
            verify(cb, times(1)).onFailed(NodeApi.ERR_WRITE_UNCERTAIN); verify(cb, never()).onSent(anyString());
        }
    }
    @Test public void unusableReplyStopsTheAlreadyQueuedPublicationBeforeNodeDispatch() throws Exception {
        try (Host host = new Host()) {
            CommsTransport.SendCb first = mock(CommsTransport.SendCb.class), next = mock(CommsTransport.SendCb.class);
            publish(host, false, signer(), first); publish(host, true, signer(), next);
            assertEquals(1, host.requests.size()); assertTrue(host.requests.get(0).begin());
            host.requests.get(0).accept(new JSONObject().put("status", true));
            verify(first).onFailed(NodeApi.ERR_WRITE_UNCERTAIN); verify(next).onFailed(NodeApi.ERR_WRITE_UNCERTAIN);
            verify(first, never()).onSent(anyString()); verify(next, never()).onSent(anyString());
            assertEquals(1, host.requests.size()); assertTrue(host.node.hasInterruptedWrite());
        }
    }
}
