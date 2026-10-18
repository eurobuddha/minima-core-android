    private static final class HostedRestore {
        final String snapshot, typed, url;
        final int epoch;
        final boolean loud;
        HostedRestore(String snapshot, String typed, String url, int epoch, boolean loud) {
            this.snapshot = snapshot; this.typed = typed; this.url = url; this.epoch = epoch; this.loud = loud;
        }
    }
    private HostedRestore hostedRestore;

    private void cancelHostedRestore() { hostedRestore = null; }

    private boolean hostedRestoreCurrent(HostedRestore op) {
        if (isFinishing() || isDestroyed() || op.epoch != renderEpoch || !op.typed.equals(profileTyped())) return false;
        try { return op.snapshot.equals(SalonStore.publicationSnapshot(SalonStore.identityRecord(this))); }
        catch (Exception unavailable) { return false; }
    }

    /** Hosted content never replaces identity/key fields or a newer local draft. */
    private void hydrateFromHosted(final String url, final boolean loud) {
        if (url == null || url.trim().isEmpty()) { if (loud) toast(MSG_NO_URL); return; }
        if (isFinishing() || isDestroyed()) return;
        if (hostedRestore != null) { if (loud) toast(MSG_BUSY); return; }
        final HostedRestore op;
        try {
            JSONObject record = SalonStore.identityRecord(this);
            if (!url.equals(record.optString("profileUrl"))) { if (loud) toast(MSG_CHANGED); return; }
            op = new HostedRestore(SalonStore.publicationSnapshot(record), profileTyped(), url, renderEpoch, loud);
        } catch (Exception unavailable) { if (loud) toast(MSG_STORAGE); return; }
        hostedRestore = op;
        if (loud) toast(MSG_RESTORING);
        try {
            io.execute(() -> {
                // Force a fresh read through the existing checked HTTP/mesh transport.
                final JSONObject profile = httpGetJson(op.url, true);
                runOnUiThread(() -> finishHostedRestore(op, profile));
            });
        } catch (java.util.concurrent.RejectedExecutionException unavailable) {
            hostedRestore = null;
            if (loud) toast(MSG_UNAVAILABLE);
        }
    }

    private void finishHostedRestore(HostedRestore op, JSONObject profile) {
        if (hostedRestore != op) return;
        hostedRestore = null;
        if (isFinishing() || isDestroyed()) return;
        if (!hostedRestoreCurrent(op)) { if (op.loud) toast(MSG_CHANGED); return; }
        if (profile == null) { if (op.loud) toast(MSG_UNREACHABLE); return; }
        SalonStore.RestoreResult result = SalonStore.restoreHosted(this, op.snapshot, profile);
        switch (result) {
            case APPLIED:
                toast(MSG_RESTORED);
                // render() commits retained editor widgets even when returning from Settings.
                // Drop the old references so opening Edit cannot undo the durable restore.
                edName = edBio = edAbout = edAvatar = edBanner = edWebvalidate = null;
                render();
                break;
            case EMPTY: if (op.loud) toast(MSG_EMPTY); break;
            case CHANGED: if (op.loud) toast(MSG_CHANGED); break;
            case INVALID: if (op.loud) toast(MSG_INVALID); break;
            case FAILED: if (op.loud) toast(MSG_STORAGE); break;
        }
    }
