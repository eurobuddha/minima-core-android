package com.eurobuddha.vestr;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** Base for vestr sub-screens (Create / Detail / Calculate): yellow header + back, own NodeApi, and
 *  small form-building helpers in the vestr style. */
public abstract class SubActivity extends AppCompatActivity {

    // Android caches the calendar grid locale. Recreate its window using the platform's
    // saved picker state, keeping callbacks and unconfirmed values without submitting them.
    private final java.util.Map<android.app.AlertDialog, java.util.function.Supplier<? extends android.app.AlertDialog>> languagePickers = new java.util.LinkedHashMap<>();
    protected <T extends android.app.AlertDialog> T languagePicker(java.util.function.Supplier<T> factory) {
        T picker = factory.get();
        languagePickers.put(picker, factory);
        picker.setOnDismissListener(d -> languagePickers.remove(picker));
        picker.setOnShowListener(d -> {
            Language.setText(picker.getButton(android.app.AlertDialog.BUTTON_POSITIVE), Language.msg(android.R.string.ok));
            Language.setText(picker.getButton(android.app.AlertDialog.BUTTON_NEGATIVE), Language.msg(android.R.string.cancel));
        });
        return picker;
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
        for (java.util.Map.Entry<android.app.AlertDialog, java.util.function.Supplier<? extends android.app.AlertDialog>> entry : new java.util.ArrayList<>(languagePickers.entrySet())) {
            android.app.AlertDialog old = entry.getKey();
            Bundle state = old.onSaveInstanceState();
            old.setOnDismissListener(null);
            old.dismiss();
            languagePickers.remove(old);
            android.app.AlertDialog replacement = languagePicker(entry.getValue());
            replacement.onRestoreInstanceState(state);
        }
    }
    protected NodeApi node;
    protected LinearLayout form;

    /** Wire up the shared chrome (insets, header, back, NodeApi) then hand off to the subclass via init(). */
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.pm_vestr_activity_sub);
        final View root = findViewById(R.id.pm_vestr_subRoot);
        Language.apply(root);
        final View header = findViewById(R.id.pm_vestr_subHeader);
        form = findViewById(R.id.pm_vestr_formContainer);
        final int headerTop = header.getPaddingTop();
        final int formBottom = form.getPaddingBottom();
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets in = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            header.setPadding(header.getPaddingLeft(), headerTop + in.top, header.getPaddingRight(), header.getPaddingBottom());
            form.setPadding(form.getPaddingLeft(), form.getPaddingTop(), form.getPaddingRight(), formBottom + in.bottom);
            return insets;
        });
        androidx.core.view.ViewCompat.requestApplyInsets(root);
        new androidx.core.view.WindowInsetsControllerCompat(getWindow(), root).setAppearanceLightStatusBars(false);
        findViewById(R.id.pm_vestr_backBtn).setOnClickListener(v -> finish());
        node = new NodeApi(this, enabled -> {});
        init();
    }

    /** Subclass builds its form here — runs after chrome/NodeApi are ready. */
    protected abstract void init();

    /** Release the node binding so we don't leak the service connection. */
    @Override protected void onDestroy() { for (android.app.AlertDialog d : new java.util.ArrayList<>(languagePickers.keySet())) d.dismiss(); languagePickers.clear(); super.onDestroy(); if (node != null) node.onDestroy(); }

    /** Set the header title. */
    protected void title(CharSequence t) { Language.setText((TextView) findViewById(R.id.pm_vestr_subTitle), t); }

    // ---- form helpers ----
    // These build the vestr-styled form rows in code (no XML) and append them to `form` in order;
    // the caller keeps the returned view to read/update it later.

    /** Bold caption row above an input. */
    protected TextView label(CharSequence text) {
        TextView t = new TextView(this);
        Language.setText(t, text);
        t.setTextColor(VestrDesign.LABEL);
        t.setTextSize(13f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(16), 0, dp(6));
        form.addView(t);
        return t;
    }

    /** Carded text field; inputType is one of the T_* constants below. */
    protected EditText input(CharSequence hint, int inputType) {
        EditText e = new EditText(this);
        Language.hint(e, hint);
        e.setHintTextColor(VestrDesign.DIM_2);
        e.setTextColor(VestrDesign.TEXT);
        e.setTextSize(15f);
        e.setInputType(inputType);
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(VestrDesign.CARD);
        bg.setCornerRadius(dp(6));
        bg.setStroke(dp(1), VestrDesign.BORDER_DARK);
        e.setBackground(bg);
        form.addView(e);
        return e;
    }

    /** Carded tappable row that looks like an input but opens a picker (date/time); caller sets the click + updates its text. */
    protected TextView pickerButton(CharSequence initial) {
        TextView t = new TextView(this);
        Language.setText(t, initial);
        t.setTextColor(VestrDesign.TEXT);
        t.setTextSize(15f);
        t.setPadding(dp(14), dp(13), dp(14), dp(13));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(VestrDesign.CARD);
        bg.setCornerRadius(dp(6));
        bg.setStroke(dp(1), VestrDesign.BORDER_DARK);
        t.setBackground(bg);
        form.addView(t);
        return t;
    }

    /** Full-width yellow CTA with top margin; caller attaches the action. */
    protected TextView primaryButton(CharSequence text) {
        TextView b = new TextView(this);
        Language.setText(b, text);
        b.setTextColor(VestrDesign.ON_YELLOW);
        b.setTextSize(16f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(android.view.Gravity.CENTER);
        b.setPadding(0, dp(15), 0, dp(15));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(VestrDesign.YELLOW);
        bg.setCornerRadius(dp(8));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(24);
        b.setLayoutParams(lp);
        form.addView(b);
        return b;
    }

    /** Hidden status line at the foot of the form; revealed by setStatus(). */
    protected TextView status() {
        TextView t = new TextView(this);
        t.setTextSize(13f);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(0, dp(14), 0, 0);
        t.setVisibility(View.GONE);
        form.addView(t);
        return t;
    }

    /** Show msg on the status line, green for ok / red for error. */
    protected void setStatus(TextView t, CharSequence msg, boolean ok) {
        t.setVisibility(View.VISIBLE);
        Language.setText(t, msg);
        t.setTextColor(ok ? VestrDesign.GREEN : VestrDesign.RED);
    }

    /** dp → px for the current screen density. */
    protected int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    protected static final int T_TEXT = InputType.TYPE_CLASS_TEXT;
    protected static final int T_DEC = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
    protected static final int T_PASS = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
}
