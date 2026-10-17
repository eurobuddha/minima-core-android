"""Fresh evidence and renewed approval for an unconfirmed NFT retry."""
from pathlib import Path

def adapt(root):
    p=root/'wallet/src/main/java/com/eurobuddha/wallet/NftView.java'
    s=p.read_text()
    def replace(old,new,n=1):
        nonlocal s
        assert s.count(old)==n, ('NFT retry drift',old,s.count(old))
        s=s.replace(old,new)
    start=s.index('    /** coinid → broadcast time')
    end=s.index('    private final java.util.function.BooleanSupplier session;',start)
    s=s[:start]+'''    /** Broadcasts remain pending until fresh evidence or explicit retry review.
     * Elapsed time only enables a review; it is never evidence that a transaction failed. */
    private static final java.util.HashMap<String, Long> PENDING = new java.util.HashMap<>();
    private static final long RETRY_WAIT_MS = 10 * 60_000;

    private static boolean isPending(String coinid) { return PENDING.containsKey(coinid); }
    private static boolean retryReady(String coinid) {
        Long time = PENDING.get(coinid);
        return time != null && System.currentTimeMillis() - time >= RETRY_WAIT_MS;
    }

'''+s[end:]
    replace('        // A pending coin that has left the live list is confirmed spent — stop tracking it.\n        PENDING.keySet().retainAll(liveCoinIds);',
            '        // A cached gallery refresh cannot prove departure or clear pending sends.')
    replace('        HashSet<String> liveCoinIds = new HashSet<>();\n','')
    replace('                liveCoinIds.add(Nft.str(c, "coinid"));\n','')
    # Keep a route to the fresh review from both the collection and the full-screen viewer.
    replace('        final boolean pending = isPending(coinid);',
            '        final boolean pending = isPending(coinid);\n        final boolean retry = retryReady(coinid);')
    replace('Language.setText(sendOne, pending ? Language.msg(l10n_nft_confirming) : Language.msg(l10n_wallet_send));',
            'Language.setText(sendOne, retry ? Language.msg(l10n_nft_review) : pending ? Language.msg(l10n_nft_confirming) : Language.msg(l10n_wallet_send));')
    replace('sendOne.setTextColor(pending ? Design.dim() : Design.accent());',
            'sendOne.setTextColor(pending && !retry ? Design.dim() : Design.accent());')
    replace('if (!pending) sendOne.setOnClickListener', 'if (!pending || retry) sendOne.setOnClickListener')
    replace('            boolean pending = isPending(Nft.str(coin, "coinid"));',
            '            boolean pending = isPending(Nft.str(coin, "coinid"));\n            boolean retry = retryReady(Nft.str(coin, "coinid"));')
    replace('Language.setText(send, pending ? Language.msg(l10n_nft_confirming) : Language.msg(l10n_wallet_send));',
            'Language.setText(send, retry ? Language.msg(l10n_nft_review) : pending ? Language.msg(l10n_nft_confirming) : Language.msg(l10n_wallet_send));')
    replace('send.setEnabled(!pending);','send.setEnabled(!pending || retry);')
    replace('send.setAlpha(pending ? 0.5f : 1f);','send.setAlpha(pending && !retry ? 0.5f : 1f);')
    replace('        final int idx = Nft.itemIndex(coin);\n        String guard = sendGuard(g, coin);', '''        final int idx = Nft.itemIndex(coin);
        if (retryReady(Nft.str(coin, "coinid"))) {
            promptRecipient(Language.msg(l10n_nft_review), recipient -> requestRetry(g,
                    java.util.Collections.singletonList(Nft.str(coin, "coinid")), recipient));
            return;
        }
        String guard = sendGuard(g, coin);''')
    replace('        int pending = 0, unstamped = 0;', '        int pending = 0, unstamped = 0;\n        boolean needsRetryReview = false;')
    replace('            if (isPending(Nft.str(c, "coinid"))) { pending++; continue; }', '''            if (isPending(Nft.str(c, "coinid"))) {
                if (!retryReady(Nft.str(c, "coinid"))) { pending++; continue; }
                needsRetryReview = true;
            }''')
    replace('        final int fpending = pending, funstamped = unstamped;', '''        final int fpending = pending, funstamped = unstamped;
        final boolean retry = needsRetryReview;''')
    replace('                recipient -> reviewSendCollection(g, sendable, fpending, funstamped, recipient));', '''                recipient -> {
                    if (!retry) reviewSendCollection(g, sendable, fpending, funstamped, recipient);
                    else {
                        List<String> ids = new ArrayList<>();
                        for (JSONObject c : sendable) ids.add(Nft.str(c, "coinid"));
                        requestRetry(g, ids, recipient);
                    }
                });''')
    signature='''    private void reviewSendCollection(final Group g, final List<JSONObject> coins,
                                      int zSkippedPending, int zSkippedUnstamped, final String recipient) {'''
    replace(signature,signature+'''
        reviewCollection(g, coins, zSkippedPending, zSkippedUnstamped, recipient, false,
                () -> runCollectionSend(g, coins, recipient));
    }

    private void reviewCollection(final Group g, final List<JSONObject> coins,
                                  int zSkippedPending, int zSkippedUnstamped, final String recipient,
                                  boolean retry, Runnable approved) {''')
    replace('                    "\\n\\n", Language.msg(l10n_nft_separate_transactions), usage.build(), "\\n\\n", Language.msg(l10n_wallet_irreversible));',
        '''                    retry ? Language.join("\\n\\n", Language.msg(l10n_nft_retry_warning)) : "",
                    "\\n\\n", Language.msg(l10n_nft_separate_transactions), usage.build(), "\\n\\n", Language.msg(l10n_wallet_irreversible));''')
    replace('.setPositiveButton(Language.msg(l10n_nft_sign_all), (d, w) -> runCollectionSend(g, coins, recipient))',
            '.setPositiveButton(Language.msg(l10n_nft_sign_all), (d, w) -> { if (session.getAsBoolean()) approved.run(); })')
    replace('        final String coinid = Nft.str(coin, "coinid");\n        Language.setText(progress,', '''        final String coinid = Nft.str(coin, "coinid");
        if (isPending(coinid)) {
            finishCollectionSend(g, recipient, coins.size(), i, txpowids, sentCoinIds,
                    Language.msg(l10n_nft_wait_pending), pd);
            return;
        }
        Language.setText(progress,''')
    replace('''                    try {
                        built = act.factory().buildNftTransfer(inputs.get(0), recipient, Util.newTxnId());''', '''                    try {
                        if (isPending(coinid)) throw new IllegalStateException(Language.text(act, l10n_nft_wait_pending));
                        built = act.factory().buildNftTransfer(inputs.get(0), recipient, Util.newTxnId());''')
    replace('        final int totalSent = zRemaining.size();', '''        final int totalSent = zRemaining.size();
        final java.util.Map<String,Long> watched = pendingSnapshot(zRemaining);''')
    replace('                            live.add(id);',
            '                            if (!live.add(id.toLowerCase(java.util.Locale.ROOT))) { schedule(); return; }')
    replace('                        zRemaining.retainAll(live);   // gone from live set == spend confirmed', '''                        for (String id : zRemaining) {
                            if (!live.contains(id.toLowerCase(java.util.Locale.ROOT)) && java.util.Objects.equals(watched.get(id), PENDING.get(id)))
                                PENDING.remove(id);
                        }
                        zRemaining.removeIf(id -> !live.contains(id.toLowerCase(java.util.Locale.ROOT)));''')
    start=s.index('    /** 15 minutes without full confirmation:')
    end=s.index('    /** One report dialog:', start)
    s=s[:start]+'''    /** Timeout offers fresh evidence and a new review; it does not free any pending coin. */
    private void offerRetry(final Group g, final String recipient, final CharSequence zHeader,
                            final List<String> zRemaining, int totalSent, List<String> txpowids) {
        if (!session.getAsBoolean()) return;
        CharSequence body = Language.join(zHeader, "\\n", Language.msg(l10n_nft_confirmed_count,
                totalSent - zRemaining.size(), totalSent), "\\n",
                Language.msg(l10n_nft_unconfirmed_body, zRemaining.size()));
        final List<String> requested = new ArrayList<>(zRemaining);
        showReport(Language.msg(l10n_nft_some_unconfirmed), body,
                Language.msg(l10n_nft_retry_unconfirmed, requested.size()),
                () -> requestRetry(g, requested, recipient));
    }

    private static java.util.Map<String,Long> pendingSnapshot(List<String> ids) {
        java.util.Map<String,Long> snapshot = new LinkedHashMap<>();
        for (String id : ids) snapshot.put(id, PENDING.get(id));
        return snapshot;
    }

    private static boolean pendingUnchanged(java.util.Map<String,Long> snapshot) {
        for (java.util.Map.Entry<String,Long> entry : snapshot.entrySet())
            if (!java.util.Objects.equals(entry.getValue(), PENDING.get(entry.getKey()))) return false;
        return true;
    }

    /** Reuse NodeLink's whole-list read and the NFT confirmation path's strict evidence gate. */
    private void requestRetry(final Group g, List<String> ids, final String recipient) {
        if (!session.getAsBoolean() || ids.isEmpty()) return;
        final List<String> requested = new ArrayList<>(ids);
        final java.util.Map<String,Long> expected = pendingSnapshot(requested);
        act.node().coinsRelevant(new NodeApi.Cb() {
            @Override public void onResult(org.json.JSONObject reply) {
                if (!session.getAsBoolean()) return;
                try {
                    if (!pendingUnchanged(expected)) throw new IllegalStateException();
                    final List<JSONObject> fresh = retryCandidates(g, requested, reply);
                    // Release only absent coins, after the entire reply has been validated.
                    java.util.HashSet<String> present = new java.util.HashSet<>();
                    for (JSONObject c : fresh) present.add(Nft.str(c,"coinid"));
                    for (String id : requested) if (!present.contains(id)) PENDING.remove(id);
                    if (fresh.isEmpty()) {
                        act.reload();
                        Toast.makeText(act, Language.msg(l10n_nft_coins_not_found), Toast.LENGTH_LONG).show();
                        return;
                    }
                    final java.util.Map<String,Long> reviewed = pendingSnapshot(new ArrayList<>(present));
                    reviewCollection(g, fresh, 0, 0, recipient, true, () -> {
                        if (!session.getAsBoolean()) return;
                        if (!pendingUnchanged(reviewed)) { retryUnavailable(); return; }
                        for (String id : reviewed.keySet()) PENDING.remove(id);
                        runCollectionSend(g, fresh, recipient);
                    });
                } catch (Exception unavailable) { retryUnavailable(); }
            }
            @Override public void onError(String message) {
                if (session.getAsBoolean()) retryUnavailable();
            }
        });
    }

    /** Nothing is signed, released or reviewed until all rows pass validation. */
    private List<JSONObject> retryCandidates(Group g, List<String> requested, org.json.JSONObject reply)
            throws Exception {
        if (reply == null || !Boolean.TRUE.equals(reply.opt("status"))) throw new IllegalStateException();
        org.json.JSONArray rows = reply.optJSONArray("response");
        if (rows == null) throw new IllegalStateException();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        List<JSONObject> fresh = new ArrayList<>();
        org.minima.utils.json.parser.JSONParser parser = new org.minima.utils.json.parser.JSONParser();
        for (int i=0; i<rows.length(); i++) {
            org.json.JSONObject row = rows.optJSONObject(i);
            Object rawId = row == null ? null : row.opt("coinid");
            if (!(rawId instanceof String) || !((String)rawId).matches("^0x[0-9a-fA-F]{2,64}$")
                    || !seen.add(((String)rawId).toLowerCase(java.util.Locale.ROOT)))
                throw new IllegalStateException();
            String id = (String)rawId;
            if (!requested.contains(id)) {
                // Case changes must not be mistaken for a missing requested coin.
                for (String wanted : requested) if (wanted.equalsIgnoreCase(id)) throw new IllegalStateException();
                continue;
            }
            if (!(row.opt("address") instanceof String) || act.keyIndexForAddress(row.getString("address")) == null
                    || !g.tokenid.equalsIgnoreCase(row.optString("tokenid", ""))
                    || row.optJSONObject("token") == null) throw new IllegalStateException();
            JSONObject coin = (JSONObject)parser.parse(row.toString());
            Nft.Meta meta = Nft.meta((JSONObject)coin.get("token"));
            if (!Nft.isNftMeta(meta) || Nft.isUnstampedLocked(meta, coin)) throw new IllegalStateException();
            fresh.add(coin);
        }
        return fresh;
    }

    private void retryUnavailable() {
        Toast.makeText(act, Language.msg(l10n_nft_retry_unavailable), Toast.LENGTH_LONG).show();
    }

'''+s[end:]
    s=s.replace('offered for one-tap retry.', 'offered for a fresh status check and review.')
    p.write_text(s)
