#!/usr/bin/env python3
"""Transport-only adaptations to the pinned native snapshots; invoked by importer."""
from pathlib import Path
import re, sys, xml.etree.ElementTree as ET
ANDROID='{http://schemas.android.com/apk/res/android}'
ET.register_namespace('android',ANDROID[1:-1])

def adapt(root):
    for name in ('pandapools','pandadex'):
        for filename in ('NodeTransport.java','NodeTransportService.java'):
            for p in (root/name/'src/main/java').rglob(filename): p.unlink()
        for p in (root/name/'src/test').rglob('NodeTransportTest.java'): p.unlink()
    # Discovery belongs to the standalone SDK; the embedded copy uses DirectNodeApi.
    for p in (root/'pandadex/src/test').rglob('NodeRoutingTest.java'): p.unlink()
    # Keep the strict JSON parser: MakerConfig uses it for durable financial records.
    for p in (root/'pandadex/src/main/java/com/eurobuddha/minimaapi').glob('*.java'):
        if p.name!='MinimaAPIResponse.java': p.unlink()
    p=root/'pandadex/src/test/java/com/eurobuddha/minimaapi/MinimaAPIResponseTest.java'
    s=p.read_text(); s=re.sub(r'    @Test public void pairingIdsAcceptExistingCaseButNotPartialCorruption\(\) \{.*?\n    \}', '',s,flags=re.S);p.write_text(s)
    for p in root.glob('*/src/main/java/**/*.java'):
        if p.name=='MinimaNotifyReceiver.java': continue
        s=p.read_text()
        s=s.replace('com.eurobuddha.pandadex.integratedsdk.MinimaAPIListener','com.eurobuddha.minimaapi.MinimaAPIListener')
        s=re.sub(r'import (?:com.eurobuddha.minimaapi|com.eurobuddha.pandadex.integratedsdk).MinimaAPI;', 'import com.eurobuddha.minimaapi.direct.DirectNodeApi;',s)
        s=re.sub(r'\bMinimaAPI\b','DirectNodeApi',s)
        s=re.sub(r'\bNodeTransport\b','DirectNodeApi',s)
        s=s.replace('which auto-registers this app with the node','which connects to the embedded node')
        s=s.replace('// Constructing DirectNodeApi auto-sends the REGISTER broadcast; the reply tells us\n        // whether the user has enabled this app in Minima Core -> Apps yet.', '// Embedded readiness callback; no registration or app permission handshake.')
        s=s.replace("Minima Core didn't respond. Is it installed, running and enabled?", 'The embedded node did not respond. Check its status in Minima Core.')
        if 'new BroadcastReceiver()' in s:
            s=re.sub(r'import (?:com.eurobuddha.minimaapi|com.eurobuddha.pandadex.integratedsdk).MinimaAPIMessages;\n','',s)
            s=s.replace('import android.content.BroadcastReceiver;', 'import com.eurobuddha.minimaapi.direct.DirectNodeEvents;')
            s=re.sub(r'\bBroadcastReceiver\b','DirectNodeEvents.Listener',s)
            s=re.sub(r'public void onReceive\(Context \w+, Intent \w+\)', 'public void onEvent(JSONObject nodeEvent)',s)
            s=re.sub(r'\s*if \(!DirectNodeApi.checkMinimaID\([^\n]+\n','\n',s)
            s=re.sub(r'\s*(?:final )?String (?:data|message) = \w+.getStringExtra\(MinimaAPIMessages.MINIMA_API_NOTIFY_DATA\);\n','\n',s)
            s=re.sub(r'\s*if \((?:data|message) == null\) return;\n','\n',s)
            s=s.replace('new JSONObject(data).optString','nodeEvent.optString').replace('JSONObject json = new JSONObject(message);','JSONObject json = nodeEvent;')
            s=s.replace('new JSONObject(i.getStringExtra(MinimaAPIMessages.MINIMA_API_NOTIFY_DATA)).optString', 'nodeEvent.optString')
            s=s.replace('intent.getStringExtra("event")','nodeEvent.optString("event")')
            s=re.sub(r'ContextCompat.registerReceiver\(this, (\w+),\s*new IntentFilter\((?:MinimaAPIMessages.MINIMA_API_NOTIFY|NodeTransportService.EVENT_ACTION)\), ContextCompat.RECEIVER_\w+\);',r'DirectNodeEvents.subscribe(\1);',s)
            s=re.sub(r'IntentFilter filter = new IntentFilter\(MinimaAPIMessages.MINIMA_API_NOTIFY\);.*?\n        mReceiverRegistered = true;', 'DirectNodeEvents.subscribe(mNotifyReceiver);\n        mReceiverRegistered = true;',s,flags=re.S)
            s=re.sub(r'unregisterReceiver\((\w+)\)',r'DirectNodeEvents.unsubscribe(\1)',s)
        s=re.sub(r'DirectNodeEvents.subscribe\((\w+)\);', r'DirectNodeEvents.subscribe(\1, "NEWBLOCK", "NEWBALANCE");', s)
        if p.name == 'MainActivity.java' and 'DirectNodeEvents.subscribe(' in s:
            match=re.search(r'DirectNodeEvents.subscribe\((\w+), "NEWBLOCK", "NEWBALANCE"\);',s)
            if match:
                listener=match[1]
                s=s.replace(match[0],'// Subscribed only while this screen is visible.')
                assert 'void onStart()' not in s and 'void onStop()' not in s
                lifecycle='\n    @Override protected void onStart() {\n        super.onStart();\n        DirectNodeEvents.subscribe('+listener+', "NEWBLOCK", "NEWBALANCE");\n    }\n    @Override protected void onStop() {\n        DirectNodeEvents.unsubscribe('+listener+');\n        super.onStop();\n    }\n'
                position=s.rfind('}');s=s[:position]+lifecycle+s[position:]
        p.write_text(s)
    p=root/'terminalide/src/main/java/com/eurobuddha/terminalide/receiver/MinimaNotifyReceiver.java'
    p.write_text('''package com.eurobuddha.terminalide.receiver;

import android.content.Context;
import org.json.JSONObject;
import com.eurobuddha.minimaapi.direct.DirectNodeEvents;

/** Application-owned local log sink. Called by the host on a bounded background worker. */
public final class MinimaNotifyReceiver implements DirectNodeEvents.Listener {
    private final Context context;
    private ReceiverDB db;
    private long nextPrune;
    public MinimaNotifyReceiver(Context context) { this.context = context.getApplicationContext(); }
    @Override public synchronized void onEvent(JSONObject event) {
        if (!"MINIMALOG".equals(event.optString("event"))) return;
        JSONObject data = event.optJSONObject("data");
        if (data == null) return;
        if (db == null) db = new ReceiverDB(context);
        else if (!db.isOpen()) db.reOpen();
        long now = System.currentTimeMillis();
        if (now >= nextPrune) { db.deleteOldMessages(); nextPrune = now + 60_000; }
        db.insertEvent("MINIMALOG", data.toString());
    }
}
''')
    p=root/'filez/src/main/java/com/eurobuddha/filez/MainActivity.java';s=p.read_text()
    start=s.index('    private void doImport(Uri src) {');end=s.index('    private String displayNameOf(Uri uri)',start)
    s=s[:start]+'''    private void doImport(Uri src) {
        status.setText("Importing…");
        final String directory = currentPath;
        io.execute(() -> {
            try {
                String name = Util.safeName(displayNameOf(src));
                if (name.isEmpty()) name = "import_" + System.currentTimeMillis();
                final String dest = Util.joinPath(directory, name);
                // Same UID: the embedded node can stream the selected SAF document directly.
                ui.post(() -> node.file("put", dest, null, src, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject j) {
                        if (!j.optBoolean("status", false)) { onFileError(j); return; }
                        String msg = "Imported " + Util.formatBytes(j.optLong("size", 0));
                        status.setText(msg);
                        toast(msg);
                        loadDirectory(currentPath);
                    }
                    @Override public void onError(String m) { handleFileCallError(m); }
                }));
            } catch (Exception exc) {
                ui.post(() -> { status.setText("Import failed"); toast("Import failed: " + exc.getMessage()); });
            }
        });
    }

'''+s[end:]
    s=s.replace('import androidx.core.content.FileProvider;\n','').replace('import java.io.FileOutputStream;\n','')
    s=re.sub(r'    private static final String FILEPROVIDER_AUTHORITY = [^\n]+\n','',s)
    s=s.replace("All file access goes through the node's ADMIN-gated FILE IPC bridge (node >= 1.3.1).", "File access uses the embedded node's guarded file implementation.")
    old='                    try {\n                        long copied = copyStream(\n                                getContentResolver().openInputStream(Uri.parse(srcUri)),\n                                getContentResolver().openOutputStream(dest));'
    new='                    try (InputStream input = getContentResolver().openInputStream(Uri.parse(srcUri));\n                         OutputStream output = getContentResolver().openOutputStream(dest)) {\n                        long copied = copyStream(input, output);'
    assert old in s;s=s.replace(old,new)

    p.write_text(s)
    for p in root.glob('*/src/main/AndroidManifest.xml'):
        tree=ET.parse(p);app=tree.getroot().find('application')
        for component in list(app):
            n=component.get(ANDROID+'name','')
            if n.endswith('.NodeTransportService') or n.endswith('.MinimaNotifyReceiver') or (p.parts[-4]=='filez' and component.tag=='provider'):app.remove(component)
        ET.indent(tree,space='    ');tree.write(p,encoding='unicode',xml_declaration=True)
    p=root/'filez/src/main/java/com/eurobuddha/filez/IntegratedFileProvider.java'
    p.unlink(missing_ok=True)
    # Keep Vestr's response-boundary regression on the actual embedded client.
    p=root/'vestr/src/test/java/com/eurobuddha/vestr/NodeApiResponseTest.java'
    s=p.read_text().replace('com.eurobuddha.minimaapi.MinimaAPI;', 'com.eurobuddha.minimaapi.direct.DirectNodeApi;')
    p.write_text(re.sub(r'\bMinimaAPI\b', 'DirectNodeApi', s))
    import importlib.util
    spec=importlib.util.spec_from_file_location('integrated_ui',Path(__file__).with_name('adapt-pandamonium-ui.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt_ui(root)
    p=root/'pandadex/build.gradle';s=p.read_text();s=s.replace('dependencies {', "dependencies {\n    implementation project(':minimaapi')");p.write_text(s)
    spec=importlib.util.spec_from_file_location('integrated_safety',Path(__file__).with_name('adapt-pandamonium-safety.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)
    spec=importlib.util.spec_from_file_location('integrated_navigation',Path(__file__).with_name('adapt-pandamonium-navigation.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

if __name__=='__main__': adapt(Path(sys.argv[1]))
