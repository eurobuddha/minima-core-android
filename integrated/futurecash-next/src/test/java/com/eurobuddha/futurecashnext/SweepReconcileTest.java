package com.eurobuddha.futurecashnext;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The reconcile pass against a real node's replies: `coins coinid:` answers {"status":true,"response":[]} for a
 *  coin that was spent. A sweep is recorded SWEPT only when its output is seen at the safe; otherwise the row gets
 *  a distinct status and the notice says which kind of "unproven" it is. */
public class SweepReconcileTest {
    static final String COIN = "0x" + "c1".repeat(32), EXPOSED = "0x" + "e1".repeat(32);
    static final String SAFE_MX = "Mx" + "S".repeat(60), SAFE_HEX = "0x" + "5a".repeat(32), OUT = "0x" + "0d".repeat(32);
    static final long POSTED_AT = 1000;

    final Node node = mock(Node.class, CALLS_REAL_METHODS);
    final Cfg cfg = mock(Cfg.class);
    final Store store = mock(Store.class);
    final List<String> notices = new ArrayList<>();
    final List<String> commands = new ArrayList<>();

    Guardian guardian(String mode, JSONArray atSafe) throws Exception {
        when(cfg.get(eq(Cfg.SAFE_MODE), anyString())).thenReturn(mode);
        when(cfg.cleanAddr()).thenReturn(new String[]{SAFE_MX, SAFE_HEX});
        Store.SweepRow row = new Store.SweepRow();
        row.coinid = COIN; row.address = EXPOSED; row.tokenid = "0x00"; row.amount = "25"; row.status = Store.SWEEP_POSTED;
        row.sweepBlock = POSTED_AT; row.attempts = 1;
        when(store.sweepsWithStatus(Store.SWEEP_POSTED)).thenReturn(Collections.singletonList(row));
        when(store.sweepNotes()).thenReturn(new ArrayList<>());
        doAnswer(call -> {
            String command = call.getArgument(0);
            commands.add(command);
            // What a real node says for a coin that is spent: status true, no row.
            if (command.equals("coins coinid:" + COIN)) return new JSONObject("{\"command\":\"coins\",\"status\":true,\"pending\":false,\"response\":[]}");
            if (command.startsWith("coins address:")) return new JSONObject().put("status", true).put("pending", false).put("response", atSafe);
            throw new AssertionError("unexpected node command: " + command);
        }).when(node).cmd(anyString());
        return new Guardian(node, cfg, store, (title, body) -> notices.add(title + " | " + body));
    }

    static void reconcile(Guardian g, long tip, String safe) throws Exception {
        java.lang.reflect.Method m = Guardian.class.getDeclaredMethod("reconcileSweeps", long.class, String.class);
        m.setAccessible(true);
        m.invoke(g, tip, safe);
    }

    List<String[]> written() {
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class), note = ArgumentCaptor.forClass(String.class);
        verify(store, atLeast(0)).setSweepStatus(eq(COIN), status.capture(), note.capture());
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < status.getAllValues().size(); i++) out.add(new String[]{status.getAllValues().get(i), note.getAllValues().get(i)});
        return out;
    }

    @Test public void ownSafeNothingArrivedIsNotSweptAndSaysSoPlainly() throws Exception {
        Guardian g = guardian("samenode", new JSONArray());
        reconcile(g, POSTED_AT + Guardian.VERIFY_MAX - 1, SAFE_MX);
        assertTrue("still inside the verify window: nothing is written", written().isEmpty());
        assertTrue(notices.isEmpty());

        reconcile(g, POSTED_AT + Guardian.VERIFY_MAX, SAFE_MX);
        assertTrue("the safe is looked up by its hex form: " + commands, commands.contains("coins address:" + SAFE_HEX + " tokenid:0x00"));
        List<String[]> w = written();
        assertEquals(1, w.size());
        assertEquals("UNVERIFIED", w.get(0)[0]);
        assertEquals("not-arrived", w.get(0)[1]);
        assertEquals(1, notices.size());
        String notice = notices.get(0);
        assertTrue(notice, notice.contains("left its exposed address") && notice.contains("did NOT arrive at your safe address")
                && notice.contains("may be compromised"));
        verify(store, never()).setSweepStatus(anyString(), eq(Store.SWEPT), any());
    }

    @Test public void externalSafeIsSentNotVerifiableAndNoAlarm() throws Exception {
        Guardian g = guardian("external", new JSONArray());
        reconcile(g, POSTED_AT + Guardian.VERIFY_MAX, SAFE_MX);
        assertFalse("an external safe is never looked up on this node: " + commands, commands.stream().anyMatch(c -> c.startsWith("coins address:")));
        List<String[]> w = written();
        assertEquals(1, w.size());
        assertEquals("UNVERIFIED", w.get(0)[0]);
        assertEquals("external", w.get(0)[1]);
        assertEquals(1, notices.size());
        String notice = notices.get(0);
        assertTrue(notice, notice.contains("sent to your external safe address") && notice.contains("cannot verify"));
        assertFalse("no alarm: " + notice, notice.contains("⚠") || notice.contains("compromised") || notice.contains("NOT"));
        verify(store, never()).setSweepStatus(anyString(), eq(Store.SWEPT), any());
    }

    @Test public void theOutputAtTheOwnSafeIsTheOnlyThingRecordedSwept() throws Exception {
        JSONObject arrived = new JSONObject().put("coinid", OUT).put("tokenid", "0x00").put("amount", "25").put("spent", false)
                .put("created", String.valueOf(POSTED_AT + 1));
        Guardian g = guardian("samenode", new JSONArray().put(arrived));
        reconcile(g, POSTED_AT + 2, SAFE_MX);
        List<String[]> w = written();
        assertEquals(1, w.size());
        assertEquals(Store.SWEPT, w.get(0)[0]);
        assertEquals("the proof is the output coin, in full", OUT, w.get(0)[1]);
        assertTrue(notices.toString(), notices.size() == 1 && notices.get(0).contains("Secured 25 to your safe address"));
    }
}
