    /** One selection/upload at a time; snapshots contain data, never live editor views. */
    private static final class MediaPick {
        final int code;
        final String identity, handle, target, hostJson;
        final Hosting.Profile host;
        final boolean meshDm;
        volatile boolean cancelled;
        MediaPick(int code, String identity, String handle, String target, Hosting.Profile host, boolean meshDm) {
            this.code = code; this.identity = identity; this.handle = handle; this.target = target;
            this.host = host; this.hostJson = host.j.toString(); this.meshDm = meshDm;
        }
    }
    private MediaPick pendingMediaPick, activeMediaUpload;

    private String mediaIdentity() {
        return new JSONArray().put(SalonStore.get(this, "tokenid")).put(SalonStore.get(this, "handle"))
                .put(SalonStore.get(this, "msgpk")).toString();
    }

    /** Read on the UI thread, so typed values and quoted replies belong to this selection. */
    private String mediaTarget(int code) {
        if (code == PICK_DM_IMG) return new JSONArray().put(threadPeer).put(threadDraft)
                .put(replyPeer).put(replyBody).put(replyFrom).put(SalonStore.get(this, XPORT_PREF + threadPeer)).toString();
        if (code == PICK_AVATAR) return edAvatar == null ? SalonStore.get(this, "avatar") : text(edAvatar);
        if (code == PICK_BANNER) return edBanner == null ? SalonStore.get(this, "banner") : text(edBanner);
        return SalonStore.arr(this, "gallery").toString();
    }

    private boolean mediaHostCurrent(MediaPick pick) {
        if (pick.meshDm) return true; // The chosen mesh transport never falls back to a different host.
        Hosting.Profile host = HostingStore.getDefault(this);
        return host != null && pick.hostJson.equals(host.j.toString());
    }

    private boolean mediaCurrent(MediaPick pick) {
        boolean current = !pick.cancelled && !isFinishing() && !isDestroyed()
                && pick.identity.equals(mediaIdentity()) && mediaHostCurrent(pick)
                && screen == (pick.code == PICK_DM_IMG ? Screen.THREAD : Screen.EDIT)
                && pick.target.equals(mediaTarget(pick.code));
        if (!current) pick.cancelled = true;
        return current;
    }

    private void cancelMediaRequests() {
        if (pendingMediaPick != null) pendingMediaPick.cancelled = true;
        if (activeMediaUpload != null) activeMediaUpload.cancelled = true;
    }

    private void mediaStatus(CharSequence message) {
        if (isFinishing() || isDestroyed()) return;
        if (screen == Screen.EDIT && profStatus != null) Language.setText(profStatus, message);
        toast(message);
    }

    private void pickMedia(int code, String mime) {
        if (code < PICK_AVATAR || code > PICK_DM_IMG) return;
        if (pendingMediaPick != null || activeMediaUpload != null) { toast(MSG_BUSY); return; }
        if (screen != (code == PICK_DM_IMG ? Screen.THREAD : Screen.EDIT)) return;
        if (code == PICK_DM_IMG && threadPeer.isEmpty()) return;
        boolean meshDm = code == PICK_DM_IMG && MaximaLink.isReady(this);
        Hosting.Profile host = meshDm ? Hosting.Profile.fresh(Hosting.TYPE_MAXIMA) : HostingStore.getDefault(this);
        if (host == null) { toast(MSG_HOST); return; }
        final MediaPick pick = new MediaPick(code, mediaIdentity(), SalonStore.get(this, "handle"), mediaTarget(code), host, meshDm);
        pendingMediaPick = pick;
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT); i.setType(mime);
            startActivityForResult(Intent.createChooser(i, MSG_CHOOSE), code);
        } catch (RuntimeException unavailable) {
            pendingMediaPick = null; toast(MSG_PICKER_FAILED);
        }
    }

    /** Ignore unowned callbacks after recreation rather than attaching to the current account. */
    private void receiveMedia(int code, int result, Intent data) {
        MediaPick pick = pendingMediaPick;
        if (pick == null || pick.code != code) {
            if (result == RESULT_OK) toast(MSG_RESELECT);
            return;
        }
        pendingMediaPick = null;
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (!mediaCurrent(pick)) { mediaStatus(MSG_CHANGED); return; }
        activeMediaUpload = pick;
        mediaStatus(pick.code == PICK_DM_IMG ? MSG_SENDING : MSG_UPLOADING);
        final Uri uri = data.getData();
        try {
            io.execute(() -> {
                try {
                    if (pick.cancelled || !pick.identity.equals(mediaIdentity()) || !mediaHostCurrent(pick)) {
                        runOnUiThread(() -> finishMedia(pick, null, null)); return;
                    }
                    final byte[] bytes;
                    final String kind, mime, extension;
                    if (pick.code == PICK_GALLERY_VID) { bytes = readCapped(uri, 60); kind = "video"; mime = "video/mp4"; extension = ".mp4"; }
                    else if (pick.code == PICK_GALLERY_AUD) { bytes = readCapped(uri, 25); kind = "audio"; mime = "audio/mpeg"; extension = ".mp3"; }
                    else { bytes = readScaledJpeg(uri, pick.code == PICK_AVATAR ? 640 : 1400); kind = "image"; mime = "image/jpeg"; extension = ".jpg"; }
                    // Recheck typed fields and recipient on the UI before dispatch.
                    runOnUiThread(() -> dispatchMedia(pick, bytes, kind, mime, extension));
                } catch (Exception error) { runOnUiThread(() -> finishMedia(pick, null, error)); }
            });
        } catch (java.util.concurrent.RejectedExecutionException unavailable) { finishMedia(pick, null, unavailable); }
    }

    private void dispatchMedia(MediaPick pick, byte[] bytes, String kind, String mime, String extension) {
        if (activeMediaUpload != pick) return;
        if (!mediaCurrent(pick)) { finishMedia(pick, null, null); return; }
        if (bytes.length == 0) { finishMedia(pick, null, new Exception("cannot read file")); return; }
        // Random suffix prevents two successful selections in the same millisecond colliding.
        final String path = Hosting.slug(pick.handle) + "/media/" + kind + "-" + java.util.UUID.randomUUID() + extension;
        try {
            io.execute(() -> {
                try {
                    if (pick.cancelled || !pick.identity.equals(mediaIdentity()) || !mediaHostCurrent(pick)) {
                        runOnUiThread(() -> finishMedia(pick, null, null)); return;
                    }
                    if (pick.code != PICK_DM_IMG && Hosting.TYPE_MAXIMA.equals(pick.host.type())) {
                        final long usage = meshUsageBytes();
                        if (usage > MAX_PROFILE_MESH_BYTES - bytes.length) {
                            runOnUiThread(() -> {
                                if (activeMediaUpload != pick) return;
                                if (!mediaCurrent(pick)) { finishMedia(pick, null, null); return; }
                                activeMediaUpload = null; mediaStatus(MSG_MESH_FULL);
                            });
                            return;
                        }
                    }
                    final String ref;
                    try (Hosting.Uploader uploader = Hosting.forProfile(pick.host)) { ref = uploader.putFile(bytes, path, mime); }
                    if (ref == null || ref.isEmpty()) throw new Exception("empty upload reference");
                    runOnUiThread(() -> finishMedia(pick, new String[]{kind, ref, mime}, null));
                } catch (Exception error) { runOnUiThread(() -> finishMedia(pick, null, error)); }
            });
        } catch (java.util.concurrent.RejectedExecutionException unavailable) { finishMedia(pick, null, unavailable); }
    }

    private void finishMedia(MediaPick pick, String[] uploaded, Exception error) {
        if (activeMediaUpload != pick) return;
        activeMediaUpload = null;
        if (isFinishing() || isDestroyed()) return;
        if (!mediaCurrent(pick)) {
            if (pick.identity.equals(mediaIdentity())) mediaStatus(MSG_CHANGED); else toast(MSG_CHANGED);
            return;
        }
        if (error != null) { mediaStatus(MSG_FAILED); return; }
        if (uploaded == null) { mediaStatus(MSG_CHANGED); return; }
        if (pick.code == PICK_DM_IMG) { sendDm("", uploaded[1], uploaded[2]); return; }
        if (pick.code == PICK_AVATAR || pick.code == PICK_BANNER) {
            String key = pick.code == PICK_AVATAR ? "avatar" : "banner";
            EditText field = pick.code == PICK_AVATAR ? edAvatar : edBanner;
            if (field != null) field.setText(uploaded[1]);
            SalonStore.put(this, key, uploaded[1]); render();
        } else promptMediaTitle(uploaded[0], uploaded[1]);
        mediaStatus(MSG_UPLOADED);
    }

    private CharSequence mediaError(Exception error) {
        String detail = error.getMessage();
        if ("cannot read file".equals(detail) || "could not read image".equals(detail)) return MSG_READ_FAILED;
        if ("file too large (max 60 MB)".equals(detail)) return MSG_SIZE_VIDEO;
        if ("file too large (max 25 MB)".equals(detail)) return MSG_SIZE_AUDIO;
        if ("empty upload reference".equals(detail)) return MSG_NO_REFERENCE;
        return detail == null || detail.isEmpty() ? error.getClass().getSimpleName() : HoldingMessages.message(detail);
    }

