    private void verifyInto(final JSONObject n, final String bindHex, final TextView tv) {
        verifyInto(n, bindHex, tv, false);
    }

    /** Explicit retry replaces an older request; duplicate badges share the check. */
    private void verifyInto(final JSONObject n, final String bindHex, final TextView tv, boolean force) {
        tv.setTag(null); // Rebinding also invalidates an old callback on this view.
        if (!nodeUp) { Language.setText(tv, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_connect)); tv.setTextColor(Design.DIM()); return; }
        final String owner = SalonStore.get(this, "tokenid");
        final int epoch = renderEpoch;
        final JSONObject snapshot = ProofRequests.snapshot(n);
        final String key = ProofRequests.key(snapshot, bindHex, owner);
        final boolean ownPage = !owner.isEmpty() && bindHex != null && bindHex.equalsIgnoreCase(NftProof.hexOf(owner));
        Language.setText(tv, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_verifying));
        tv.setTextColor(Design.DIM());
        java.util.function.Consumer<ProofRequests.Result> listener = new java.util.function.Consumer<ProofRequests.Result>() {
            @Override public void accept(ProofRequests.Result result) {
                if (tv.getTag() != this) return;
                tv.setTag(null);
                if (isFinishing() || isDestroyed() || epoch != renderEpoch || !nodeUp
                        || !owner.equals(SalonStore.get(MainActivity.this, "tokenid"))
                        || !key.equals(ProofRequests.key(n, bindHex, owner))) return;
                Language.setText(tv, result.verified
                        ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_verified)
                        : Language.join("✕ ", HoldingMessages.message(result.reason)));
                tv.setTextColor(result.verified ? 0xFF1F7A3F : 0xFFB4462A);
                if (result.live && ownPage) maybeRefreshProof(n, bindHex);
            }
        };
        tv.setTag(listener); // Requests retain subscribers weakly, as in WebValidate.
        proofRequests.request(key, force, done -> {
            Runnable bound = () -> NftProof.verify(node, snapshot, bindHex,
                    (ok, reason, image) -> done.accept(new ProofRequests.Result(ok, reason, false)));
            if (ownPage) NftProof.holds(node, snapshot.optString("tokenid"), (held, coin) -> {
                if (held) done.accept(new ProofRequests.Result(true, "verified holding", true));
                else bound.run();
            });
            else bound.run();
        }, listener);
    }

