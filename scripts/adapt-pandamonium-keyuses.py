"""Embed the native KeyUses APK UI; reuse the mature audit engine and node-seed derivation."""
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET

def adapt(root):
    project=Path(__file__).resolve().parents[1]
    base=root/'keyreuses'
    folder=base/'src/main/java/com/eurobuddha/keyreuses'
    sources=project/'scripts/pandamonium-sources/keyreuses'
    p=base/'build.gradle';s=p.read_text();s=s.replace('dependencies {',"""dependencies {
    implementation project(':integrated-futurecash-next')
    implementation project(':integrated-wallet')
    testImplementation files('../../app/libs/minima.jar')
    testImplementation 'com.h2database:h2:2.1.214'
    testImplementation 'org.robolectric:robolectric:4.14.1'
    testImplementation 'org.mockito:mockito-core:5.12.0'""");s=s.replace('    buildFeatures { buildConfig true }','    buildFeatures { buildConfig true }\n    testOptions { unitTests.includeAndroidResources = true; unitTests.returnDefaultValues = true }');p.write_text(s)
    for name in ['Copy.java','AuditSelection.java']:
        shutil.copy2(sources/name,folder/name)
    # The same bounded HTTP client and incomplete-evidence rules already used by Future Cash.
    p=folder/'MainActivity.java';s=p.read_text()
    s=s.replace('package com.eurobuddha.keyreuses;','''package com.eurobuddha.keyreuses;
import com.eurobuddha.futurecashnext.KeyAudit;
import com.eurobuddha.futurecashnext.AuditApi;
import com.eurobuddha.futurecashnext.MinimaAddress;
import com.eurobuddha.minimacore.i18n.Language;''')
    s=s.replace('    private NodeApi node;','')
    s=s.replace('        node = new NodeApi(this, enabled -> setPaired(enabled));','        includeWallet=getSharedPreferences("keyuses_settings",MODE_PRIVATE).getBoolean("include_wallet",false);')
    s=s.replace('        buildUi();','        buildUi();\n        Language.addListener(languageChanged);',1)
    s=s.replace('        if (node != null) node.onDestroy();','        generation++;\n        auditWorker.shutdownNow();\n        Language.removeListener(languageChanged);')
    start=s.index('    private void runAudit() {');end=s.index('    /* ====================== rendering',start)
    s=s[:start]+(sources/'AuditFlow.java.inc').read_text()+'\n'+s[end:]
    s=s.replace('    private void renderResults(KeyAudit.Result r, long archiveTip) {','    private void renderResults(KeyAudit.Result r, long archiveTip) {\n        lastResult=r; lastTip=archiveTip;')
    s=s.replace('        if (r.derivationMismatch) {','        if (!r.complete) resultsBox.addView(noticeCard(Copy.incomplete(), Design.WARN));\n        resultsBox.addView(text(Copy.scope(r.classicKeys,r.blockKeys),12f,Design.DIM,Typeface.NORMAL));\n        if (r.derivationMismatch) {')
    s=s.replace('                color = Design.OK;\n                title = Copy.OK_TITLE;\n                body = Copy.OK_BODY;','                color = r.complete ? Design.OK : Design.WARN;\n                title = r.complete ? Copy.OK_TITLE : Copy.partialTitle();\n                body = r.complete ? Copy.OK_BODY : Copy.incomplete();')
    s=s.replace('if (r.classicCount > 0)', 'if (r.complete && r.classicKeys > 0 && r.recommendedKeyUses < 262144)').replace('r.blockCount','r.blockKeys')
    s=s.replace('r.blockScheme','r.family == KeyAudit.Family.BLOCK')
    old='        table.addView(headerRow());\n        for (KeyAudit.Row row : r.rows) table.addView(row(row));'
    assert old in s;s=s.replace(old,'        renderKeySections(table,r);')
    s=s.replace('private View row(final KeyAudit.Row r)', 'private View row(final KeyAudit.Row r, int displayIndex)')
    s=s.replace('text("#" + r.index +', 'text("#" + displayIndex +')
    s=s.replace('        addrCell.addView(n);','        n.setTag("keyuses.number."+r.index);\n        addrCell.addView(n);')

    s=s.replace('        addrCell.addView(n);','        addrCell.addView(n);\n        addrCell.addView(text(sourceLabel(r.index),9.5f,Design.DIM_2,Typeface.NORMAL));')
    s=s.replace('num(r.local)', 'r.local < 0 ? "—" : num(r.local)')
    s=s.replace('            default:\n                fg = Design.OK;', '            case UNKNOWN:\n                fg=Design.WARN; bg=Design.WARN_SOFT; label=Copy.unknown(); break;\n            default:\n                fg = Design.OK;')
    s=s.replace('        card.addView(runButton);','''        card.addView(runButton);
        walletToggle=new android.widget.CheckBox(this);
        walletToggle.setText(Copy.includeWallet()); walletToggle.setTextColor(Design.TEXT);
        walletToggle.setMinHeight(dp(48)); walletToggle.setChecked(includeWallet);
        walletToggle.setOnCheckedChangeListener((button,checked) -> {
            includeWallet=checked;
            getSharedPreferences("keyuses_settings",MODE_PRIVATE).edit().putBoolean("include_wallet",checked).apply();
            lastResult=null;selection=null;resultsBox.removeAllViews();resultsBox.setVisibility(View.GONE);
        });
        card.addView(walletToggle);''')
    s=s.replace('        runLabel.setText(b ? "Auditing…" : "Run Audit");','        if(walletToggle!=null)walletToggle.setEnabled(!b);\n        updateProgress();')
    s=s.replace('runButton.setEnabled(!b);', 'runButton.setEnabled(!b && derivationValid);')
    s=s.replace('if (!Sha3.selfTest())', 'if (!derivationValid)')
    s=s.replace('        statusBox.setText(HtmlCompat.fromHtml(msg, HtmlCompat.FROM_HTML_MODE_COMPACT));','        statusBox.setText(msg);')
    s=s.replace('    private void clearError() {','    private void clearError() {\n        errorText=null;')
    # Keep donor view/layout helpers; resolve copy on each live-language render.
    import re
    s=re.sub(r'Copy\.([A-Z][A-Z_]+)\b',r'Copy.\1()',s)
    replacements={
      '"Run Audit"':'Copy.run()', '"Command copied"':'Copy.copied()', '"Address copied"':'Copy.copied()',
      '"Address (tap to copy)"':'Copy.address()', '"Node uses"':'Copy.localUses()',
      '"On-chain signatures"':'Copy.onchain()', '"Spent coins"':'Copy.coins()', '"Status"':'Copy.status()',
      '"RE-USED"':'Copy.reused()', '"AT RISK"':'Copy.atRisk()', '"Open Minima Core"':'Copy.openNode()',
      '"Minima Core is not installed"':'Copy.unavailable()'}
    for a,b in replacements.items():s=s.replace(a,b)
    old='''"Internal error: the SHA3-256 self-test failed, so addresses cannot be "
                    + "derived safely. This build is broken — do not trust any result from it."'''
    assert old in s;s=s.replace(old,'Copy.selfTest()')
    s=s.replace('            showError(Copy.selfTest());','            errorText=Copy::selfTest; showError(errorText.get());')
    # Fit every column to the viewport. Keep the status with the key/address.
    old="""        final HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(table);
        card.addView(hs);"""
    assert old in s;s=s.replace(old,'        card.addView(table);')
    s=s.replace('        row.addView(headerCell(Copy.status(), COL_STATUS, Gravity.END));','')
    s=s.replace('new LinearLayout.LayoutParams(dp(COL_ADDR),\n                LinearLayout.LayoutParams.WRAP_CONTENT)',
                'new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,COL_ADDR)')
    s=s.replace('        t.setWidth(dp(widthDp));','        t.setLayoutParams(new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,widthDp));')
    s=s.replace('final TextView t = text(s, 12f, Design.TEXT, Typeface.NORMAL);',
                'final TextView t = text(s, 12f, Design.TEXT, Typeface.NORMAL);\n        t.setMaxLines(1);\n        t.setAutoSizeTextTypeUniformWithConfiguration(8,12,1,TypedValue.COMPLEX_UNIT_SP);')
    start=s.index('        final LinearLayout chipCell =');end=s.index('        final LinearLayout wrap =',start)
    s=s[:start]+"""        chip.setTag("keyuses.status."+r.index);
        n.setTextColor(fg);
        addrCell.addView(chip,1);

"""+s[end:]
    s=s.replace('switch (r.status())', 'switch (r.reused ? KeyAudit.Status.REUSED : r.risk ? KeyAudit.Status.AT_RISK : r.publicChecked ? KeyAudit.Status.OK : KeyAudit.Status.UNKNOWN)')
    s=s.replace('                label = "OK";', '                label = r.sigs>0 ? Copy.used(num(r.sigs)) : Copy.unused();')
    s=s.replace('if (!r.complete) resultsBox', 'if (!r.publicComplete) resultsBox')
    s=s.replace('r.complete ? Design.OK', 'r.publicComplete ? Design.OK')
    s=s.replace('r.complete ? Copy.OK_TITLE()', 'r.publicComplete ? Copy.OK_TITLE()')
    s=s.replace('r.complete ? Copy.OK_BODY()', 'r.publicComplete ? Copy.OK_BODY()')
    # Reuse AtomiX's self-owned menu: language rebuilds must retain navigation.
    s=s.replace('        wrap.addView(b);',"""        wrap.setTag("pandamonium.app.toolbar.self");
        TextView menu=text("\u2630",20f,Design.TEXT,Typeface.NORMAL);
        menu.setTag("keyuses.menu"); menu.setGravity(Gravity.CENTER);
        menu.setContentDescription(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_drawer_open));
        menu.setOnClickListener(v -> {
            View shell=getWindow().getDecorView().findViewWithTag("pandamonium.navigation.shell");
            if(shell instanceof androidx.drawerlayout.widget.DrawerLayout)
                ((androidx.drawerlayout.widget.DrawerLayout)shell).openDrawer(Gravity.LEFT);
        });
        wrap.addView(menu,new LinearLayout.LayoutParams(dp(48),dp(48)));
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.addView(b);""")
    p.write_text(s)
    # Preserve locale changes without restarting the running read-only audit.
    p=base/'src/main/AndroidManifest.xml';tree=ET.parse(p)
    for activity in tree.getroot().find('application').findall('activity'):
        activity.set('{http://schemas.android.com/apk/res/android}configChanges','locale|layoutDirection|screenLayout')
    ET.indent(tree,space='    ');tree.write(p,encoding='unicode',xml_declaration=True)
    for path in (project/'scripts/pandamonium-tests/wallet/src/test/resources').glob('keyuses-*'):
        dest=root/'wallet/src/test/resources'/path.name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(path,dest)
