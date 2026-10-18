"""Use the node's unsigned-send flow before signing a quick transfer."""
from pathlib import Path
import shutil

def adapt(root):
    project=Path(__file__).resolve().parents[1]
    folder=root/'NFTwallet/src/main/java/com/eurobuddha/nftwallet'
    shutil.copy2(project/'scripts/pandamonium-sources/NFTwallet/PreparedSend.java',folder/'PreparedSend.java')
    p=folder/'NodeApi.java';s=p.read_text();anchor='    public void onDestroy() {'
    assert s.count(anchor)==1,'NFT prepared-file cleanup drift'
    s=s.replace(anchor,(project/'scripts/pandamonium-sources/NFTwallet/prepared-file-cleanup.inc').read_text()+anchor)
    p.write_text(s)
    p=folder/'SendView.java';s=p.read_text()
    start=s.index('        StringBuilder cmd = new StringBuilder("send address:")')
    end=s.index('\n    // ----- expert confirm breakdown -----',start)
    s=s[:start]+'''        new PreparedSend(act,recipient,amountStr,burnStr,split,tokenid,tokenName.toString(),new TxnBuilder.Done() {
            @Override public void onPosted(String txpowid,List<TxnBuilder.OutCoin> outputs) {
                sending=false;previewBtn.setEnabled(true);
                status(Language.msg(l10n_nft_send_sent,Util.tidyAmount(amountStr),tokenName,recipient),true);
                if(matchesDraft(submittedFields,submittedCoins)) {
                    Language.setText(addrInput,"");Language.setText(amountInput,"");Language.setText(burnInput,"");Language.setText(splitInput,"");
                }
                act.reload();
            }
            @Override public void onFailed(String message) {
                sending=false;previewBtn.setEnabled(true);
                if(NodeApi.ERR_NOT_ENABLED.equals(message))message="The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry.";
                status(Language.msg(l10n_utxo_failed,NftMessages.message(message)),false);
            }
            @Override public void onUnknown(String message) {
                sending=false;previewBtn.setEnabled(true);
                status(NftMessages.message(message),false);act.reload();
            }
        }).run();
    }
''' +s[end:]
    s=s.replace('/** Posts the node-level `send` and reports the outcome. */','/** Prepare the node-selected transaction, persist its intent, then sign and post it. */')
    p.write_text(s)
