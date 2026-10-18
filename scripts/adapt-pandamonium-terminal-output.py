"""Bound the Terminal IDE display without truncating exported command results."""
from pathlib import Path
import shutil

def adapt(root):
    project=Path(__file__).resolve().parents[1]
    folder=root/'terminalide/src/main/java/com/eurobuddha/terminalide/terminal'
    for name in ['TerminalOutput.java', 'TerminalLabel.java']:
        shutil.copy2(project/'scripts/pandamonium-sources/terminalide'/name, folder/name)
    p=folder/'TerminalView.java';s=p.read_text()
    def change(old,new):
        nonlocal s
        assert old in s, ('Terminal output source drift', old)
        s=s.replace(old,new)
    change('    private static final int MAX_ENTRIES = 300;', """    private static final int MAX_ENTRIES = 300;
    private final java.util.concurrent.ThreadPoolExecutor formatter = TerminalOutput.worker();
    private boolean destroyed;
    private int generation;
    private static final int MAX_DISPLAY_CHARS = 120000;
    private static final int MAX_RETAINED_CHARS = 8 * 1024 * 1024;""")
    change('        mOutput.removeAllViews();', '        generation++;\n        formatter.getQueue().clear();\n        mOutput.removeAllViews();')
    change('        if (mHistory != null) mHistory.close();', '        destroyed = true;\n        formatter.shutdownNow();\n        if (mHistory != null) mHistory.close();')
    change('        final long start = System.currentTimeMillis();', '        final long start = System.currentTimeMillis();\n        final int requestGeneration = generation;')
    start=s.index('                long ms = System.currentTimeMillis() - start;')
    end=s.index('\n            }\n\n            @Override\n            public void onError',start)
    s=s[:start]+"""                if (destroyed || requestGeneration != generation || result.getParent() != mOutput) return;
                final long ms = System.currentTimeMillis() - start;
                final boolean failed = !json.optBoolean("status", true);
                try {
                    formatter.execute(() -> {
                        String pretty;
                        try { pretty = json.toString(2); }
                        catch (JSONException e) { pretty = json.toString(); }
                        final TerminalOutput output = new TerminalOutput(pretty);
                        mActivity.runOnUiThread(() -> {
                            if (destroyed || requestGeneration != generation || result.getParent() != mOutput) return;
                            // Only small help replies can use the retained translated help renderer.
                            CharSequence help = output.shortened ? null : renderHelp(zCommand, json);
                            final String helpSnapshot = help == null ? null : output.full;
                            final CharSequence raw = new android.text.SpannedString(help == null
                                    ? OutputFormatter.json(output.preview, false) : help);
                            result.setFullOutput(help == null ? output.full : null);
                            result.bind(() -> {
                                android.text.SpannableStringBuilder display = new android.text.SpannableStringBuilder(
                                        helpSnapshot == null ? raw : refreshHelp(zCommand, helpSnapshot, raw));
                                if (output.shortened) display.append(OutputFormatter.colored("\\n" + Language.msg(l10n_terminal_ide_preview), OutputFormatter.COL_DIM));
                                if (failed) display.append(OutputFormatter.colored("\\n" + Language.msg(l10n_terminal_ide_command_failed), OutputFormatter.COL_ERROR));
                                display.append(OutputFormatter.colored("\\n" + Language.msg(l10n_terminal_ide_milliseconds, ms), OutputFormatter.COL_DIM));
                                return display;
                            });
                            trimOutput();
                            scrollToBottom();
                        });
                    });
                } catch (java.util.concurrent.RejectedExecutionException busy) {
                    result.bind(() -> OutputFormatter.colored(Language.msg(l10n_terminal_ide_display_busy).toString(), OutputFormatter.COL_ERROR));
                }"""+s[end:]
    change('            public void onError(String message) {', '            public void onError(String message) {\n                if (destroyed || requestGeneration != generation || result.getParent() != mOutput) return;')
    change('else result.setText(OutputFormatter.colored("✖ " + message, OutputFormatter.COL_ERROR));', """else {
                        TerminalOutput output = new TerminalOutput("✖ " + message);
                        result.setFullOutput(output.full);
                        result.setText(OutputFormatter.colored(output.preview, OutputFormatter.COL_ERROR));
                    }""")
    change('sb.append(((TextView) mOutput.getChildAt(i)).getText().toString()).append("\\n\\n");', 'sb.append(((TerminalLabel) mOutput.getChildAt(i)).exportText()).append("\\n\\n");')
    change('final String text = block.getText().toString();', 'final String text = ((TerminalLabel)block).exportText();')
    change('        tv.setText(text);\n        tv.setPadding', '        TerminalOutput output = new TerminalOutput(text.toString());\n        tv.setText(output.shortened ? output.preview : text);\n        if (output.shortened) ((TerminalLabel)tv).setFullOutput(output.full);\n        tv.setPadding')
    change('        mOutput.addView(tv);', '        mOutput.addView(tv);\n        trimOutput();')
    change('    private void scrollToBottom() {', """    private void trimOutput() {
        long displayed = 0, retained = 0;
        for (int i=0; i<mOutput.getChildCount(); i++) {
            TerminalLabel label = (TerminalLabel)mOutput.getChildAt(i);
            displayed += label.length(); retained += label.exportText().length();
        }
        // As with MAX_ENTRIES, only old blocks are discarded. Keep the latest full reply.
        while (mOutput.getChildCount() > 1 && (displayed > MAX_DISPLAY_CHARS || retained > MAX_RETAINED_CHARS)) {
            TerminalLabel oldest = (TerminalLabel)mOutput.getChildAt(0);
            displayed -= oldest.length(); retained -= oldest.exportText().length();
            mOutput.removeViewAt(0);
        }
    }

    private void scrollToBottom() {""")
    change('    private int generation;', '    private int generation;\n    private int inFlight;')
    change('        generation++;', '        generation++;\n        inFlight = 0;')
    change('    private void execute(String zCommand) {', """    private void execute(String zCommand) {
        if (destroyed) return;
        // Admit before dispatch, so a saturated formatter never loses an accepted result.
        if (inFlight >= 8) {
            Toast.makeText(mActivity, Language.msg(l10n_terminal_ide_wait_commands), Toast.LENGTH_SHORT).show();
            return;
        }
        inFlight++;""")
    change('                            // Only small help replies', '                            inFlight--;\n                            // Only small help replies')
    change('                } catch (java.util.concurrent.RejectedExecutionException busy) {', '                } catch (java.util.concurrent.RejectedExecutionException busy) {\n                    inFlight--;')
    change('                if (NodeApi.ERR_NOT_ENABLED.equals(message)) {', '                inFlight--;\n                if (NodeApi.ERR_NOT_ENABLED.equals(message)) {')
    # A discarded/trimmed result still releases admission; a cleared generation owns its own count.
    change('if (destroyed || requestGeneration != generation || result.getParent() != mOutput) return;',
           'if (destroyed || requestGeneration != generation) return;\n                if (result.getParent() != mOutput) { inFlight--; return; }')
    s=s.replace('                            if (destroyed || requestGeneration != generation) return;\n                if (result.getParent()', '                            if (destroyed || requestGeneration != generation) return;\n                            if (result.getParent()')
    p.write_text(s)
    p=root/'terminalide/build.gradle';s=p.read_text().replace('    buildFeatures { buildConfig true }','    buildFeatures { buildConfig true }\n    testOptions { unitTests.includeAndroidResources = true }')
    s=s.replace('dependencies {',"dependencies {\n    testImplementation 'org.robolectric:robolectric:4.14.1'\n    testImplementation 'org.mockito:mockito-core:5.12.0'")
    p.write_text(s)
