package com.eurobuddha.nftwallet;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The one place a mint tick is started, used by both the Activity and {@link MintService}.
 *
 * Mirrors the family's shared-brain convention (Limit's {@code LimitProcessor}, AtomiX's
 * {@code SwapEngine}) so foreground and background behave identically.
 *
 * Every call returns a {@link Result}. That is the point: the old entry point returned silently
 * when a tick was already running or when it decided nothing was active — and its idea of "active"
 * excluded NEEDIMAGES, while the Resume button was shown for every phase but DONE. Tapping Resume
 * on a paused collection therefore did nothing at all, with no explanation. A caller can no longer
 * fail to know what happened.
 */
public final class MintDriver {

    public enum Result {
        /** A tick was started; the callback will fire when it finishes. */
        STARTED,
        /** A tick is already in flight — a write can hold the node for minutes. */
        BUSY,
        /** A collection is parked waiting for the user to supply images. */
        NEEDS_IMAGES,
        /** Nothing to do: every collection is DONE or BURIED. */
        NOTHING_TO_DO,
        /** The node isn't paired, so no command can be run. */
        NOT_PAIRED
    }

    public interface Done {
        void onFinished(String message);
    }

    /** Guards re-entrancy across BOTH hosts — a tick chains many commands and must not overlap. */
    private static volatile boolean running = false;
    private static NodeApi activeNode;
    private static long generation;

    private MintDriver() {}

    public static boolean isRunning() { return inFlight(); }

    /** A live node owns its tick until its callback; elapsed time cannot authorize overlapping writes. */
    private static synchronized boolean inFlight() {
        if (running && activeNode != null && activeNode.isClosed()) {
            running = false; activeNode = null; generation++;
        }
        return running;
    }

    static synchronized boolean pauseForBurial(Context ctx, String tokenid) {
        return !inFlight() && LocalStore.pauseForBurial(ctx, tokenid);
    }

    /** Reuse the operation lock so image updates cannot be overwritten by a live engine snapshot. */
    static synchronized boolean saveProgressImage(Context ctx, long rowId, int index, String image) {
        return !inFlight() && LocalStore.saveMintImage(ctx, rowId, index, image);
    }

    static synchronized boolean retryCreate(Context ctx, long rowId) {
        return !inFlight() && LocalStore.retryMintCreate(ctx, rowId);
    }

    public static boolean hasPausedBurial(Context ctx) {
        JSONArray rows = LocalStore.load(ctx);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && "BURY".equals(row.optString("phase"))) return true;
        }
        return false;
    }

    /** Phases the engine can advance on its own, without user input. */
    public static boolean hasActiveMint(Context ctx) {
        JSONArray rows = LocalStore.load(ctx);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null) continue;
            if (r.optInt("stuck", 0) == 1) continue;   // unrecoverable — don't tick it forever
            String p = r.optString("phase", "DONE");
            if ("CREATE".equals(p) || "MOVE".equals(p) || "SPLIT".equals(p) || "STAMP".equals(p)) return true;
        }
        return false;
    }

    /** A collection stalled because an embedded item has no image yet — needs the user, not a tick. */
    public static boolean needsImages(Context ctx) {
        JSONArray rows = LocalStore.load(ctx);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r != null && "NEEDIMAGES".equals(r.optString("phase", ""))) return true;
        }
        return false;
    }

    /** True while any collection still needs work of any kind — what decides if the service lives. */
    public static boolean hasWork(Context ctx) {
        return hasActiveMint(ctx) || needsImages(ctx);
    }

    /** A collection that can never proceed — currently only the oversized-token-record case. */
    public static boolean isStuck(JSONObject row) {
        return row != null && row.optInt("stuck", 0) == 1;
    }

    /**
     * True when any collection is parked as unrecoverable.
     *
     * hasWork() deliberately ignores stuck rows, so a stuck collection looks exactly like a
     * finished one to the service. Without this, burning out on the 64KB cap was announced as
     * "your collection is fully stamped" — the opposite of what happened.
     */
    public static boolean hasStuck(Context ctx) {
        JSONArray rows = LocalStore.load(ctx);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && !"BURY".equals(row.optString("phase")) && !"BURIED".equals(row.optString("phase")) && isStuck(row)) return true;
        }
        return false;
    }

    /** Advance one collection by one step. {@code done} fires only when the result is STARTED. */
    public static synchronized Result tick(Context ctx, NodeApi node, final Done done) {
        if (node == null || node.isClosed()) return Result.NOT_PAIRED;
        if (inFlight()) return Result.BUSY;
        if (!hasActiveMint(ctx)) {
            return needsImages(ctx) ? Result.NEEDS_IMAGES : Result.NOTHING_TO_DO;
        }
        running = true;
        activeNode = node;
        final long current = ++generation;
        MintEngine.tick(ctx, node, message -> {
            synchronized (MintDriver.class) {
                if (current != generation) return;
                running = false; activeNode = null;
            }
            if (done != null) done.onFinished(message == null ? "" : message);
        });
        return Result.STARTED;
    }

    /** What to tell the user when they asked for a tick and didn't get one. */
    public static String explain(Result r) {
        switch (r) {
            case STARTED:       return MintMessages.message("Working on it…").toString();
            case BUSY:          return MintMessages.message("Already working — a transaction is still in flight.").toString();
            case NEEDS_IMAGES:  return MintMessages.message("Waiting for you: an item still needs its image.").toString();
            case NOT_PAIRED:    return MintMessages.message("Not connected to Minima Core.").toString();
            default:            return MintMessages.message("Nothing left to mint.").toString();
        }
    }

    // ---- progress, for the notification and the progress screen ----

    /** {stamped, size} for the first collection still being worked on, or null. */
    public static int[] activeProgress(Context ctx) {
        JSONArray rows = LocalStore.load(ctx);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null) continue;
            String p = r.optString("phase", "DONE");
            if ("DONE".equals(p) || "BURIED".equals(p) || "BURY".equals(p)) continue;
            int size = r.optInt("size", 0);
            int stamped = 0;
            JSONArray items = MintEngine.localItems(r);
            for (int j = 0; j < items.length(); j++) {
                JSONObject it = items.optJSONObject(j);
                if (it != null && !it.optString("coinid", "").isEmpty()) stamped++;
            }
            return new int[]{stamped, size};
        }
        return null;
    }

    /** Name of the collection currently being worked on, or "" when there is none. */
    public static String activeName(Context ctx) {
        JSONArray rows = LocalStore.load(ctx);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null) continue;
            String p = r.optString("phase", "DONE");
            if ("DONE".equals(p) || "BURIED".equals(p) || "BURY".equals(p)) continue;
            return r.optString("name", MintMessages.message("Collection").toString());
        }
        return "";
    }
}
