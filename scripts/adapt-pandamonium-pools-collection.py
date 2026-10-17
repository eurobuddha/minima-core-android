"""Keep collection jobs until every journalled withdrawal is confirmed and the payout is empty."""
from pathlib import Path
import shutil

def adapt(root):
 project=Path(__file__).resolve().parents[1];folder=root/'pandapools/src/main/java/com/eurobuddha/pandapools'
 def change(s,old,new,count=1):
  assert s.count(old)==count,('Collection evidence source drift',old,s.count(old));return s.replace(old,new)
 for name in ['CollectionEvidence','CollectConfirmation']:
  shutil.copy2(project/'scripts/pandamonium-sources/pandapools'/(name+'.java'),folder/(name+'.java'))
 p=folder/'PendingCollect.java';s=p.read_text()
 s=change(s,'        String oadr;','''        String raw;
        boolean legacy = true;
        final java.util.LinkedHashMap<String,String> transactions = new java.util.LinkedHashMap<>();
        String oadr;''')
 s=change(s,'            e.oadr = o.optString("oadr", oadr);','''            e.raw = raw;
            e.oadr = o.optString("oadr", oadr);
            if (!FundingCoins.hex(e.oadr) || !e.oadr.equalsIgnoreCase(oadr)) return null;
            org.json.JSONArray receipts = o.optJSONArray("transactions");
            e.legacy = !Integer.valueOf(1).equals(o.opt("format")) || !Boolean.FALSE.equals(o.opt("legacy"));
            if (receipts == null || receipts.length() == 0) e.legacy = true;
            if (receipts != null) for (int i = 0; i < receipts.length(); i++) {
                JSONObject receipt = receipts.optJSONObject(i);
                String transaction = receipt == null ? "" : receipt.optString("transaction", "");
                String mined = receipt == null ? "" : receipt.optString("mined", "");
                if (!CollectionEvidence.id(transaction) || (!mined.isEmpty() && !CollectionEvidence.id(mined))
                        || e.transactions.put(transaction.toLowerCase(Locale.ROOT), mined) != null) e.legacy = true;
            }''')
 s=change(s,'            JSONObject o = new JSONObject()\n                    .put("oadr", e.oadr)','            JSONObject o = new JSONObject(e.raw)\n                    .put("oadr", e.oadr)')
 s=change(s,'.put("attempts", e.attempts + 1)','.put("attempts", e.attempts == Integer.MAX_VALUE ? e.attempts : e.attempts + 1)')
 s=change(s,'            prefs(c).edit().putString(key(oadr), o.toString()).commit();','            save(c, oadr, e.raw, o.toString());')
 a=s.index('    /**\n     * Stop tracking.');b=s.index('    /** Addresses the user needs',a)
 s=s[:a]+'''    /** Clear only the exact evidence snapshot checked by the current chain/address callbacks. */
    static synchronized boolean clear(Context c, Entry expected) {
        if (c == null || expected == null || expected.legacy || expected.transactions.isEmpty()) return false;
        for (String mined : expected.transactions.values()) if (!CollectionEvidence.id(mined)) return false;
        return save(c, expected.oadr, expected.raw, null);
    }

    /** Commit a change only while its previous snapshot is current. Restore memory on failed commit. */
    private static boolean save(Context c, String address, String before, String after) {
        SharedPreferences store = prefs(c);
        if (!java.util.Objects.equals(before, store.getString(key(address), null))) return false;
        try {
            SharedPreferences.Editor edit = store.edit();
            boolean committed = (after == null ? edit.remove(key(address)) : edit.putString(key(address), after)).commit();
            if (committed) return true;
        } catch (RuntimeException failed) { /* Keep the old job in memory even when commit failed. */ }
        try {
            SharedPreferences.Editor rollback = store.edit();
            if (before == null) rollback.remove(key(address)); else rollback.putString(key(address), before);
            rollback.commit();
        } catch (RuntimeException ignored) { /* The caller must treat this operation as failed. */ }
        return false;
    }

    /** Durable barrier before txnpost. The transaction hash survives the miner changing the TxPoW ID. */
    static synchronized boolean track(Context c, String address, String transaction) {
        if (c == null || !FundingCoins.hex(address) || !CollectionEvidence.id(transaction)) return false;
        try {
            String raw = prefs(c).getString(key(address), null);
            Entry entry = get(c, address);
            if (raw != null && entry == null) return false;
            JSONObject value = raw == null ? new JSONObject().put("oadr", address)
                    .put("status", Status.RETRYING.name()).put("attempts", 0)
                    .put("added_at", System.currentTimeMillis()).put("last_try_at", 0).put("last_error", "")
                    : new JSONObject(raw);
            java.util.LinkedHashMap<String,String> transactions = entry == null
                    ? new java.util.LinkedHashMap<>() : entry.transactions;
            String id = transaction.toLowerCase(Locale.ROOT);
            if (!transactions.containsKey(id)) transactions.put(id, "");
            value.put("format", 1).put("legacy", entry != null && entry.legacy)
                    .put("transactions", receipts(transactions));
            return save(c, address, raw, value.toString());
        } catch (Exception invalid) { return false; }
    }
    private static org.json.JSONArray receipts(java.util.Map<String,String> values) throws org.json.JSONException {
        org.json.JSONArray result = new org.json.JSONArray();
        for (java.util.Map.Entry<String,String> value : values.entrySet())
            result.put(new JSONObject().put("transaction", value.getKey()).put("mined", value.getValue()));
        return result;
    }

    /** Matching an immutable transaction hash identifies a candidate; fresh inclusion is still required. */
    static synchronized void observe(Context c, JSONObject txpow) {
        if (txpow == null || !Boolean.TRUE.equals(txpow.opt("istransaction"))) return;
        String transaction = ActivityLog.transactionId(txpow).toLowerCase(Locale.ROOT);
        String mined = txpow.optString("txpowid", "");
        if (!CollectionEvidence.id(transaction) || !CollectionEvidence.id(mined)) return;
        for (Entry entry : all(c)) {
            if (!entry.transactions.containsKey(transaction) || mined.equals(entry.transactions.get(transaction))) continue;
            try {
                entry.transactions.put(transaction, mined);
                JSONObject value = new JSONObject(entry.raw).put("transactions", receipts(entry.transactions));
                save(c, entry.oadr, entry.raw, value.toString());
            } catch (Exception invalid) { /* Leave the job unresolved. */ }
        }
    }
    static int scanOffset(Context c) {
        try { return Math.max(0, prefs(c).getInt("collection_history_offset", 0)); }
        catch (RuntimeException invalid) { return 0; }
    }
    static void scanOffset(Context c, int offset) {
        prefs(c).edit().putInt("collection_history_offset", offset).apply();
    }

'''+s[b:]
 p.write_text(s)
 p=folder/'TxPost.java';s=p.read_text()
 s=change(s,'    private TxPost() {}','''    interface BeforePost { void prepare(Runnable post, java.util.function.Consumer<String> fail); }

    private TxPost() {}''')
 old='    static void checkThenPost(NodeApi node, String txid, List<String> cmdsThroughBasics, List<String> ownerKeys, Done done) {'
 s=change(s,old,old+'''
        checkThenPost(node, txid, cmdsThroughBasics, ownerKeys, null, done);
    }

    static void checkThenPost(NodeApi node, String txid, List<String> cmdsThroughBasics, List<String> ownerKeys,
                              BeforePost beforePost, Done done) {''')
 s=change(s,'runChain(node, txid, cmdsThroughBasics, gatedDone);','runChain(node, txid, cmdsThroughBasics, beforePost, gatedDone);')
 s=change(s,'private static void runChain(NodeApi node, String txid, List<String> cmdsThroughBasics, Done done)','private static void runChain(NodeApi node, String txid, List<String> cmdsThroughBasics, BeforePost beforePost, Done done)')
 s=change(s,'                node.cmd("txnpost id:" + txid, new NodeApi.Cb() {','                Runnable post = () -> node.cmd("txnpost id:" + txid, new NodeApi.Cb() {')
 anchor='''                });
            }
            @Override public void fail(String message) { done.fail(message); }'''
 s=change(s,anchor,'''                });
                if (beforePost == null) post.run();
                else beforePost.prepare(post, message -> {
                    node.cmd("txndelete id:" + txid, NOOP);
                    done.fail(message);
                });
            }
            @Override public void fail(String message) { done.fail(message); }''')
 p.write_text(s)
 p=folder/'PoolManager.java';s=p.read_text()
 a=s.index('    public void close(Pool p, Result cb)');b=s.index('    // ===================================================================== FORWARD',a);part=s[a:b]
 part=change(part,'        ownerSignPost(txid, p.opk, cmds, cb);','''        final String payoutAddress = p.oadr;
        ownerSignPost(txid, p.opk, cmds,
                (post, fail) -> CollectConfirmation.beforePost(node, txid, payoutAddress, post, fail), cb);''')
 s=s[:a]+part+s[b:]
 old='    private void ownerSignPost(String txid, String opk, List<String> cmds, Result cb) {'
 s=change(s,old,old+'''
        ownerSignPost(txid, opk, cmds, null, cb);
    }

    private void ownerSignPost(String txid, String opk, List<String> cmds, TxPost.BeforePost beforePost, Result cb) {''')
 s=change(s,'TxPost.checkThenPost(node, txid, cmds, new TxPost.Done() {\n                @Override public void ok(String txpowid) { cb.onPosted(txpowid); }','TxPost.checkThenPost(node, txid, cmds, java.util.Collections.emptyList(), beforePost, new TxPost.Done() {\n                @Override public void ok(String txpowid) { cb.onPosted(txpowid); }')
 a=s.index('    private void buildMigrate(');b=s.index('    // ===================================================================== CLOSE',a);part=s[a:b]
 part=change(part,'                        ownerSignPost(txid, p.opk, cmds, new Result() {','''                        final String payoutAddress = p.oadr;
                        ownerSignPost(txid, p.opk, cmds,
                                (post, fail) -> CollectConfirmation.beforePost(node, txid, payoutAddress, post, fail), new Result() {''')
 s=s[:a]+part+s[b:]
 p.write_text(s)
 p=folder/'CollectSweeper.java';s=p.read_text()
 s=change(s,'        if (remaining == null) return Verdict.RETRY;','        if (remaining == null || remaining < 0) return Verdict.RETRY;')
 s=change(s,'        step(queue, 0, new int[]{0, 0, 0}, cb);','        new CollectConfirmation(ctx, node).scan(() -> step(PendingCollect.all(ctx), 0, new int[]{0, 0, 0}, cb));')
 a=s.index('        // Read the address FIRST.');b=s.index('            if (remaining == null)',a)
 s=s[:a]+'''        // An empty address may mean its withdrawal has not arrived yet.
        readRemaining(oadr, remaining -> {
            if (decide(remaining, null) == Verdict.CLEAR) {
                new CollectConfirmation(ctx, node).confirmed(e, confirmed -> {
                    if (!confirmed) { pendingEmpty(e, queue, i, tally, cb); return; }
                    // Re-read after confirmation; never clear from an earlier balance snapshot.
                    readRemaining(oadr, latest -> {
                        if (latest != null && latest == 0 && PendingCollect.clear(ctx, e)) {
                            tally[0]++;
                            step(queue, i + 1, tally, cb);
                        } else pendingEmpty(e, queue, i, tally, cb);
                    });
                });
                return;
            }
'''+s[b:]
 a=s.index('                JSONArray rows = FundingCoins.rows(reply);');b=s.index('\n            }\n            @Override public void onError',a)
 s=s[:a]+'                cb.accept(CollectionEvidence.remaining(reply));'+s[b:]
 s=change(s,'                cb.accept(r.optBoolean("relevant", false));','                Object relevant = r.opt("relevant");\n                cb.accept(relevant instanceof Boolean ? (Boolean) relevant : null);')
 marker='    /** How many coins sit at'
 helper='''    private void pendingEmpty(PendingCollect.Entry entry, List<PendingCollect.Entry> queue, int index,
                              int[] tally, Cb cb) {
        PendingCollect.attempted(ctx, entry.oadr, PendingCollect.Status.RETRYING,
                "Waiting for verified withdrawal confirmation and an empty payout address. Older entries without transaction evidence remain unresolved.");
        tally[1]++;
        step(queue, index + 1, tally, cb);
    }

'''
 s=change(s,marker,helper+marker)
 s=s.replace('Empty means done — and that is the only thing that means done.','An empty read also needs confirmed withdrawal evidence.')
 s=s.replace('The address is empty. This is the ONLY thing that finishes the job.','The address is empty; the caller must still verify withdrawal evidence before clearing.')
 p.write_text(s)
 p=folder/'ActivityLog.java';s=p.read_text();s=change(s,'        if (transaction.isEmpty() || !FundingCoins.hex(minedId)) return;','        if (transaction.isEmpty() || !FundingCoins.hex(minedId)) return;\n        PendingCollect.observe(ctx, txpow);');p.write_text(s)
 p=root/'pandapools/build.gradle';s=p.read_text();s=change(s,'dependencies {',"dependencies {\n    testImplementation 'org.mockito:mockito-core:5.12.0'");p.write_text(s)

 p=folder/'MyLpView.java';s=p.read_text();s=change(s,'status("Withdrawn funds moved to your wallet ✓")','status(Language.msg(l10n_pools_collection_checked_empty))');p.write_text(s)

 p=folder/'CollectSweeper.java';s=p.read_text();s=change(s,' * until they are actually empty.',' * until they are actually empty and every journalled withdrawal has fresh chain confirmation.');p.write_text(s)
