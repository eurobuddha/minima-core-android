"""Reuse NFT Wallet burial journaling in Atelier, retaining its explicit stripped-state fallback."""
from pathlib import Path
import re

def adapt(root):
    project=Path(__file__).resolve().parents[1];folder=root/'atelier/src/main/java/com/eurobuddha/statenft'
    sources=project/'scripts/pandamonium-sources'
    def change(s,old,new,n=1):
        assert s.count(old)==n,('Atelier burial source drift',old,s.count(old),n)
        return s.replace(old,new)
    def method(s,signature):
        a=s.index('    '+signature);b=s.index('\n    }',a)+6;return s[a:b]
    p=folder/'BurialEvidence.java';s=p.read_text()
    s=change(s,'    static String stateHash(JSONObject coin) {','''    static String stateHash(JSONObject coin) { return stateHash(coin, true); }
    private static String stateHash(JSONObject coin, boolean identityRequired) {''')
    s=change(s,'if (!ports.containsKey(0)) throw invalid();','if (identityRequired && !ports.containsKey(0)) throw invalid();')
    insertion='''    // Atelier can deliberately strip state when the creator buries an oversized coin.
    static boolean preserves(JSONObject intent) {
        Object value = intent.opt("preserve");
        if (value == null) return true; // Existing transfer receipts preserve state.
        if (!(value instanceof Boolean)) throw invalid();
        return Boolean.TRUE.equals(value);
    }
    static String outputStateHash(JSONObject coin, boolean preserve) {
        if (!preserve) {
            Object state = coin == null ? null : coin.opt("state");
            if (!(state instanceof JSONArray && ((JSONArray)state).length() == 0)
                    && !(state instanceof JSONObject && ((JSONObject)state).length() == 0)) throw invalid();
        }
        return stateHash(coin, preserve);
    }
    static JSONObject burialIntent(JSONObject coin, String tokenid, boolean preserve) {
        if (preserve) return intent(coin, tokenid);
        if (!same(tokenid, id(coin, "tokenid"))) throw invalid();
        try {
            return new JSONObject().put("input", id(coin, "coinid")).put("output", "")
                    .put("tokenid", tokenid).put("tokenamount", amount(coin)).put("preserve", false)
                    .put("statehash", outputStateHash(new JSONObject().put("state", new JSONArray()), false));
        } catch (org.json.JSONException e) { throw invalid(); }
    }
'''
    s=change(s,'    static JSONObject intent(JSONObject coin, String tokenid) {',insertion+'    static JSONObject intent(JSONObject coin, String tokenid) {')
    s=change(s,'!Boolean.TRUE.equals(output.opt("storestate"))','!Boolean.valueOf(preserves(intent)).equals(output.opt("storestate"))')
    s=change(s,'stateHash(txn)))throw invalid();','outputStateHash(txn, preserves(intent))))throw invalid();')
    s=change(s,'            amount(e);','            amount(e); preserves(e);')
    s=change(s,'+ "\\n" + e.optString("statehash"));','+ "\\n" + e.optString("statehash") + "\\n" + preserves(e));')
    s=change(s,'Boolean.TRUE.equals(coin.opt("storestate"))','Boolean.valueOf(preserves(receipt)).equals(coin.opt("storestate"))')
    s=change(s,'receipt.optString("statehash").equals(stateHash(coin));','receipt.optString("statehash").equals(outputStateHash(coin, preserves(receipt)));')
    p.write_text(s)
    # Persistent journal methods from NFT Wallet; integrate the existing Atelier cache.
    p=folder/'LocalStore.java';s=p.read_text();d=(sources/'NFTwallet/burial-store.inc').read_text()
    d=d[:d.index('    private static JSONArray burialCollections')]
    d=d.replace('return prefs(ctx).edit().putString(KEY_COLLECTIONS, rows.toString()).commit();','''boolean saved = prefs(ctx).edit().putString(KEY_COLLECTIONS, rows.toString()).commit();
            if (saved) sCollections = rows;
            return saved;''')
    d=d.replace('JSONArray expected) {','JSONArray expected, int tip) {\n        if (tip <= 0) return false;')
    d=d.replace('row.put("phase", "BURIED"); row.put("error", "");','row.put("phase", "BURIED"); row.put("error", ""); row.put("buriedat", tip);')
    d=d.replace('return prefs(ctx).edit().putString(KEY_COLLECTIONS, rows.toString())\n                    .remove("burial:" + tokenid.toLowerCase(java.util.Locale.ROOT)).commit();','''boolean saved = prefs(ctx).edit().putString(KEY_COLLECTIONS, rows.toString())
                    .remove("burial:" + tokenid.toLowerCase(java.util.Locale.ROOT)).commit();
            if (saved) sCollections = rows;
            return saved;''')
    s=change(s,'    private LocalStore() {}','    private LocalStore() {}\n\n'+d)
    old='''    public static void upsert(Context c, JSONObject row) {
        JSONArray arr = load(c);'''
    s=change(s,old,'''    public static synchronized void upsert(Context c, JSONObject row) {
        // Cached rows are mutable. Read the persisted phase before accepting a late callback.
        JSONArray arr = burialCollections(c);''')
    s=change(s,'                try { arr.put(i, row); }','''                String phase = cur.optString("phase");
                if (("BURY".equals(phase) || "BURIED".equals(phase))
                        && !phase.equals(row.optString("phase"))) { sCollections = arr; return; }
                try { arr.put(i, row); }''')
    s=change(s,'        try {\n            JSONArray rows = burialCollections(ctx);','        try {\n            if (burialJournal(ctx, tokenid).length() > 0) return false;\n            JSONArray rows = burialCollections(ctx);')
    p.write_text(s)
    # Same node lifetime boundary used by NFT Wallet's operation guard.
    p=folder/'NodeApi.java';s=p.read_text();s=change(s,'    public boolean isEnabled()', '    public boolean isClosed() { return mReleased || dead(); }\n\n    public boolean isEnabled()')
    s=change(s,'        if (mReleased) {','        if (isClosed()) {')
    s=change(s,'                    if (done[0]) return;','                    if (done[0] || isClosed()) return;')
    p.write_text(s)
    # Atelier emits per-row progress: release its tick lock only at the end of the full row chain.
    p=folder/'MintEngine.java';s=p.read_text()
    guard=(sources/'atelier/burial-engine-lock.inc').read_text()
    s=change(s,'    private MintEngine() {}','    private MintEngine() {}\n\n'+guard)
    s=change(s,'    public static void tick(Context ctx, NodeApi node, Done done) {','''    public static synchronized void tick(Context ctx, NodeApi node, Done done) {
        if (node == null || node.isClosed() || mintInFlight()) return;
        mintNode = node;
        final long generation = ++mintGeneration;
        final Runnable finished = () -> {
            synchronized (MintEngine.class) {
                if (generation == mintGeneration) mintNode = null;
            }
        };''')
    s=change(s,'tickRow(ctx, node, rows, 0, tip, false, done);','tickRow(ctx, node, rows, 0, tip, false, done, finished);')
    s=change(s,'@Override public void fail(String e) { done.done("Mint tick failed: " + e); }','@Override public void fail(String e) { finished.run(); done.done("Mint tick failed: " + e); }')
    s=change(s,'boolean acted, Done done) {','boolean acted, Done done, Runnable finished) {')
    s=change(s,'        if (i >= rows.length()) {\n            if (!acted)', '        if (i >= rows.length()) {\n            finished.run();\n            if (!acted)')
    s=s.replace('tickRow(ctx, node, rows, i + 1, tip, acted, done);','tickRow(ctx, node, rows, i + 1, tip, acted, done, finished);')
    s=change(s,'tickRow(ctx, node, rows, i + 1, tip, true, done);','tickRow(ctx, node, rows, i + 1, tip, true, done, finished);')
    signature='    static void postTxnWithReceipt(NodeApi node, String id, List<String> steps, java.util.function.Consumer<JSONObject> ok, java.util.function.Consumer<String> fail) {'
    s=change(s,signature,signature+'''
        beginPost(node);
        postTxnTracked(node, id, steps, reply -> { endPost(node); ok.accept(reply); }, error -> { endPost(node); fail.accept(error); });
    }

    private static void postTxnTracked(NodeApi node, String id, List<String> steps, java.util.function.Consumer<JSONObject> ok, java.util.function.Consumer<String> fail) {''')
    s=change(s,'        String tid = row.optString("tokenid");\n        LocalStore.clearPending(ctx);','''        String tid = row.optString("tokenid");
        JSONObject current = LocalStore.findById(ctx, row.optLong("id"));
        if (current != null && ("BURY".equals(current.optString("phase")) || "BURIED".equals(current.optString("phase")))) {
            done.done(com.eurobuddha.minimacore.i18n.Language.text(ctx, "BURIED".equals(current.optString("phase")) ? com.eurobuddha.minimacore.i18n.R.string.l10n_nft_burial_confirmed : com.eurobuddha.minimacore.i18n.R.string.l10n_nft_burial_paused)); return;
        }
        LocalStore.clearPending(ctx);''')
    p.write_text(s)
    # A queued airdrop read cannot initiate a new post after a burial has paused this collection.
    p=folder/'AirdropEngine.java';s=p.read_text();s=change(s,'                postedThisTick[0] = true;','''                if (!LocalStore.canDepart(ctx, j.optString("tokenid"))) { advance.run(); return; }
                postedThisTick[0] = true;''');p.write_text(s)
    p=folder/'MainActivity.java';s=p.read_text();s=change(s,'import com.eurobuddha.minimacore.i18n.Language;', 'import com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;')
    a=s.index('    private void renderBury()');b=s.index('    /* ================= STUDIO',a)
    s=s[:a]+(sources/'atelier/burial-ui.inc').read_text()+'\n'+(sources/'atelier/burial-flow.inc').read_text()+'\n'+s[b:]
    s=change(s,'        if ("BURIED".equals(p)) return Design.pill', '        if ("BURY".equals(p)) return Design.pill(this, Language.msg(l10n_nft_burial_paused_title), Design.PILL_ERR);\n        if ("BURIED".equals(p)) return Design.pill')
    s=change(s,'TextView bury = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_gallery_bury), false);','TextView bury = Design.button(this, Language.msg("BURY".equals(m.phase) ? l10n_nft_burial_check_retry : l10n_atelier_gallery_bury), false);')
    p.write_text(s)
