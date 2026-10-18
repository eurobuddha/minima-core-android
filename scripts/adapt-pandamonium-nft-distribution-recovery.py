"""Reuse the existing journal and UTXO batch flow with confirmed durable distribution progress."""
from pathlib import Path

def adapt(root):
    folder=root/'NFTwallet/src/main/java/com/eurobuddha/nftwallet'
    source=Path(__file__).with_name('pandamonium-sources')/'NFTwallet'
    for name in ['DistributeJob.java','DistributeManager.java']:(folder/name).write_text((source/name).read_text())
    p=folder/'MainActivity.java';s=p.read_text();needle='        if (node != null) node.onDestroy();';assert s.count(needle)==1
    s=s.replace(needle,needle+'\n        if (distribute != null) distribute.close();');p.write_text(s)
