"""Check Inbox delivery-record writes before advancing the UI or verification queue."""
from pathlib import Path

def adapt(root):
    folder = root/'merchinbox/src/main/java/com/eurobuddha/merchinbox'
    p = root/'merchinbox/src/main/java/com/eurobuddha/comms/MerchDb.java'
    s = p.read_text()
    needle = '    @Override public String getMeta(String k, String def) {'
    assert s.count(needle) == 1
    # Reuse the existing insert exactly, exposing its result as DexDb.claimOwnerTurn does.
    s = s.replace(needle, '''    /** Delivery callers must check this result and handle database exceptions before proceeding. */
    public boolean setMetaChecked(String k, String v) {
        ContentValues cv = new ContentValues(); cv.put("k", k); cv.put("v", v);
        return getWritableDatabase().insertWithOnConflict(META, null, cv, SQLiteDatabase.CONFLICT_REPLACE) != -1;
    }

''' + needle)
    p.write_text(s)
    p = folder/'MainActivity.java'; s = p.read_text()
    def change(old, new):
        nonlocal s
        assert s.count(old) == 1, old[:100]
        s = s.replace(old, new)
    change('    private void txDone() { txBusy = false; pumpTxQueue(); }', '''    private void txDone() {
        txBusy = false;
        if (isFinishing() || isDestroyed()) { txQueue.clear(); return; }
        pumpTxQueue();
    }''')
    a = s.index('    private void markSentAndMaybeAdvance(')
    b = s.index('    /** Transfer the exact coin', a)
    s = s[:a] + '''    /** A write failure after a post has an unknown outcome: keep this line locked in this host. */
    private void deliveryStorageFailed(MerchDb.Order o, String key, boolean auto) {
        if (isFinishing() || isDestroyed()) return;
        // Do not use deliverFailed: that clears nftSending and enables another transfer.
        CharSequence message = Language.msg(l10n_mall_inbox_storage_unconfirmed);
        if (auto) notifyDelivery(o.ref, false, message);
        else toast(message);
    }

    /** Serialize post-record persistence with the txn queue; never start confirmation before it succeeds. */
    private void recordPostedDelivery(MerchDb.Order o, String key, String value, boolean auto, Runnable recorded) {
        try {
            io.execute(() -> {
                boolean saved;
                try { saved = db.setMetaChecked("nftposted:" + key, value); }
                catch (RuntimeException unavailable) { saved = false; }
                final boolean stored = saved;
                ui.post(() -> {
                    txDone();
                    if (isFinishing() || isDestroyed()) return;
                    if (!stored) { deliveryStorageFailed(o, key, auto); return; }
                    recorded.run();
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            txDone();
            deliveryStorageFailed(o, key, auto);
        }
    }

    private void markSentAndMaybeAdvance(final MerchDb.Order o, final String key, final String txid, final boolean auto) {
        try {
            io.execute(() -> {
                final MerchDb.Order fresh;
                final boolean advance;
                try {
                    if (!db.setMetaChecked("nftsent:" + key, txid == null || txid.isEmpty() ? "sent" : txid)) {
                        ui.post(() -> deliveryStorageFailed(o, key, auto));
                        return;
                    }
                    fresh = db.order(o.ref);
                    boolean all = fresh != null;
                    if (fresh != null) for (String[] l : nftLines(fresh)) {
                        if (db.getMeta("nftsent:" + lineKey(o.ref, l), "").isEmpty()) { all = false; break; }
                    }
                    advance = all && (MerchDb.PAID.equals(fresh.status) || MerchDb.CONFIRMED.equals(fresh.status));
                } catch (RuntimeException unavailable) {
                    ui.post(() -> deliveryStorageFailed(o, key, auto));
                    return;
                }
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    nftSending.remove(key);
                    if (auto) notifyDelivery(o.ref, true, txid);
                    else toast(Language.msg(l10n_mall_inbox_sent));
                    if (advance) advanceStatus(fresh, MerchDb.SHIPPED, auto);
                    else refreshOrderIfTopmost(o.ref);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            deliveryStorageFailed(o, key, auto);
        }
    }

''' + s[b:]
    change('''                io.execute(() -> db.setMeta("nftposted:" + key, postedVal));
                txDone();
                watchDeparture(o, key, tokenid, coinid, postedVal, auto, 0);''', '''                recordPostedDelivery(o, key, postedVal, auto,
                        () -> watchDeparture(o, key, tokenid, coinid, postedVal, auto, 0));''')
    change('''        if (i >= coins.size()) {
            txDone();
            if (postedIds.isEmpty()) {''', '''        if (i >= coins.size()) {
            if (postedIds.isEmpty()) {
                txDone();''')
    change('''            io.execute(() -> db.setMeta("nftposted:" + key, "posted"));
            watchBundleDeparture(o, key, tokenid, postedIds, firstError[0], auto, 0);''', '''            recordPostedDelivery(o, key, "posted", auto,
                    () -> watchBundleDeparture(o, key, tokenid, postedIds, firstError[0], auto, 0));''')
    change('''                                       : Language.msg(l10n_mall_inbox_retry_notice, ref))''', '''                                       : detail instanceof Language.Message ? detail : Language.msg(l10n_mall_inbox_retry_notice, ref))''')
    p.write_text(s)
