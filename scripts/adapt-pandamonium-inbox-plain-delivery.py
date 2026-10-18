"""Reuse NFT Wallet's native prepared-send evidence directly for ordinary Inbox NFT orders."""
from pathlib import Path

def adapt(root):
    source=Path(__file__).with_name('pandamonium-sources')
    nft=root/'NFTwallet/src/main/java/com/eurobuddha/nftwallet'
    (nft/'OrderPaymentEvidence.java').write_text((source/'NFTwallet/OrderPaymentEvidence.java').read_text())
    folder=root/'merchinbox/src/main/java/com/eurobuddha/merchinbox'
    for name in ['PlainDeliveryJournal.java','PlainDeliveryFlow.java']:(folder/name).write_text((source/'merchinbox'/name).read_text())
    p=folder/'DeliveryJournal.java';s=p.read_text()
    s=s.replace('    private void fresh(', '    void fresh(')
    needle='            save(db,key,row,insert);db.setTransactionSuccessful();return row;';assert s.count(needle)==1
    s=s.replace(needle,'            PlainDeliveryJournal.assertAvailable(db,claiming,null);\n'+needle)
    needle='    boolean owned(JSONObject e)';assert s.count(needle)==1
    s=s.replace(needle,'''    static boolean reserved(JSONObject e) {
        return !NOT_SUBMITTED.equals(e.optString("phase"))
                && !(PREPARED.equals(e.optString("phase"))&&!LIVE.contains(e.optString("owner")));
    }
    boolean owned(JSONObject e)''');p.write_text(s)
    p=root/'merchinbox/src/main/java/com/eurobuddha/comms/MerchDb.java';s=p.read_text()
    s=s.replace('private static final int VERSION = 3;   // v3: durable StateNFT delivery journal','private static final int VERSION = 4;   // v4: ordinary NFT delivery journal')
    needle='        com.eurobuddha.merchinbox.DeliveryJournal.create(db);';assert s.count(needle)==2
    s=s.replace(needle,needle+'\n        com.eurobuddha.merchinbox.PlainDeliveryJournal.create(db);');p.write_text(s)
    p=root/'merchinbox/build.gradle';s=p.read_text();needle="    implementation project(':localization')";assert s.count(needle)==1
    s=s.replace(needle,needle+"\n    implementation project(':integrated-NFTwallet')");p.write_text(s)
    p=folder/'MainActivity.java';s=p.read_text()
    def change(old,new):
        nonlocal s
        assert s.count(old)==1,old[:80]
        s=s.replace(old,new)
    change('    private DeliveryJournal deliveryJournal;', '''    private DeliveryJournal deliveryJournal;
    private PlainDeliveryJournal plainDeliveryJournal;
    private synchronized PlainDeliveryJournal plainDeliveryJournal() {
        if (plainDeliveryJournal == null) plainDeliveryJournal = new PlainDeliveryJournal(db, deliveryJournal());
        return plainDeliveryJournal;
    }''')
    change('        if ("0".equals(line[3]) && "0".equals(line[4])) return db.getMeta("nftsent:" + key, "");','''        if ("0".equals(line[3]) && "0".equals(line[4])) {
            try {
                JSONObject record = plainDeliveryJournal().load(key, order, line);
                return PlainDeliveryJournal.complete(record) ? record.optJSONObject("receipt").optString("txpowid") : "";
            } catch (RuntimeException unavailable) { return ""; }
        }''')
    change('        if (deliveryJournal != null) deliveryJournal.close();','        if (deliveryJournal != null) deliveryJournal.close();\n        if (plainDeliveryJournal != null) plainDeliveryJournal.close();')
    change('''        if (piece || bundle) {
            try { resume = deliveryJournal().load(key, o, line) != null''','''        {
            try { resume = (piece || bundle ? deliveryJournal().load(key, o, line) : plainDeliveryJournal().load(key, o, line)) != null''')
    change('''     *  replayed; plain NFTs go via `send` (atomic, mark-on-post). auto=true is the headless''','''     *  replayed; plain NFTs use a verified native unsigned transaction. auto=true is the headless''')
    a=s.index('        if (!nftSending.add(key)) return;',s.index('    private void sendNft('));b=s.index('    // ---- StateNFT piece delivery',a)
    s=s[:a]+'''        if (!nftSending.add(key)) return;
        refreshOrderIfTopmost(o.ref);
        enqueueTx(() -> new PlainDeliveryFlow(node, plainDeliveryJournal(), io, ui,
                () -> !isFinishing() && !isDestroyed(), new DeliveryFlow.Listener() {
            public void posted() { txDone(); }
            public void confirmed() { markSentAndMaybeAdvance(o, key, "sent", auto); }
            public void unconfirmed() {
                nftSending.remove(key);
                CharSequence message = Language.msg(l10n_mall_inbox_delivery_unconfirmed);
                if (auto) notifyDelivery(o.ref, false, message); else toast(message);
                refreshOrderIfTopmost(o.ref);
            }
        }).start(o, key, line));
    }

'''+s[b:]
    s=s.replace('// ---- StateNFT piece delivery (state-replay transfer, confirm by coin departure) ----','// ---- StateNFT piece delivery (state-replay transfer, exact receipt and inclusion) ----')
    p.write_text(s)
