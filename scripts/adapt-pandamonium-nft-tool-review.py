"""Reuse the existing Send selection review for Tools confirmations and delayed callbacks."""
from pathlib import Path

def adapt(root):
    project=Path(__file__).resolve().parents[1];folder=root/'NFTwallet/src/main/java/com/eurobuddha/nftwallet'
    def change(s,old,new,count=1):
        assert s.count(old)==count,('NFT tool review drift',old,s.count(old));return s.replace(old,new)
    p=folder/'TxnUtil.java';s=p.read_text();s=change(s,'    private TxnUtil() {}','    private TxnUtil() {}\n\n'+'    /** Literal public selection fields: refreshes and edits must not alter an accepted review. */\n    static String[] selectionSnapshot(List<Coin> coins) {\n        List<String> values=new ArrayList<>();\n        for(Coin coin:coins) {\n            if(coin==null){values.add(null);continue;}\n            values.add(coin.coinid);values.add(coin.tokenid);values.add(coin.amount);values.add(coin.rawAmount);\n            values.add(coin.address);values.add(coin.miniaddress);\n            values.add(Boolean.toString(coin.confirmed));values.add(Boolean.toString(coin.sendable));values.add(Boolean.toString(coin.spent));\n        }\n        return values.toArray(new String[0]);\n    }\n\n');p.write_text(s)
    p=folder/'SendView.java';s=p.read_text();s=change(s,'    private String[] selectionSnapshot() {\n        List<String> values = new ArrayList<>();\n        for (Coin coin : act.selectedCoins()) {\n            values.add(coin.coinid); values.add(coin.tokenid); values.add(coin.amount);\n            values.add(Boolean.toString(coin.sendable));\n        }\n        return values.toArray(new String[0]);\n    }','    private String[] selectionSnapshot() { return TxnUtil.selectionSnapshot(act.selectedCoins()); }');p.write_text(s)
    p=folder/'WalletTools.java';s=p.read_text()
    s=change(s,'    // ---- shared helpers ----', (project/'scripts/pandamonium-sources/NFTwallet/tool-review.inc').read_text()+'    // ---- shared helpers ----')
    s=change(s,'        final List<Coin> sel = act.selectedCoins();','        final Review review = new Review();\n        final List<Coin> sel = review.coins;',4)
    s=change(s,'() -> doSplit(sel, count.getText().toString())','() -> doSplit(review, count.getText().toString())')
    s=change(s,'    private void doSplit(List<Coin> sel, String cStr) {','    private void doSplit(Review review, String cStr) {\n        if (!review.accept(true)) return;\n        final List<Coin> sel = review.coins;')
    s=change(s,'        final String tokenid = act.selectedTokenid();','        final String tokenid = review.tokenid;',3)
    s=change(s,'() -> autoConsolidate(Util.MINIMA_TOKENID)','() -> { if(review.accept(false)) autoConsolidate(Util.MINIMA_TOKENID); }')
    s=change(s,'() -> mergeSelected(sel)','() -> mergeSelected(review)')
    s=change(s,'    private void mergeSelected(final List<Coin> sel) {','    private void mergeSelected(Review review) {\n        if (!review.accept(true)) return;\n        final List<Coin> sel = review.coins;')
    s=change(s,'                doDistribute(sel, count.getText().toString(), amount.getText().toString())','                doDistribute(review, count.getText().toString(), amount.getText().toString())')
    s=change(s,'    private void doDistribute(final List<Coin> sel, String cStr, String aStr) {','    private void doDistribute(Review review, String cStr, String aStr) {\n        if (!review.accept(true)) return;\n        final List<Coin> sel = review.coins;')
    s=change(s,'            @Override public void onAddresses(List<String> addrs) {\n                List<TxnBuilder.Out> outs','            @Override public void onAddresses(List<String> addrs) {\n                if (!review.current(true)) return;\n                List<TxnBuilder.Out> outs')
    s=change(s,'            @Override public void onResult(JSONObject json) {\n                JSONObject r = json.optJSONObject("response");','            @Override public void onResult(JSONObject json) {\n                if (!review.current(true)) return;\n                JSONObject r = json.optJSONObject("response");')
    s=change(s,'                act.distribute().start(sel, addrs, perStr, tokenid, tokenName);','                if (!review.current(true)) { act.distribute().cancelReserve(); return; }\n                act.distribute().start(sel, addrs, perStr, tokenid, tokenName);')
    s=change(s,'() -> untrackNext(sel, 0)','() -> { if(review.accept(false)) untrackNext(sel, 0); }')
    s=change(s,'        status.show(Language.msg(l10n_utxo_posting), true);','        final String[] submittedCoins=TxnUtil.selectionSnapshot(inputs);\n        status.show(Language.msg(l10n_utxo_posting), true);')
    s=change(s,'                act.clearSelection();','                if(java.util.Arrays.equals(submittedCoins,TxnUtil.selectionSnapshot(act.selectedCoins()))) act.clearSelection();')
    s=change(s,'            act.clearSelection();','            if(java.util.Arrays.equals(TxnUtil.selectionSnapshot(sel),TxnUtil.selectionSnapshot(act.selectedCoins()))) act.clearSelection();')
    p.write_text(adapt_status(s))

def adapt_status(s):
    """Carry the accepted review's display owner through every asynchronous tool helper."""
    def change(old,new,count=1):
        nonlocal s
        assert s.count(old)==count,('NFT status source drift',old,s.count(old))
        s=s.replace(old,new)
    change('    private final Status status;','    private final Status status;\n    private long statusGeneration;')
    change('        final Review review = new Review();','        final Review review = new Review();\n        final Status status = review.status;',4)
    for signature in ['private void doSplit(Review review, String cStr) {','private void mergeSelected(Review review) {','private void doDistribute(Review review, String cStr, String aStr) {']:
        change(signature,signature+'\n        final Status status = review.status;')
    change('String internalid, CharSequence okLabel) {','String internalid, CharSequence okLabel, final Status status) {')
    change('l10n_utxo_split_done, fn));','l10n_utxo_split_done, fn), status);')
    change('l10n_utxo_merged_done, sel.size()));','l10n_utxo_merged_done, sel.size()), status);')
    change('autoConsolidate(Util.MINIMA_TOKENID);','autoConsolidate(Util.MINIMA_TOKENID, status);')
    change('private void autoConsolidate(String tokenid) {','private void autoConsolidate(String tokenid, final Status status) {')
    change('untrackNext(sel, 0);','untrackNext(sel, 0, status);')
    change('private void untrackNext(final List<Coin> sel, final int i) {','private void untrackNext(final List<Coin> sel, final int i, final Status status) {')
    change('untrackNext(sel, i + 1);','untrackNext(sel, i + 1, status);',2)
    return s
