    /** Check each opened sheet against the current node; ownership must not outlive a wallet session. */
    private void markOwnership(final String addr, final TextView badge) {
        if (!Util.isValidAddress(addr)) { Language.setText(badge, ""); return; }
        Language.setText(badge, Language.msg(l10n_future_checking));
        badge.setTextColor(Design.dim());
        act.node().cmd("checkaddress address:" + addr, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json == null ? null : json.optJSONObject("response");
                Object relevant = r == null ? null : r.opt("relevant");
                boolean matches = r != null && (addr.startsWith("0x")
                        ? r.opt("0x") instanceof String && addr.equalsIgnoreCase(r.optString("0x"))
                        : r.opt("Mx") instanceof String && addr.equals(r.optString("Mx")));
                if (json == null || !Boolean.TRUE.equals(json.opt("status")) || !matches || !(relevant instanceof Boolean)) {
                    onError("Incomplete address evidence");
                    return;
                }
                boolean mine = (Boolean) relevant;
                setBadge(badge, mine, mine ? Language.msg(l10n_history_yours) : Language.msg(l10n_nft_history_not_yours));
            }
            @Override public void onError(String message) {
                Language.setText(badge, Language.msg(l10n_nft_history_unverified));
                badge.setTextColor(Design.amber());
            }
        });
    }

    private void setBadge(TextView badge, boolean mine, CharSequence text) {
        Language.setText(badge, text);
        badge.setTextColor(mine ? Design.success() : Design.dim());
    }
