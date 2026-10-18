package com.eurobuddha.terminalide.terminal;

import android.app.Activity;
import android.os.Looper;
import android.widget.TextView;
import com.eurobuddha.terminalide.NodeApi;
import com.eurobuddha.minimacore.i18n.Language;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.Shadows;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class TerminalRenderingTest {
    private TerminalView terminal;
    private NodeApi node;
    private NodeApi.Cb callback;
    @Before public void setup() {
        Language.initialize(RuntimeEnvironment.getApplication(), () -> false);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat);
        terminal = new TerminalView(activity); activity.setContentView(terminal.getMainView());
        node = mock(NodeApi.class); terminal.setNodeApi(node);
        doAnswer(i -> {callback=i.getArgument(1); return null;}).when(node).cmd(anyString(),any());
    }
    @After public void close() {terminal.onDestroy();}
    private void awaitResult() throws Exception {
        long until=System.nanoTime()+10_000_000_000L;
        while (System.nanoTime()<until) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (terminal.exportText().contains("end-marker")) return;
            Thread.sleep(10);
        }
        fail("Background formatter did not complete");
    }
    @Test public void multiMegabyteReplyDrawsSmallPreviewButExportsCompleteJson() throws Exception {
        terminal.runCMD("balance");
        JSONObject reply = new JSONObject().put("status",true).put("image","a".repeat(5*1024*1024)).put("marker","end-marker");
        callback.onResult(reply); awaitResult();
        TextView label=(TextView)terminal.mOutput.getChildAt(terminal.mOutput.getChildCount()-1);
        assertTrue(label.length()<14000);
        assertTrue(label.getText().toString().contains("Preview shortened"));
        assertTrue(terminal.exportText().contains(reply.toString(2)));
        assertTrue(terminal.exportText().contains("MINIMA  TERMINAL  IDE"));
        terminal.setSelectionMode(true);
        assertTrue(label.length()<14000);
        for(String code:new String[]{"en","zh-Hans","ru","uk"}) {
            Language.select(RuntimeEnvironment.getApplication(),code);
            TerminalLabel.refreshTree(terminal.getMainView());
            assertTrue(label.length()<14000); assertTrue(terminal.exportText().contains(reply.toString(2)));
        }
        verify(node,times(1)).cmd(eq("balance"),any());
    }
    @Test public void clearingTerminalDiscardsLateResultsWithoutRerunningTheCommand() throws Exception {
        terminal.runCMD("balance"); terminal.clearTerminal();
        callback.onResult(new JSONObject().put("status",true).put("marker","end-marker"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1,terminal.mOutput.getChildCount());
        assertFalse(terminal.exportText().contains("end-marker"));
        verify(node,times(1)).cmd(eq("balance"),any());
    }
    @Test public void commandAdmissionStopsBeforeDispatchAndResumesAfterAReply() {
        for(int i=0;i<9;i++) terminal.runCMD("status");
        verify(node,times(8)).cmd(eq("status"),any());
        callback.onError("offline"); terminal.runCMD("status");
        verify(node,times(9)).cmd(eq("status"),any());
    }
    @Test public void responseFormattingRunsAwayFromTheMainLooper() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean formattedOnMain = new java.util.concurrent.atomic.AtomicBoolean(true);
        JSONObject reply = new JSONObject() {
            @Override public String toString(int spaces) throws org.json.JSONException {
                formattedOnMain.set(Looper.myLooper() == Looper.getMainLooper());
                return super.toString(spaces);
            }
        };
        reply.put("status",true).put("marker","end-marker");
        terminal.runCMD("status"); callback.onResult(reply); awaitResult();
        assertFalse(formattedOnMain.get());
    }
    @Test public void destructionDiscardsLateResults() throws Exception {
        terminal.runCMD("balance"); terminal.onDestroy();
        callback.onResult(new JSONObject().put("status",true).put("marker","end-marker"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(terminal.exportText().contains("end-marker"));
    }
}
