"""Reuse Explorer detail lookups and strict wallet evidence without mutating the journal."""
from pathlib import Path

def adapt(root):
    source=Path(__file__).with_name('pandamonium-sources')/'NFTwallet/HistoryChainCheck.java'
    for app,pkg in [('utxo','utxo'),('NFTwallet','nftwallet')]:
        folder=root/app/'src/main/java/com/eurobuddha'/pkg
        (folder/'HistoryChainCheck.java').write_text(source.read_text().replace('com.eurobuddha.nftwallet','com.eurobuddha.'+pkg))
        p=folder/'TransactionEvidence.java';s=p.read_text()
        marker='    static JSONObject inclusion(JSONObject reply) {'
        assert s.count(marker)==1
        s=s.replace(marker,'''    static JSONObject inclusion(JSONObject reply) {
        JSONObject proof=observedInclusion(reply);
        if(new java.math.BigInteger(proof.optString("confirmations")).compareTo(java.math.BigInteger.valueOf(3))<0)throw invalid();
        return proof;
    }
    /** Complete current-chain observation; display may show depths below settlement's three blocks. */
    static JSONObject observedInclusion(JSONObject reply) {''')
        old='depth.compareTo(java.math.BigInteger.valueOf(3)) < 0';assert s.count(old)==1
        s=s.replace(old,'depth.signum() < 0');p.write_text(s)
        p=folder/'HistoryView.java';s=p.read_text()
        def change(old,new,count=1):
            nonlocal s
            assert s.count(old)==count,(app,old,s.count(old));s=s.replace(old,new)
        change('Language.msg(l10n_nft_history_confirmed)','Language.msg(l10n_wallet_history_previously_confirmed)')
        change('Language.setText(right, "#" + n.block);','Language.setText(right, Language.msg(l10n_wallet_history_saved_block, String.valueOf(n.block)));')
        change('kv(box, Language.msg(l10n_history_block), String.valueOf(n.block));','kv(box, Language.msg(l10n_wallet_history_recorded_block), String.valueOf(n.block));\n        HistoryChainCheck.show(act,box,n.txpowid);')
        if app=='utxo':
            change('kv(box, Language.msg(l10n_utxo_status), statusWord(r));','kv(box, Language.msg(l10n_utxo_status), statusWord(r));\n        HistoryChainCheck.show(act,box,r.txnid);')
            change('failed ? Design.red() : HistoryDb.STATUS_CONFIRMED.equals(r.status) ? 0xFF1EA85A : Design.amber()', 'failed ? Design.red() : Design.amber()')
            change('failed ? "✗" : HistoryDb.STATUS_CONFIRMED.equals(r.status) ? "✓" : "⏳"','failed ? "✗" : "⏳"')
        else:
            change('kv(box, Language.msg(l10n_utxo_status), localStatus(entry));','kv(box, Language.msg(l10n_utxo_status), localStatus(entry));\n        HistoryChainCheck.show(act,box,entry.txpowid());')
            change('entry.confirmed() ? Design.success() : failed ? Design.red() : Design.amber()','failed ? Design.red() : Design.amber()')
            change('entry.confirmed() ? "✓" : failed ? "✗" : "⏳"','failed ? "✗" : "⏳"')
        p.write_text(s)
