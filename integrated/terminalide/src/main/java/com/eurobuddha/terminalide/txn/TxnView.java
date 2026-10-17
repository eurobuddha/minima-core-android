package com.eurobuddha.terminalide.txn;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;


import android.app.Activity;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.eurobuddha.terminalide.BaseView;
import com.eurobuddha.terminalide.NodeApi;
import com.eurobuddha.terminalide.R;
import com.eurobuddha.terminalide.terminal.OutputFormatter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;

/**
 * Guided manual-UTXO transaction workbench over the node's txn* family, enforcing the
 * proven rules: sign BEFORE basics, never auto:true post for script coins, inputs must
 * equal outputs or the difference burns, txndelete on abandoned transactions.
 */
public class TxnView extends BaseView {

    NodeApi mNode;

    EditText mTxnId;
    TextView mStatus;
    TextView mSummary;

    public TxnView(Activity zActivity) {
        super(zActivity, R.layout.pm_terminalide_view_txn);

        mTxnId = mMainView.findViewById(R.id.pm_terminalide_txn_id);
        mStatus = mMainView.findViewById(R.id.pm_terminalide_txn_status);
        mSummary = mMainView.findViewById(R.id.pm_terminalide_txn_summary);

        wire(R.id.pm_terminalide_txn_create, v -> create());
        wire(R.id.pm_terminalide_txn_addinput, v -> pickCoin());
        wire(R.id.pm_terminalide_txn_addoutput, v -> outputDialog());
        wire(R.id.pm_terminalide_txn_change, v -> addChange());
        wire(R.id.pm_terminalide_txn_setstate, v -> stateDialog());
        wire(R.id.pm_terminalide_txn_sign, v -> signDialog());
        wire(R.id.pm_terminalide_txn_basics, v -> simple("txnbasics", Language.msg(l10n_terminal_txn_proofs_added)));
        wire(R.id.pm_terminalide_txn_check, v -> refresh());
        wire(R.id.pm_terminalide_txn_post, v -> postDialog());
        wire(R.id.pm_terminalide_txn_export, v -> exportTxn());
        wire(R.id.pm_terminalide_txn_import, v -> importDialog());
        wire(R.id.pm_terminalide_txn_delete, v -> deleteDialog());
        wire(R.id.pm_terminalide_txn_list, v -> listAll());
    }

    private void wire(int id, View.OnClickListener l) {
        ((Button) mMainView.findViewById(id)).setOnClickListener(l);
    }

    public void setNodeApi(NodeApi zNode) {
        mNode = zNode;
    }

    private String txnId() {
        String id = mTxnId.getText().toString().trim();
        if (id.isEmpty()) {
            Toast.makeText(mActivity, Language.msg(l10n_terminal_txn_id_required), Toast.LENGTH_SHORT).show();
            return null;
        }
        if (hasCommandChars(id)) {
            Toast.makeText(mActivity, Language.msg(l10n_terminal_txn_id_invalid), Toast.LENGTH_SHORT).show();
            return null;
        }
        return id;
    }

    /**
     * A field is concatenated straight into a space-delimited node command, so any
     * whitespace or ':' would inject/split params (amount "1 000" -> stray param,
     * address "Mx.. burn:9" -> unintended burn). Reject up front.
     */
    private static boolean hasCommandChars(String s) {
        return s.contains(" ") || s.contains("\t") || s.contains("\n")
                || s.contains(";") || s.contains(":");
    }

    /** Validates a value that legitimately may contain ':' (only reject whitespace/;). */
    private static boolean hasWhitespaceOrSemicolon(String s) {
        return s.contains(" ") || s.contains("\t") || s.contains("\n") || s.contains(";");
    }

    private boolean rejectBadField(CharSequence label, String value, boolean allowColon) {
        boolean bad = allowColon ? hasWhitespaceOrSemicolon(value) : hasCommandChars(value);
        if (bad) {
            Toast.makeText(mActivity, allowColon ? Language.msg(l10n_terminal_txn_field_invalid_colon, label) : Language.msg(l10n_terminal_txn_field_invalid, label), Toast.LENGTH_LONG).show();
        }
        return bad;
    }

    private void setStatus(CharSequence text, int color) {
        Language.setText(mStatus, text);
        mStatus.setTextColor(color);
    }

    // ---------------- actions ----------------

    private void create() {
        String id = txnId();
        if (id == null) return;
        run("txncreate id:" + id, Language.msg(l10n_terminal_txn_created, id), true);
    }

    private void pickCoin() {
        String id = txnId();
        if (id == null) return;
        setStatus(Language.msg(l10n_terminal_txn_loading_coins), OutputFormatter.COL_DIM);
        // Shrink the reply at the SOURCE with simplestate:true (drops the large per-coin
        // state blobs) — an over-limit Binder reply crashes app+node uncatchably (see
        // minima-ipc-gotchas). We keep relevant+sendable (never hide coins by age) and
        // the coin picker doesn't need state, so this is lossless for our purpose.
        mNode.cmd("coins relevant:true sendable:true simplestate:true", new NodeApi.Cb() {
            @Override
            public void onResult(JSONObject json) {
                JSONArray coins = json.optJSONArray("response");
                if (coins == null || coins.length() == 0) {
                    setStatus(Language.msg(l10n_terminal_txn_no_coins), OutputFormatter.COL_ERROR);
                    return;
                }
                int n = Math.min(coins.length(), 100);
                boolean capped = coins.length() > n;
                String[] labels = new String[n];
                String[] coinids = new String[n];
                for (int i = 0; i < n; i++) {
                    JSONObject c = coins.optJSONObject(i);
                    if (c == null || c.optString("coinid", "").isEmpty()) {
                        setStatus(Language.msg(l10n_terminal_txn_incomplete), OutputFormatter.COL_ERROR);
                        return;
                    }
                    String amount = c.optString("tokenamount", c.optString("amount", "?"));
                    String tokenid = c.optString("tokenid", "0x00");
                    String token = tokenid.equals("0x00") ? "MINIMA" : shorten(tokenid);
                    coinids[i] = c.optString("coinid", "");
                    labels[i] = amount + " " + token + "\n" + shorten(coinids[i]);
                }
                CharSequence title = capped ? Language.msg(l10n_terminal_txn_coin_title_capped, n, coins.length()) : Language.msg(l10n_terminal_txn_coin_title, coins.length());
                ((com.eurobuddha.terminalide.MainActivity)mActivity).languageDialog()
                        .setTitle(title)
                        .setItems(labels, (d, which) ->
                                run("txninput id:" + id + " coinid:" + coinids[which],
                                        Language.msg(l10n_terminal_txn_input_added), true))
                        .setNegativeButton(Language.msg(l10n_common_cancel), null)
                        .show();
            }

            @Override
            public void onError(String message) { nodeError(message); }
        });
    }

    private void outputDialog() {
        String id = txnId();
        if (id == null) return;
        View v = mActivity.getLayoutInflater().inflate(R.layout.pm_terminalide_dialog_output, null);
        Language.apply(v);
        EditText amount = v.findViewById(R.id.pm_terminalide_out_amount);
        EditText address = v.findViewById(R.id.pm_terminalide_out_address);
        EditText tokenid = v.findViewById(R.id.pm_terminalide_out_tokenid);
        CheckBox storestate = v.findViewById(R.id.pm_terminalide_out_storestate);
        ((com.eurobuddha.terminalide.MainActivity)mActivity).languageDialog()
                .setTitle(Language.msg(l10n_terminal_txn_add_output))
                .setMessage(Language.msg(l10n_terminal_txn_output_body))
                .setView(v)
                .setPositiveButton(Language.msg(l10n_terminal_txn_add), (d, w) -> {
                    String amt = amount.getText().toString().trim();
                    String addr = address.getText().toString().trim();
                    if (amt.isEmpty() || addr.isEmpty()) {
                        Toast.makeText(mActivity, Language.msg(l10n_terminal_txn_amount_address_required), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String tok = tokenid.getText().toString().trim();
                    if (rejectBadField(Language.msg(l10n_history_amount), amt, false) || rejectBadField(Language.msg(l10n_history_address), addr, false)
                            || rejectBadField(Language.msg(l10n_terminal_txn_token_id), tok, false)) {
                        return;
                    }
                    String cmd = "txnoutput id:" + id + " amount:" + amt + " address:" + addr
                            + (tok.isEmpty() ? "" : " tokenid:" + tok)
                            + " storestate:" + storestate.isChecked();
                    run(cmd, Language.msg(l10n_terminal_txn_output_added), true);
                })
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .show();
    }

    /**
     * One-tap change output: reads txncheck's per-token input/output difference and
     * sends every positive remainder back to one of your own addresses — the #1
     * guard against accidental burns.
     */
    // Guards the whole async change flow (txncheck → dialog → getaddress → txnoutput*)
    // so a double-tap can't run two flows that both see the same pre-change difference
    // and add change twice (outputs > inputs → rejected/over-send).
    private boolean mChangeInFlight = false;

    private void addChange() {
        String id = txnId();
        if (id == null) return;
        if (mChangeInFlight) {
            Toast.makeText(mActivity, Language.msg(l10n_terminal_txn_change_busy), Toast.LENGTH_SHORT).show();
            return;
        }
        mChangeInFlight = true;
        setStatus(Language.msg(l10n_terminal_txn_calculating), OutputFormatter.COL_DIM);
        mNode.cmd("txncheck id:" + id, new NodeApi.Cb() {
            @Override
            public void onResult(JSONObject json) {
                JSONObject resp = json.optJSONObject("response");
                JSONArray coins = resp != null ? resp.optJSONArray("coins") : null;
                if (coins == null) {
                    mChangeInFlight = false;
                    setStatus(Language.join("✖ ", Language.msg(l10n_terminal_txn_missing_txn, id)), OutputFormatter.COL_ERROR);
                    return;
                }
                final java.util.List<String[]> changes = new java.util.ArrayList<>();  // {tokenid, amount}
                StringBuilder planned = new StringBuilder();
                for (int i = 0; i < coins.length(); i++) {
                    JSONObject c = coins.optJSONObject(i);
                    if (c == null || c.optString("tokenid", "").isEmpty()) {
                        mChangeInFlight = false;
                        setStatus(Language.msg(l10n_terminal_txn_incomplete), OutputFormatter.COL_ERROR);
                        return;
                    }
                    String tokenid = c.optString("tokenid", "0x00");
                    String diff = c.optString("difference", "");
                    try {
                        int balance = new BigDecimal(diff).compareTo(BigDecimal.ZERO);
                        if (balance < 0) {
                            mChangeInFlight = false;
                            setStatus(Language.msg(l10n_terminal_txn_negative_change), OutputFormatter.COL_ERROR);
                            return;
                        }
                        if (balance > 0) {
                            changes.add(new String[]{tokenid, diff});
                            planned.append(diff).append(" ")
                                    .append(tokenid.equals("0x00") ? "MINIMA" : shorten(tokenid))
                                    .append("\n");
                        }
                    } catch (NumberFormatException invalid) {
                        mChangeInFlight = false;
                        setStatus(Language.msg(l10n_terminal_txn_incomplete), OutputFormatter.COL_ERROR);
                        return;
                    }
                }
                if (changes.isEmpty()) {
                    mChangeInFlight = false;
                    setStatus(Language.msg(l10n_terminal_txn_balanced),
                            OutputFormatter.COL_STRING);
                    return;
                }
                ((com.eurobuddha.terminalide.MainActivity)mActivity).languageDialog()
                        .setTitle(Language.msg(l10n_terminal_txn_change_title))
                        .setMessage(Language.msg(l10n_terminal_txn_change_body, planned.toString()))
                        .setPositiveButton(Language.msg(l10n_terminal_txn_add_change), (d, w) -> fetchAddressAndAdd(id, changes))
                        .setNegativeButton(Language.msg(l10n_common_cancel), (d, w) -> mChangeInFlight = false)
                        .setOnCancelListener(d -> mChangeInFlight = false)
                        .show();
            }

            @Override
            public void onError(String message) {
                mChangeInFlight = false;
                nodeError(message);
            }
        });
    }

    private void fetchAddressAndAdd(String id, java.util.List<String[]> changes) {
        mNode.cmd("getaddress", new NodeApi.Cb() {
            @Override
            public void onResult(JSONObject json) {
                JSONObject resp = json.optJSONObject("response");
                String addr = resp != null ? resp.optString("miniaddress",
                        resp.optString("address", "")) : "";
                if (addr.isEmpty()) {
                    mChangeInFlight = false;
                    setStatus(Language.msg(l10n_terminal_txn_address_failed), OutputFormatter.COL_ERROR);
                    return;
                }
                addChangeOutputs(id, addr, changes, 0);
            }

            @Override
            public void onError(String message) {
                mChangeInFlight = false;
                nodeError(message);
            }
        });
    }

    private void addChangeOutputs(String id, String addr, java.util.List<String[]> changes, int idx) {
        if (idx >= changes.size()) {
            mChangeInFlight = false;
            setStatus(Language.msg(l10n_terminal_txn_change_added, changes.size()), OutputFormatter.COL_STRING);
            refresh();
            return;
        }
        String[] ch = changes.get(idx);
        String cmd = "txnoutput id:" + id + " amount:" + ch[1] + " address:" + addr
                + (ch[0].equals("0x00") ? "" : " tokenid:" + ch[0]) + " storestate:false";
        mNode.cmd(cmd, new NodeApi.Cb() {
            @Override
            public void onResult(JSONObject json) {
                if (!json.optBoolean("status", false)) {
                    // Mid-chain failure: tokens 0..idx-1 got change, idx.. did NOT and will
                    // still BURN. Name what's left exposed so the user can fix or txndelete.
                    mChangeInFlight = false;
                    StringBuilder left = new StringBuilder();
                    for (int j = idx; j < changes.size(); j++) {
                        left.append(changes.get(j)[1]).append(" ")
                                .append(changes.get(j)[0].equals("0x00") ? "MINIMA"
                                        : shorten(changes.get(j)[0])).append("\n");
                    }
                    setStatus(Language.msg(l10n_terminal_txn_change_failed, idx + 1, changes.size(), json.has("error") ? json.optString("error") : Language.msg(l10n_terminal_txn_output_failed), left.toString()),
                            OutputFormatter.COL_ERROR);
                    return;
                }
                addChangeOutputs(id, addr, changes, idx + 1);
            }

            @Override
            public void onError(String message) {
                mChangeInFlight = false;
                nodeError(message);
            }
        });
    }

    private void stateDialog() {
        String id = txnId();
        if (id == null) return;
        LinearLayout box = new LinearLayout(mActivity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (mActivity.getResources().getDisplayMetrics().density * 20);
        box.setPadding(pad, 0, pad, 0);
        EditText port = new EditText(mActivity);
        Language.hint(port, Language.msg(l10n_terminal_txn_port_hint));
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText value = new EditText(mActivity);
        Language.hint(value, Language.msg(l10n_terminal_txn_value_hint));
        box.addView(port);
        box.addView(value);
        ((com.eurobuddha.terminalide.MainActivity)mActivity).languageDialog()
                .setTitle(Language.msg(l10n_terminal_txn_state_title))
                .setMessage(Language.msg(l10n_terminal_txn_state_body))
                .setView(box)
                .setPositiveButton(Language.msg(l10n_terminal_txn_set), (d, w) -> {
                    String p = port.getText().toString().trim();
                    String val = value.getText().toString().trim();
                    if (p.isEmpty() || val.isEmpty()) {
                        Toast.makeText(mActivity, Language.msg(l10n_terminal_txn_port_value_required), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // value may be hex (0x..) but must not carry spaces/';' onto the command line.
                    if (rejectBadField(Language.msg(l10n_terminal_txn_port), p, false) || rejectBadField(Language.msg(l10n_terminal_txn_value), val, true)) return;
                    run("txnstate id:" + id + " port:" + p + " value:" + val, Language.msg(l10n_terminal_txn_state_set), true);
                })
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .show();
    }

    private void signDialog() {
        String id = txnId();
        if (id == null) return;
        EditText key = new EditText(mActivity);
        Language.hint(key, Language.msg(l10n_terminal_txn_key_hint));
        ((com.eurobuddha.terminalide.MainActivity)mActivity).languageDialog()
                .setTitle(Language.msg(l10n_terminal_txn_sign_title))
                .setMessage(Language.msg(l10n_terminal_txn_sign_body))
                .setView(key)
                .setPositiveButton(Language.msg(l10n_terminal_txn_sign), (d, w) -> {
                    String k = key.getText().toString().trim();
                    if (rejectBadField(Language.msg(l10n_terminal_txn_public_key), k, false)) return;
                    run("txnsign id:" + id + " publickey:" + (k.isEmpty() ? "auto" : k),
                            Language.msg(l10n_terminal_txn_signed), true);
                })
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .show();
    }

    private void simple(String cmd, CharSequence okMsg) {
        String id = txnId();
        if (id == null) return;
        run(cmd + " id:" + id, okMsg, true);
    }

    private void postDialog() {
        String id = txnId();
        if (id == null) return;
        EditText burn = new EditText(mActivity);
        Language.hint(burn, Language.msg(l10n_terminal_txn_burn_hint));
        burn.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        ((com.eurobuddha.terminalide.MainActivity)mActivity).languageDialog()
                .setTitle(Language.msg(l10n_terminal_txn_post_title))
                .setMessage(Language.msg(l10n_terminal_txn_post_body))
                .setView(burn)
                .setPositiveButton(Language.msg(l10n_terminal_txn_post), (d, w) -> {
                    String b = burn.getText().toString().trim();
                    if (rejectBadField(Language.msg(l10n_explorer_burn), b, false)) return;
                    run("txnpost id:" + id + (b.isEmpty() ? "" : " burn:" + b),
                            Language.msg(l10n_terminal_txn_posted), true);
                })
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .show();
    }

    private void exportTxn() {
        String id = txnId();
        if (id == null) return;
        setStatus(Language.msg(l10n_terminal_txn_exporting), OutputFormatter.COL_DIM);
        mNode.cmd("txnexport id:" + id, new NodeApi.Cb() {
            @Override
            public void onResult(JSONObject json) {
                JSONObject resp = json.optJSONObject("response");
                String data = resp != null ? resp.optString("data", "") : "";
                if (data.isEmpty()) {
                    setStatus(Language.msg(l10n_terminal_txn_export_failed, json.has("error") ? json.optString("error") : Language.msg(l10n_terminal_txn_no_data)),
                            OutputFormatter.COL_ERROR);
                    return;
                }
                if (!com.eurobuddha.terminalide.terminal.SessionExport.copy(mActivity, data, "txn")) {
                    setStatus(Language.msg(l10n_terminal_txn_copy_failed), OutputFormatter.COL_ERROR);
                    return;
                }
                setStatus(Language.msg(l10n_terminal_txn_exported, data.length()), OutputFormatter.COL_STRING);
            }

            @Override
            public void onError(String message) { nodeError(message); }
        });
    }

    private void importDialog() {
        String id = txnId();
        if (id == null) return;
        EditText data = new EditText(mActivity);
        Language.hint(data, Language.msg(l10n_terminal_txn_import_hint));
        ((com.eurobuddha.terminalide.MainActivity)mActivity).languageDialog()
                .setTitle(Language.msg(l10n_terminal_txn_import_title))
                .setView(data)
                .setPositiveButton(Language.msg(l10n_wallet_import), (d, w) -> {
                    String hex = data.getText().toString().trim();
                    if (hex.isEmpty()) return;
                    if (rejectBadField(Language.msg(l10n_terminal_txn_import_data), hex, false)) return;
                    run("txnimport id:" + id + " data:" + hex, Language.msg(l10n_terminal_txn_imported), true);
                })
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .show();
    }

    private void deleteDialog() {
        String id = txnId();
        if (id == null) return;
        ((com.eurobuddha.terminalide.MainActivity)mActivity).languageDialog()
                .setTitle(Language.msg(l10n_terminal_editor_delete_title, id))
                .setMessage(Language.msg(l10n_terminal_txn_delete_body))
                .setPositiveButton(Language.msg(l10n_backup_delete), (d, w) -> run("txndelete id:" + id, Language.msg(l10n_terminal_txn_deleted), false))
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .show();
    }

    private void listAll() {
        setStatus(Language.msg(l10n_terminal_txn_listing), OutputFormatter.COL_DIM);
        mNode.cmd("txnlist", new NodeApi.Cb() {
            @Override
            public void onResult(JSONObject json) {
                // txnlist returns EVERY pending txn with full inputs/outputs/MMR proofs —
                // rendering the raw pretty-print can be huge and janks the main thread.
                // Show a compact id + in/out-count summary instead.
                JSONArray arr = json.optJSONArray("response");
                if (arr == null || arr.length() == 0) {
                    setStatus(Language.msg(l10n_terminal_txn_no_pending), OutputFormatter.COL_DIM);
                    return;
                }
                Language.TextBuilder sb = new Language.TextBuilder().append(Language.msg(l10n_terminal_txn_pending, arr.length())).append("\n\n");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject t = arr.optJSONObject(i);
                    if (t == null) continue;
                    String tid = t.optString("id", "?");
                    JSONObject txn = t.optJSONObject("transaction");
                    int ins = txn != null && txn.optJSONArray("inputs") != null
                            ? txn.optJSONArray("inputs").length() : 0;
                    int outs = txn != null && txn.optJSONArray("outputs") != null
                            ? txn.optJSONArray("outputs").length() : 0;
                    sb.append("• ").append(tid).append("  ")
                            .append(Language.msg(l10n_terminal_txn_in_out, ins, outs)).append("\n");
                }
                sb.append("\n").append(Language.msg(l10n_terminal_txn_inspect));
                setStatus(sb.build(), OutputFormatter.COL_PLAIN);
            }

            @Override
            public void onError(String message) { nodeError(message); }
        });
    }

    // ---------------- helpers ----------------

    private void run(String cmd, CharSequence okMsg, boolean refreshAfter) {
        setStatus("… " + cmd, OutputFormatter.COL_DIM);
        mNode.cmd(cmd, new NodeApi.Cb() {
            @Override
            public void onResult(JSONObject json) {
                if (!json.optBoolean("status", false)) {
                    setStatus(Language.join("✖ ", cmd, "\n", json.has("error") ? json.optString("error") : Language.msg(l10n_future_error_failed), "\n\n", Language.msg(l10n_terminal_txn_delete_tip)),
                            OutputFormatter.COL_ERROR);
                    return;
                }
                setStatus(Language.join("✓ ", okMsg), OutputFormatter.COL_STRING);
                if (refreshAfter) refresh();
            }

            @Override
            public void onError(String message) { nodeError(message); }
        });
    }

    /** Re-reads txncheck for the current txn and renders the balance summary. */
    public void refresh() {
        if (mTxnId.getText().toString().trim().isEmpty() || mNode == null) return;
        String id = txnId();
        if (id == null) return;
        mNode.cmd("txncheck id:" + id, new NodeApi.Cb() {
            @Override
            public void onResult(JSONObject json) {
                if (!id.equals(mTxnId.getText().toString().trim())) return;
                if (!json.optBoolean("status", false)) {
                    Language.setText(mSummary, Language.msg(l10n_terminal_txn_missing_txn, id));
                    mSummary.setTextColor(OutputFormatter.COL_DIM);
                    return;
                }
                JSONObject resp = json.optJSONObject("response");
                Language.TextBuilder sb = new Language.TextBuilder();
                boolean burnWarning = false;
                if (resp != null) {
                    JSONArray coins = resp.optJSONArray("coins");
                    if (coins != null) {
                        for (int i = 0; i < coins.length(); i++) {
                            JSONObject c = coins.optJSONObject(i);
                            if (c == null) {
                                Language.setText(mSummary, Language.msg(l10n_terminal_txn_incomplete));
                                mSummary.setTextColor(OutputFormatter.COL_ERROR);
                                return;
                            }
                            String tokenid = c.optString("tokenid", "0x00");
                            String in = c.optString("input", "?");
                            String outp = c.optString("output", "?");
                            String diff = c.optString("difference", "?");
                            sb.append(Language.msg(l10n_terminal_txn_balance_row, tokenid.equals("0x00") ? "MINIMA" : shorten(tokenid), in, outp, diff)).append("\n");
                            try {
                                if (new BigDecimal(diff).compareTo(BigDecimal.ZERO) > 0) burnWarning = true;
                            } catch (NumberFormatException invalid) {
                                Language.setText(mSummary, Language.msg(l10n_terminal_txn_incomplete));
                                mSummary.setTextColor(OutputFormatter.COL_ERROR);
                                return;
                            }
                        }
                    }
                    String burn = resp.optString("burn", "");
                    if (!burn.isEmpty()) sb.append(Language.msg(l10n_terminal_txn_burn_value, burn)).append("\n");
                    JSONObject valid = resp.optJSONObject("valid");
                    if (valid != null) {
                        Object signatures = resp.opt("allsignaturesvalid");
                        if (!(signatures instanceof Boolean)) signatures = valid.opt("signatures");
                        sb.append(Language.msg(l10n_terminal_txn_valid, valid.optBoolean("basic"), (signatures instanceof Boolean ? signatures : "?"), valid.optBoolean("mmrproofs"), valid.optBoolean("scripts")));
                    }
                }
                if (burnWarning) {
                    sb.append("\n").append(Language.msg(l10n_terminal_txn_burn_warning));
                }
                Language.setText(mSummary, sb.build().length() == 0 ? Language.msg(l10n_terminal_txn_exists) : sb.build());
                mSummary.setTextColor(burnWarning ? OutputFormatter.COL_BOOL : OutputFormatter.COL_PLAIN);
            }

            @Override
            public void onError(String message) { /* summary is best-effort */ }
        });
    }

    private void nodeError(String message) {
        if (NodeApi.ERR_NOT_ENABLED.equals(message)) {
            setStatus(Language.msg(l10n_explorer_node_retry),
                    OutputFormatter.COL_ERROR);
        } else {
            setStatus(Language.join("✖ ", com.eurobuddha.terminalide.terminal.TerminalMessages.message(message)), OutputFormatter.COL_ERROR);
        }
    }

    private static String shorten(String hex) {
        return hex.length() > 18 ? hex.substring(0, 12) + "…" + hex.substring(hex.length() - 4) : hex;
    }
}
