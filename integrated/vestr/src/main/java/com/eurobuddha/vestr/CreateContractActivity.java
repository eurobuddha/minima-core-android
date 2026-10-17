package com.eurobuddha.vestr;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Create a vesting contract — token, withdrawal address, amount, start/end, grace, burn → send state:{}. */
public class CreateContractActivity extends SubActivity {



    private String script = "";
    private int tip = 0;
    private final List<String> tokenIds = new ArrayList<>();
    private final List<CharSequence> tokenNames = new ArrayList<>();

    private Spinner tokenSpinner, graceSpinner;
    private EditText addressInput, amountInput, burnInput, passwordInput;
    private TextView startBtn, endBtn, createBtn, status;
    private long startMs = 0, endMs = 0;

    /** Build the form, then kick off the three async fetches (tip, token list, default address). */
    @Override
    protected void init() {
        title(Language.msg(l10n_vestr_create));
        script = getIntent().getStringExtra("script");
        if (script == null) script = "";
        buildForm();
        fetchBlock();
        fetchTokens();
        fetchDefaultAddress();
    }

    /** Lay out every field; spinners start seeded with Minima and get replaced once fetches land. */
    private void buildForm() {
        label(Language.msg(l10n_vestr_token));
        tokenSpinner = new Spinner(this);
        tokenNames.add("Minima"); tokenIds.add("0x00");
        tokenSpinner.setAdapter(tokenAdapter());
        form.addView(tokenSpinner);

        label(Language.msg(l10n_vestr_withdrawal));
        addressInput = input(Language.msg(l10n_vestr_address_hint), T_TEXT);

        label(Language.msg(l10n_vestr_amount));
        amountInput = input("0.0", T_DEC);

        label(Language.msg(l10n_vestr_start));
        startBtn = pickerButton(Language.msg(l10n_vestr_pick_start));
        startBtn.setOnClickListener(v -> pickDateTime(startMs, ms -> { startMs = ms; Language.setText(startBtn, Language.stamp(ms, "yMMMdHm")); }));

        label(Language.msg(l10n_vestr_end));
        endBtn = pickerButton(Language.msg(l10n_vestr_pick_end));
        endBtn.setOnClickListener(v -> pickDateTime(endMs, ms -> { endMs = ms; Language.setText(endBtn, Language.stamp(ms, "yMMMdHm")); }));

        label(Language.msg(l10n_vestr_grace_help));
        graceSpinner = new Spinner(this);
        List<CharSequence> graces = new ArrayList<>();
        for (VestingContract.Grace g : VestingContract.Grace.values()) graces.add(VestrText.grace(g));
        ArrayAdapter<CharSequence> ga = VestrText.choices(this, graces);
        ga.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        graceSpinner.setAdapter(ga);
        graceSpinner.setSelection(1);   // Daily (the dapp default)
        form.addView(graceSpinner);

        label(Language.msg(l10n_vestr_burn));
        burnInput = input("0", T_DEC);

        label(Language.msg(l10n_vestr_password_help));
        passwordInput = input(Language.msg(l10n_vestr_password), T_PASS);

        createBtn = primaryButton(Language.msg(l10n_vestr_review_create));
        createBtn.setOnClickListener(v -> submit());
        status = status();
    }

    /** Spinner adapter backed by the live tokenNames list (rebuilt when the balance fetch returns). */
    private ArrayAdapter<CharSequence> tokenAdapter() {
        ArrayAdapter<CharSequence> a = VestrText.choices(this, tokenNames);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    // ---- node fetches ----

    /** Cache the current chain tip — needed to convert start/end dates to block heights at submit time. */
    private void fetchBlock() {
        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                if (r != null) try { tip = Integer.parseInt(r.optString("block", "0")); } catch (Exception ignored) {}
            }
            @Override public void onError(String m) {}
        });
    }

    /** Replace the seed token list with the wallet's real balances (id + name + sendable). */
    private void fetchTokens() {
        node.cmd("balance", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONArray arr = j.optJSONArray("response");
                if (arr == null) return;
                tokenIds.clear(); tokenNames.clear();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject b = arr.optJSONObject(i);
                    if (b == null) continue;
                    String tid = b.optString("tokenid", "0x00");
                    CharSequence name = VestrText.token(b.opt("token"), tid);
                    tokenIds.add(tid); tokenNames.add(Language.join(name, "  (", Util.tidyAmount(b.optString("sendable", "0")), ")"));
                }
                if (tokenIds.isEmpty()) { tokenIds.add("0x00"); tokenNames.add("Minima"); }
                tokenSpinner.setAdapter(tokenAdapter());
            }
            @Override public void onError(String m) {}
        });
    }

    /** Pre-fill the withdrawal field with one of the node's own addresses (only if still blank). */
    private void fetchDefaultAddress() {
        node.cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                String a = r == null ? "" : r.optString("miniaddress", r.optString("address", ""));
                if (!a.isEmpty() && addressInput.getText().length() == 0) Language.setText(addressInput, a);
            }
            @Override public void onError(String m) {}
        });
    }

    // ---- submit ----

    /** Validate the form, then resolve the address before building+sending the contract. */
    private void submit() {
        final String tokenid = tokenIds.get(Math.max(0, tokenSpinner.getSelectedItemPosition()));
        final String addr = addressInput.getText().toString().trim();
        final String amountStr = amountInput.getText().toString().trim();
        final VestingContract.Grace grace = VestingContract.Grace.values()[graceSpinner.getSelectedItemPosition()];
        final String burn = burnInput.getText().toString().trim();
        final String pw = passwordInput.getText().toString().trim();

        // Validation order matters: each guard assumes the earlier ones passed (amount parsed before
        // sign check; both dates picked before the future/ordering checks; tip last so a still-syncing
        // node fails with the friendliest message rather than a confusing earlier one).
        if (addr.isEmpty() || !Util.isValidAddress(addr)) { setStatus(status, Language.msg(l10n_vestr_valid_address), false); return; }
        final BigDecimal amount;
        try { amount = new BigDecimal(amountStr); } catch (Exception e) { setStatus(status, Language.msg(l10n_vestr_valid_amount), false); return; }
        if (amount.signum() <= 0) { setStatus(status, Language.msg(l10n_vestr_positive), false); return; }
        if (startMs == 0) { setStatus(status, Language.msg(l10n_vestr_start_required), false); return; }
        if (endMs == 0) { setStatus(status, Language.msg(l10n_vestr_end_required), false); return; }
        if (startMs <= System.currentTimeMillis()) { setStatus(status, Language.msg(l10n_vestr_future_start), false); return; }
        if (endMs <= startMs) { setStatus(status, Language.msg(l10n_vestr_end_after), false); return; }
        if (tip == 0) { setStatus(status, Language.msg(l10n_vestr_syncing_tip), false); return; }

        // Resolve to the 0x hex form — the contract compares GETOUTADDR (always 0x) against state[0],
        // so a user-entered Mx... address must be normalised here or the on-chain check would never match.
        // checkaddress runs async, so the actual send is deferred to doSend() in the callback below.
        createBtn.setEnabled(false);
        setStatus(status, Language.msg(l10n_vestr_validating), true);
        node.cmd("checkaddress address:" + addr, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if (!j.optBoolean("status", false)) { createBtn.setEnabled(true); setStatus(status, Language.msg(l10n_vestr_invalid_address), false); return; }
                JSONObject r = j.optJSONObject("response");
                String hex = r == null ? "" : r.optString("0x", "");
                if (hex.isEmpty()) hex = addr;
                doSend(hex, amount, tokenid, grace, burn, pw);
            }
            @Override public void onError(String m) {
                createBtn.setEnabled(true);
                setStatus(status, NodeApi.ERR_NOT_ENABLED.equals(m) ? Language.msg(l10n_explorer_node_retry) : Language.msg(l10n_vestr_validate_failed), false);
            }
        });
    }

    /** Build the contract state map and `send` the amount to the script address with that state attached. */
    private void doSend(String unlockHex, BigDecimal amount, String tokenid, VestingContract.Grace grace, String burn, String pw) {
        // State ports the script reads: 0 unlock addr, 1 amount, 2/3 start/end block, 4 grace blocks,
        // 5 created-at ms, 6/8 start/end ms (display), 7 grace hours, 199 unique id (distinguishes coins).
        long startBlock = VestingContract.blockHeightForDate(tip, startMs);
        long endBlock = VestingContract.blockHeightForDate(tip, endMs);
        long graceBlocks = grace.blocks();
        long now = System.currentTimeMillis();
        String uid = "0x" + randomHex(32);

        StringBuilder state = new StringBuilder("{");
        state.append("\"0\":\"").append(unlockHex).append("\",");
        state.append("\"1\":\"").append(amount.toPlainString()).append("\",");
        state.append("\"2\":\"").append(startBlock).append("\",");
        state.append("\"3\":\"").append(endBlock).append("\",");
        state.append("\"4\":\"").append(graceBlocks).append("\",");
        state.append("\"5\":\"").append(now).append("\",");
        state.append("\"6\":\"").append(startMs).append("\",");
        state.append("\"7\":\"").append(grace.hours).append("\",");
        state.append("\"8\":\"").append(endMs).append("\",");
        state.append("\"199\":\"").append(uid).append("\"}");

        StringBuilder cmd = new StringBuilder("send amount:").append(amount.toPlainString())
                .append(" address:").append(script)
                .append(" tokenid:").append(tokenid)
                .append(" state:").append(state);
        if (!pw.isEmpty()) cmd.append(" password:").append(pw);
        if (!burn.isEmpty() && isPositive(burn)) cmd.append(" burn:").append(burn);

        createBtn.setEnabled(false);
        setStatus(status, Language.msg(l10n_vestr_creating), true);
        node.cmd(cmd.toString(), new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                boolean ok = j.optBoolean("status", false);
                boolean pending = j.optBoolean("pending", false);
                if (ok || pending) {
                    setStatus(status, pending ? Language.msg(l10n_vestr_pending)
                            : Language.msg(l10n_vestr_created), true);
                    startBtn.postDelayed(CreateContractActivity.this::finish, 1400);
                } else {
                    createBtn.setEnabled(true);
                    setStatus(status, j.has("error") ? j.optString("error") : Language.msg(l10n_vestr_create_failed), false);
                }
            }
            @Override public void onError(String m) {
                createBtn.setEnabled(true);
                setStatus(status, NodeApi.ERR_NOT_ENABLED.equals(m) ? Language.msg(l10n_explorer_node_retry) : VestrMessages.message(m), false);
            }
        });
    }

    // ---- date-time picker ----

    /** Callback delivering the chosen instant in epoch millis. */
    private interface MsCb { void on(long ms); }

    /** Show a date picker, then chain a time picker from its result; only the combined instant fires cb. */
    private void pickDateTime(long initial, final MsCb cb) {
        final Calendar c = Calendar.getInstance();
        if (initial > 0) c.setTimeInMillis(initial);
        // DatePicker first; its onDateSet writes Y/M/D into c, then opens the TimePicker which writes
        // H/M and finally hands the full timestamp to cb. cb never fires if the user cancels either dialog.
        languagePicker(() -> new DatePickerDialog(this, (dp, y, mo, d) -> {
            c.set(Calendar.YEAR, y); c.set(Calendar.MONTH, mo); c.set(Calendar.DAY_OF_MONTH, d);
            languagePicker(() -> new TimePickerDialog(this, (tp, h, mi) -> {
                c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, mi); c.set(Calendar.SECOND, 0);
                cb.on(c.getTimeInMillis());
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true)).show();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))).show();
    }

    /** True if s parses as a strictly-positive number. */
    private boolean isPositive(String s) { try { return new BigDecimal(s).signum() > 0; } catch (Exception e) { return false; } }

    /** Random uppercase-hex string of the given length (used for the contract's unique state[199] id). */
    private String randomHex(int chars) {
        Random r = new Random();
        StringBuilder s = new StringBuilder();
        String hex = "0123456789ABCDEF";
        for (int i = 0; i < chars; i++) s.append(hex.charAt(r.nextInt(16)));
        return s.toString();
    }
}
