"""Reuse UTXO local history presentation with NFT journal outcomes and existing sheets."""
from pathlib import Path

def adapt(root):
    folder=root/'NFTwallet/src/main/java/com/eurobuddha/nftwallet'
    source=Path(__file__).with_name('pandamonium-sources')/'NFTwallet'
    (folder/'LocalHistory.java').write_text((source/'LocalHistory.java').read_text())
    p=folder/'HistoryView.java';s=p.read_text()
    old="""        container.addView(filterBar());
        List<NodeTx> rows = act.history().loadNodeTx(CAP);"""
    new="""        List<NodeTx> rows = act.history().loadNodeTx(CAP);
        List<LocalHistory.Entry> local=LocalHistory.visible(LocalHistory.read(act.history(),CAP),rows);
        if(!local.isEmpty()) {
            localHeading(container,Language.msg(l10n_nft_history_local_activity));
            for(LocalHistory.Entry entry:local)container.addView(localRow(entry));
            localHeading(container,Language.msg(l10n_nft_history_node_history));
        }
        container.addView(filterBar());"""
    assert s.count(old)==1;s=s.replace(old,new)
    marker='    // ----- detail dialog (identical to the History app) -----'
    assert s.count(marker)==1;s=s.replace(marker,(source/'history-local.inc').read_text()+'\n'+marker)
    p.write_text(s)
