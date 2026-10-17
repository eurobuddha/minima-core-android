"""Reserve native consolidation plans through the existing exact custom transaction builder."""
from pathlib import Path
import shutil

def adapt(root):
    project=Path(__file__).resolve().parents[1]
    folder=root/'NFTwallet/src/main/java/com/eurobuddha/nftwallet'
    shutil.copy2(project/'scripts/pandamonium-sources/NFTwallet/PreparedConsolidation.java',folder/'PreparedConsolidation.java')
    def change(name,old,new):
        p=folder/(name+'.java');s=p.read_text();assert s.count(old)==1,('NFT consolidation drift',name,old);p.write_text(s.replace(old,new))
    change('TxnBuilder','public Out(String address, String amount) { this.address = address; this.amount = amount; }','''public final Boolean storestate;
        public Out(String address, String amount) { this(address,amount,null); }
        public Out(String address,String amount,Boolean storestate) { this.address=address;this.amount=amount;this.storestate=storestate; }''')
    change('TxnBuilder','        public OutCoin(String coinid, String address, String miniaddress, String amount) {\n            this.coinid = coinid;','        public final Boolean storestate;\n        public OutCoin(String coinid,String address,String miniaddress,String amount) { this(coinid,address,miniaddress,amount,null); }\n        public OutCoin(String coinid, String address, String miniaddress, String amount,Boolean storestate) {\n            this.storestate=storestate;\n            this.coinid = coinid;')
    change('TxnBuilder','                + (minima ? "" : " tokenid:" + tokenid);','                + (minima ? "" : " tokenid:" + tokenid)\n                + (o.has("storestate")?" storestate:"+o.optBoolean("storestate"):"");')
    p=folder/'WalletTools.java';s=p.read_text();start=s.index('        act.node().cmd("consolidate tokenid:" + tokenid,');end=s.index('\n    /** Merge exactly the selected coins',start)
    s=s[:start]+'''        new PreparedConsolidation(act,tokenid,internalid,new TxnBuilder.Done() {
            @Override public void onPosted(String txpowid,List<TxnBuilder.OutCoin> outputs) {
                act.history().update(internalid,HistoryDb.STATUS_POSTED,null,"");
                status.show(Language.msg(l10n_utxo_consolidate_submitted),true);act.reload();
            }
            @Override public void onFailed(String message) {
                if(NodeApi.ERR_NOT_ENABLED.equals(message))message="The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry.";
                act.history().update(internalid,HistoryDb.STATUS_ERROR,null,message);
                status.show(Language.msg(l10n_utxo_failed,NftMessages.message(message)),false);
            }
            @Override public void onUnknown(String message) {
                act.history().update(internalid,HistoryDb.STATUS_UNKNOWN,null,message);
                status.show(NftMessages.message(message),false);act.reload();
            }
        }).run();
    }
'''+s[end:];p.write_text(s)
