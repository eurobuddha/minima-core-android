"""Reuse the History screen as a host-owned tab; mark native toolbars for the shared drawer."""
from pathlib import Path
import xml.etree.ElementTree as ET
A='{http://schemas.android.com/apk/res/android}'

def adapt(root):
    # Native header rows retain their own controls; navigation is inserted at their left edge.
    for folder in root.iterdir():
        layout=folder/'src/main/res/layout'/('pm_'+folder.name.replace('-','_')+'_activity_main.xml')
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
