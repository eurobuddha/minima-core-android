"""Adapt AtomiX's draft/focus retention to Inbox order replies and async refresh ownership."""
from pathlib import Path

def adapt(root):
    folder=root/'merchinbox/src/main/java/com/eurobuddha/merchinbox'
    (folder/'ReplyDrafts.java').write_text((Path(__file__).with_name('pandamonium-sources')/'merchinbox/ReplyDrafts.java').read_text())
    p=folder/'MainActivity.java';s=p.read_text()
    def change(old,new):
        nonlocal s
        assert s.count(old)==1,old[:100]
        s=s.replace(old,new)
    change('    private DeliveryJournal deliveryJournal;', '    private DeliveryJournal deliveryJournal;\n    private final ReplyDrafts replyDrafts = new ReplyDrafts();')
    change('        super.onCreate(b);', '        super.onCreate(b);\n        replyDrafts.restore(b);')
    change('        showOrders();\n\n        node =', '''        showOrders();
        if (b != null) {
            String orderRef = b.getString("mall.openOrder", "");
            if (!orderRef.isEmpty() && db.order(orderRef) != null) push(buildOrderDetail(orderRef));
        }

        node =''')
    change('    @Override protected void onDestroy() {', '''    @Override protected void onSaveInstanceState(Bundle state) {
        replyDrafts.save(state);
        View top = stack.peek(); Object tag = top == null ? null : top.getTag();
        if (tag instanceof String && ((String) tag).startsWith("order:")) state.putString("mall.openOrder", ((String) tag).substring(6));
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        replyDrafts.close();''')
    change('adoptIdentity(id); refreshTop(buildOrders()); requestScan();','adoptIdentity(id); refreshVisibleOrderOrList(); requestScan();')
    change('''            View top = stack.peek();
            Object tag = top == null ? null : top.getTag();
            if (stack.size() == 1) refreshTop(buildOrders());
            else if (tag instanceof String && ((String) tag).startsWith("order:")) refreshTop(buildOrderDetail(((String) tag).substring(6)));''', '            refreshVisibleOrderOrList();')
    change('''        if (tag instanceof String && tag.equals("order:" + ref)) refreshTop(buildOrderDetail(ref));''','''        if (tag instanceof String && tag.equals("order:" + ref)) {
            if (!replyDrafts.defer(ref)) refreshTop(buildOrderDetail(ref));
        }''')
    change('''    // ---- NFT delivery ----''','''    private void refreshVisibleOrderOrList() {
        if (isFinishing() || isDestroyed()) return;
        View top = stack.peek(); Object tag = top == null ? null : top.getTag();
        if (tag instanceof String && ((String) tag).startsWith("order:")) refreshOrderIfTopmost(((String) tag).substring(6));
        else if (stack.size() == 1) refreshTop(buildOrders());
    }

    // ---- NFT delivery ----''')
    change('''    private void refreshOrderIfTopmost(String ref) {
        View top''','''    private void refreshOrderIfTopmost(String ref) {
        if (isFinishing() || isDestroyed()) return;
        View top''')
    change('''        final EditText box = input(Language.msg(l10n_mall_inbox_reply));''','''        final EditText box = input(Language.msg(l10n_mall_inbox_reply));
        replyDrafts.bind(o.ref, box, () -> refreshOrderIfTopmost(o.ref));''')
    change('''            String txt = box.getText().toString().trim();
            if (txt.isEmpty()) return;
            Language.setText(box, "");
            sendReply(o, txt);''','''            if (crypto == null || myId == null) { toast(Language.msg(l10n_mall_shop_connect_first)); return; }
            ReplyDrafts.Submission ticket = replyDrafts.begin(o.ref);
            if (ticket != null) sendReply(o, ticket);''')
    a=s.index('    private void sendReply(');b=s.index('    // ---- CSV export ----',a)
    s=s[:a]+'''    private void sendReply(final MerchDb.Order o, final ReplyDrafts.Submission ticket) {
        final String rid = MailText.randomId();
        final String text = ticket.text();
        try {
            io.execute(() -> {
                try {
                    if (!db.insertChat(o.ref, false, text, rid, System.currentTimeMillis())) throw new IllegalStateException("Reply not saved");
                } catch (RuntimeException unavailable) {
                    ui.post(() -> replySaveFailed(ticket));
                    return;
                }
                MerchMessage m = new MerchMessage();
                m.type = MerchMessage.REPLY; m.ref = o.ref; m.message = text;
                m.from = myId; m.to = o.counterparty; m.randomid = rid; m.date = System.currentTimeMillis();
                CommsTransport.sendMessage(node, crypto, o.counterparty, m.toWire(), new CommsTransport.SendCb() {
                    @Override public void onSent(String txid) {}
                    @Override public void onFailed(String e) { ui.post(() -> {
                        if (!isFinishing() && !isDestroyed()) toast(Language.msg(l10n_mall_inbox_reply_error, InboxMessages.message(e)));
                    }); }
                });
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    replyDrafts.saved(ticket);
                    refreshOrderIfTopmost(o.ref);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException closed) { replySaveFailed(ticket); }
    }
    private void replySaveFailed(ReplyDrafts.Submission ticket) {
        replyDrafts.failed(ticket);
        if (!isFinishing() && !isDestroyed()) toast(Language.msg(l10n_mall_inbox_reply_save_failed));
    }

''' +s[b:]
    p.write_text(s)
