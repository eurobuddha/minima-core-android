"""Reuse NFT tool review/controllers, retaining UTXO's themed native dialogs."""
from pathlib import Path

def adapt(root):
    folder=root/'utxo/src/main/java/com/eurobuddha/utxo'
    donor=(root/'NFTwallet/src/main/java/com/eurobuddha/nftwallet/WalletTools.java').read_text()
    donor=donor.replace('NftMessages','UtxoMessages').replace('NftText','UtxoText').replace('BurialEvidence','TransactionFields')
    p=folder/'WalletTools.java';s=p.read_text()
    def change(old,new,count=1):
        nonlocal s
        assert s.count(old)==count,('UTXO tool source drift',old,s.count(old));s=s.replace(old,new)
    a=donor.index('    /** Reuse SendView');b=donor.index('    // ---- shared helpers ----',a)
    change('    // ---- shared helpers ----',donor[a:b]+'    // ---- shared helpers ----')
    change('    private final Status status;','    private final Status status;\n    private long statusGeneration;')
    # Reuse the reviewed controller bodies exactly. Their UI callers retain native dialogs.
    for name in ['runBuilder','doSplit','autoConsolidate','mergeSelected','doDistribute','untrackNext']:
        marker='    private void '+name+'('
        a=s.index(marker);b=s.index('\n    }',a)+len('\n    }')
        c=donor.index(marker);d=donor.index('\n    }',c)+len('\n    }')
        s=s[:a]+donor[c:d]+s[b:]
    change('        final List<Coin> sel = act.selectedCoins();','        final Review review = new Review();\n        final Status status = review.status;\n        final List<Coin> sel = review.coins;',4)
    change('doSplit(sel, count.getText().toString())','doSplit(review, count.getText().toString())')
    change('(d, w) -> autoConsolidate(Util.MINIMA_TOKENID)','(d, w) -> { if(review.accept(false)) autoConsolidate(Util.MINIMA_TOKENID, status); }')
    change('(d, w) -> mergeSelected(sel)','(d, w) -> mergeSelected(review)')
    change('doDistribute(sel, count.getText().toString(), amount.getText().toString())','doDistribute(review, count.getText().toString(), amount.getText().toString())')
    change('(d, w) -> untrackNext(sel, 0)','(d, w) -> { if(review.accept(false)) untrackNext(sel, 0, status); }')
    p.write_text(s)
