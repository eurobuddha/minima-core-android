"""Reuse the History screen as a host-owned tab; mark native toolbars for the shared drawer."""
from pathlib import Path
import xml.etree.ElementTree as ET
A='{http://schemas.android.com/apk/res/android}'
ET.register_namespace('android', A[1:-1])
ET.register_namespace('tools', 'http://schemas.android.com/tools')

def adapt_mail_freshness(root):
    """Only the display-age timer sleeps; mail scan/send completion is untouched."""
    p=root/'mail/src/main/java/com/eurobuddha/mail/MainActivity.java'
    s=p.read_text()
    replacements={
        '        ui.postDelayed(freshTick, 30000);':
            '        // Freshness display starts with the visible Activity.',
        '    private final Runnable freshTick = new Runnable() {':
            '    private boolean freshnessVisible;\n    private final Runnable freshTick = new Runnable() {',
        '            updateFreshness();\n            ui.postDelayed(this, 30000);':
            '            if (!freshnessVisible) return;\n            updateFreshness();\n            ui.postDelayed(this, 30000);',
        '    @Override protected void onDestroy() {\n        super.onDestroy();':
            '    @Override protected void onDestroy() {\n        freshnessVisible = false;\n        super.onDestroy();',
        '    @Override protected void onStart() {\n        super.onStart();':
            '    @Override protected void onStart() {\n        super.onStart();\n        freshnessVisible = true;\n        ui.removeCallbacks(freshTick);\n        freshTick.run();',
        '    @Override protected void onStop() {':
            '    @Override protected void onStop() {\n        freshnessVisible = false;\n        ui.removeCallbacks(freshTick);',
    }
    for old,new in replacements.items():
        assert s.count(old)==1, ('Mail freshness adaptation drift',old)
        s=s.replace(old,new,1)
    p.write_text(s)


def adapt_atomix_toolbar(root):
    """Preserve the shipped 1.9.32 menu fix when recreating the pinned snapshot."""
    p = root / 'atomix/src/main/java/com/eurobuddha/atomix/MainActivity.java'
    s = p.read_text()
    old = r"""    private void buildHeader(LinearLayout col) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView brand = new TextView(this);
        brand.setText("AtomiX");
        brand.setTextColor(Design.TEXT()); brand.setTextSize(21f); brand.setTypeface(Design.sansBold()); brand.setLetterSpacing(-0.01f);
        header.addView(brand, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));"""
    new = r"""    private void buildHeader(LinearLayout col) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        boolean hosted = "com.eurobuddha.pandamonium".equals(getPackageName());
        if (hosted) {
            // render() rebuilds this whole tree every ~30s, so the HOST's one-shot
            // menu insertion dies with the first rebuild (proven on the Fold: the
            // hamburger vanished). The tag below tells the host to stand down and
            // this header draws its own hamburger on every rebuild instead.
            header.setTag("pandamonium.app.toolbar.self");
            TextView burger = new TextView(this);
            burger.setText("\u2630");
            burger.setTextColor(Design.TEXT());
            burger.setTextSize(20f);
            burger.setGravity(Gravity.CENTER);
            burger.setContentDescription("Open Minima Core menu");
            Design.pressable(burger);
            burger.setOnClickListener(v -> {
                View shell = getWindow().getDecorView()
                        .findViewWithTag("pandamonium.navigation.shell");
                if (shell instanceof androidx.drawerlayout.widget.DrawerLayout) {
                    ((androidx.drawerlayout.widget.DrawerLayout) shell)
                            .openDrawer(Gravity.LEFT);
                }
            });
            header.addView(burger, new LinearLayout.LayoutParams(dp(44), dp(44)));
        }

        TextView brand = new TextView(this);
        brand.setText("AtomiX");
        brand.setTextColor(Design.TEXT()); brand.setTextSize(21f); brand.setTypeface(Design.sansBold()); brand.setLetterSpacing(-0.01f);
        if (hosted) {
            // Hamburger left, title centred in the remaining span.
            brand.setGravity(Gravity.CENTER);
        }
        header.addView(brand, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));"""
    assert s.count(old) == 1, 'AtomiX toolbar adaptation drift'
    p.write_text(s.replace(old, new, 1))


def adapt_wallet_screen_host(root):
    """Reuse the wallet tab container pattern so screen replacement retains host navigation."""
    p = root / "wallet/src/main/java/com/eurobuddha/wallet/MainActivity.java"
    s = p.read_text()
    old = "public class MainActivity extends AppCompatActivity {\n"
    assert s.count(old) == 1, "Wallet screen host adaptation drift"
    p.write_text(s.replace(old, old + """
    // Keep the host drawer attached while setup, unlock and wallet screens replace their content.
    // Reuse the existing tab FrameLayout pattern; all wallet state stays in its original owners.
    private FrameLayout mScreenHost;
    @Override public void setContentView(View view) {
        if (mScreenHost == null) {
            mScreenHost = new FrameLayout(this);
            super.setContentView(mScreenHost);
        }
        mScreenHost.removeAllViews();
        mScreenHost.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        androidx.core.view.ViewCompat.requestApplyInsets(mScreenHost);
    }

""", 1))


def adapt_languages(root):
    """All native screens share the host choice and retain their views on locale changes."""
    for manifest in root.glob('*/src/main/AndroidManifest.xml'):
        tree = ET.parse(manifest)
        for activity in tree.getroot().find('application').findall('activity'):
            flags = activity.get(A + 'configChanges', '').split('|')
            flags = [flag for flag in flags if flag]
            for flag in ('locale', 'layoutDirection', 'screenLayout'):
                if flag not in flags:
                    flags.append(flag)
            activity.set(A + 'configChanges', '|'.join(flags))
        ET.indent(tree, space='    ')
        tree.write(manifest, encoding='unicode', xml_declaration=True)
        gradle = manifest.parents[2] / 'build.gradle'
        source = gradle.read_text()
        dependency = "    implementation project(':localization')"
        assert source.count('dependencies {') == 1, ('Dependencies drift', gradle)
        assert dependency not in source, ('Language adaptation applied twice', gradle)
        gradle.write_text(source.replace('dependencies {', 'dependencies {\n' + dependency, 1))
    # AtomiX rebuilds its own menu button; bind every replacement button as well.
    path = root / 'atomix/src/main/java/com/eurobuddha/atomix/MainActivity.java'
    source = path.read_text()
    old = 'burger.setContentDescription("Open Minima Core menu");'
    assert source.count(old) == 1, 'AtomiX language menu adaptation drift'
    path.write_text(source.replace(old,
        'com.eurobuddha.minimacore.i18n.Language.description(burger, '
        'com.eurobuddha.minimacore.i18n.Language.msg('
        'com.eurobuddha.minimacore.i18n.R.string.l10n_drawer_open));', 1))

def adapt(root):
    adapt_mail_freshness(root)
    adapt_atomix_toolbar(root)
    adapt_wallet_screen_host(root)
    # Native header rows retain their own controls; navigation is inserted at their left edge.
    for folder in root.iterdir():
        layout=folder/'src/main/res/layout'/('pm_'+folder.name.replace('-','_').lower()+'_activity_main.xml')
        if not layout.exists():continue
        tree=ET.parse(layout); top=tree.getroot(); header=list(top)[0]
        header.set(A+'tag','pandamonium.app.toolbar')
        ET.indent(tree,space='    ');tree.write(layout,encoding='unicode',xml_declaration=True)
    dex=root/'pandadex/src/main/java/com/eurobuddha/pandadex/MainActivity.java'
    s=dex.read_text().replace('        headerChrome = header;', '        headerChrome = header;\n        header.setTag("pandamonium.app.toolbar");')
    dex.write_text(s)

    folder=root/'history/src/main/java/com/eurobuddha/history'
    p=folder/'MainActivity.java';s=p.read_text()
    s=s.replace('public class MainActivity extends AppCompatActivity {', '''public final class HistoryScreen extends android.view.ContextThemeWrapper {
    private final AppCompatActivity host;
    private View screen;
    private boolean closed;
    public HistoryScreen(AppCompatActivity host) {
        super(host, R.style.pm_history_Theme_Utxo);
        this.host = host;
        create();
    }
    public View getView() { return screen; }
    private <T extends View> T findViewById(int id) { return screen.findViewById(id); }
    private android.view.Window getWindow() { return host.getWindow(); }
    private <I,O> ActivityResultLauncher<I> registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContract<I,O> contract,
            androidx.activity.result.ActivityResultCallback<O> callback) {
        return host.registerForActivityResult(contract, result -> { if (!closed) callback.onActivityResult(result); });
    }
''')
    s=s.replace('MainActivity.this','HistoryScreen.this')
    s=s.replace('    @Override\n    protected void onCreate(Bundle b) {\n        super.onCreate(b);\n        setContentView(R.layout.pm_history_activity_main);', '    private void create() {\n        screen = android.view.LayoutInflater.from(this).inflate(R.layout.pm_history_activity_main, null, false);')
    s=s.replace('        applyInsets();','        // The core tab owns system/keyboard insets.')
    s=s.replace('new NodeApi(this, this::onPaired)', 'new NodeApi(host, this::onPaired)')
    s=s.replace('    @Override\n    protected void onResume() { super.onResume(); requestSync(); }','    public void refresh() { if (!closed) requestSync(); }')
    s=s.replace('    @Override\n    protected void onDestroy() {\n        super.onDestroy();\n        ui.removeCallbacks(syncTask);', '    public void close() {\n        if (closed) return;\n        closed = true;\n        sync.cancel();\n        ui.removeCallbacksAndMessages(null);')
    s=s.replace('        io.shutdownNow();','        io.execute(db::close);\n        io.shutdown();')
    s=s.replace('    @Override protected void onStart() {\n        super.onStart();', '    public void start() {\n        if (closed) return;')
    s=s.replace('    @Override protected void onStop() {','    public void stop() {')
    s=s.replace('        super.onStop();','        ui.removeCallbacks(syncTask);')
    s=s.replace('private void requestSync() { ui.removeCallbacks', 'private void requestSync() { if (closed) return; ui.removeCallbacks')
    s=s.replace('    private void doSync() {','    private void doSync() {\n        if (closed) return;')
    s=s.replace('    private void reloadList() {','    private void reloadList() {\n        if (closed) return;')
    s=s.replace('    private void refreshOwnership() {','    private void refreshOwnership() {\n        if (closed) return;')
    s=s.replace('    private void adopt(Ownership parsed) {','    private void adopt(Ownership parsed) {\n        if (closed) return;')
    s=s.replace('    private void updateStatus(int total) {','    private void updateStatus(int total) {\n        if (closed) return;')
    s=s.replace('private void updateStatus() { io.execute', 'private void updateStatus() { if (closed) return; io.execute')
    s=s.replace('ui.post(() -> {', 'ui.post(() -> { if (closed) return;')
    (folder/'HistoryScreen.java').write_text(s)
    # Keep the internal activity usable, delegating to exactly the same screen as the core tab.
    p.write_text('''package com.eurobuddha.history;
public class MainActivity extends androidx.appcompat.app.AppCompatActivity {
    private HistoryScreen screen;
    @Override protected void onCreate(android.os.Bundle state) {
        super.onCreate(state); screen = new HistoryScreen(this); setContentView(screen.getView());
    }
    @Override protected void onStart() { super.onStart(); screen.start(); }
    @Override protected void onResume() { super.onResume(); screen.refresh(); }
    @Override protected void onStop() { screen.stop(); super.onStop(); }
    @Override protected void onDestroy() { screen.close(); super.onDestroy(); }
}
''')
    p=folder/'HistorySync.java';s=p.read_text().replace('MainActivity act','HistoryScreen act')
    s=s.replace('    private boolean running = false;', '    private boolean running = false;\n    private boolean cancelled;\n    public void cancel() { cancelled = true; running = false; }')
    s=s.replace('        if (running) return;','        if (running || cancelled) return;')
    s=s.replace('    private void fetchPage(final int offset) {','    private void fetchPage(final int offset) {\n        if (cancelled) return;')
    s=s.replace('public void onResult(JSONObject j) {','public void onResult(JSONObject j) {\n                if (cancelled) return;')
    s=s.replace('public void onError(String m) {','public void onError(String m) {\n                if (cancelled) return;')
    p.write_text(s)

    adapt_languages(root)
