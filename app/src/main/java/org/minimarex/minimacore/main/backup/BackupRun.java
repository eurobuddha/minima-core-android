package org.minimarex.minimacore.main.backup;

import org.minima.utils.json.JSONObject;
import org.minimarex.minimacore.main.ResyncJob;
import org.minimarex.minimacore.utils.Feedback;
import org.minimarex.minimacore.utils.MinimaCMD;
import org.minimarex.minimacore.utils.MinimaCMDListener;

/**
 * One in-progress `backup`, independent of Activity recreation.
 *
 * Same reasoning as ResyncSession, smaller problem: a backup locks the DB and copies the whole
 * wallet, chain and archive, which is slow on a phone. If the result lived in the Activity a
 * rotation would lose it, and the user would be told nothing while a .bak they cannot see
 * quietly appears in the node folder. Unlike a resync this is NOT destructive and does not
 * restart the node, so it deliberately does NOT go through ResyncLauncher.
 */
public final class BackupRun {

    public enum State { IDLE, RUNNING, SUCCEEDED, FAILED }

    interface Runner { void run(String command, MinimaCMDListener listener); }

    private static final BackupRun INSTANCE = new BackupRun(MinimaCMD::runMinima);
    public static BackupRun get() { return INSTANCE; }

    private final Runner runner;
    private State state = State.IDLE;
    private String error = "";
    /** Absolute path as the NODE reported it - the ground truth, never rebuilt by us. */
    private String path = "";
    private String size = "";
    private String block = "";
    private long attempt;

    BackupRun(Runner runner) { this.runner = runner; }

    public synchronized boolean start(String filename, String password) {
        if (state == State.RUNNING) return false;
        // Same rules as every other node command we build - see ResyncJob for why they exist.
        if (!ResyncJob.validFilename(filename) || !ResyncJob.validPassword(password)) return false;
        final long current = ++attempt;
        state = State.RUNNING;
        error = "";
        path = size = block = "";
        // confirm: makes the NODE enforce that the two fields match, rather than trusting us to.
        String command = "backup file:\"" + filename + "\""
                + " password:\"" + password + "\" confirm:\"" + password + "\"";
        try {
            runner.run(command, reply -> finished(current, reply));
        } catch (Exception exc) {
            JSONObject reply = new JSONObject();
            reply.put("status", false);
            reply.put("error", exc.getMessage() == null ? exc.toString() : exc.getMessage());
            finished(current, reply);
        }
        return true;
    }

    private synchronized void finished(long completed, JSONObject reply) {
        if (state != State.RUNNING || completed != attempt) return;
        String failure = Feedback.errorOf(reply);
        if (failure != null) {
            state = State.FAILED;
            error = failure;
            return;
        }
        Object response = reply == null ? null : reply.get("response");
        JSONObject backup = response instanceof JSONObject
                ? (JSONObject) ((JSONObject) response).get("backup") : null;
        if (backup == null) {
            state = State.FAILED;
            error = "The node reported success but returned no backup details.";
            return;
        }
        path = text(backup.get("file"));
        size = text(backup.get("size"));
        block = text(backup.get("block"));
        if (path.isEmpty()) {
            state = State.FAILED;
            error = "The node did not report where the backup was written.";
            return;
        }
        state = State.SUCCEEDED;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public synchronized State state() { return state; }
    public synchronized String error() { return error; }
    /** Full absolute path, exactly as the node gave it. Never shortened for display. */
    public synchronized String path() { return path; }
    /** Just the filename, for the places that need a base-folder name rather than a path. */
    public synchronized String filename() {
        int cut = path.lastIndexOf('/');
        return cut < 0 ? path : path.substring(cut + 1);
    }
    public synchronized String size() { return size; }
    public synchronized String block() { return block; }

    public synchronized void reset() {
        if (state == State.RUNNING) return;
        state = State.IDLE;
        error = "";
        path = size = block = "";
    }

}
