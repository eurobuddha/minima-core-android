"""PandaDEX retained shell bindings; reuse Casino messages and existing native view state."""
from pathlib import Path
import json,importlib.util

def adapt(root):
 project=Path(__file__).resolve().parents[1];folder=root/'pandadex/src/main/java/com/eurobuddha/pandadex'
 def change(s,old,new,count=1):
  assert s.count(old)==count,('PandaDEX language source drift',old,s.count(old));return s.replace(old,new)
 spec=json.loads((project/'localization/dex-messages.json').read_text());catalog=json.loads((project/'localization/catalog.json').read_text())
 loader=importlib.util.spec_from_file_location('dex_message_builder',project/'scripts/adapt-pandamonium-wallet-error-languages.py');m=importlib.util.module_from_spec(loader);loader.loader.exec_module(m)
 source=m.build_source(spec,catalog).replace('com.eurobuddha.wallet','com.eurobuddha.pandadex').replace('WalletMessages','DexMessages').replace('Explicit wallet failures only.','Explicit PandaDEX application labels only.').replace('adapt-pandamonium-wallet-error-languages.py from localization/wallet-errors.json','adapt-pandamonium-dex-languages.py from localization/dex-messages.json')
 source=change(source,'    public static CharSequence message(String value)', '''    public static CharSequence message(CharSequence value) {
        return value instanceof String ? message((String)value) : value == null ? "" : value;
    }
    /** Stored timestamp and unknown diagnostic payloads remain literal. Reuses Casino's log binding. */
    public static CharSequence logLine(String line) {
        int split = line.indexOf("  ");
        return split < 0 ? line : Language.join(line.substring(0, split + 2), message(line.substring(split + 2)));
    }
    public static CharSequence message(String value)''')
 (folder/'DexMessages.java').write_text(source)
 p=folder/'MainActivity.java';s=p.read_text()
 s=change(s,'package com.eurobuddha.pandadex;','package com.eurobuddha.pandadex;\nimport com.eurobuddha.minimacore.i18n.Language;\nimport com.eurobuddha.minimacore.i18n.LanguageDialog;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;')
 s=change(s,'    @Override protected void onResume() {','''    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        // Retain forms, tab, focus, callbacks and live transaction objects. Only rebind display text.
        Language.configurationChanged(this);
    }

    @Override protected void onResume() {''')
 s=change(s,'        headerChrome = header;','''        LinearLayout headerGroup = new LinearLayout(this);
        headerGroup.setOrientation(LinearLayout.VERTICAL);
        headerChrome = headerGroup;
        headerGroup.addView(header);
        LinearLayout nodeRow = new LinearLayout(this);
        nodeRow.setGravity(Gravity.CENTER_VERTICAL);
        nodeRow.setPadding(pad, 0, pad, Design.dp(this, 6));
        headerGroup.addView(nodeRow);''')
 s=change(s,'        themePill.setOnClickListener(v -> toggleTheme());','''        themePill.setMinWidth(Design.dp(this, 48));
        themePill.setMinHeight(Design.dp(this, 48));
        Language.description(themePill, Language.msg(Design.isDark() ? l10n_dex_theme_light : l10n_dex_theme_dark));
        themePill.setOnClickListener(v -> toggleTheme());''')
 s=change(s,'        header.addView(pairPill);','''        Language.setText(pairPill, DexMessages.message("PAIRING…"));
        nodeRow.addView(pairPill, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));''')
 s=change(s,'        header.addView(blockPill, bp);\n        root.addView(header);','        nodeRow.addView(blockPill, bp);\n        root.addView(headerGroup);')
 s=change(s,'            t.setText(TAB_NAMES[i]);','            Language.setText(t, DexMessages.message(TAB_NAMES[i]));')
 s=change(s,'            t.setPadding(0, Design.dp(this, 11), 0, Design.dp(this, 11));','''            t.setPadding(Design.dp(this, 12), Design.dp(this, 11), Design.dp(this, 12), Design.dp(this, 11));
            t.setMinHeight(Design.dp(this, 48));
            t.setMinWidth(Design.dp(this, 72));
            // Future Cash's retained selected-tab reveal: no focus move or screen rebuild.
            t.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    view.post(() -> {
                        if (view.isAttachedToWindow() && tab == idx)
                            view.requestRectangleOnScreen(new android.graphics.Rect(0, 0, view.getWidth(), view.getHeight()), true);
                    }));''')
 s=change(s,'            tabBar.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));','            tabBar.addView(t, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));')
 s=change(s,'        root.addView(tabBar);','''        android.widget.HorizontalScrollView tabScroll = new android.widget.HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setFillViewport(true);
        tabScroll.addView(tabBar, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        root.addView(tabScroll);''')
 s=change(s,'        tab = idx;\n        inputFocused = false;','''        tab = idx;
        android.view.View selected = tabBar.getChildAt(idx);
        selected.post(() -> {
            if (selected.isAttachedToWindow() && tab == idx)
                selected.requestRectangleOnScreen(new android.graphics.Rect(0, 0, selected.getWidth(), selected.getHeight()), true);
        });
        inputFocused = false;''')
 s=change(s,'        pairPill.setText(nodeLabel(paired, pairingKnown));','        Language.setText(pairPill, DexMessages.message(nodeLabel(paired, pairingKnown)));')
 s=change(s,'Toast.makeText(this, msg, Toast.LENGTH_SHORT)','Toast.makeText(this, DexMessages.message(msg), Toast.LENGTH_SHORT)')
 s=change(s,'''        logTitle.setText((busy ? "TRANSACTION IN PROGRESS" : awaitingFill != null ? "CHECKING ON-CHAIN" : "ACTIVITY LOG")
                + " · tap for log");''','''        Language.setText(logTitle, Language.msg(l10n_dex_log_tap,
                DexMessages.message(busy ? "TRANSACTION IN PROGRESS" : awaitingFill != null ? "CHECKING ON-CHAIN" : "ACTIVITY LOG")));''')
 s=change(s,'        StringBuilder recent = new StringBuilder();','        Language.TextBuilder recent = new Language.TextBuilder();')
 s=change(s,"            if (recent.length() > 0) recent.append('\\n');\n            recent.append(line);","            if (count > 1) recent.append('\\n');\n            recent.append(DexMessages.logLine(line));")
 s=change(s,'        logText.setText(recent.toString());','        Language.setText(logText, recent.build());')
 s=change(s,'            String full = fullLogText();','            CharSequence full = fullLogText();')
 s=change(s,'android.text.TextUtils.equals(logDialogText.getText(), full)','android.text.TextUtils.equals(logDialogText.getText(), full.toString())')
 s=change(s,'                logDialogText.setText(full);','                Language.setText(logDialogText, full);')
 s=change(s,'    private String fullLogText() {','    private CharSequence fullLogText() {')
 s=change(s,'        return android.text.TextUtils.join("\\n\\n", chronological);','''        Language.TextBuilder display = new Language.TextBuilder();
        boolean first = true;
        for (String line : chronological) {
            if (!first) display.append("\\n\\n");
            first = false;
            display.append(DexMessages.logLine(line));
        }
        return display.build();''')
 s=change(s,'        text.setText(fullLogText());','        Language.setText(text, fullLogText());')
 s=change(s,'new AlertDialog.Builder(this,Design.dialogTheme()).setTitle("Activity log")','new LanguageDialog(this,Design.dialogTheme()).setTitle(DexMessages.message("Activity log"))')
 s=change(s,'.setView(scroll).setPositiveButton("Close",null).create();','.setView(scroll).setPositiveButton(DexMessages.message("Close"),null).create();')
 p.write_text(s)
 # The importer copies canonical tests as part of its existing adaptation workflow.

 loader=importlib.util.spec_from_file_location("dex_trade_languages",project/"scripts/adapt-pandamonium-dex-trade-languages.py");m=importlib.util.module_from_spec(loader);loader.loader.exec_module(m);m.adapt(root)

 loader=importlib.util.spec_from_file_location("dex_history_languages",project/"scripts/adapt-pandamonium-dex-history-languages.py");m=importlib.util.module_from_spec(loader);loader.loader.exec_module(m);m.adapt(root)

 loader=importlib.util.spec_from_file_location("dex_orders_languages",project/"scripts/adapt-pandamonium-dex-orders-languages.py");m=importlib.util.module_from_spec(loader);loader.loader.exec_module(m);m.adapt(root)

 loader=importlib.util.spec_from_file_location("dex_quotes_languages",project/"scripts/adapt-pandamonium-dex-quotes-languages.py");m=importlib.util.module_from_spec(loader);loader.loader.exec_module(m);m.adapt(root)

 loader=importlib.util.spec_from_file_location("dex_assets_languages",project/"scripts/adapt-pandamonium-dex-assets-languages.py");m=importlib.util.module_from_spec(loader);loader.loader.exec_module(m);m.adapt(root)

 loader=importlib.util.spec_from_file_location("dex_export_languages",project/"scripts/adapt-pandamonium-dex-export-languages.py");m=importlib.util.module_from_spec(loader);loader.loader.exec_module(m);m.adapt(root)

 loader=importlib.util.spec_from_file_location("dex_maker_languages",project/"scripts/adapt-pandamonium-dex-maker-languages.py");m=importlib.util.module_from_spec(loader);loader.loader.exec_module(m);m.adapt(root)

 loader=importlib.util.spec_from_file_location("dex_maker_dialog_languages",project/"scripts/adapt-pandamonium-dex-maker-dialog-languages.py");m=importlib.util.module_from_spec(loader);loader.loader.exec_module(m);m.adapt(root)

 loader=importlib.util.spec_from_file_location("dex_notice_languages",project/"scripts/adapt-pandamonium-dex-notice-languages.py");m=importlib.util.module_from_spec(loader);loader.loader.exec_module(m);m.adapt(root)
