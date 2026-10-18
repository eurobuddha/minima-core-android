    private static final class ProfilePublish {
        final String draft, typed, html;
        final Hosting.Profile host;
        final JSONObject profile;
        final int epoch;
        final TextView status;
        final Runnable done;
        volatile boolean cancelled;
        boolean refreshing = true;
        ProfilePublish(String draft, String typed, String html, Hosting.Profile host,
                JSONObject profile, int epoch, TextView status, Runnable done) {
            this.draft = draft; this.typed = typed; this.html = html;
            this.host = host; this.profile = profile; this.epoch = epoch; this.status = status; this.done = done;
        }
    }
    private ProfilePublish profilePublish;
    private Object announcement;

    private String profileTyped() {
        return new JSONArray().put(edName == null ? "" : text(edName)).put(edBio == null ? "" : text(edBio))
                .put(edAbout == null ? "" : text(edAbout)).put(edAvatar == null ? "" : text(edAvatar))
                .put(edBanner == null ? "" : text(edBanner)).put(edWebvalidate == null ? "" : text(edWebvalidate)).toString();
    }

    private boolean publicationStoredCurrent(ProfilePublish op) {
        if (op.cancelled) return false;
        try {
            Hosting.Profile host = HostingStore.getDefault(this);
            return op.draft.equals(SalonStore.publicationSnapshot(SalonStore.identityRecord(this)))
                    && host != null && op.host.j.toString().equals(host.j.toString());
        } catch (Exception unavailable) { return false; }
    }

    private boolean publicationCurrent(ProfilePublish op) {
        return !isFinishing() && !isDestroyed() && op.epoch == renderEpoch
                && op.typed.equals(profileTyped()) && publicationStoredCurrent(op);
    }

    private void publicationStatus(TextView status, CharSequence message) {
        if (isFinishing() || isDestroyed()) return;
        if (status != null) Language.setText(status, message); else toast(message);
    }

    private void cancelPublication() {
        if (profilePublish != null) profilePublish.cancelled = true;
    }

    private void hostProfile(TextView status, Runnable done) {
        if (profilePublish != null || announcement != null) { publicationStatus(status, MSG_BUSY); return; }
        Hosting.Profile host = HostingStore.getDefault(this);
        if (host == null) { publicationStatus(status, MSG_HOST); return; }
        final ProfilePublish op;
        try {
            op = new ProfilePublish(SalonStore.publicationSnapshot(SalonStore.identityRecord(this)),
                    profileTyped(), salonHtml(), host, buildProfileJson(), renderEpoch, status, done);
        } catch (Exception unavailable) { publicationStatus(status, MSG_STORAGE); return; }
        profilePublish = op;
        publicationStatus(status, MSG_PREPARING);
        refreshPublicationEditions(op);
    }

    /** All edition mutation and completion runs on the UI thread; late replies cannot
     *  mutate the uploaded JSON or overwrite a replacement account's holdings. */
    private void refreshPublicationEditions(ProfilePublish op) {
        JSONArray nfts = op.profile.optJSONArray("nfts");
        if (!nodeUp || nfts == null || nfts.length() == 0) { queuePublication(op); return; }
        final int[] pending = {nfts.length()};
        final boolean[] received = new boolean[nfts.length()];
        final android.os.Handler timer = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable finish = () -> { if (op.refreshing) queuePublication(op); };
        timer.postDelayed(finish, 12_000);
        for (int i = 0; i < nfts.length(); i++) {
            final int index = i;
            final JSONObject item = nfts.optJSONObject(i);
            final JsonArrayCb result = editions -> runOnUiThread(() -> {
                if (!op.refreshing || received[index]) return;
                received[index] = true;
                if (publicationCurrent(op) && item != null && editions.length() > 0)
                    try { item.put("editions", editions); } catch (Exception ignored) { }
                if (--pending[0] == 0) { timer.removeCallbacks(finish); finish.run(); }
            });
            if (item == null || item.optString("tokenid").isEmpty()) result.ready(new JSONArray());
            else try { captureEditions(item.optString("tokenid"), result); }
            catch (RuntimeException unavailable) { result.ready(new JSONArray()); }
        }
    }

    private void queuePublication(ProfilePublish op) {
        op.refreshing = false;
        if (profilePublish != op) return;
        if (!publicationCurrent(op)) { finishPublication(op, null, null); return; }
        final String content = op.profile.toString();
        publicationStatus(op.status, MSG_UPLOADING);
        try {
            io.execute(() -> {
                try {
                    if (!publicationStoredCurrent(op)) { runOnUiThread(() -> finishPublication(op, null, null)); return; }
                    String url = ProfileHosting.upload(op.host, op.profile.optString("handle"), content, op.html);
                    runOnUiThread(() -> finishPublication(op, url, null));
                } catch (Exception error) { runOnUiThread(() -> finishPublication(op, null, error)); }
            });
        } catch (java.util.concurrent.RejectedExecutionException unavailable) { finishPublication(op, null, unavailable); }
    }

    private void finishPublication(ProfilePublish op, String url, Exception error) {
        if (profilePublish != op) return;
        profilePublish = null;
        if (!publicationCurrent(op)) {
            if (!isFinishing() && !isDestroyed()) toast(MSG_CHANGED);
            return;
        }
        if (error != null || url == null) {
            publicationStatus(op.status, error == null ? MSG_STORAGE : MSG_FAILED);
            return;
        }
        if (!SalonStore.savePublication(this, op.draft, url, op.profile.optJSONArray("nfts"))) {
            publicationStatus(op.status, MSG_STORAGE); return;
        }
        mProfileCache.remove(url);
        publicationStatus(op.status, MSG_UPLOADED);
        if (op.done != null) op.done.run();
    }
