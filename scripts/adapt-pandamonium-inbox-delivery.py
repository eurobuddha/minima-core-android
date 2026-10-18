"""Reuse NFT Wallet's exact receipts, durable phases and bounded recovery for StateNFT order delivery."""
from pathlib import Path

def adapt(root):
    folder=root/'merchinbox/src/main/java/com/eurobuddha/merchinbox'
    nft=root/'NFTwallet/src/main/java/com/eurobuddha/nftwallet'
    evidence=(nft/'BurialEvidence.java').read_text().replace('com.eurobuddha.nftwallet','com.eurobuddha.merchinbox').replace('BurialEvidence','DeliveryEvidence').replace('Incomplete burial evidence','Incomplete delivery evidence')
    extra=(nft/'TransactionEvidence.java').read_text()
    methods=[]
    for first,last in [('    static JSONObject storedObject(', '    /** Validate a saved public intent'),('    static String nonzeroId(', '    /** Validate journal receipts'),('    static JSONObject inclusion(', '    /** Match every input')]:
        a=extra.index(first);b=extra.index(last,a);methods.append(extra[a:b].replace('BurialEvidence','DeliveryEvidence'))
    evidence=evidence[:evidence.rfind('}')]+''.join(methods)+'}\n'
    (folder/'DeliveryEvidence.java').write_text(evidence)
    source=Path(__file__).with_name('pandamonium-sources')/'merchinbox'
    for name in ['DeliveryJournal.java','DeliveryFlow.java']:(folder/name).write_text((source/name).read_text())
    p=root/'merchinbox/src/main/java/com/eurobuddha/comms/MerchDb.java';s=p.read_text()
    s=s.replace('private static final int VERSION = 2;   // v2: + shopid/shopname on orders', 'private static final int VERSION = 3;   // v3: durable StateNFT delivery journal')
    needle='        db.execSQL("CREATE TABLE IF NOT EXISTS " + META + " (k TEXT PRIMARY KEY, v TEXT)");';assert s.count(needle)==1
    s=s.replace(needle,needle+'\n        com.eurobuddha.merchinbox.DeliveryJournal.create(db);')
    needle='    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {';assert s.count(needle)==1
    s=s.replace(needle,needle+'\n        com.eurobuddha.merchinbox.DeliveryJournal.create(db);');p.write_text(s)
    p=folder/'MainActivity.java';s=p.read_text()
    needle='    private final java.util.HashSet<String> nftSending = new java.util.HashSet<>();';assert s.count(needle)==1
    s=s.replace(needle,needle+'''
    private DeliveryJournal deliveryJournal;
    private synchronized DeliveryJournal deliveryJournal() {
        if (deliveryJournal == null) deliveryJournal = new DeliveryJournal(db);
        return deliveryJournal;
    }
    private String deliveredTx(MerchDb.Order order, String[] line) {
        String key = lineKey(order.ref, line);
        if ("0".equals(line[3]) && "0".equals(line[4])) return db.getMeta("nftsent:" + key, "");
        try {
            JSONObject record = deliveryJournal().load(key, order, line);
            return DeliveryJournal.complete(record) ? record.optJSONArray("entries").optJSONObject(0).optString("txpowid") : "";
        } catch (RuntimeException unavailable) { return ""; }
    }
''')
    needle='        if (node != null) node.onDestroy();';assert s.count(needle)==1
    s=s.replace(needle,needle+'\n        if (deliveryJournal != null) deliveryJournal.close();')
    s=s.replace('if (!db.getMeta("nftsent:" + key, "").isEmpty()) continue;', 'if (!deliveredTx(o, line).isEmpty()) continue;')
    s=s.replace('String sentTx = db.getMeta("nftsent:" + key, "");','String sentTx = deliveredTx(o, line);')
    s=s.replace('if (db.getMeta("nftsent:" + lineKey(o.ref, l), "").isEmpty())', 'if (deliveredTx(fresh, l).isEmpty())')
    s=s.replace('        boolean inFlight = nftSending.contains(key);', '        boolean inFlight = nftSending.contains(key);\n        boolean resume = false;\n        if (piece || bundle) {\n            try { resume = deliveryJournal().load(key, o, line) != null\n                    || !db.getMeta("nftposted:" + key, "").isEmpty() || !db.getMeta("nftsent:" + key, "").isEmpty(); }\n            catch (RuntimeException unavailable) { resume = true; }\n        }\n        final boolean recovering = resume;')
    s=s.replace('inFlight ? Language.msg(l10n_chat_sending) : Language.msg(l10n_mall_inbox_send_buyer, label)', 'inFlight ? Language.msg(l10n_chat_sending) : recovering ? Language.msg(l10n_mall_inbox_resume_delivery) : Language.msg(l10n_mall_inbox_send_buyer, label)')
    s=s.replace('.setTitle(Language.msg(l10n_mall_inbox_send_nft))', '.setTitle(Language.msg(recovering ? l10n_mall_inbox_resume_delivery : l10n_mall_inbox_send_nft))')
    s=s.replace('.setMessage(Language.msg(l10n_mall_inbox_send_help, label, shortId(tokenid), shortId(o.payaddr)))', '.setMessage(recovering ? Language.msg(l10n_mall_inbox_resume_help, shortId(o.payaddr)) : Language.msg(l10n_mall_inbox_send_help, label, shortId(tokenid), shortId(o.payaddr)))')
    s=s.replace('.setPositiveButton(Language.msg(l10n_mail_send), (d, w) -> sendNft(o, line, false))', '.setPositiveButton(Language.msg(recovering ? l10n_mall_inbox_resume_delivery : l10n_mail_send), (d, w) -> sendNft(o, line, false))')
    # Both old absence-based completion loops and the unjournalled submitters are replaced together.
    a=s.index('    /** Transfer the exact coin carrying');b=s.index('    private View replyRow(',a)
    s=s[:a]+'''    private void stateNftDeliver(MerchDb.Order o, String[] line, boolean auto) {
        if (!line[3].matches("^[1-9][0-9]*$")) { deliverFailed(o, lineKey(o.ref, line), "bad edition index", auto); return; }
        stateDelivery(o, line, auto);
    }
    private void collectionDeliver(MerchDb.Order o, String[] line, boolean auto) { stateDelivery(o, line, auto); }
    private void stateDelivery(MerchDb.Order o, String[] line, boolean auto) {
        final String key = lineKey(o.ref, line);
        if (!nftSending.add(key)) return;
        refreshOrderIfTopmost(o.ref);
        enqueueTx(() -> new DeliveryFlow(node, deliveryJournal(), io, ui,
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

''' +s[b:]
    # Posting is now recorded by DeliveryJournal's atomic phase/receipt methods.
    a=s.index('    /** Serialize post-record persistence');b=s.index('    private void markSentAndMaybeAdvance(',a);s=s[:a]+s[b:]
    p.write_text(s)
    p=root/'merchinbox/build.gradle';s=p.read_text();needle="    testImplementation 'junit:junit:4.13.2'";assert s.count(needle)==1;s=s.replace(needle,needle+"\n    testImplementation 'org.robolectric:robolectric:4.14.1'");p.write_text(s)
