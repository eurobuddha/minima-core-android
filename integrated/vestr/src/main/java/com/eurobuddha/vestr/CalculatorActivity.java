package com.eurobuddha.vestr;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Vesting calculator — preview tokens released per grace period before creating a contract. */
public class CalculatorActivity extends SubActivity {

    private final SimpleDateFormat fmt = new SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.ENGLISH);
    private int tip = 0;
    private long startMs = 0, endMs = 0;
    private EditText amountInput;
    private Spinner graceSpinner;
    private TextView startBtn, endBtn, result;

    /** Build the preview form and fetch the chain tip (needed to convert dates to block heights). */
    @Override
    protected void init() {
        title("Calculate a contract");

        label("Token amount");
        amountInput = input("0.0", T_DEC);

        label("Contract start");
        startBtn = pickerButton("Select start date & time");
        startBtn.setOnClickListener(v -> pick(startMs, ms -> { startMs = ms; startBtn.setText(fmt.format(ms)); }));

        label("Contract end");
        endBtn = pickerButton("Select end date & time");
        endBtn.setOnClickListener(v -> pick(endMs, ms -> { endMs = ms; endBtn.setText(fmt.format(ms)); }));

        label("Grace period");
        graceSpinner = new Spinner(this);
        List<String> g = new ArrayList<>();
        for (VestingContract.Grace gr : VestingContract.Grace.values()) g.add(gr.label);
        ArrayAdapter<String> ga = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, g);
        ga.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        graceSpinner.setAdapter(ga);
        graceSpinner.setSelection(1);
        form.addView(graceSpinner);

        primaryButton("Calculate").setOnClickListener(v -> calculate());
        result = status();

        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                if (r != null) try { tip = Integer.parseInt(r.optString("block", "0")); } catch (Exception ignored) {}
            }
            @Override public void onError(String m) {}
        });
    }

    /** Validate inputs, then show the amount released each grace period (preview only — no transaction). */
    private void calculate() {
        // Same guard ordering as Create: parse amount, then dates picked, then ordering, then tip last.
        BigDecimal amount;
        try { amount = new BigDecimal(amountInput.getText().toString().trim()); }
        catch (Exception e) { setStatus(result, "Enter a valid amount.", false); return; }
        if (startMs == 0 || endMs == 0) { setStatus(result, "Pick start and end dates.", false); return; }
        if (endMs <= startMs) { setStatus(result, "End must be after the start.", false); return; }
        if (tip == 0) { setStatus(result, "Still syncing — try again in a moment.", false); return; }

        long startBlock = VestingContract.blockHeightForDate(tip, startMs);
        long endBlock = VestingContract.blockHeightForDate(tip, endMs);
        VestingContract.Grace grace = VestingContract.Grace.values()[graceSpinner.getSelectedItemPosition()];
        BigDecimal perGrace = VestingContract.paymentPerGrace(amount, startBlock, endBlock, grace.blocks());
        setStatus(result, "Released per " + grace.label + ": " + ContractUi.amount(perGrace), true);
    }

    /** Callback delivering the chosen instant in epoch millis. */
    private interface MsCb { void on(long ms); }

    /** Show a date picker, then chain a time picker from its result; only the combined instant fires cb. */
    private void pick(long initial, final MsCb cb) {
        final Calendar c = Calendar.getInstance();
        if (initial > 0) c.setTimeInMillis(initial);
        // DatePicker first, then TimePicker chained off its result; cb gets the full timestamp, and
        // never fires if the user cancels either dialog.
        new DatePickerDialog(this, (dp, y, mo, d) -> {
            c.set(Calendar.YEAR, y); c.set(Calendar.MONTH, mo); c.set(Calendar.DAY_OF_MONTH, d);
            new TimePickerDialog(this, (tp, h, mi) -> {
                c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, mi); c.set(Calendar.SECOND, 0);
                cb.on(c.getTimeInMillis());
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }
}
