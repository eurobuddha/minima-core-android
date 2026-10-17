package com.eurobuddha.nftwallet;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;

import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.List;

/**
 * Transfer and Bury for StateNFT unit coins — the statenft-suite flows, wallet-side.
 *
 * Hard rules honoured here (proven on-chain):
 *  - never {@code send} (script coins are sendable:0) — always the manual txn path;
 *  - replay EVERY state port verbatim with storestate:true (SAMESTATE);
 *  - refuse coins whose state fails {@link StateNft#replayableState} — hostile state could
 *    smuggle extra command parameters when replayed;
 *  - {@code txndelete} on every failure path (CmdChain cleanup);
 *  - never trust txnpost status — confirm the intended output, amount and preserved state on-chain.
 */
public final class StateNftActions {

    private static final int WATCH_INTERVAL_MS = 20000;
    private static final int WATCH_TRIES = 20;

    private StateNftActions() {}

    // ===================== transfer =====================

    /** Ask for a recipient, confirm, then post the identity-preserving transfer. */
    public static void transferDialog(MainActivity act, String tokenid, CharSequence displayName, JSONObject coin) {
        if (!idsSafe(act, tokenid, coin)) return;
        if (StateNft.stamped(coin) == null) {
            // An unstamped unit still carries the sentinel — the creator bypass is LIVE on it, so a
            // recipient would hold a coin the creator can rewrite; and an active mint's MOVE phase
            // would fight to pull it back. Only sealed identities leave the wallet.
            Sheet.create(act, Language.msg(l10n_nft_actions_cannot_transfer))
                .subtitle(Language.msg(l10n_nft_actions_unstamped_transfer))
                .action(Language.msg(l10n_language_close), Sheet.Style.SECONDARY, null)
                .show();
            return;
        }
        if (!StateNft.replayableState(coin)) {
            Sheet.create(act, Language.msg(l10n_nft_actions_cannot_transfer))
                .subtitle(Language.msg(l10n_nft_actions_unsafe_transfer))
                .action(Language.msg(l10n_language_close), Sheet.Style.SECONDARY, null)
                .show();
            return;
        }

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Design.bg());
        int pad = dp(act, 18);
        box.setPadding(pad, dp(act, 8), pad, dp(act, 8));

        TextView t = new TextView(act);
        Language.setText(t, Language.msg(l10n_nft_actions_identity_replay));
        t.setTextColor(Design.dim());
        t.setTextSize(12f);
        box.addView(t);

        LinearLayout addrRow = new LinearLayout(act);
        addrRow.setOrientation(LinearLayout.HORIZONTAL);
        addrRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText addr = new EditText(act);
        Language.hint(addr, Language.msg(l10n_nft_actions_recipient_hint));
        addr.setHintTextColor(Design.dim());
        addr.setTextColor(Design.text());
        addr.setTextSize(13f);
        addr.setTypeface(android.graphics.Typeface.MONOSPACE);
        addr.setBackgroundColor(Design.surface2());
        addr.setPadding(dp(act, 10), dp(act, 10), dp(act, 10), dp(act, 10));
        addr.setInputType(InputType.TYPE_CLASS_TEXT);
        addrRow.addView(addr, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView scan = new TextView(act);
        Language.setText(scan, " ⌗ ");
        scan.setTextColor(Design.accentText());
        scan.setMinHeight(dp(act,48)); scan.setMinWidth(dp(act,48)); scan.setGravity(Gravity.CENTER);
        Language.description(scan, Language.msg(l10n_wallet_scan_qr));
        scan.setTextSize(16f);
        scan.setOnClickListener(v -> act.scanQr(text -> { if (text != null) Language.setText(addr, text.trim()); }));
        addrRow.addView(scan);
        LinearLayout.LayoutParams arlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        arlp.topMargin = dp(act, 10);
        addrRow.setLayoutParams(arlp);
        box.addView(addrRow);

        Sheet.create(act, Language.msg(l10n_nft_actions_transfer_name, displayName))
                .body(box)
                .action(Language.msg(l10n_utxo_back), Sheet.Style.SECONDARY, null)
                .action(Language.msg(l10n_nft_actions_transfer), Sheet.Style.PRIMARY, () -> {
                    String to = addr.getText().toString().trim();
                    if (!Util.isValidAddress(to)) {
                        android.widget.Toast.makeText(act, Language.msg(l10n_vestr_invalid_address),
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    runTransfer(act, tokenid, displayName, coin, to);
                })
                .show();
    }

    private static void runTransfer(MainActivity act, String tokenid, CharSequence displayName,
                                    JSONObject coin, String to) {
        final String coinid = coin.optString("coinid", "");
        String txn = "tr" + shortId(coinid);
        List<String> cmds = StateNft.transferCommands(txn, tokenid, coin, to);
        final Sheet.Progress progress = progressDialog(act,
                Language.msg(l10n_nft_actions_transferring_name, displayName), Language.msg(l10n_nft_actions_building));
        final JSONObject intent = departureIntent(act, tokenid, coin, progress);
        if (intent == null) return;
        CmdChain.run(act.node(), cmds, "txndelete id:" + txn, new CmdChain.Done() {
            @Override public void ok(JSONObject last) {
                act.node().cmd("txndelete id:" + txn, NOOP);
                setMessage(progress, Language.msg(l10n_nft_actions_waiting_transfer));
                makeDismissable(progress);
                confirmDeparture(act, tokenid, intent, to, last, progress,
                        Language.msg(l10n_nft_actions_transferred, displayName, to));
            }
            @Override public void fail(String message) {
                setMessage(progress, Language.msg(l10n_utxo_failed, NftMessages.message(message)));
                makeDismissable(progress);
            }
        });
    }

    // ===================== bury =====================

    /** Typed-confirmation bury to the graveyard address (irreversible). Stamped units only. */
    public static void buryDialog(MainActivity act, String tokenid, String collectionName,
                                  CharSequence displayName, JSONObject coin) {
        if (!idsSafe(act, tokenid, coin)) return;
        if (StateNft.stamped(coin) == null) {
            Sheet.create(act, Language.msg(l10n_nft_actions_cannot_bury))
                .subtitle(Language.msg(l10n_nft_actions_unstamped_bury))
                .action(Language.msg(l10n_language_close), Sheet.Style.SECONDARY, null)
                .show();
            return;
        }
        if (!StateNft.replayableState(coin)) {
            Sheet.create(act, Language.msg(l10n_nft_actions_cannot_bury))
                .subtitle(Language.msg(l10n_nft_actions_unsafe_bury))
                .action(Language.msg(l10n_language_close), Sheet.Style.SECONDARY, null)
                .show();
            return;
        }

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Design.bg());
        int pad = dp(act, 18);
        box.setPadding(pad, dp(act, 8), pad, dp(act, 8));

        TextView t = new TextView(act);
        Language.setText(t, Language.msg(l10n_nft_actions_bury_help, displayName, collectionName));
        t.setTextColor(Design.red());
        t.setTextSize(12f);
        box.addView(t);

        final EditText confirm = new EditText(act);
        Language.hint(confirm, collectionName);
        confirm.setHintTextColor(Design.dim());
        confirm.setTextColor(Design.text());
        confirm.setTextSize(13f);
        confirm.setBackgroundColor(Design.surface2());
        confirm.setPadding(dp(act, 10), dp(act, 10), dp(act, 10), dp(act, 10));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(act, 10);
        confirm.setLayoutParams(clp);
        box.addView(confirm);

        Sheet.create(act, Language.msg(l10n_nft_actions_bury_name, displayName))
                .body(box)
                .action(Language.msg(l10n_utxo_back), Sheet.Style.SECONDARY, null)
                .action(Language.msg(l10n_nft_actions_bury_forever), Sheet.Style.DANGER, () -> {
                    if (!confirm.getText().toString().trim().equals(collectionName)) {
                        android.widget.Toast.makeText(act, Language.msg(l10n_nft_actions_exact_name),
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    runBury(act, tokenid, displayName, coin);
                })
                .show();
    }

    private static void runBury(MainActivity act, String tokenid, CharSequence displayName, JSONObject coin) {
        final String coinid = coin.optString("coinid", "");
        String txn = "by" + shortId(coinid);
        // Stamped unit: identity-preserving path, owner signature only (creatorPk empty).
        List<String> cmds = StateNft.buryCommands(txn, tokenid, "", coin, true);
        final Sheet.Progress progress = progressDialog(act,
                Language.msg(l10n_nft_actions_burying_name, displayName), Language.msg(l10n_nft_actions_building_bury));
        final JSONObject intent = departureIntent(act, tokenid, coin, progress);
        if (intent == null) return;
        CmdChain.run(act.node(), cmds, "txndelete id:" + txn, new CmdChain.Done() {
            @Override public void ok(JSONObject last) {
                act.node().cmd("txndelete id:" + txn, NOOP);
                setMessage(progress, Language.msg(l10n_nft_actions_waiting_bury));
                makeDismissable(progress);
                confirmDeparture(act, tokenid, intent, StateNft.GRAVEYARD, last, progress,
                        Language.msg(l10n_nft_actions_buried_name, displayName));
            }
            @Override public void fail(String message) {
                setMessage(progress, Language.msg(l10n_utxo_failed, NftMessages.message(message)));
                makeDismissable(progress);
            }
        });
    }

    // ===================== transfer a whole collection =====================

    /**
     * Send every item of a collection you hold to one address.
     *
     * This is ONE action but it is NOT one transaction, and it cannot be. Minima state is
     * per-TRANSACTION — a transaction carries a single state map — while every stamped item holds a
     * different index at port 0. The locked-edition script asserts SAMESTATE over that index, so
     * two items with different identities can never satisfy it in the same transaction. One txn per
     * item is the protocol, not an inefficiency in this wallet.
     *
     * So we do what the bury flow does: enumerate, then post in sequence with honest progress.
     */
    public static void transferCollectionDialog(final MainActivity act, final String tokenid,
                                                final String collectionName, final Runnable onDone) {
        if (!Util.isValidHexId(tokenid)) {
            Sheet.create(act, Language.msg(l10n_nft_actions_refuse_collection))
                    .subtitle(Language.msg(l10n_nft_actions_bad_collection_id))
                    .action(Language.msg(l10n_language_close), Sheet.Style.SECONDARY, null)
                    .show();
            return;
        }

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Design.bg());
        int pad = dp(act, 18);
        box.setPadding(pad, dp(act, 8), pad, dp(act, 8));

        TextView t = new TextView(act);
        Language.setText(t, Language.msg(l10n_nft_actions_collection_transfer_help, collectionName));
        t.setTextColor(Design.dim());
        t.setTextSize(12f);
        box.addView(t);

        LinearLayout addrRow = new LinearLayout(act);
        addrRow.setOrientation(LinearLayout.HORIZONTAL);
        addrRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText addr = new EditText(act);
        Language.hint(addr, Language.msg(l10n_nft_actions_recipient_hint));
        addr.setHintTextColor(Design.dim());
        addr.setTextColor(Design.text());
        addr.setTextSize(13f);
        addr.setTypeface(android.graphics.Typeface.MONOSPACE);
        addr.setBackgroundColor(Design.surface2());
        addr.setPadding(dp(act, 10), dp(act, 10), dp(act, 10), dp(act, 10));
        addr.setInputType(InputType.TYPE_CLASS_TEXT);
        addrRow.addView(addr, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView scan = new TextView(act);
        Language.setText(scan, " ⌗ ");
        scan.setTextColor(Design.accentText());
        scan.setMinHeight(dp(act,48)); scan.setMinWidth(dp(act,48)); scan.setGravity(Gravity.CENTER);
        Language.description(scan, Language.msg(l10n_wallet_scan_qr));
        scan.setTextSize(16f);
        scan.setOnClickListener(v -> act.scanQr(text -> { if (text != null) Language.setText(addr, text.trim()); }));
        addrRow.addView(scan);
        LinearLayout.LayoutParams arlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        arlp.topMargin = dp(act, 10);
        addrRow.setLayoutParams(arlp);
        box.addView(addrRow);

        Sheet.create(act, Language.msg(l10n_nft_gallery_send_collection))
                .body(box)
                .action(Language.msg(l10n_utxo_back), Sheet.Style.SECONDARY, null)
                .action(Language.msg(l10n_nft_actions_send_all), Sheet.Style.PRIMARY, () -> {
                    String to = addr.getText().toString().trim();
                    if (!Util.isValidAddress(to)) {
                        android.widget.Toast.makeText(act, Language.msg(l10n_vestr_invalid_address),
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    gatherAndTransfer(act, tokenid, collectionName, to, onDone);
                })
                .show();
    }

    private static void gatherAndTransfer(final MainActivity act, final String tokenid,
                                          final String collectionName, final String to,
                                          final Runnable onDone) {
        final Sheet.Progress progress = progressDialog(act,
                Language.msg(l10n_nft_actions_sending_name, collectionName), Language.msg(l10n_nft_actions_finding_items));
        if (!LocalStore.canDepart(act, tokenid)) {
            setMessage(progress, Language.msg(l10n_nft_actions_mint_unfinished));
            makeDismissable(progress); return;
        }
        act.node().cmd("coins relevant:true own:true tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                final org.json.JSONArray arr;
                try { arr = BurialEvidence.coins(json, tokenid); }
                catch (RuntimeException e) {
                    setMessage(progress, Language.msg(l10n_nft_burial_coin_list_incomplete));
                    makeDismissable(progress); return;
                }
                final java.util.List<JSONObject> coins = new java.util.ArrayList<>();
                int skipped = 0;
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject c = arr.optJSONObject(i);
                        if (c == null) continue;
                        // Same refusals as a single transfer: a malformed id or state this wallet
                        // will not replay, and unstamped units whose creator bypass is still live.
                        if (!Util.isValidHexId(c.optString("coinid", ""))
                                || !StateNft.replayableState(c)
                                || StateNft.stamped(c) == null) { skipped++; continue; }
                        coins.add(c);
                    }
                }
                if (coins.isEmpty()) {
                    setMessage(progress, skipped > 0
                            ? Language.msg(l10n_nft_actions_skipped_unsafe, skipped)
                            : Language.msg(l10n_nft_actions_no_items));
                    makeDismissable(progress);
                    return;
                }
                transferNext(act, tokenid, to, coins, 0, new int[]{0}, skipped, new String[]{null},
                        progress, onDone);
            }
            @Override public void onError(String message) {
                setMessage(progress, NodeApi.ERR_TOO_LONG.equals(message)
                        ? Language.msg(l10n_nft_actions_transfer_list_large)
                        : Language.msg(l10n_nft_actions_list_items_failed, NftMessages.message(message)));
                makeDismissable(progress);
            }
        });
    }

    private static void transferNext(final MainActivity act, final String tokenid, final String to,
                                     final java.util.List<JSONObject> coins, final int i,
                                     final int[] done, final int skipped, final String[] firstError,
                                     final Sheet.Progress progress, final Runnable onDone) {
        if (i >= coins.size()) {
            int failed = coins.size() - done[0];
            setMessage(progress, Language.join(Language.msg(l10n_nft_actions_posted_transfers, done[0], coins.size()),
                    skipped > 0 ? Language.msg(l10n_nft_actions_skipped_count, skipped) : "", "\n\n",
                    failed > 0 && firstError[0] != null ? Language.join(Language.msg(l10n_nft_actions_failed_first, failed, NftMessages.message(firstError[0])), "\n\n") : "",
                    Language.msg(l10n_nft_actions_chain_decides)));
            makeDismissable(progress);
            act.reload();
            if (onDone != null) onDone.run();
            return;
        }
        final JSONObject coin = coins.get(i);
        String idx = StateNft.stamped(coin);
        setMessage(progress, Language.msg(l10n_nft_actions_sending_item, i + 1, coins.size(), idx == null ? "" : "  (#" + idx + ")"));
        final String txn = "tc" + shortId(coin.optString("coinid", ""));
        java.util.List<String> cmds = StateNft.transferCommands(txn, tokenid, coin, to);
        CmdChain.run(act.node(), cmds, "txndelete id:" + txn, new CmdChain.Done() {
            @Override public void ok(JSONObject last) {
                act.node().cmd("txndelete id:" + txn, NOOP);
                done[0]++;
                transferNext(act, tokenid, to, coins, i + 1, done, skipped, firstError, progress, onDone);
            }
            @Override public void fail(String message) {
                // One failure must not strand the rest — carry on and report the true tally,
                // keeping the first reason so the summary can say WHY, not just how many.
                if (firstError[0] == null && message != null && !message.isEmpty()) {
                    firstError[0] = message;
                }
                transferNext(act, tokenid, to, coins, i + 1, done, skipped, firstError, progress, onDone);
            }
        });
    }

    // ===================== bury a whole collection =====================

    /**
     * Bury EVERY coin of a collection you still hold — the escape hatch for a mint that went wrong
     * (a ruined image, a bad index) and can never be edited, only destroyed.
     *
     * Each coin is a separate transaction: stamped units take the identity-preserving path with the
     * owner signature, unstamped ones need the creator signature for the bypass. They are posted in
     * sequence rather than in parallel — one input coin each, so they cannot conflict, but serial
     * posting keeps the node's single command thread sane and lets us report honest progress.
     */
    public static void buryCollectionDialog(final MainActivity act, final String tokenid,
                                            final String collectionName, final String creatorPk,
                                            final Runnable onDone) {
        if (!Util.isValidHexId(tokenid)) {
            Sheet.create(act, Language.msg(l10n_nft_actions_refuse_collection))
                .subtitle(Language.msg(l10n_nft_actions_bad_collection_id))
                .action(Language.msg(l10n_language_close), Sheet.Style.SECONDARY, null)
                .show();
            return;
        }

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Design.bg());
        int pad = dp(act, 18);
        box.setPadding(pad, dp(act, 8), pad, dp(act, 8));

        TextView t = new TextView(act);
        Language.setText(t, Language.msg(l10n_nft_actions_collection_bury_help, collectionName, collectionName));
        t.setTextColor(Design.red());
        t.setTextSize(12f);
        box.addView(t);

        final EditText confirm = new EditText(act);
        Language.hint(confirm, collectionName);
        confirm.setHintTextColor(Design.dim());
        confirm.setTextColor(Design.text());
        confirm.setTextSize(13f);
        confirm.setBackgroundColor(Design.surface2());
        confirm.setPadding(dp(act, 10), dp(act, 10), dp(act, 10), dp(act, 10));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(act, 10);
        confirm.setLayoutParams(clp);
        box.addView(confirm);

        Sheet.create(act, Language.msg(l10n_nft_actions_bury_collection))
                .body(box)
                .action(Language.msg(l10n_utxo_back), Sheet.Style.SECONDARY, null)
                .action(Language.msg(l10n_nft_actions_bury_all), Sheet.Style.DANGER, () -> {
                    if (!confirm.getText().toString().trim().equals(collectionName)) {
                        android.widget.Toast.makeText(act, Language.msg(l10n_nft_actions_exact_name),
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    gatherAndBury(act, tokenid, collectionName, creatorPk, onDone);
                })
                .show();
    }

    private static void gatherAndBury(final MainActivity act, final String tokenid,
                                      final String collectionName, final String creatorPk,
                                      final Runnable onDone) {
        final Sheet.Progress progress = progressDialog(act,
                Language.msg(l10n_nft_actions_burying_name, collectionName), Language.msg(l10n_nft_actions_finding_coins));
        try {
            LocalStore.burialJournal(act, tokenid);
            if (!MintDriver.pauseForBurial(act, tokenid)) {
                setMessage(progress, Language.msg(l10n_nft_burial_pause_failed));
                makeDismissable(progress); return;
            }
        } catch (RuntimeException e) {
            setMessage(progress, Language.msg(l10n_nft_burial_evidence_missing));
            makeDismissable(progress); return;
        }
        act.node().cmd("coins relevant:true own:true tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                if (act.isFinishing() || act.isDestroyed()) return;
                final java.util.List<JSONObject> coins = new java.util.ArrayList<>();
                try {
                    org.json.JSONArray arr = BurialEvidence.coins(json, tokenid);
                    org.json.JSONArray journal = LocalStore.burialJournal(act, tokenid);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject coin = arr.getJSONObject(i);
                        if (BurialEvidence.same(StateNft.GRAVEYARD, coin.optString("address"))) throw BurialEvidence.invalid();
                        if (StateNft.stamped(coin) == null && !Util.isValidHexId(creatorPk)) throw BurialEvidence.invalid();
                        JSONObject intent = BurialEvidence.intent(coin, tokenid);
                        JSONObject old = burialEntry(journal, coin.optString("coinid"));
                        if (old == null) journal.put(intent);
                        else if (!old.optString("statehash").equals(intent.optString("statehash"))
                                || !old.optString("tokenamount").equals(intent.optString("tokenamount"))) throw BurialEvidence.invalid();
                        coins.add(coin);
                    }
                    // Record every intended input before the first signature. Failed/unknown posts remain pending.
                    if (!LocalStore.saveBurialJournal(act, tokenid, journal)) throw BurialEvidence.invalid();
                    if (coins.isEmpty()) {
                        if (journal.length() == 0) {
                            setMessage(progress, Language.msg(l10n_nft_actions_no_coins));
                            makeDismissable(progress);
                        } else watchBurialCompletion(act, tokenid, progress, onDone);
                        return;
                    }
                } catch (Exception e) {
                    setMessage(progress, Language.msg(l10n_nft_burial_evidence_missing));
                    makeDismissable(progress); return;
                }
                buryNext(act, tokenid, creatorPk, coins, 0, new int[]{0}, new String[]{null}, progress, onDone);
            }
            @Override public void onError(String message) {
                setMessage(progress, NodeApi.ERR_TOO_LONG.equals(message)
                        ? Language.msg(l10n_nft_actions_bury_list_large)
                        : Language.msg(l10n_nft_actions_list_coins_failed, NftMessages.message(message)));
                makeDismissable(progress);
            }
        });
    }

    private static JSONObject burialEntry(org.json.JSONArray journal, String input) {
        for (int i = 0; i < journal.length(); i++) {
            JSONObject entry = journal.optJSONObject(i);
            if (entry != null && BurialEvidence.same(input, entry.optString("input"))) return entry;
        }
        return null;
    }

    private static void buryNext(final MainActivity act, final String tokenid, final String creatorPk,
                                 final java.util.List<JSONObject> coins, final int i, final int[] done,
                                 final String[] firstError, final Sheet.Progress progress, final Runnable onDone) {
        if (act.isFinishing() || act.isDestroyed()) return;
        if (i >= coins.size()) {
            setMessage(progress, Language.join(Language.msg(l10n_nft_actions_posted_burials, done[0], coins.size()),
                    firstError[0] == null ? "" : Language.join("\n\n", NftMessages.message(firstError[0]))));
            makeDismissable(progress);
            if (done[0] == coins.size()) watchBurialCompletion(act, tokenid, progress, onDone);
            return;
        }
        final JSONObject coin = coins.get(i);
        final JSONObject intent;
        try {
            intent = burialEntry(LocalStore.burialJournal(act, tokenid), coin.optString("coinid"));
            if (intent == null) throw BurialEvidence.invalid();
            if (!intent.optString("output").isEmpty()) {
                done[0]++;
                buryNext(act, tokenid, creatorPk, coins, i + 1, done, firstError, progress, onDone);
                return;
            }
        } catch (RuntimeException e) {
            setMessage(progress, Language.msg(l10n_nft_burial_evidence_missing));
            makeDismissable(progress); return;
        }
        setMessage(progress, Language.msg(l10n_nft_actions_burying_item, i + 1, coins.size()));
        final String txn = "bc" + shortId(coin.optString("coinid", ""));
        boolean stamped = StateNft.stamped(coin) != null;
        java.util.List<String> cmds = StateNft.buryCommands(txn, tokenid, stamped ? "" : creatorPk, coin, true);
        CmdChain.run(act.node(), cmds, "txndelete id:" + txn, new CmdChain.Done() {
            @Override public void ok(JSONObject last) {
                act.node().cmd("txndelete id:" + txn, NOOP);
                try {
                    JSONObject receipt = BurialEvidence.receipt(last, intent);
                    org.json.JSONArray journal = LocalStore.burialJournal(act, tokenid);
                    boolean replaced = false;
                    for (int j = 0; j < journal.length(); j++) {
                        if (BurialEvidence.same(coin.optString("coinid"), journal.getJSONObject(j).optString("input"))) {
                            journal.put(j, receipt); replaced = true; break;
                        }
                    }
                    if (!replaced || !LocalStore.saveBurialJournal(act, tokenid, journal)) throw BurialEvidence.invalid();
                    done[0]++;
                } catch (Exception e) {
                    setMessage(progress, Language.msg(l10n_nft_burial_evidence_missing));
                    makeDismissable(progress); return;
                }
                buryNext(act, tokenid, creatorPk, coins, i + 1, done, firstError, progress, onDone);
            }
            @Override public void fail(String message) {
                if (firstError[0] == null) firstError[0] = message == null ? "" : message;
                buryNext(act, tokenid, creatorPk, coins, i + 1, done, firstError, progress, onDone);
            }
        });
    }

    /** Reuse the StateNFT Suite's bounded polling cadence, requiring exact graveyard outputs too. */
    private static void watchBurialCompletion(MainActivity act, String tokenid,
                                              Sheet.Progress progress, Runnable onDone) {
        final org.json.JSONArray journal;
        try {
            journal = LocalStore.burialJournal(act, tokenid);
            if (journal.length() == 0) throw BurialEvidence.invalid();
            for (int i = 0; i < journal.length(); i++) BurialEvidence.id(journal.getJSONObject(i), "output");
        } catch (Exception e) {
            setMessage(progress, Language.msg(l10n_nft_burial_evidence_missing));
            makeDismissable(progress); return;
        }
        setMessage(progress, Language.msg(l10n_nft_burial_waiting));
        makeDismissable(progress);
        final Handler handler = new Handler(Looper.getMainLooper());
        final int[] tries = {0};
        final Runnable[] poll = new Runnable[1];
        final Runnable retry = () -> {
            if (++tries[0] >= WATCH_TRIES) {
                setMessage(progress, Language.msg(l10n_nft_burial_unconfirmed));
                makeDismissable(progress);
            } else handler.postDelayed(poll[0], WATCH_INTERVAL_MS);
        };
        poll[0] = () -> {
            if (act.isFinishing() || act.isDestroyed()) return;
            verifyBurialOutputs(act, tokenid, journal, 0, () -> {
                act.node().cmd("coins relevant:true own:true tokenid:" + tokenid, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject json) {
                        if (act.isFinishing() || act.isDestroyed()) return;
                        try {
                            if (BurialEvidence.coins(json, tokenid).length() != 0) { retry.run(); return; }
                            // A newer burial attempt changes the journal; an old watcher cannot finalize it.
                            if (!BurialEvidence.sameJournal(journal, LocalStore.burialJournal(act, tokenid), tokenid)) return;
                            if (!LocalStore.completeBurial(act, tokenid, journal)) throw BurialEvidence.invalid();
                        } catch (RuntimeException e) { retry.run(); return; }
                        setMessage(progress, Language.msg(l10n_nft_burial_confirmed));
                        makeDismissable(progress);
                        act.reload();
                        if (onDone != null) onDone.run();
                    }
                    @Override public void onError(String message) { retry.run(); }
                });
            }, retry);
        };
        handler.postDelayed(poll[0], WATCH_INTERVAL_MS);
    }

    private static void verifyBurialOutputs(MainActivity act, String tokenid, org.json.JSONArray journal,
                                            int i, Runnable confirmed, Runnable retry) {
        if (act.isFinishing() || act.isDestroyed()) return;
        if (i >= journal.length()) { confirmed.run(); return; }
        JSONObject receipt = journal.optJSONObject(i);
        act.node().cmd("coins coinid:" + receipt.optString("output"), new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                if (act.isFinishing() || act.isDestroyed()) return;
                try {
                    org.json.JSONArray coins = BurialEvidence.coins(json, tokenid);
                    if (coins.length() != 1 || !BurialEvidence.confirmed(receipt, coins.optJSONObject(0))) { retry.run(); return; }
                } catch (RuntimeException e) { retry.run(); return; }
                verifyBurialOutputs(act, tokenid, journal, i + 1, confirmed, retry);
            }
            @Override public void onError(String message) { retry.run(); }
        });
    }

    // ===================== departure watch =====================

    /** Validate the chosen item again at submission; a dialog may have outlived its mint state. */
    private static JSONObject departureIntent(MainActivity act, String tokenid, JSONObject coin, Sheet.Progress progress) {
        if (!LocalStore.canDepart(act, tokenid)) {
            setMessage(progress, Language.msg(l10n_nft_actions_mint_unfinished));
            makeDismissable(progress); return null;
        }
        try {
            if (StateNft.stamped(coin) == null) throw BurialEvidence.invalid();
            return BurialEvidence.intent(coin, tokenid);
        } catch (RuntimeException e) {
            setMessage(progress, Language.msg(l10n_nft_actions_invalid_departure));
            makeDismissable(progress); return null;
        }
    }

    private static void confirmDeparture(MainActivity act, String tokenid, JSONObject intent, String destination,
                                          JSONObject posted, Sheet.Progress progress, CharSequence successMsg) {
        try {
            JSONObject receipt = BurialEvidence.receipt(posted, intent, destination);
            watchDeparture(act, tokenid, receipt, progress, successMsg);
        } catch (RuntimeException e) {
            setMessage(progress, Language.msg(l10n_nft_actions_output_unverified));
            makeDismissable(progress);
        }
    }

    /** A post acknowledgement or missing input is insufficient: verify the exact intended output. */
    private static void watchDeparture(MainActivity act, String tokenid, JSONObject receipt,
                                       Sheet.Progress progress, CharSequence successMsg) {
        final String outputid, destination;
        try {
            BurialEvidence.journal(new org.json.JSONArray().put(receipt), tokenid);
            outputid = BurialEvidence.id(receipt, "output");
            destination = BurialEvidence.id(receipt, "address");
        } catch (RuntimeException e) {
            setMessage(progress, Language.msg(l10n_nft_actions_output_unverified));
            makeDismissable(progress); return;
        }
        final Handler h = new Handler(Looper.getMainLooper());
        final int[] tries = {0};
        final Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            if (act.isFinishing() || act.isDestroyed()) return;
            act.node().cmd("coins coinid:" + outputid, new NodeApi.Cb() {
                @Override public void onResult(JSONObject json) {
                    if (act.isFinishing() || act.isDestroyed()) return;
                    try {
                        org.json.JSONArray coins = BurialEvidence.coins(json, tokenid);
                        if (coins.length() == 1 && BurialEvidence.confirmed(receipt, coins.optJSONObject(0), destination)) {
                            setMessage(progress, successMsg);
                            makeDismissable(progress);
                            act.reload(); return;
                        }
                    } catch (RuntimeException e) { /* Keep an incomplete response unconfirmed. */ }
                    reschedule();
                }
                @Override public void onError(String message) {
                    if (act.isFinishing() || act.isDestroyed()) return;
                    if (NodeApi.ERR_TOO_LONG.equals(message)) {
                        setMessage(progress, Language.msg(l10n_nft_actions_unconfirmed_large));
                        makeDismissable(progress); return;
                    }
                    reschedule();
                }
                private void reschedule() {
                    if (++tries[0] >= WATCH_TRIES) {
                        setMessage(progress, Language.msg(l10n_nft_actions_unconfirmed_checks, WATCH_TRIES));
                        makeDismissable(progress);
                    } else h.postDelayed(poll[0], WATCH_INTERVAL_MS);
                }
            });
        };
        h.postDelayed(poll[0], WATCH_INTERVAL_MS);
    }

    // ===================== plumbing =====================

    /**
     * Both ids are replayed into node commands, and both arrive as chain data — the node emits a
     * JSON-shaped token name unescaped, so a hostile token can inject synthetic id strings into a
     * balance/coins reply. Anything that isn't plain hex never reaches a command.
     */
    private static boolean idsSafe(MainActivity act, String tokenid, JSONObject coin) {
        String coinid = coin == null ? "" : coin.optString("coinid", "");
        if (Util.isValidHexId(tokenid) && Util.isValidHexId(coinid)) return true;
        Sheet.create(act, Language.msg(l10n_nft_actions_refuse_coin))
                .subtitle(Language.msg(l10n_nft_actions_bad_coin_id))
                .action(Language.msg(l10n_language_close), Sheet.Style.SECONDARY, null)
                .show();
        return false;
    }

    private static final NodeApi.Cb NOOP = new NodeApi.Cb() {
        @Override public void onResult(JSONObject json) {}
        @Override public void onError(String message) {}
    };

    private static Sheet.Progress progressDialog(MainActivity act, CharSequence title, CharSequence msg) {
        return Sheet.progress(act, title, msg);
    }

    private static void setMessage(Sheet.Progress d, CharSequence msg) { d.text(msg); }

    /** The work is over — let the user close it, and give them the button. */
    private static void makeDismissable(Sheet.Progress d) { d.finish(); }

    private static String shortId(String coinid) {
        String c = coinid == null ? "" : coinid.replace("0x", "");
        return c.length() > 10 ? c.substring(0, 10) : c;
    }

    private static int dp(MainActivity act, int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density);
    }
}
