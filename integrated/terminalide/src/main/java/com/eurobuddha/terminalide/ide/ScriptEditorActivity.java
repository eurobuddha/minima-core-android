package com.eurobuddha.terminalide.ide;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.LanguageDialog;
import static com.eurobuddha.minimacore.i18n.R.string.*;


import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.eurobuddha.terminalide.NodeApi;
import com.eurobuddha.terminalide.R;
import com.eurobuddha.terminalide.terminal.OutputFormatter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * KISS VM script editor: live syntax highlighting, char counter with the ~1200-char
 * danger line, static lints for the proven silent killers, offline testing against the
 * node's own VM (runscript with state/prevstate/globals/signatures + full trace), and
 * deploy via newscript.
 */
public class ScriptEditorActivity extends AppCompatActivity {

    @Override public boolean onCreateOptionsMenu(android.view.Menu menu) {
        menu.add(0, 1, 0, Language.msg(l10n_terminal_editor_reference));
        menu.add(0, 2, 1, Language.msg(l10n_terminal_ide_docs));
        return true;
    }
    private final java.util.Set<AlertDialog> languageDialogs = new java.util.HashSet<>();
    private LanguageDialog languageDialog() {
        return new LanguageDialog(this) {
            @Override public AlertDialog create() {
                AlertDialog dialog = super.create();
                languageDialogs.add(dialog);
                dialog.setOnDismissListener(d -> languageDialogs.remove(dialog));
                return dialog;
            }
        };
    }
    private void refreshLanguage() {
        Toolbar toolbar = findViewById(R.id.pm_terminalide_editor_toolbar);
        if (toolbar == null) return;
        if (toolbar.getMenu().findItem(1) != null) toolbar.getMenu().findItem(1).setTitle(Language.msg(l10n_terminal_editor_reference));
        if (toolbar.getMenu().findItem(2) != null) toolbar.getMenu().findItem(2).setTitle(Language.msg(l10n_terminal_ide_docs));
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
        refreshLanguage();
    }
    @Override protected void onResume() { super.onResume(); refreshLanguage(); }

    ScriptDB mDB;
    NodeApi mNode;
    long mScriptId;

    EditText mName;
    EditText mEditor;
    TextView mStatusLine;
    LinearLayout mRunConfig;
    EditText mState, mPrevState, mGlobals, mSignatures;
    TextView mResults;
    android.widget.HorizontalScrollView mChipScroller;
    LinearLayout mChipRow;

    List<LintEngine.Lint> mLints;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.pm_terminalide_activity_script_editor);
        View editorRoot = findViewById(R.id.pm_terminalide_editor_root);
        Language.apply(editorRoot);
        ViewCompat.setOnApplyWindowInsetsListener(editorRoot, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            // Pad for the keyboard too — edge-to-edge otherwise hides the editor's
            // action row and the field being typed into.
            v.setPadding(systemBars.left, systemBars.top, systemBars.right,
                    Math.max(systemBars.bottom, ime.bottom));
            return insets;
        });
        ViewCompat.requestApplyInsets(editorRoot);

        Toolbar tb = findViewById(R.id.pm_terminalide_editor_toolbar);
        Language.toolbarTitle(tb, Language.msg(l10n_terminal_editor_title));
        setSupportActionBar(tb);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        tb.setNavigationOnClickListener(v -> finish());

        tb.setOnMenuItemClickListener(mi -> {
            if (mi.getItemId() == 1) {
                showKissReference();
                return true;
            }
            if (mi.getItemId() == 2) {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://docs.minima.global")));
                return true;
            }
            return false;
        });

        mDB = new ScriptDB(this);
        mNode = new NodeApi(this, null);

        mScriptId = getIntent().getLongExtra("script_id", -1);
        ScriptDB.Script script = mDB.get(mScriptId);
        if (script == null) {
            Toast.makeText(this, Language.msg(l10n_terminal_editor_not_found), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        mName = findViewById(R.id.pm_terminalide_editor_name);
        mEditor = findViewById(R.id.pm_terminalide_editor_source);
        mStatusLine = findViewById(R.id.pm_terminalide_editor_status);
        mRunConfig = findViewById(R.id.pm_terminalide_editor_runconfig);
        mState = findViewById(R.id.pm_terminalide_editor_state);
        Language.hint(mState, Language.msg(l10n_terminal_editor_state, "{\"0\":\"0xFF..\",\"6\":\"1\"}"));
        mPrevState = findViewById(R.id.pm_terminalide_editor_prevstate);
        Language.hint(mPrevState, Language.msg(l10n_terminal_editor_prevstate, "{\"0\":\"0xFF..\",\"6\":\"0\"}"));
        mGlobals = findViewById(R.id.pm_terminalide_editor_globals);
        Language.hint(mGlobals, Language.msg(l10n_terminal_editor_globals, "{\"@COINAGE\":\"64\",\"@AMOUNT\":\"10\"}"));
        mSignatures = findViewById(R.id.pm_terminalide_editor_signatures);
        Language.hint(mSignatures, Language.msg(l10n_terminal_editor_signatures, "[\"0xFF..\"]"));
        mResults = findViewById(R.id.pm_terminalide_editor_results);

        mName.setText(script.name);
        mEditor.setText(script.source);
        mState.setText(script.testState);
        mPrevState.setText(script.testPrevState);
        mGlobals.setText(script.testGlobals);
        mSignatures.setText(script.testSignatures);
        if (!script.testState.isEmpty() || !script.testPrevState.isEmpty()
                || !script.testGlobals.isEmpty() || !script.testSignatures.isEmpty()) {
            mRunConfig.setVisibility(View.VISIBLE);
            Language.setText((Button) findViewById(R.id.pm_terminalide_editor_toggle_config), Language.msg(l10n_terminal_editor_inputs_open));
        }
        mChipScroller = findViewById(R.id.pm_terminalide_editor_chipscroller);
        mChipRow = findViewById(R.id.pm_terminalide_editor_chiprow);

        mEditor.addTextChangedListener(new KissHighlighter(mEditor));
        mEditor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                updateStatus();
                updateEditorChips();
            }
        });

        mStatusLine.setOnClickListener(v -> showLints());

        Button toggle = findViewById(R.id.pm_terminalide_editor_toggle_config);
        toggle.setOnClickListener(v -> {
            boolean show = mRunConfig.getVisibility() != View.VISIBLE;
            mRunConfig.setVisibility(show ? View.VISIBLE : View.GONE);
            Language.setText(toggle, show ? Language.msg(l10n_terminal_editor_inputs_open) : Language.msg(l10n_terminal_editor_inputs_closed));
        });

        findViewById(R.id.pm_terminalide_editor_check).setOnClickListener(v -> check());
        findViewById(R.id.pm_terminalide_editor_run).setOnClickListener(v -> run());
        findViewById(R.id.pm_terminalide_editor_deploy).setOnClickListener(v -> deployDialog(script));

        if (!script.address.isEmpty()) {
            setResults(Language.msg(l10n_terminal_editor_deployed_at, script.address), OutputFormatter.COL_STRING);
        }

        updateStatus();
        // Highlight the initial content.
        new KissHighlighter(mEditor).highlight(mEditor.getText());
    }

    private void save() {
        // mName is null when onCreate bailed out (missing script) — onPause still fires.
        if (mScriptId < 0 || mName == null) return;
        String name = mName.getText().toString().trim();
        if (name.isEmpty()) name = Language.msg(l10n_terminal_template_untitled).toString();
        mDB.update(mScriptId, name, mEditor.getText().toString());
        mDB.saveTestInputs(mScriptId,
                mState.getText().toString().trim(),
                mPrevState.getText().toString().trim(),
                mGlobals.getText().toString().trim(),
                mSignatures.getText().toString().trim());
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }

    @Override
    protected void onDestroy() {
        for (AlertDialog dialog : new java.util.ArrayList<>(languageDialogs)) dialog.dismiss();
        languageDialogs.clear();
        super.onDestroy();
        if (mNode != null) mNode.onDestroy();
        if (mDB != null) mDB.close();
    }

    /** Bundled KISS VM language reference (assets/kissvm.txt). */
    private void showKissReference() {
        CharSequence ref = Language.msg(l10n_terminal_reference_body);
        TextView tv = new TextView(this);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextSize(12);
        tv.setTextColor(OutputFormatter.COL_PLAIN);
        tv.setTextIsSelectable(true);
        Language.setText(tv, ref);
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        android.widget.ScrollView scroller = new android.widget.ScrollView(this);
        scroller.setPadding(pad, pad / 2, pad, pad / 2);
        scroller.addView(tv);
        languageDialog()
                .setTitle(Language.msg(l10n_terminal_editor_reference))
                .setView(scroller)
                .setPositiveButton(Language.msg(l10n_language_close), null)
                .show();
    }

    // ---------------- editor autocomplete ----------------

    /** Suggest KISS keywords / functions / @globals for the word being typed. */
    private void updateEditorChips() {
        mChipRow.removeAllViews();
        String text = mEditor.getText().toString();
        int cur = mEditor.getSelectionStart();
        if (cur < 0 || cur > text.length()) cur = text.length();
        int start = cur;
        while (start > 0) {
            char c = text.charAt(start - 1);
            if (Character.isLetterOrDigit(c) || c == '@') start--;
            else break;
        }
        String word = text.substring(start, cur);
        if (word.isEmpty()) {
            mChipScroller.setVisibility(View.GONE);
            return;
        }
        String uw = word.toUpperCase();

        java.util.List<String[]> matches = new java.util.ArrayList<>();   // {label, insert}
        if (uw.startsWith("@")) {
            for (String g : sorted(KissVm.GLOBALS)) {
                if (g.startsWith(uw) && !g.equals(uw)) matches.add(new String[]{g, g + " "});
            }
        } else {
            for (String k : sorted(KissVm.KEYWORDS)) {
                if (k.startsWith(uw) && !k.equals(uw)) matches.add(new String[]{k, k + " "});
            }
            for (String f : sorted(KissVm.FUNCTIONS)) {
                if (f.startsWith(uw) && !f.equals(uw)) matches.add(new String[]{f, f + "("});
            }
        }

        int count = 0;
        for (String[] m : matches) {
            if (count++ >= 20) break;
            TextView chip = new TextView(this);
            chip.setText(m[0]);
            chip.setTypeface(android.graphics.Typeface.MONOSPACE);
            chip.setTextSize(13);
            chip.setTextColor(OutputFormatter.COL_CMD);
            chip.setBackgroundResource(R.drawable.pm_terminalide_chip_bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 12, 0);
            chip.setLayoutParams(lp);
            int pad = (int) (getResources().getDisplayMetrics().density * 6);
            chip.setPadding(pad * 2, pad, pad * 2, pad);
            final String insert = m[1];
            chip.setOnClickListener(v -> {
                // Re-derive the word boundaries from the CURRENT caret — the caret may
                // have moved (a tap doesn't fire the TextWatcher) since chips were built.
                String t = mEditor.getText().toString();
                int c = mEditor.getSelectionStart();
                if (c < 0 || c > t.length()) c = t.length();
                int s = c;
                while (s > 0) {
                    char ch = t.charAt(s - 1);
                    if (Character.isLetterOrDigit(ch) || ch == '@') s--;
                    else break;
                }
                mEditor.getText().replace(s, c, insert);
                mEditor.setSelection(s + insert.length());
            });
            mChipRow.addView(chip);
        }
        mChipScroller.setVisibility(mChipRow.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        mChipScroller.scrollTo(0, 0);
    }

    private static java.util.List<String> sorted(java.util.Set<String> set) {
        java.util.List<String> list = new java.util.ArrayList<>(set);
        java.util.Collections.sort(list);
        return list;
    }

    // ---------------- status / lints ----------------

    private void updateStatus() {
        String src = mEditor.getText().toString();
        mLints = LintEngine.lint(src);
        int errors = 0, warns = 0;
        for (LintEngine.Lint l : mLints) {
            if (l.error) errors++; else warns++;
        }
        int len = src.trim().length();
        CharSequence size = len > KissVm.MAX_SAFE_CHARS ? Language.msg(l10n_terminal_editor_over_limit) : (len + "/" + KissVm.MAX_SAFE_CHARS);
        Language.TextBuilder line = new Language.TextBuilder().append(Language.msg(l10n_terminal_editor_chars, size));
        if (errors > 0) line.append(" · ✖ ").append(errors);
        if (warns > 0) line.append(" · ⚠ ").append(warns);
        if (errors == 0 && warns == 0) line.append(" · ✓ ").append(Language.msg(l10n_terminal_editor_no_issues));
        Language.setText(mStatusLine, line.build());
        mStatusLine.setTextColor(errors > 0 || len > KissVm.MAX_SAFE_CHARS
                ? OutputFormatter.COL_ERROR
                : (warns > 0 ? OutputFormatter.COL_BOOL : OutputFormatter.COL_DIM));
    }

    private void showLints() {
        if (mLints == null || mLints.isEmpty()) {
            Toast.makeText(this, Language.msg(l10n_terminal_editor_no_issues), Toast.LENGTH_SHORT).show();
            return;
        }
        Language.TextBuilder sb = new Language.TextBuilder();
        for (int i=0;i<mLints.size();i++) {
            LintEngine.Lint lint = mLints.get(i);
            if (i>0) sb.append("\n\n");
            sb.append(lint.error ? "✖ " : "⚠ ").append(EditorMessages.message(lint.message));
        }
        languageDialog()
                .setTitle(Language.msg(l10n_terminal_editor_lint_results))
                .setMessage(sb.build())
                .setPositiveButton(Language.msg(l10n_common_ok), null)
                .show();
    }

    // ---------------- check / run / deploy ----------------

    /** The script flattened for a command line (the node cleans it properly). */
    private String flatScript() {
        return mEditor.getText().toString()
                .replaceAll("(?s)/\\*.*?\\*/", " ")   // strip comments (keeps on-chain size honest)
                .replace("\n", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean validScript(String flat) {
        if (flat.isEmpty()) {
            Toast.makeText(this, Language.msg(l10n_terminal_editor_empty), Toast.LENGTH_SHORT).show();
            return false;
        }
        if (flat.contains("\"")) {
            Toast.makeText(this, Language.msg(l10n_terminal_editor_quotes), Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private String jsonParam(String key, EditText field, boolean array) {
        String raw = field.getText().toString().trim();
        if (raw.isEmpty()) return "";
        String minified;
        try {
            minified = array ? new JSONArray(raw).toString() : new JSONObject(raw).toString();
        } catch (JSONException e) {
            throw new IllegalArgumentException(key + " is not valid JSON: " + e.getMessage());
        }
        // Minifying only strips whitespace BETWEEN tokens — spaces (and ';') INSIDE a JSON
        // string value survive and would break the node's space-delimited tokenizer, or
        // worse, a ';' would start a second command. These fields hold hex/number values,
        // so reject any that can't ride the command line safely.
        if (minified.contains(" ") || minified.contains(";")) {
            throw new IllegalArgumentException(key + " values can't contain spaces or ';' "
                    + "(they'd break the node command line). Use hex/number values.");
        }
        return " " + key + ":" + minified;
    }

    private void check() {
        String flat = flatScript();
        if (!validScript(flat)) return;
        save();
        setResults(Language.msg(l10n_terminal_editor_checking), OutputFormatter.COL_DIM);
        mNode.cmd("runscript script:\"" + flat + "\"", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) { showCheckResult(json, false); }
            @Override public void onError(String message) { showNodeError(message); }
        });
    }

    private void run() {
        String flat = flatScript();
        if (!validScript(flat)) return;
        save();
        String cmd;
        try {
            cmd = "runscript script:\"" + flat + "\""
                    + jsonParam("state", mState, false)
                    + jsonParam("prevstate", mPrevState, false)
                    + jsonParam("globals", mGlobals, false)
                    + jsonParam("signatures", mSignatures, true);
        } catch (IllegalArgumentException e) {
            setResults(Language.join("✖ ", EditorMessages.error(e)), OutputFormatter.COL_ERROR);
            return;
        }
        setResults(Language.msg(l10n_terminal_editor_running), OutputFormatter.COL_DIM);
        mNode.cmd(cmd, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) { showCheckResult(json, true); }
            @Override public void onError(String message) { showNodeError(message); }
        });
    }

    private void showCheckResult(JSONObject json, boolean fullRun) {
        if (!json.optBoolean("status", false)) {
            setResults(Language.join("✖ ", json.has("error") ? json.optString("error") : Language.msg(l10n_terminal_editor_run_failed)), OutputFormatter.COL_ERROR);
            return;
        }
        JSONObject resp = json.optJSONObject("response");
        if (resp == null) {
            setResults(Language.msg(l10n_terminal_editor_empty_response), OutputFormatter.COL_ERROR);
            return;
        }
        boolean parseok = resp.optBoolean("parseok", false);
        JSONObject clean = resp.optJSONObject("clean");
        String address = clean != null ? clean.optString("address", "?") : "?";
        String cleanScript = clean != null ? clean.optString("script", "") : "";

        Language.TextBuilder sb = new Language.TextBuilder();
        sb.append(parseok ? Language.msg(l10n_terminal_editor_parse_ok) : Language.msg(l10n_terminal_editor_parse_failed));
        sb.append("\n").append(Language.msg(l10n_terminal_editor_address, address));
        sb.append("\n").append(Language.msg(l10n_terminal_editor_clean, cleanScript.length(), cleanScript));

        if (fullRun) {
            boolean success = resp.optBoolean("success", false);
            boolean monotonic = resp.optBoolean("monotonic", false);
            sb.append("\n\n").append(Language.msg(l10n_terminal_editor_result, success ? Language.msg(l10n_terminal_editor_spendable) : "✖ RETURN FALSE"));
            sb.append("\n").append(Language.msg(l10n_terminal_editor_monotonic, monotonic));
            Object vars = resp.opt("variables");
            if (vars != null) sb.append("\n\n").append(Language.msg(l10n_terminal_editor_variables)).append("\n").append(vars.toString());
            Object trace = resp.opt("trace");
            if (trace != null) sb.append("\n\n").append(Language.msg(l10n_terminal_editor_trace)).append("\n").append(trace.toString().replace("\\n", "\n"));
            setResults(sb.build(), success && parseok
                    ? OutputFormatter.COL_STRING : OutputFormatter.COL_ERROR);
        } else {
            setResults(sb.build(), parseok ? OutputFormatter.COL_STRING : OutputFormatter.COL_ERROR);
        }
    }

    private void deployDialog(ScriptDB.Script script) {
        String flat = flatScript();
        if (!validScript(flat)) return;
        save();

        View v = getLayoutInflater().inflate(R.layout.pm_terminalide_dialog_deploy, null);
        Language.apply(v);
        CheckBox trackall = v.findViewById(R.id.pm_terminalide_deploy_trackall);
        languageDialog()
                .setTitle(Language.msg(l10n_terminal_editor_deploy_title))
                .setMessage(Language.msg(l10n_terminal_editor_deploy_body))
                .setView(v)
                .setPositiveButton(Language.msg(l10n_terminal_editor_deploy), (d, w) -> deploy(flat, trackall.isChecked()))
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .show();
    }

    private void deploy(String flat, boolean trackall) {
        setResults(Language.msg(l10n_terminal_editor_deploying), OutputFormatter.COL_DIM);
        mNode.cmd("newscript trackall:" + trackall + " script:\"" + flat + "\"", new NodeApi.Cb() {
            @Override
            public void onResult(JSONObject json) {
                if (!json.optBoolean("status", false)) {
                    setResults(Language.join("✖ ", json.has("error") ? json.optString("error") : Language.msg(l10n_terminal_editor_newscript_failed)), OutputFormatter.COL_ERROR);
                    return;
                }
                JSONObject resp = json.optJSONObject("response");
                String address = resp != null ? resp.optString("address", "") : "";
                if (!address.isEmpty()) {
                    mDB.setAddress(mScriptId, address);
                }
                setResults(Language.msg(l10n_terminal_editor_deployed, address, address),
                        OutputFormatter.COL_STRING);
            }

            @Override
            public void onError(String message) { showNodeError(message); }
        });
    }

    private void showNodeError(String message) {
        if (NodeApi.ERR_NOT_ENABLED.equals(message)) {
            setResults(Language.msg(l10n_explorer_node_retry),
                    OutputFormatter.COL_ERROR);
        } else {
            setResults(Language.join("✖ ", com.eurobuddha.terminalide.terminal.TerminalMessages.message(message)), OutputFormatter.COL_ERROR);
        }
    }

    private void setResults(CharSequence text, int color) {
        Language.setText(mResults, text);
        mResults.setTextColor(color);
    }
}
