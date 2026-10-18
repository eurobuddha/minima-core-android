    /** Salon media-request pattern, scoped to Atelier's draft and screen generation. */
    private static final class ImagePick {
        int context, start, count; boolean batch; long recovery, generation, sequence;
        Screen screen; String lane, target, slug, hostJson;
        Hosting.Profile host; volatile boolean cancelled;
        Runnable cancelConfirmation;
    }
    private ImagePick pendingImagePick, activeImagePick;
    private long imageSequence;
    private final java.util.concurrent.ExecutorService imageIo = java.util.concurrent.Executors.newSingleThreadExecutor();

    private String imageTarget(int context, long recovery) {
        if (context == PICK_RECOVERY) {
            JSONObject row = LocalStore.findById(this, recovery);
            return row == null ? "" : row.toString();
        }
        if (context == PICK_ARTPHOTO) return artDraftSnapshot();
        return mintDraftSnapshot(mintDraftKind());
    }
    private boolean imageCurrent(ImagePick pick) {
        if (pick == null || pick.cancelled || isFinishing() || isDestroyed()
                || pick.sequence != imageSequence || pick.generation != screenGeneration || pick.screen != screen
                || !pick.target.equals(imageTarget(pick.context,pick.recovery))) return false;
        if (pick.context == PICK_RECOVERY) {
            JSONObject row = LocalStore.findById(this,pick.recovery);
            if (openMeta == null || openMeta.localId != pick.recovery || row == null
                    || !"NEEDIMAGES".equals(row.optString("phase"))) return false;
        }
        if (pick.host != null) {
            Hosting.Profile current = HostingStore.getDefault(this);
            if (current == null || !pick.hostJson.equals(current.j.toString())) return false;
        }
        return true;
    }
    private void cancelImageRequests() {
        for (ImagePick pick : new ImagePick[]{pendingImagePick,activeImagePick}) if (pick != null) {
            pick.cancelled = true;
            if (pick.cancelConfirmation != null) pick.cancelConfirmation.run();
        }
    }
    private boolean acceptImageResult(ImagePick pick) {
        if (activeImagePick != pick) return false;
        activeImagePick = null;
        if (imageCurrent(pick)) return true;
        if (!isFinishing() && !isDestroyed()) toast(Language.msg(l10n_atelier_image_changed));
        return false;
    }
    private void executeImageWork(ImagePick pick, Runnable work) {
        try {
            imageIo.execute(() -> {
                if (pick.cancelled) { main.post(() -> acceptImageResult(pick)); return; }
                try { work.run(); }
                catch (Exception failure) { main.post(() -> {
                    if (acceptImageResult(pick)) toast(Language.msg(l10n_atelier_image_failed, failure.getClass().getSimpleName()));
                }); }
            });
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            if (acceptImageResult(pick)) toast(Language.msg(l10n_atelier_image_failed, stopped.getClass().getSimpleName()));
        }
    }
    /** Worker-to-UI handoff before creating a transport; all draft reads stay on the UI thread. */
    private boolean allowImageUpload(ImagePick pick) {
        // This handoff must never block the UI thread if called by a synchronous executor.
        if (Looper.myLooper() == main.getLooper()) {
            if (activeImagePick == pick && imageCurrent(pick)) return true;
            acceptImageResult(pick); return false;
        }
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean allowed = new java.util.concurrent.atomic.AtomicBoolean();
        main.post(() -> { allowed.set(activeImagePick == pick && imageCurrent(pick)); ready.countDown(); });
        try {
            if (ready.await(30, java.util.concurrent.TimeUnit.SECONDS) && allowed.get() && !pick.cancelled) return true;
        } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        pick.cancelled = true;
        main.post(() -> acceptImageResult(pick));
        return false;
    }

    private CharSequence imageError(String error) {
        if ("could not open the image".equals(error) || "Could not encode the image as JPEG".equals(error))
            return Language.msg(l10n_atelier_image_process_failed);
        if ("image is over 60MB".equals(error)) return Language.msg(l10n_atelier_image_too_large);
        if ("Empty upload reference".equals(error)) return Language.msg(l10n_atelier_image_empty_reference);
        if ("Upload stopped after navigation".equals(error)) return Language.msg(l10n_atelier_image_changed);
        return error;
    }
