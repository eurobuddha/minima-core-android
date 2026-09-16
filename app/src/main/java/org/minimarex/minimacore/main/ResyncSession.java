package org.minimarex.minimacore.main;

import org.minima.utils.json.JSONObject;
import org.minimarex.minimacore.utils.Feedback;
import org.minimarex.minimacore.utils.LogBuffer;
import org.minimarex.minimacore.utils.MinimaCMD;
import org.minimarex.minimacore.utils.MinimaCMDListener;

/**
 * One in-process destructive node job, independent of Activity recreation. Never restarts or
 * retries itself.
 *
 * Which job it is comes from {@link ResyncJob} - resync from a host, restore from a backup file,
 * or both. The lifecycle below is identical for all of them and must stay that way: they are all
 * destructive, they all end with the node shutting down, and they all have to survive the screen
 * being recreated underneath them.
 */
public final class ResyncSession implements LogBuffer.Sink {
    public enum State { IDLE, RUNNING, SUCCEEDED, FAILED }

    interface Runner { void run(String command, MinimaCMDListener listener); }
    private static final ResyncSession INSTANCE = new ResyncSession(MinimaCMD::runMinima);
    public static ResyncSession get() { return INSTANCE; }

    private final Runner runner;
    public final LogBuffer.Tail log = new LogBuffer.Tail();
    private State state = State.IDLE;
    private ResyncJob job;
    private String result = "";
    private long startedAt;
    private long finishedAt;
    private boolean restartRequested;
    private long attempt;

    ResyncSession(Runner runner) { this.runner = runner; }

    /** Kept here because callers and tests have always asked the session. See ResyncJob. */
    public static boolean validHost(String host) { return ResyncJob.validHost(host); }

    public synchronized boolean start(ResyncJob newJob, long now) {
        if (state == State.RUNNING || state == State.SUCCEEDED || restartRequested || newJob == null) return false;
        this.job = newJob;
        final long currentAttempt = ++attempt;
        state = State.RUNNING;
        result = "";
        startedAt = now;
        finishedAt = 0;
        log.clear();
        log.append(newJob.startLine());
        log.append("Checking connection, downloading and importing chain data. Keep Minima Core running.");
        LogBuffer.addObserver(this);
        try {
            // newJob.command() can carry a backup password - it is passed to the node and never logged.
            runner.run(newJob.command(), reply -> finished(currentAttempt, reply));
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
        result = error == null ? label() + " complete. Waiting for the node to finish shutting down." : error;
        // The reply is node output, not our command string, so it cannot echo the password back.
        if (reply != null) log.append(reply.toString());
        log.append(error == null ? result : label() + " failed: " + error);
        LogBuffer.removeObserver(this);
    }

    @Override public synchronized void onLine(String line) {
        if (state == State.RUNNING) log.append(line);
    }

    public synchronized State state() { return state; }
    public synchronized ResyncJob job() { return job; }
    /** "" when no job has run, matching the old host-only behaviour. */
    public synchronized String host() { return job == null ? "" : job.host(); }
    /** "Resync" unless a job says otherwise - the wording every screen builds its text from. */
    public synchronized String label() { return job == null ? "Resync" : job.label(); }
    public synchronized String result() { return result; }
    public synchronized long elapsedSeconds(long now) {
        return startedAt == 0 ? 0 : Math.max(0, ((finishedAt == 0 ? now : finishedAt) - startedAt) / 1_000_000_000L);
    }
    public synchronized boolean restartRequested() { return restartRequested; }
    public synchronized void requestRestart() {
        if (state == State.SUCCEEDED || state == State.FAILED) restartRequested = true;
    }

    /** Process death mid-job. The job itself is gone, so the caller supplies what was pending. */
    public synchronized void interrupted(String operation) {
        if (state != State.IDLE) return;
        state = State.FAILED;
        result = "The app stopped before a " + operation.toLowerCase()
                + " result was received. Completion is unknown. Restart the node and check its status.";
        log.append(result);
    }

    public synchronized void interrupted() { interrupted("Resync"); }

    public synchronized void reset() {
        if (state == State.RUNNING) return;
        LogBuffer.removeObserver(this);
        state = State.IDLE;
        restartRequested = false;
        job = null;
        result = "";
        startedAt = finishedAt = 0;
        log.clear();
    }
}
