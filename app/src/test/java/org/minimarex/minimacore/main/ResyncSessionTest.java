package org.minimarex.minimacore.main;

import org.junit.Test;
import org.minima.utils.json.JSONObject;
import org.minimarex.minimacore.utils.LogBuffer;
import org.minimarex.minimacore.utils.MinimaCMDListener;
import static org.junit.Assert.*;

public class ResyncSessionTest {
    private static class FakeRunner implements ResyncSession.Runner {
        int calls;
        String command;
        MinimaCMDListener callback;
        @Override public void run(String command, MinimaCMDListener callback) {
            calls++;
            this.command = command;
            this.callback = callback;
        }
        void reply(boolean success, String error) {
            JSONObject result = new JSONObject();
            result.put("status", success);
            if (error != null) result.put("message", error);
            callback.cmdResult(result);
        }
    }

    @Test public void oneCommandSurvivesRepeatedStartsAndLogTextCannotSignalSuccess() {
        FakeRunner runner = new FakeRunner();
        ResyncSession session = new ResyncSession(runner);
        try {
            assertTrue(session.start("example.org:9001", System.nanoTime()));
            assertFalse(session.start("other.org:9001", System.nanoTime()));
            LogBuffer.append("MegaMMR sync finished.. please restart");
            assertEquals(ResyncSession.State.RUNNING, session.state());
            assertEquals(1, runner.calls);
            assertEquals("megammrsync action:resync host:example.org:9001", runner.command);
            session.reset();
            assertEquals(ResyncSession.State.RUNNING, session.state());
            runner.reply(true, null);
            assertEquals(ResyncSession.State.SUCCEEDED, session.state());
            assertFalse(session.start("example.org:9001", System.nanoTime()));
            long revision = session.log.revision();
            LogBuffer.append("unrelated future node log");
            assertEquals(revision, session.log.revision());
        } finally { LogBuffer.removeObserver(session); }
    }

    @Test public void failureKeepsNodeMessageAndCanRetryWithoutReplayingOldResult() {
        FakeRunner runner = new FakeRunner();
        ResyncSession session = new ResyncSession(runner);
        try {
            session.start("example.org:9001", System.nanoTime());
            MinimaCMDListener oldCallback = runner.callback;
            runner.reply(false, "Could not connect to Archive host!");
            assertEquals(ResyncSession.State.FAILED, session.state());
            assertEquals("Could not connect to Archive host!", session.result());
            assertTrue(session.start("second.org:9002", System.nanoTime()));
            assertEquals("second.org:9002", session.host());
            assertEquals("", session.result());
            JSONObject stale = new JSONObject();
            stale.put("status", true);
            oldCallback.cmdResult(stale);
            assertEquals(ResyncSession.State.RUNNING, session.state());
            runner.reply(false, "retry failed");
            session.requestRestart();
            assertTrue(session.restartRequested());
            assertFalse(session.start("second.org:9002", System.nanoTime()));
            session.reset();
            assertEquals(ResyncSession.State.IDLE, session.state());
        } finally { LogBuffer.removeObserver(session); }
    }

    @Test public void exceptionAndInterruptedProcessNeverReportSuccess() {
        ResyncSession session = new ResyncSession((cmd, cb) -> { throw new IllegalStateException("Node is not running"); });
        session.start("example.org:9001", System.nanoTime());
        assertEquals(ResyncSession.State.FAILED, session.state());
        assertEquals("Node is not running", session.result());
        session.reset();
        session.interrupted();
        assertEquals(ResyncSession.State.FAILED, session.state());
        assertTrue(session.result().contains("Completion is unknown"));
    }

    @Test public void validatesHostBeforeItCanBecomeCommandParameters() {
        assertTrue(ResyncSession.validHost("node.example.org:9001"));
        assertTrue(ResyncSession.validHost("192.168.1.10:9001"));
        for (String host : new String[]{"", "node", ":9001", "node:0", "node:65536", "node:9001;quit", "node:9001 phrase:abc", "node:9001\n", null}) {
            assertFalse(String.valueOf(host), ResyncSession.validHost(host));
        }
    }
}
