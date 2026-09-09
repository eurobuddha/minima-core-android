package org.minimarex.minimacore.main;

import org.minima.system.commands.network.connect;
import org.minima.utils.json.JSONObject;
import org.minima.utils.messages.Message;
import org.minimarex.minimacore.utils.Feedback;
import org.minimarex.minimacore.utils.LogBuffer;
import org.minimarex.minimacore.utils.MinimaCMD;
import org.minimarex.minimacore.utils.MinimaCMDListener;

/** One in-process resync, independent of Activity recreation. Never restarts or retries itself. */
public final class ResyncSession implements LogBuffer.Sink {
    public enum State { IDLE, RUNNING, SUCCEEDED, FAILED }

    interface Runner { void run(String command, MinimaCMDListener listener); }
    private static final ResyncSession INSTANCE = new ResyncSession(MinimaCMD::runMinima);
    public static ResyncSession get() { return INSTANCE; }

    private final Runner runner;
    public final LogBuffer.Tail log = new LogBuffer.Tail();
    private State state = State.IDLE;
    private String host = "";
    private String result = "";
    private long startedAt;
    private long finishedAt;
    private boolean restartRequested;
    private long attempt;

    ResyncSession(Runner runner) { this.runner = runner; }

    /** Reject command separators/extra parameters, then reuse the node's host parser. */
    public static boolean validHost(String host) {
        if (host == null || !host.matches("[A-Za-z0-9.-]+:[0-9]{1,5}")) return false;
        Message parsed = connect.createConnectMessage(host);
        return parsed != null && parsed.getInteger("port") > 0 && parsed.getInteger("port") <= 65535;
    }

    public synchronized boolean start(String host, long now) {
        if (state == State.RUNNING || state == State.SUCCEEDED || restartRequested || !validHost(host)) return false;
        this.host = host;
        final long currentAttempt = ++attempt;
        state = State.RUNNING;
        result = "";
        startedAt = now;
        finishedAt = 0;
        log.clear();
        log.append("Starting node resync from " + host);
        log.append("Checking connection, downloading and importing chain data. Keep Minima Core running.");
        LogBuffer.addObserver(this);
        try {
            runner.run("megammrsync action:resync host:" + host, reply -> finished(currentAttempt, reply));
        } catch (Exception exc) {
            JSONObject reply = new JSONObject();
            reply.put("status", false);
            reply.put("error", exc.getMessage() == null ? exc.toString() : exc.getMessage());
            finished(currentAttempt, reply);
        }
        return true;
    }

    private synchronized void finished(long completedAttempt, JSONObject reply) {
        if (state != State.RUNNING || completedAttempt != attempt) return;
        String error = Feedback.errorOf(reply);
        state = error == null ? State.SUCCEEDED : State.FAILED;
        finishedAt = System.nanoTime();
        result = error == null ? "Resync complete. Waiting for the node to finish shutting down." : error;
        if (reply != null) log.append(reply.toString());
        log.append(error == null ? result : "Resync failed: " + error);
        LogBuffer.removeObserver(this);
    }

    @Override public synchronized void onLine(String line) {
        if (state == State.RUNNING) log.append(line);
    }

    public synchronized State state() { return state; }
    public synchronized String host() { return host; }
    public synchronized String result() { return result; }
    public synchronized long elapsedSeconds(long now) {
        return startedAt == 0 ? 0 : Math.max(0, ((finishedAt == 0 ? now : finishedAt) - startedAt) / 1_000_000_000L);
    }
    public synchronized boolean restartRequested() { return restartRequested; }
    public synchronized void requestRestart() {
        if (state == State.SUCCEEDED || state == State.FAILED) restartRequested = true;
    }

    public synchronized void interrupted() {
        if (state != State.IDLE) return;
        state = State.FAILED;
        result = "The app stopped before a resync result was received. Completion is unknown. Restart the node and check its status.";
        log.append(result);
    }

    public synchronized void reset() {
        if (state == State.RUNNING) return;
        LogBuffer.removeObserver(this);
        state = State.IDLE;
        restartRequested = false;
        result = "";
        startedAt = finishedAt = 0;
        log.clear();
    }
}
