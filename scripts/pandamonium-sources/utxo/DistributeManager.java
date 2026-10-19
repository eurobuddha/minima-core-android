package com.eurobuddha.utxo;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;


import android.widget.Toast;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Drives a multi-batch Distribute: spread a fixed amount across many addresses, working around
 * Minima's 14-recipient-per-transaction limit by auto-chaining up to {@link #MAX_BATCHES}
 * transactions. Each batch sends 14 recipients + one change output; the change coin from one
 * batch becomes the input of the next, identified by its exact coinid (captured from the posted
 * transaction) once it confirms on a new block.
 *
 * The heartbeat is {@link #onCoinsUpdated()}, called by {@link MainActivity#reload()} after every
 * coin refresh (which itself fires on each NEWBLOCK via the NOTIFY receiver).
 */
public class DistributeManager {

    public static final int BATCH = 14;
    public static final int MAX_BATCHES = 4;            // 4 × 14 = 56 addresses
    private static final int EXPIRY_BLOCKS = 20;
    private static final int MAX_RETRIES = 3;

    private static DistributeManager owner;
    private final MainActivity act;
    private boolean closed;
    private boolean live() { return !closed && !act.isFinishing() && !act.isDestroyed(); }
    private boolean claim() {
        synchronized (DistributeManager.class) {
            if (!live() || owner != null && owner != this) return false;
            if (owner != this) { job = DistributeJob.load(act); owner = this; }
            return true;
        }
    }
    private void release() { synchronized (DistributeManager.class) { if (owner == this) owner = null; } }
    public void close() { closed = true; release(); }

    private DistributeJob job;
    private boolean inFlight = false;   // a TxnBuilder is mid-post
    private boolean starting = false;   // reserved between the Tools-tab tap and start()

    public DistributeManager(MainActivity a) {
        act = a;
        job = DistributeJob.load(a);    // resume a job left over from a previous run
    }

    /** True while a job exists (running or waiting between batches). */
    public boolean isActive() {
        return job != null;
    }

    /** Active, or reserved while addresses are being fetched (prevents a concurrent start). */
    public boolean isBusy() {
        return job != null || starting;
    }

    /** Reserve the right to start a job. Returns false if one is already running/starting. */
    public boolean reserve() {
        if (!claim() || isBusy()) return false;
        starting = true;
        return true;
    }

    /** Release a reservation that never became a job (e.g. address fetch failed). */
    public void cancelReserve() {
        starting = false;
        if (job == null) release();
    }

    /** One-line progress string for the Tools tab, or null when idle. */
    public CharSequence statusLine() {
        if (job == null) return null;
        if ((job.invalid || job.legacy || job.uncertain) && !inFlight) return UtxoMessages.message(TransactionEvidence.DISTRIBUTION_UNKNOWN);
        CharSequence s = Language.join(Language.msg(l10n_utxo_distribution_progress, job.batchesDone, job.remaining.size()),
                " · ", job.waiting && !inFlight ? Language.msg(l10n_utxo_distribution_waiting) : Language.msg(l10n_utxo_status_posting));
        if (job.retryCount > 0) s = Language.join(s, " · ", Language.msg(l10n_utxo_distribution_retry, job.retryCount, MAX_RETRIES));
        return s;
    }

    // ===== start a new job =====

    /** Begin a new job: validate funds, build the job state, then post the first batch from the
     *  user's selected coins. Later batches chain off each batch's change coin (see onCoinsUpdated). */
    public void start(List<Coin> inputs, List<String> addrs, String per, String tokenid, String tokenName) {
        if (!claim()) { toast("A distribute job is already running."); return; }
        starting = false;
        if (job != null) { toast("A distribute job is already running."); return; }
        final BigDecimal total;
        try {
            if (inputs == null || inputs.isEmpty() || addrs == null || addrs.isEmpty() || addrs.size() > BATCH * MAX_BATCHES
                    || !Util.isValidAmount(per) || new BigDecimal(per).signum() <= 0 || !Util.isValidHexId(tokenid)) throw TransactionEvidence.invalid();
            for (String address : addrs) if (!Util.isValidAddress(address)) throw TransactionEvidence.invalid();
            total = sum(inputs);
            BigDecimal required = new BigDecimal(per).multiply(new BigDecimal(addrs.size()));
            if (required.compareTo(total) > 0) throw TransactionEvidence.invalid();
        } catch (RuntimeException invalid) { toast(TransactionEvidence.INVALID); release(); return; }

        job = new DistributeJob();
        job.tokenid = tokenid;
        job.tokenName = tokenName;
        job.per = per;
        job.remaining = new ArrayList<>(addrs);

        // Batch 1 spends the user's selected coins.
        doNextBatch(freeze(inputs), total);
    }

    // ===== heartbeat: advance the chain on each coin refresh =====

    /** Heartbeat from every coin refresh: when the previous batch's change coin has confirmed,
     *  chain the next batch onto it; bail out if it never shows within EXPIRY_BLOCKS. */
    public void onCoinsUpdated() {
        if (!live() || inFlight || starting || !claim()) return;
        if (job == null) { release(); return; }
        if (job.invalid || job.legacy) return;
        if (job.uncertain) { recoverBatch(); return; }
        if (job.remaining.isEmpty()) { complete(); return; }
        if (!job.waiting) return;
        if (job.retryCount > MAX_RETRIES) { abort("batch " + (job.batchesDone+1) + " failed after " + MAX_RETRIES + " retries: " + TransactionEvidence.NOT_SENT); return; }
        try {
            HistoryDb.Attempt previous = attempt(job.lastConfirmedId);
            if (previous == null || !HistoryDb.CONFIRMED.equals(previous.phase)) return;
            org.json.JSONArray out = previous.receipt.optJSONArray("outputs");
            org.json.JSONObject expected = out.optJSONObject(out.length()-1);
            if (!TransactionFields.same(job.expectedChangeCoinId,expected.optString("coinid"))
                    || !TransactionFields.addressIs(expected,job.nextChangeAddr)
                    || !amountsEqual(job.expectedChangeAmt,expected.optString("amount"))) return;
        } catch (RuntimeException unavailable) { return; }
        Coin change = findChangeCoin();
        if (change == null) {
            if (act.chainBlock() > 0 && job.atBlock > 0 && act.chainBlock() - job.atBlock > EXPIRY_BLOCKS)
                abort("timed out waiting for the change coin to confirm");
            return;
        }
        doNextBatch(freeze(Collections.singletonList(change)), new BigDecimal(change.amount));
    }

    private HistoryDb.Attempt attempt(String id) {
        for (HistoryDb.Attempt a : act.history().transactionAttempts()) if (a.id.equals(id)) return a;
        return null;
    }
    private void recoverBatch() {
        try {
            HistoryDb.Attempt a = attempt(job.currentInternalId);
            if (a == null || !TransactionEvidence.storedIntent(job.batchIntent.toString()).toString().equals(a.intent.toString())) return;
            if (HistoryDb.NOT_SUBMITTED.equals(a.phase)) { failBatch(TransactionEvidence.NOT_SENT); return; }
            if (!HistoryDb.CONFIRMED.equals(a.phase)) return;
            TransactionEvidence.storedReceipt(a.receipt,job.batchIntent);
            DistributeJob next = job.copy();
            int n = next.pendingRecipients;
            for (int i=0;i<n;i++) next.remaining.remove(0);
            next.batchesDone++; next.retryCount=0; next.uncertain=false;
            next.pendingRecipients=0; next.batchIntent=null; next.lastConfirmedId=a.id;
            org.json.JSONArray outputs=a.receipt.optJSONArray("outputs");
            boolean change=outputs.length()>n;
            next.expectedChangeCoinId=change?outputs.optJSONObject(n).optString("coinid"):"";
            next.nextChangeAddr=change?outputs.optJSONObject(n).optString("address"):"";
            next.expectedChangeAmt=change?outputs.optJSONObject(n).optString("amount"):"";
            next.atBlock=act.chainBlock();next.waiting=!next.remaining.isEmpty();
            if (next.waiting && (!change || next.batchesDone>=next.maxBatches)) throw TransactionEvidence.invalid();
            if (!next.save(act)) { toast(TransactionEvidence.DISTRIBUTION_UNKNOWN); return; }
            job=next;
            if (job.remaining.isEmpty()) complete();
            else act.refreshTools();
        } catch (RuntimeException unavailable) { /* Keep the frozen job and reservation for a later check. */ }
    }

    // ===== core: build + post one batch =====

    /** Build one batch: up to 14 recipients plus a change output for the remainder, fetching a fresh
     *  change address first when there's a remainder to return. */
    private void doNextBatch(final List<Coin> inputs, final BigDecimal inputTotal) {
        final int n = Math.min(BATCH, job.remaining.size());
        final List<String> recipients = new ArrayList<>(job.remaining.subList(0, n));

        final BigDecimal perBD = new BigDecimal(job.per);
        final BigDecimal paid = perBD.multiply(new BigDecimal(n));
        final BigDecimal change = inputTotal.subtract(paid);
        // Minima burns inputs−outputs, so we MUST return any remainder as a change output.
        final boolean hasChange = change.signum() > 0;
        final String changeStr = hasChange ? change.stripTrailingZeros().toPlainString() : "";

        inFlight = true;
        toast("Distribute: posting batch " + (job.batchesDone + 1) + " (" + n + " addresses)…");

        if (hasChange) {
            act.node().cmd("getaddress", new NodeApi.Cb() {
                @Override public void onResult(org.json.JSONObject j) {
                    if (!live()) return;
                    org.json.JSONObject r = j.optJSONObject("response");
                    String addr = r == null ? "" : r.optString("miniaddress", r.optString("address", ""));
                    if (!Boolean.TRUE.equals(j.opt("status")) || !Util.isValidAddress(addr)) { failBatch("could not get a change address"); return; }
                    postBatch(inputs, recipients, n, changeStr, addr);
                }
                @Override public void onError(String message) { failBatch(message); }
            });
        } else {
            postBatch(inputs, recipients, n, "", "");
        }
    }

    /** Post the batch transaction; on success advance the job, capture the change coinid to chain on,
     *  and either arm the wait for the next batch or complete. */
    private void postBatch(List<Coin> inputs, final List<String> recipients, final int n,
                           final String changeStr, final String changeAddr) {
        List<TxnBuilder.Out> outs = new ArrayList<>();
        for (String a : recipients) outs.add(new TxnBuilder.Out(a, job.per));
        final boolean hasChange = !changeStr.isEmpty();
        if (hasChange) outs.add(new TxnBuilder.Out(changeAddr, changeStr));

        final String internalid = TxnUtil.recordPosting(act,
                "Distribute batch " + (job.batchesDone + 1) + " → " + n + " addrs",
                Util.tidyAmount(new BigDecimal(job.per).multiply(new BigDecimal(n)).toPlainString()),
                job.tokenid, job.tokenName, inputs, outs, hasChange ? changeAddr : null, null);
        if (internalid == null) { failBatch(TransactionEvidence.SAVE_FAILED); return; }
        final DistributeJob pending;
        try {
            pending=job.copy();pending.currentInternalId=internalid;pending.pendingRecipients=n;
            pending.batchIntent=TransactionEvidence.intent(inputs,outs,job.tokenid,null);
            pending.uncertain=true;pending.waiting=true;
            if (!pending.save(act)) { failBatch(TransactionEvidence.SAVE_FAILED); return; }
        } catch (RuntimeException invalid) { failBatch(TransactionEvidence.INVALID); return; }
        job=pending;

        new TxnBuilder(act, inputs, outs, job.tokenid, new TxnBuilder.Done() {
            @Override public void onPosted(String txpowid, List<TxnBuilder.OutCoin> outputs) {
                if (!live() || job != pending) return;
                // TxnBuilder persisted the receipt. Recipients remain pending until journal confirmation.
                act.history().update(internalid,HistoryDb.STATUS_POSTED,null,"");
                inFlight=false;
                act.refreshTools();
            }

            @Override public void onNotStarted(String message) {
                if (!live() || job != pending) return;
                // TxnBuilder's pre-command admission failure is stronger than a missing receipt.
                // Persist that distinction before allowing the ordinary not-sent retry/clear path.
                DistributeJob stopped=pending.copy();stopped.uncertain=false;
                stopped.pendingRecipients=0;stopped.batchIntent=null;
                stopped.waiting=stopped.batchesDone>0;
                if (!stopped.save(act)) { inFlight=false;toast(TransactionEvidence.DISTRIBUTION_UNKNOWN);act.refreshTools();return; }
                job=stopped;failBatch(message);
            }
            @Override public void onFailed(String message) { if (live() && job == pending) failBatch(message); }
            @Override public void onUnknown(String message) {
                if (!live() || job != pending) return;
                inFlight=false;
                act.history().update(internalid,HistoryDb.STATUS_UNKNOWN,null,TransactionEvidence.UNKNOWN);
                toast(TransactionEvidence.DISTRIBUTION_UNKNOWN);
                act.refreshTools();
            }
        }).record(internalid).run();
    }

    /** Only the exact output of a confirmed journalled batch can fund its successor. */
    private Coin findChangeCoin() {
        if (job.expectedChangeCoinId.isEmpty()) return null;
        for (Coin c : act.coins()) {
            if (c == null || !c.isWellFormed() || c.spent || !c.sendable || !c.confirmed) continue;
            if (!TransactionFields.same(job.tokenid,c.tokenid) || !TransactionFields.same(job.expectedChangeCoinId,c.coinid)) continue;
            if (!amountsEqual(job.expectedChangeAmt,c.amount)) continue;
            if (job.nextChangeAddr.equals(c.miniaddress) || job.nextChangeAddr.equalsIgnoreCase(c.address)) return c;
        }
        return null;
    }

    // ===== terminal states =====

    /** All batches done: clear persisted state and refresh the UI. */
    private void complete() {
        if (job == null || !job.remaining.isEmpty() || job.uncertain) return;
        if (!DistributeJob.clearChecked(act)) { toast(TransactionEvidence.DISTRIBUTION_UNKNOWN); return; }
        inFlight=false;job=null;release();toast("Distribute complete.");act.refreshTools();
    }

    /** Only a verified, already-settled prefix may end with remaining addresses unfunded. */
    private void abort(String why) {
        if (job == null || job.uncertain) return;
        int left=job.remaining.size();
        if (!DistributeJob.clearChecked(act)) { toast(TransactionEvidence.DISTRIBUTION_UNKNOWN); return; }
        inFlight=false;job=null;release();toast("Distribute aborted ("+left+" address(es) unfunded): "+why);act.refreshTools();
    }

    private void failBatch(String message) {
        if (!live()) return;
        inFlight=false;if(job==null)return;
        if (job.uncertain) {
            try {
                HistoryDb.Attempt a=attempt(job.currentInternalId);
                // Missing or ambiguous evidence is never permission for another attempt.
                if(a==null||!HistoryDb.NOT_SUBMITTED.equals(a.phase)||!TransactionEvidence.storedIntent(job.batchIntent.toString()).toString().equals(a.intent.toString())) { toast(TransactionEvidence.DISTRIBUTION_UNKNOWN);act.refreshTools();return; }
            }catch(RuntimeException unavailable){return;}
        }
        if (job.batchesDone==0) {
            if(!DistributeJob.clearChecked(act)){toast(TransactionEvidence.DISTRIBUTION_UNKNOWN);return;}
            job=null;release();toast("Distribute couldn't start: "+message);act.refreshTools();return;
        }
        DistributeJob next=job.copy();next.uncertain=false;next.pendingRecipients=0;next.batchIntent=null;next.waiting=true;
        if(next.retryCount>=MAX_RETRIES) {
            next.retryCount++;
            if(!next.save(act)){toast(TransactionEvidence.DISTRIBUTION_UNKNOWN);return;}
            job=next;abort("batch "+(job.batchesDone+1)+" failed after "+MAX_RETRIES+" retries: "+message);return;
        }
        next.retryCount++;
        if(!next.save(act)){toast(TransactionEvidence.DISTRIBUTION_UNKNOWN);return;}
        job=next;toast("Distribute batch failed; retrying next block ("+job.retryCount+"/"+MAX_RETRIES+")");act.refreshTools();
    }

    // ===== helpers =====

    /** Freeze the same command/display fields as WalletTools.Review before the asynchronous lookup. */
    private List<Coin> freeze(List<Coin> source) {
        List<Coin> copy=new ArrayList<>();
        for(Coin original:source) {
            Coin c=new Coin();c.coinid=original.coinid;c.tokenid=original.tokenid;c.amount=original.amount;c.rawAmount=original.rawAmount;
            c.address=original.address;c.miniaddress=original.miniaddress;c.confirmed=original.confirmed;c.sendable=original.sendable;c.spent=original.spent;
            c.tokenName=original.tokenName;c.tokenNameProvided=original.tokenNameProvided;copy.add(c);
        }
        return copy;
    }
    private BigDecimal sum(List<Coin> coins) {
        BigDecimal total=BigDecimal.ZERO;
        for(Coin c:coins){if(c==null||!c.isWellFormed()||c.spent||!c.confirmed||!c.sendable||new BigDecimal(c.amount).signum()<=0)throw TransactionEvidence.invalid();total=total.add(new BigDecimal(c.amount));}
        return total;
    }
    private boolean amountsEqual(String a,String b){return Util.isValidAmount(a)&&Util.isValidAmount(b)&&new BigDecimal(a).compareTo(new BigDecimal(b))==0;}

    /** Short toast helper. */
    private void toast(String msg) {
        Toast.makeText(act, UtxoMessages.message(msg), Toast.LENGTH_SHORT).show();
    }
}
