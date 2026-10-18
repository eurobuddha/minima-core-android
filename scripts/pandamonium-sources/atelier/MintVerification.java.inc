    // Only set during the synchronous, draft-checked verification continuation.
    private boolean hostGatePassed = false;
    private String confirmedCollectionDraft;

    /** Reuse persisted draft structure; include pending fields that must survive completion. */
    private String mintDraftSnapshot(int kind) {
        JSONObject draft = buildDraftJsons()[kind];
        if (kind == 0) {
            put(draft, "pendingType", traitTypeDraft); put(draft, "pendingValue", traitValueDraft);
        } else if (kind == 1) {
            put(draft, "pendingKey", pairKeyDraft); put(draft, "pendingValue", pairValueDraft);
        } else {
            put(draft, "wvAuto", createWvAuto);
            put(draft, "itemTraits", collectionItemTraits);
        }
        return draft.toString();
    }

    private int mintDraftKind() {
        return screen == Screen.CREATE_NFT ? 0 : screen == Screen.CREATE_TOKEN ? 1 : 2;
    }

    private void mintDraftChanged() {
        toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_mint_draft_changed));
    }

    private interface HostFail { void onFail(String msg); }
    private void verifyHostedThen(java.util.List<String> urls, Runnable onOk) {
        verifyHostedThen(urls, onOk, this::toast);
    }
    private volatile boolean verifyGateRunning = false;
    private volatile boolean verifyGateCancel = false;

    /** Same hosted verifier/deadline as the standalone app, bound to this draft and screen. */
    private void verifyHostedThen(java.util.List<String> urls, Runnable onOk, HostFail onFail) {
        if (verifyGateRunning) {
            toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_mint_verifying_busy));
            return;
        }
        final int kind = mintDraftKind();
        final String draft = mintDraftSnapshot(kind);
        final long generation = screenGeneration;
        final java.util.List<String> checkedUrls = new java.util.ArrayList<>(urls);
        verifyGateRunning = true;
        verifyGateCancel = false;
        final Hosting.Profile p = HostingStore.getDefault(this);
        final boolean slow = p != null && Hosting.TYPE_ARWEAVE.equals(p.type());
        LocalStore.logEvent(this, "MINT GATE start (" + checkedUrls.size() + " urls, type="
                + (p == null ? "none" : p.type()) + "): " + checkedUrls);
        final TextView msgV = Design.note(this, Language.msg(slow
                ? com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_mint_slow_verification
                : com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_mint_verifying));
        msgV.setPadding(dp(20), dp(14), dp(20), dp(6));
        androidx.appcompat.app.AlertDialog dlg = null;
        try {
            dlg = new com.eurobuddha.minimacore.i18n.LanguageDialog(this)
                    .setTitle(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_mint_verify_title))
                    .setView(msgV).setCancelable(false)
                    .setPositiveButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_mint_hide), null)
                    .setNegativeButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_mint_stop), null).show();
        } catch (RuntimeException unavailable) {
            verifyGateRunning = false;
            return; // No invisible, uncancellable mint attempt if the activity cannot show the gate.
        }
        final androidx.appcompat.app.AlertDialog dlgF = dlg;
        // VoiceRecordDialog pattern: cancel synchronously in the click handler, before queued completion.
        dlgF.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setOnClickListener(v -> {
            verifyGateCancel = true;
            toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_mint_stopping));
            dlgF.dismiss();
        });
        final long deadline = System.currentTimeMillis() + 12L * 60 * 1000;
        final long began = System.currentTimeMillis();
        new Thread(() -> {
            String bad = null;
            for (int i = 0; i < checkedUrls.size(); i++) {
                if (verifyGateCancel) break;
                final String u = checkedUrls.get(i);
                final int idx = i + 1, n = checkedUrls.size();
                try {
                    Hosting.verifyUrl(u, p, slow ? deadline : 0, (url, attempt, elapsedMs) -> {
                        if (verifyGateCancel) return false;
                        long el = (System.currentTimeMillis() - began) / 1000;
                        final CharSequence line = Language.join(
                                Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_mint_progress, idx, n, attempt, el / 60, el % 60),
                                "\n\n", url, slow ? Language.join("\n\n", Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_mint_slow_verification)) : "");
                        main.post(() -> { if (!verifyGateCancel) Language.setText(msgV, line); });
                        return true;
                    });
                    LocalStore.logEvent(this, "MINT GATE ok " + idx + "/" + n + " — " + u);
                } catch (Hosting.HostingException e) { bad = e.getMessage(); break; }
                catch (Throwable t) { bad = Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_mint_unreachable, u).toString(); break; }
            }
            final String err = bad;
            final long took = (System.currentTimeMillis() - began) / 1000;
            main.post(() -> {
                verifyGateRunning = false;
                if (dlgF != null) { try { dlgF.dismiss(); } catch (RuntimeException ignored) { } }
                if (isFinishing() || isDestroyed()) return;
                // Stop can land after the final HTTP response but before this main-thread callback.
                if (verifyGateCancel) {
                    toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_mint_stopped));
                    return;
                }
                if (generation != screenGeneration || !draft.equals(mintDraftSnapshot(kind))) {
                    mintDraftChanged(); return;
                }
                if (err != null) {
                    LocalStore.logEvent(this, "MINT GATE FAILED after " + took + "s: " + err);
                    onFail.onFail(err);
                } else {
                    LocalStore.logEvent(this, "MINT GATE passed in " + took + "s");
                    toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_mint_verified));
                    onOk.run();
                }
            });
        }).start();
    }
