package com.eurobuddha.terminalide.terminal;

import android.content.Context;
import android.content.res.Configuration;
import android.text.Selection;
import android.text.Spannable;
import android.widget.TextView;
import java.util.function.Supplier;

/** Live app-owned labels around literal, colorized terminal output. Owned by its existing view. */
public final class TerminalLabel extends androidx.appcompat.widget.AppCompatTextView {
    private Supplier<CharSequence> display;
    private boolean rendering;
    private String fullOutput;
    public void setFullOutput(String value) { fullOutput = value; }
    public String exportText() { return fullOutput == null ? getText().toString() : fullOutput; }
    public TerminalLabel(Context context) { super(context); }
    public void bind(Supplier<CharSequence> value) { display = value; refreshDisplay(); }
    public void refreshDisplay() {
        if (display == null) return;
        CharSequence value = display.get();
        if (getText().toString().equals(value.toString())) return;
        int start = getSelectionStart(), end = getSelectionEnd();
        String previous = getText().toString();
        rendering = true;
        try { setText(value); } finally { rendering = false; }
        // Preserve a selected literal prefix; translated status words may change length.
        if (start >= 0 && end >= 0 && getText() instanceof Spannable
                && Math.max(start,end) <= value.length()
                && previous.substring(0, Math.max(start,end)).equals(value.subSequence(0,Math.max(start,end)).toString()))
            Selection.setSelection((Spannable)getText(), start, end);
    }
    @Override public void setText(CharSequence value, TextView.BufferType type) {
        if (!rendering) display = null; // a completed/replaced result must never resurrect an old status
        super.setText(value,type);
    }
    @Override public void setTextIsSelectable(boolean selectable) {
        Supplier<CharSequence> previous = display;
        super.setTextIsSelectable(selectable);
        display = previous;
    }
    @Override protected void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config); refreshDisplay();
    }
    public static void refreshTree(android.view.View view) {
        if (view instanceof TerminalLabel) ((TerminalLabel)view).refreshDisplay();
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup)view;
            for (int i=0;i<group.getChildCount();i++) refreshTree(group.getChildAt(i));
        }
    }
}
