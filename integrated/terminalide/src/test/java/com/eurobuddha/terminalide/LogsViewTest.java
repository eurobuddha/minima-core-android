package com.eurobuddha.terminalide;

import android.app.Activity;
import android.os.Looper;
import android.widget.TextView;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.terminalide.receiver.ReceiverDB;
import com.eurobuddha.terminalide.terminal.TerminalOutput;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class LogsViewTest {
    private ReceiverDB db;
    private LogsView view;
    @Before public void setup() {
        RuntimeEnvironment.getApplication().deleteDatabase(ReceiverDB.DATABASE_NAME);
        Language.initialize(RuntimeEnvironment.getApplication(), () -> false);
        db = new ReceiverDB(RuntimeEnvironment.getApplication());
    }
    @After public void cleanup() {
        if (view != null) view.onDestroy();
        db.close();
    }
    private void log(String message) throws Exception {
        db.insertEvent("MINIMALOG", new JSONObject().put("message",message).toString());
    }
    private TextView createView() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat);
        view = new LogsView(activity);
        activity.setContentView(view.getMainView());
        return view.getMainView().findViewById(R.id.pm_terminalide_logs_maintext);
    }
    @Test public void previewKeepsRecentLogsInOrderAndLeavesFullRecordsUnchanged() throws Exception {
        for (int i=0;i<300;i++) log("event-"+i);
        db.insertEvent("OTHER", "unrelated");
        String preview = db.getRecentLogPreview();
        assertFalse(preview.contains("event-139\n"));
        assertTrue(preview.startsWith("event-140\n"));
        assertTrue(preview.endsWith("event-299\n"));
        assertFalse(preview.contains("unrelated"));
        assertEquals(301,db.getAllMessages().length());
    }
    @Test public void oversizedLegacyRowsDoNotOverflowCursorOrHideNewerLogs() throws Exception {
        log("image="+"a".repeat(5*1024*1024));
        log("newest-marker");
        String preview = db.getRecentLogPreview();
        assertTrue(preview.length()<=TerminalOutput.MAX_CHARS);
        assertTrue(preview.endsWith("newest-marker\n"));
        assertTrue(preview.contains("…"));
        for(String line:preview.split("\n")) assertTrue(line.length()<=TerminalOutput.MAX_LINE+1);
    }
    @Test public void multilineBacklogIsBoundedAndMalformedRowsCannotBlockRecentMessages() throws Exception {
        for(int i=0;i<160;i++) log(("line "+i+"\n").repeat(200));
        db.insertEvent("MINIMALOG", "broken json");
        log("newest-marker");
        String preview=db.getRecentLogPreview();
        assertTrue(preview.length()<=TerminalOutput.MAX_CHARS);
        assertTrue(preview.split("\n").length<=TerminalOutput.MAX_LINES);
        assertTrue(preview.endsWith("newest-marker\n"));
        assertTrue(preview.contains("broken json"));
    }
    @Test public void logsTabLoadsLargeHistoryAsynchronouslyAndSurvivesRepeatedSelection() throws Exception {
        log("a".repeat(5*1024*1024)); log("newest-marker");
        TextView text=createView();
        for(int i=0;i<40;i++) view.refreshView();
        assertEquals("",text.getText().toString());
        long until=System.nanoTime()+10_000_000_000L;
        while(System.nanoTime()<until && !text.getText().toString().contains("newest-marker")) {
            Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10);
        }
        assertTrue(text.getText().toString().contains("newest-marker"));
        assertTrue(text.length()<14000);
        for(String code:new String[]{"en","zh-Hans","ru","uk"}) {
            Language.select(RuntimeEnvironment.getApplication(),code);
            Language.apply(view.getMainView());
            assertTrue(text.getText().toString().contains("newest-marker"));
            assertTrue(text.length()<14000);
        }
    }
    @Test public void destroyedViewIgnoresQueuedResultAndRefresh() throws Exception {
        log("newest-marker"); TextView text=createView();
        view.refreshView(); view.onDestroy(); view.refreshView();
        Thread.sleep(100); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("",text.getText().toString());
    }
}
