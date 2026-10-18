"""Reuse Inbox's prepared native-send journal and recovery for buyer payments."""
from pathlib import Path

def adapt(root):
    source=Path(__file__).with_name('pandamonium-sources')/'merchshop'
    folder=root/'merchshop/src/main/java/com/eurobuddha/merchshop'
    for name in ['PaymentJournal.java','PaymentFlow.java']:(folder/name).write_text((source/name).read_text())
    comms=root/'merchshop/src/main/java/com/eurobuddha/comms'
    (comms/'SendReceipt.java').write_text((source/'SendReceipt.java').read_text())
    p=comms/'MerchDb.java';s=p.read_text()
    s=s.replace('private static final int VERSION = 2;   // v2: + shopid/shopname on orders','private static final int VERSION = 3;   // v3: durable buyer payment journal')
    for signature in ['    @Override public void onCreate(SQLiteDatabase db) {','    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {']:
        assert s.count(signature)==1;s=s.replace(signature,signature+'\n        com.eurobuddha.merchshop.PaymentJournal.create(db);')
    p.write_text(s)
    p=comms/'CommsTransport.java';s=p.read_text()
    a=s.index('                // status:true = accepted;');b=s.index('                } else {',a)
    s=s[:a]+'''                String receipt = SendReceipt.sendReceipt(j);
                if (receipt != null) {
                    cb.onSent(receipt);
'''+s[b:]
    s=s.replace('j.optString("error", "the node rejected the send")','j == null ? "Node result unavailable. Outcome unknown." : j.optString("error", "Node result unavailable. Outcome unknown.")')
    p.write_text(s)
    p=comms/'NodeApi.java';s=p.read_text()
    donor=(root/'merchinbox/src/main/java/com/eurobuddha/comms/NodeApi.java').read_text()
    a=donor.index('    private static boolean isTooLong(');b=donor.index('    private static final long READ_TIMEOUT_MS',a)
    helper=donor[a:b].replace('.toLowerCase()', '.toLowerCase(java.util.Locale.ROOT)')
    needle='    private static final long READ_TIMEOUT_MS';s=s.replace(needle,'    public static final String ERR_TOO_LONG = "TOO_LONG";\n'+helper+needle,1)
    needle='                    if (cb != null) cb.onResult(zResponse);';assert s.count(needle)==1
    s=s.replace(needle,'                    if (isTooLong(zResponse)) { if (cb != null) cb.onError(ERR_TOO_LONG); return; }\n'+needle);p.write_text(s)
    p=comms/'NodeApi.java';s=p.read_text();needle='    public void onDestroy() {';assert s.count(needle)==1
    p.write_text(s.replace(needle,(source/'prepared-file-cleanup.inc').read_text()+needle))
    p=root/'merchshop/build.gradle';s=p.read_text();needle="    implementation project(':localization')";assert s.count(needle)==1
    p.write_text(s.replace(needle,needle+"\n    implementation project(':integrated-NFTwallet')"))
    p=folder/'MainActivity.java';s=p.read_text()
    needle='    private void confirmAndPlace(';s=s.replace(needle,(source/'payment-ui.inc').read_text()+'\n'+needle,1)
    needle='        io.shutdownNow();';assert s.count(needle)==1;s=s.replace(needle,'        if (paymentJournal != null) paymentJournal.close();\n'+needle)
    a=s.index('        io.execute(() -> {',s.index('    private void placeOrder('));b=s.index('    // ---- my orders ----',a)
    s=s[:a]+'''        final View checkout = stack.peek();
        final Catalog shop = catalog;
        io.execute(() -> {
            try {
                if (!paymentJournal().saveOrder(m, vendorAddress)) throw PaymentJournal.invalid();
            } catch (RuntimeException failed) {
                ui.post(() -> { dismiss(progress); if (paymentUiLive()) toast(Language.msg(l10n_mall_shop_payment_save_failed)); });
                return;
            }
            CommsTransport.sendMessage(node, crypto, m.to, m.toWire(), new CommsTransport.SendCb() {
                @Override public void onSent(String txid) {
                    io.execute(() -> {
                        try { paymentJournal().orderSent(ref); }
                        catch (RuntimeException failed) {
                            ui.post(() -> { dismiss(progress); if (paymentUiLive()) toast(Language.msg(l10n_mall_shop_payment_unavailable)); }); return;
                        }
                        ui.post(() -> { if (paymentUiLive()) startPayment(ref, progress, checkout, shop); });
                    });
                }
                @Override public void onFailed(String e) {
                    ui.post(() -> { dismiss(progress); if (paymentUiLive()) toast(Language.msg(l10n_mall_shop_order_unknown)); });
                }
            });
        });
    }

    private void retryPayment(final MerchDb.Order o) {
        try {
            MerchDb.Order current = paymentJournal().paymentOrder(o.ref);
            if (!java.util.Objects.equals(o.amount, current.amount) || !java.util.Objects.equals(o.tokenid, current.tokenid)
                    || !java.util.Objects.equals(o.counterparty, current.counterparty) || !java.util.Objects.equals(o.items, current.items)
                    || !paymentJournal().available(current)) throw PaymentJournal.invalid();
        } catch (RuntimeException unavailable) { toast(Language.msg(l10n_mall_shop_payment_unavailable)); return; }
        final AlertDialog progress = languageDialog().setTitle(Language.msg(l10n_mall_shop_order))
                .setMessage(Language.msg(l10n_mall_shop_payment_checking)).setCancelable(false).create();
        progress.show(); startPayment(o.ref, progress, null, null);
    }

'''+s[b:]
    a=s.index('            if ("1".equals(db.getMeta("unpaid:" + o.ref, ""))) {');b=s.index('\n        }',a)
    s=s[:a]+'            addPaymentStatus(body, o);'+s[b:];p.write_text(s)
