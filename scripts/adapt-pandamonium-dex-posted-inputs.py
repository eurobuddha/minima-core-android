"""Keep account-session checks while adopting the PandaDEX donor's durable input claims."""
from pathlib import Path
def adapt(root):
    source=Path(__file__).with_name('pandamonium-sources')/'pandadex'
    folder=root/'pandadex/src/main/java/com/eurobuddha/pandadex'
    (folder/'CoinLock.java').write_text((source/'CoinLock.java').read_text())
    p=folder/'DexTxn.java';s=p.read_text()
    if 'CoinLock.loadPosted(node.context())' not in s:
        old='if (!CoinLock.claimInputs(inputs)) {';assert s.count(old)==1
        s=s.replace(old,'if (!CoinLock.loadPosted(node.context()) || !CoinLock.claimInputs(inputs)) {')
    if 'cb.beforePost() && CoinLock.rememberPosting(inputs,txid)' not in s:
        old='return cb.beforePost();';assert s.count(old)==1
        s=s.replace(old,'return cb.beforePost() && CoinLock.rememberPosting(inputs,txid);')
    if 'CoinLock.checkAvailable(commands,inputs,0' not in s:
        old='            CmdChain.run(narrated, new ArrayList<>(steps), "txndelete id:" + txid, new CmdChain.Done() {';assert s.count(old)==1
        s=s.replace(old,'            CoinLock.checkAvailable(commands,inputs,0,available->{\n                if(!available){gated.onFailed("An input is spent, pending, or unreadable. Refresh before retrying. Nothing was signed.");return;}\n'+old)
        old='                public void fail(String message) { gated.onFailed(message); }\n            });';assert s.count(old)==1;s=s.replace(old,old+'\n            });')
    s=s.replace('An input is already in another queued transaction or appears twice. Wait and refresh.','An input is queued, submitted, or has an uncertain outcome. Wait for confirmation and refresh.')
    p.write_text(s)
