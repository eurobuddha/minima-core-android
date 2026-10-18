    private static CharSequence localStatus(LocalHistory.Entry entry) {
        if(entry.attempt==null)return Language.msg(l10n_nft_history_unverified_status);
        switch(entry.phase()) {
            case HistoryDb.PREPARED:return Language.msg(l10n_nft_history_preparing);
            case HistoryDb.POSTING:return Language.msg(l10n_utxo_status_posting);
            case HistoryDb.POSTED:return Language.msg(l10n_utxo_status_posted);
            case HistoryDb.CONFIRMED:return Language.msg(l10n_nft_history_confirmed);
            case HistoryDb.NOT_SUBMITTED:return Language.msg(l10n_nft_history_not_submitted);
            default:return Language.msg(l10n_nft_history_unverified_status);
        }
    }
    private void localHeading(LinearLayout parent,CharSequence title) {
        TextView heading=new TextView(act);Language.setText(heading,title);
        heading.setTextColor(Design.accentText());heading.setTextSize(12f);heading.setTypeface(Design.typefaceBold());
        heading.setPadding(dp(8),dp(14),dp(8),dp(6));parent.addView(heading);
    }

    private View localRow(final LocalHistory.Entry entry) {
        HistoryRow r=entry.row;
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(11), dp(8), dp(11));
        boolean failed = HistoryDb.NOT_SUBMITTED.equals(entry.phase());
        int color = entry.confirmed() ? Design.success() : failed ? Design.red() : Design.amber();

        TextView glyph = new TextView(act);
        Language.setText(glyph, entry.confirmed() ? "✓" : failed ? "✗" : "⏳"); glyph.setTextColor(color); glyph.setTextSize(18f); glyph.setWidth(dp(28));
        row.addView(glyph);

        LinearLayout mid = new LinearLayout(act);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(6), 0, dp(6), 0);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView line1 = new TextView(act);
        Language.setText(line1, Util.tidyAmount(r.amount) + "  " + r.tokenName);
        line1.setTextColor(color); line1.setTextSize(15f); line1.setTypeface(Typeface.DEFAULT_BOLD);
        TextView line2 = new TextView(act);
        // Recipient is either an address (tap the row for the full, copyable value) or a tool label.
        CharSequence to = r.recipient == null ? "" : (Util.isValidAddress(r.recipient) ? Util.shorten(r.recipient) : NftMessages.message(r.recipient));
        Language.setText(line2, Language.join(to, "  ·  ", localStatus(entry), "  ·  ", relative(r.ts)));
        line2.setTextColor(Design.dim()); line2.setTextSize(12f);
        mid.addView(line1); mid.addView(line2);
        row.addView(mid);

        TextView right = new TextView(act);
        Language.setText(right, Language.msg(l10n_utxo_local)); right.setTextColor(Design.dim()); right.setTextSize(11f); right.setGravity(Gravity.END);
        row.addView(right);

        row.setOnClickListener(v -> {
            for(LocalHistory.Entry current:LocalHistory.read(act.history(),CAP))
                if(java.util.Objects.equals(current.row.internalid,r.internalid)){showLocalDetail(current);return;}
        });
        return row;
    }

    /** Everything recorded before signing: status, the node's error, recipient, inputs (coinids), outputs, change, burn. */
    private void showLocalDetail(final LocalHistory.Entry entry) {
        HistoryRow r=entry.row;
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(12));
        box.setBackgroundColor(Design.bg());
        kv(box, Language.msg(l10n_utxo_status), localStatus(entry));
        if (!entry.note().isEmpty()) kv(box, Language.msg(l10n_utxo_note), NftMessages.message(entry.note()));
        copyRow(box, Language.msg(l10n_nft_history_local_id), r.internalid);
        if(!entry.txpowid().isEmpty())copyRow(box, Language.msg(l10n_history_txpow), entry.txpowid());
        kv(box, Language.msg(l10n_nft_history_requested_amount), Util.tidyAmount(r.amount) + " " + r.tokenName);
        kv(box, Language.msg(l10n_history_date_time), Language.stamp(r.ts, "yMMMdHms"));
        if (r.recipient != null && Util.isValidAddress(r.recipient)) copyRow(box, Language.msg(l10n_history_to), r.recipient);
        else if (r.recipient != null && !r.recipient.isEmpty()) kv(box, Language.msg(l10n_utxo_action), NftMessages.message(r.recipient));
        if (!Util.isMinima(r.tokenid)) copyRow(box, Language.msg(l10n_history_token), r.tokenid);
        if (r.changeaddr != null && !r.changeaddr.isEmpty()) copyRow(box, Language.msg(l10n_utxo_history_change), r.changeaddr);
        if (r.burn != null && !r.burn.isEmpty()) kv(box, Language.msg(l10n_utxo_history_burn), Util.tidyAmount(r.burn) + " Minima");
        addLocalCoins(box, Language.msg(l10n_history_inputs), r.inputs, "coinid");
        addLocalCoins(box, Language.msg(l10n_history_outputs), r.outputs, null);
        Sheet.create(act, Language.msg(l10n_history_transaction)).body(box)
                .action(Language.msg(l10n_utxo_close), Sheet.Style.SECONDARY, null).show();
    }

    /** [{coinid,address,amount}] or [{address,amount}] → bullets with full, copyable identifiers. */
    private void addLocalCoins(LinearLayout p, CharSequence title, String json, String idKey) {
        try {
            JSONArray a = new JSONArray(json == null ? "[]" : json);
            if (a.length() == 0) return;
            localHeading(p, title);
            for (int i = 0; i < a.length(); i++) {
                JSONObject c = a.optJSONObject(i);
                if (c == null) continue;
                kv(p, Language.msg(l10n_history_amount), Util.tidyAmount(c.optString("amount", "")));
                if (idKey != null && !c.optString(idKey, "").isEmpty()) copyRow(p, "coinid".equals(idKey) ? Language.msg(l10n_utxo_coin_id) : idKey, c.optString(idKey, ""));
                copyRow(p, Language.msg(l10n_history_address), c.optString("address", ""));
            }
        } catch (Exception ignored) {}
    }
