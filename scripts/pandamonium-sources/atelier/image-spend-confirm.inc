    /** The existing paid-upload gate, tied to the selected draft and released on navigation. */
    private boolean confirmArweaveSpend(ImagePick pick, long totalBytes, int fileCount) {
        String estimate = ArweaveUploader.priceEstimate(totalBytes);
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean approved = new java.util.concurrent.atomic.AtomicBoolean();
        final androidx.appcompat.app.AlertDialog[] dialog = {null};
        main.post(() -> {
            if (activeImagePick != pick || !imageCurrent(pick)) { latch.countDown(); return; }
            try {
                dialog[0] = new com.eurobuddha.minimacore.i18n.LanguageDialog(this)
                        .setTitle(Language.msg(l10n_atelier_image_cost_title))
                        .setMessage(Language.msg(l10n_atelier_image_cost, fileCount,
                                String.format(Locale.US, "%.1f MiB", totalBytes / 1048576.0), totalBytes,
                                estimate.isEmpty() ? Language.msg(l10n_atelier_image_fee_unknown) : estimate))
                        .setPositiveButton(Language.msg(l10n_atelier_image_upload), (d,w) -> {
                            approved.set(activeImagePick == pick && imageCurrent(pick)); latch.countDown();
                        })
                        .setNegativeButton(Language.msg(l10n_common_cancel), (d,w) -> latch.countDown())
                        .setOnCancelListener(d -> latch.countDown()).show();
                dialog[0].setOnDismissListener(d -> latch.countDown());
                pick.cancelConfirmation = () -> { approved.set(false); latch.countDown(); dialog[0].dismiss(); };
            } catch (RuntimeException unavailable) { latch.countDown(); }
        });
        try { latch.await(5, java.util.concurrent.TimeUnit.MINUTES); }
        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        main.post(() -> { pick.cancelConfirmation = null; if (dialog[0] != null) dialog[0].dismiss(); });
        return approved.get() && !pick.cancelled;
    }
