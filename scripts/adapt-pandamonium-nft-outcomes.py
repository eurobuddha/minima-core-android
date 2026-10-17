"""Explicit NFT transaction outcomes and strict receipts; retain uncertain distribution state."""
from pathlib import Path
import shutil

def adapt(root):
    folder=root/'NFTwallet/src/main/java/com/eurobuddha/nftwallet'
    project=Path(__file__).resolve().parents[1]
    def change(name,old,new,count=1):
        p=folder/(name+'.java');s=p.read_text();assert s.count(old)==count,('NFT outcome drift',name,old);p.write_text(s.replace(old,new))
    shutil.copy2(project/'scripts/pandamonium-sources/NFTwallet/TransactionEvidence.java',folder/'TransactionEvidence.java')
    change('NodeApi','        void onError(String message);','''        void onError(String message);
        /** The transport cannot establish whether an executing command completed. */
        default void onUnknown(String message) { onError(message); }''')
    change('NodeApi','''                    callback.onError(result == null ? "Node result unavailable. Outcome unknown."
                            : result.optString("transporterror", "Node result unavailable. Outcome unknown."));''','''                    String message = result == null ? "Node result unavailable. Outcome unknown."
                            : result.optString("transporterror", "Node result unavailable. Outcome unknown.");
                    if (nothingSent(message)) callback.onError(message);
                    else callback.onUnknown(message);''')
    change('NodeApi','''            @Override public void onError(String message) { callback.onError(message); }
        };
    }''','''            @Override public void onError(String message) { callback.onError(message); }
            @Override public void onUnknown(String message) { callback.onUnknown(message); }
        };
    }

    /** Exact local transport rejections that occur before execution, from DirectNodeApi. */
    private static boolean nothingSent(String message) {
        return "The embedded node is not running. No command was sent.".equals(message)
                || "Too many pending node requests. Nothing was sent.".equals(message)
                || "Node request cancelled before execution. Nothing was sent.".equals(message)
                || "Node connection is closed.".equals(message);
    }''')
    change('NodeApi','cb.onError("The embedded node did not respond. Check its status in Minima Core.")','cb.onUnknown("The embedded node did not respond. Check its status in Minima Core.")')
    change('NodeApi','if (isTooLong(zResponse)) { if (cb != null) cb.onError(ERR_TOO_LONG); return; }','if (isTooLong(zResponse)) { if (cb != null) cb.onUnknown(ERR_TOO_LONG); return; }')
    change('NodeApi','cb.onError("Bad node reply")','cb.onUnknown("Bad node reply")')

    change('TxnBuilder','        void onFailed(String message);','''        void onFailed(String message);
        /** Required: callers must preserve an unknown outcome instead of treating it as failed. */
        void onUnknown(String message);''')
    change('TxnBuilder','    private final String txid;','''    private final String txid;
    private JSONObject intent;
    private boolean started, finished;''')
    change('TxnBuilder','''    public void run() {
        stage("Building transaction…");''','''    public void run() {
        if (started) return;
        started = true;
        try { intent = TransactionEvidence.intent(inputs, outputs, tokenid, burn); }
        catch (RuntimeException invalid) { finished = true; done.onFailed(TransactionEvidence.INVALID); return; }
        stage("Building transaction…");''')
    change('TxnBuilder','''        if (i >= inputs.size()) { addOutput(0); return; }
        stage("Adding inputs (" + (i + 1) + "/" + inputs.size() + ")…");
        act.node().cmd("txninput id:" + txid + " coinid:" + inputs.get(i).coinid,''','''        if (finished) return;
        JSONArray frozen = intent.optJSONArray("inputs");
        if (i >= frozen.length()) { addOutput(0); return; }
        stage("Adding inputs (" + (i + 1) + "/" + frozen.length() + ")…");
        act.node().cmd("txninput id:" + txid + " coinid:" + frozen.optJSONObject(i).optString("coinid"),''')
    change('TxnBuilder','''        if (i >= outputs.size()) { sign(); return; }
        stage("Adding outputs (" + (i + 1) + "/" + outputs.size() + ")…");
        Out o = outputs.get(i);
        String cmd = "txnoutput id:" + txid + " amount:" + o.amount + " address:" + o.address''','''        if (finished) return;
        JSONArray frozen = intent.optJSONArray("outputs");
        if (i >= frozen.length()) { sign(); return; }
        stage("Adding outputs (" + (i + 1) + "/" + frozen.length() + ")…");
        JSONObject o = frozen.optJSONObject(i);
        String cmd = "txnoutput id:" + txid + " amount:" + o.optString("amount") + " address:" + o.optString("address")''')
    change('TxnBuilder','''                        done.onPosted(parseTxpowid(json), parseOutputs(json));''','''                        if (finished) return;
                        final List<OutCoin> receipt;
                        try { receipt = TransactionEvidence.receipt(json, intent); }
                        catch (RuntimeException incomplete) { unknown(TransactionEvidence.UNKNOWN); return; }
                        finished = true;
                        done.onPosted(TransactionEvidence.txpow(json).optString("txpowid"), receipt);''')
    change('TxnBuilder','''                    @Override public void onError(String message) { fail(message); }
                });''','''                    @Override public void onError(String message) { fail(message); }
                    @Override public void onUnknown(String message) { unknown(message); }
                });''')
    change('TxnBuilder','''    private void fail(String message) {
        // Best-effort''','''    private void unknown(String message) {
        if (finished) return;
        finished = true;
        // An executing post may still finish. Keep its record; never schedule cleanup or a retry.
        done.onUnknown(TransactionEvidence.UNKNOWN);
    }

    private void fail(String message) {
        if (finished) return;
        finished = true;
        // Best-effort''')
    p=folder/'TxnBuilder.java';s=p.read_text();start=s.index('    private JSONObject txpowOf(');s=s[:start]+'}\n';p.write_text(s)

    # Reuse Future Cash's selected-view reveal after text/layout changes; keep focus and draft intact.
    change('SendView','        statusView = find(R.id.pm_nftwallet_sendStatus);','''        statusView = find(R.id.pm_nftwallet_sendStatus);
        statusView.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                view.post(() -> {
                    if (view.isAttachedToWindow() && act.currentTab() == MainActivity.TAB_SEND
                            && view.getVisibility() == View.VISIBLE)
                        view.requestRectangleOnScreen(new android.graphics.Rect(0, 0, view.getWidth(), view.getHeight()), true);
                }));''')

    change('HistoryDb','    public static final String STATUS_ERROR     = "error";','''    public static final String STATUS_ERROR     = "error";
    public static final String STATUS_UNKNOWN   = "unknown"; // no reliable posting result''')
    change('WalletTools','''                status.show(Language.msg(l10n_utxo_failed, NftMessages.message(message)), false);
            }
        }).run();''','''                status.show(Language.msg(l10n_utxo_failed, NftMessages.message(message)), false);
            }
            @Override public void onUnknown(String message) {
                act.history().update(internalid, HistoryDb.STATUS_UNKNOWN, null, message);
                status.show(NftMessages.message(message), false);
                act.reload();
            }
        }).run();''')
    change('SendView','''        }).onProgress(label -> status(NftMessages.message(label), true)).run();''','''            @Override public void onUnknown(String message) {
                act.history().update(internalid, HistoryDb.STATUS_UNKNOWN, null, message);
                status(NftMessages.message(message), false);
                sending = false;
                previewBtn.setEnabled(true);
                act.reload();
            }
        }).onProgress(label -> status(NftMessages.message(label), true)).run();''')

    change('DistributeJob','    public boolean waiting = false;', '    public boolean uncertain = false;      // persisted before signing; no automatic retry without evidence\n    public boolean waiting = false;')
    change('DistributeJob','            o.put("waiting", waiting);','            o.put("waiting", waiting);\n            o.put("uncertain", uncertain);')
    change('DistributeJob','        j.waiting = o.optBoolean("waiting", false);','''        j.waiting = o.optBoolean("waiting", false);
        j.uncertain = o.has("uncertain") && !Boolean.FALSE.equals(o.opt("uncertain"));''')
    change('DistributeJob','public void save(Context ctx)','public boolean save(Context ctx)')
    change('DistributeJob','        p.edit().putString(KEY, toJson().toString()).apply();','        return p.edit().putString(KEY, toJson().toString()).commit();')
    change('DistributeManager','        if (job == null) return null;','''        if (job == null) return null;
        if (job.uncertain && !inFlight) return NftMessages.message(TransactionEvidence.DISTRIBUTION_UNKNOWN);''')
    change('DistributeManager','if (job == null || inFlight || !job.waiting) return;','if (job == null || inFlight || !job.waiting || job.uncertain) return;')
    change('DistributeManager','job.waiting ? Language.msg(l10n_utxo_distribution_waiting)', 'job.waiting && !inFlight ? Language.msg(l10n_utxo_distribution_waiting)')
    change('DistributeManager','''        job.currentInternalId = internalid;

        new TxnBuilder''','''        job.currentInternalId = internalid;
        job.uncertain = true;
        job.waiting = true;
        if (!job.save(act)) { failBatch(TransactionEvidence.SAVE_FAILED); return; }

        new TxnBuilder''')
    change('DistributeManager','''                // Remove the addresses we just funded.
''','''                job.uncertain = false;
                // Remove the addresses we just funded.
''')
    change('DistributeManager','''                    job.save(act);        // persist so a restart resumes mid-distribution''','''                    if (!job.save(act)) {
                        job.uncertain = true;
                        toast(TransactionEvidence.DISTRIBUTION_UNKNOWN);
                    }''')
    change('DistributeManager','''            @Override public void onFailed(String message) { failBatch(message); }
''','''            @Override public void onFailed(String message) { failBatch(message); }
            @Override public void onUnknown(String message) {
                act.history().update(internalid, HistoryDb.STATUS_UNKNOWN, null, message);
                inFlight = false;
                job.uncertain = true;
                job.waiting = true;
                job.save(act); // the pre-sign marker already survives if this write fails
                toast(TransactionEvidence.DISTRIBUTION_UNKNOWN);
                act.refreshTools();
            }
''')
    change('DistributeManager','''        if (job == null) return;

        if (!job.currentInternalId.isEmpty())''','''        if (job == null) return;
        job.uncertain = false;

        if (!job.currentInternalId.isEmpty())''')
    # The strict receipt is returned in intended-output order: change is the final intended output.
    change('DistributeManager','''        for (TxnBuilder.OutCoin oc : outputs) {
            if (oc.addressIs(changeAddr) && amountsEqual(oc.amount, changeStr)) return oc.coinid;
        }
        return outputs.isEmpty() ? "" : outputs.get(outputs.size() - 1).coinid;''','''        TxnBuilder.OutCoin change = outputs.get(outputs.size() - 1);
        if (!change.addressIs(changeAddr) || !amountsEqual(change.amount, changeStr))
            throw TransactionEvidence.invalid();
        return change.coinid;''')
    change('DistributeManager',"/** The change output's coinid: match by address (either form) + amount, else the last output. */","/** The final intended output is change; TxnBuilder has matched the complete receipt. */")
    change('TxnBuilder','a.equals(address) || a.equals(miniaddress)','(a.startsWith("0x") && a.equalsIgnoreCase(address)) || a.equals(miniaddress)')
    change('NftMessages','        switch(value) {','''        switch(value) {
            case TransactionEvidence.INVALID: return Language.msg(l10n_nft_transaction_invalid);
            case TransactionEvidence.UNKNOWN: return Language.msg(l10n_nft_transaction_unknown);
            case TransactionEvidence.SAVE_FAILED: return Language.msg(l10n_nft_transaction_save_failed);
            case TransactionEvidence.DISTRIBUTION_UNKNOWN: return Language.msg(l10n_nft_transaction_distribution_unknown);''')
