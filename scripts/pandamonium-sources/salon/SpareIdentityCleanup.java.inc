    private static final class SpareBurn {
        final String owner, keep;
        final int epoch;
        final NodeApi node;
        boolean lookedUp, submitted;
        int pending;
        androidx.appcompat.app.AlertDialog dialog;
        SpareBurn(String owner, String keep, int epoch, NodeApi node) {
            this.owner = owner; this.keep = keep; this.epoch = epoch; this.node = node;
        }
    }
    private SpareBurn spareBurn;

    private boolean spareBurnCurrent(SpareBurn op) {
        if (spareBurn != op || isFinishing() || isDestroyed() || op.epoch != renderEpoch
                || node != op.node || !nodeUp) return false;
        try { return op.owner.equals(backupOwner()); }
        catch (Exception unavailable) { return false; }
    }

    private void cancelSpareBurn() {
        SpareBurn op = spareBurn;
        spareBurn = null;
        if (op != null && op.dialog != null) op.dialog.dismiss();
    }

    private void spareBurnNotice(SpareBurn op, CharSequence message) {
        if (spareBurnCurrent(op)) toast(message);
    }

    /** Confirm only validated spare identities, retaining the original amount/destination. */
    private void burnSpares() {
        if (isFinishing() || isDestroyed()) return;
        if (!nodeUp || node == null) { toast(MSG_NODE); return; }
        if (spareBurn != null) { toast(MSG_BUSY); return; }
        final SpareBurn op;
        try {
            JSONObject me = SalonStore.identityRecord(this);
            String keep = me.optString("tokenid");
            if (!Args.isTokenId(keep) || new java.math.BigInteger(keep.substring(2), 16).signum() == 0) {
                toast(MSG_IDENTITY); return;
            }
            op = new SpareBurn(backupOwner(me), keep, renderEpoch, node);
        } catch (Exception unavailable) { toast(MSG_IDENTITY); return; }
        spareBurn = op;
        NodeApi.Cb result = new NodeApi.Cb() {
            @Override public void onResult(JSONObject reply) { runOnUiThread(() -> showSpareBurn(op, reply)); }
            @Override public void onError(String ignored) { runOnUiThread(() -> showSpareBurn(op, null)); }
        };
        try { op.node.cmd("balance", result); } catch (RuntimeException unavailable) { result.onError(""); }
    }

    private void showSpareBurn(SpareBurn op, JSONObject reply) {
        if (spareBurn != op || op.lookedUp) return;
        op.lookedUp = true;
        if (!spareBurnCurrent(op)) { cancelSpareBurn(); return; }
        final List<String> spares;
        try { spares = IdentityBalance.spares(reply, op.keep); }
        catch (RuntimeException unavailable) { spareBurnNotice(op, MSG_LOOKUP); cancelSpareBurn(); return; }
        if (spares.isEmpty()) { spareBurnNotice(op, MSG_EMPTY); cancelSpareBurn(); return; }
        StringBuilder ids = new StringBuilder();
        for (String id : spares) ids.append("• ").append(id).append("\n");
        op.dialog = new com.eurobuddha.minimacore.i18n.LanguageDialog(this)
                .setTitle(MSG_TITLE)
                .setMessage(MSG_CONFIRM)
                .setPositiveButton(MSG_BURN, (d, which) -> submitSpareBurn(op, spares))
                .setNegativeButton(MSG_CANCEL, null).create();
        op.dialog.setOnDismissListener(d -> { if (spareBurn == op && !op.submitted) spareBurn = null; });
        op.dialog.show();
    }

    private void submitSpareBurn(SpareBurn op, List<String> spares) {
        if (spareBurn != op || op.submitted) return;
        if (!spareBurnCurrent(op)) {
            if (!isFinishing() && !isDestroyed()) toast(MSG_CHANGED);
            cancelSpareBurn(); return;
        }
        op.submitted = true;
        op.pending = spares.size();
        for (String tid : spares) {
            // Recheck immediately before dispatch. Already dispatched sends cannot be cancelled.
            if (!spareBurnCurrent(op) || tid.equalsIgnoreCase(SalonStore.get(this, "tokenid"))) {
                cancelSpareBurn(); return;
            }
            final boolean[] received = {false};
            NodeApi.Cb result = new NodeApi.Cb() {
                private void finish(JSONObject reply) {
                    runOnUiThread(() -> {
                        if (received[0]) return;
                        received[0] = true;
                        if (reply != null && Boolean.TRUE.equals(reply.opt("status"))) spareBurnNotice(op, MSG_SUBMITTED);
                        else spareBurnNotice(op, MSG_UNKNOWN);
                        if (--op.pending == 0 && spareBurn == op) spareBurn = null;
                    });
                }
                @Override public void onResult(JSONObject reply) { finish(reply); }
                @Override public void onError(String ignored) { finish(null); }
            };
            try { op.node.cmd("send amount:1 tokenid:" + tid + " address:" + GRAVEYARD, result); }
            catch (RuntimeException unavailable) { result.onError(""); }
        }
    }
