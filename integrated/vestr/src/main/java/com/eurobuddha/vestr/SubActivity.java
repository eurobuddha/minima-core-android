package com.eurobuddha.vestr;

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

    protected NodeApi node;
    protected LinearLayout form;

    /** Wire up the shared chrome (insets, header, back, NodeApi) then hand off to the subclass via init(). */
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.pm_vestr_activity_sub);
        final View root = findViewById(R.id.pm_vestr_subRoot);
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
    @Override protected void onDestroy() { super.onDestroy(); if (node != null) node.onDestroy(); }

    /** Set the header title. */
    protected void title(String t) { ((TextView) findViewById(R.id.pm_vestr_subTitle)).setText(t); }

    // ---- form helpers ----
    // These build the vestr-styled form rows in code (no XML) and append them to `form` in order;
    // the caller keeps the returned view to read/update it later.

    /** Bold caption row above an input. */
    protected TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(VestrDesign.LABEL);
        t.setTextSize(13f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(16), 0, dp(6));
        form.addView(t);
        return t;
    }

    /** Carded text field; inputType is one of the T_* constants below. */
    protected EditText input(String hint, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
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
    protected TextView pickerButton(String initial) {
        TextView t = new TextView(this);
        t.setText(initial);
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
    protected TextView primaryButton(String text) {
        TextView b = new TextView(this);
        b.setText(text);
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
    protected void setStatus(TextView t, String msg, boolean ok) {
        t.setVisibility(View.VISIBLE);
        t.setText(msg);
        t.setTextColor(ok ? VestrDesign.GREEN : VestrDesign.RED);
    }

    /** dp → px for the current screen density. */
    protected int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    protected static final int T_TEXT = InputType.TYPE_CLASS_TEXT;
    protected static final int T_DEC = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
    protected static final int T_PASS = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
}
