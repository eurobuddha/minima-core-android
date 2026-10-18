#!/usr/bin/env python3
"""Transport-only adaptations to the pinned native snapshots; invoked by importer."""
from pathlib import Path
import re, sys, xml.etree.ElementTree as ET
ANDROID='{http://schemas.android.com/apk/res/android}'
ET.register_namespace('android',ANDROID[1:-1])

def adapt(root):
    # Adapt Salon's closed-screen notification intake before generic SDK rewriting.
    import importlib.util
    spec = importlib.util.spec_from_file_location('creative_apps', Path(__file__).with_name('adapt-pandamonium-creative.py'))
    creative_apps = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(creative_apps)
    creative_apps.prepare(root)
    # Reproduce 1.9.32's shared codec dependency; importing old copies would
    # reintroduce duplicate classes. Refuse to discard a copy that has diverged.
    shared = Path(__file__).resolve().parents[1] / 'maxima-core/src/main/java/com/eurobuddha/maxima/core'
    copies = root / 'pandapools/src/main/java/com/eurobuddha/maxima/core'
    for relative in ('msg/Magic.java', 'msg/TxHeader.java', 'codec/MiniData.java',
                     'codec/MiniByte.java', 'codec/Hex.java', 'codec/Reads.java',
                     'codec/MiniNumber.java', 'codec/Streamable.java'):
        duplicate = copies / relative
        assert duplicate.read_bytes() == (shared / relative).read_bytes(), ('Shared codec drift', relative)
        duplicate.unlink()
    gradle = root / 'pandapools/build.gradle'
    source = gradle.read_text()
    assert source.count('dependencies {') == 1, 'PandaPools dependencies drift'
    gradle.write_text(source.replace('dependencies {', """dependencies {
    // Shared Maxima codec classes now come from the vendored transport core
    // (they were byte-identical copies; D8 refuses the duplicate).
    implementation project(':maxima-core')""", 1))
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
            s=s.replace('new org.json.JSONObject(data).optString','nodeEvent.optString').replace('new JSONObject(data).optString','nodeEvent.optString').replace('JSONObject json = new JSONObject(message);','JSONObject json = nodeEvent;')
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
    spec=importlib.util.spec_from_file_location('integrated_key_audit',Path(__file__).with_name('adapt-pandamonium-key-audit.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)
    spec=importlib.util.spec_from_file_location('integrated_malls',Path(__file__).with_name('adapt-pandamonium-malls.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)
    spec=importlib.util.spec_from_file_location('integrated_nft',Path(__file__).with_name('adapt-pandamonium-nft.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)
    spec=importlib.util.spec_from_file_location('integrated_wallet',Path(__file__).with_name('adapt-pandamonium-wallet.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)
    spec=importlib.util.spec_from_file_location('integrated_navigation',Path(__file__).with_name('adapt-pandamonium-navigation.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)
    spec=importlib.util.spec_from_file_location('history_languages',Path(__file__).with_name('adapt-pandamonium-history-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('wallet_languages',Path(__file__).with_name('adapt-pandamonium-wallet-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('wallet_tabs_languages',Path(__file__).with_name('adapt-pandamonium-wallet-tabs-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('wallet_settings_languages',Path(__file__).with_name('adapt-pandamonium-wallet-settings-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('wallet_settings_details_languages',Path(__file__).with_name('adapt-pandamonium-wallet-settings-details-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('wallet_nft_languages',Path(__file__).with_name('adapt-pandamonium-wallet-nft-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)


    spec=importlib.util.spec_from_file_location("wallet_audit",Path(__file__).with_name("adapt-pandamonium-wallet-audit.py"))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('wallet_runtime',Path(__file__).with_name('adapt-pandamonium-wallet-runtime.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('wallet_retry',Path(__file__).with_name('adapt-pandamonium-wallet-retry.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('wallet_settings_work',Path(__file__).with_name('adapt-pandamonium-wallet-settings-work.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('wallet_signing_work',Path(__file__).with_name('adapt-pandamonium-wallet-signing-work.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('wallet_error_languages',Path(__file__).with_name('adapt-pandamonium-wallet-error-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('wallet_startup',Path(__file__).with_name('adapt-pandamonium-wallet-startup.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('future_languages',Path(__file__).with_name('adapt-pandamonium-future-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('future_panels',Path(__file__).with_name('adapt-pandamonium-future-panels.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('future_notices',Path(__file__).with_name('adapt-pandamonium-future-notices.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('explorer_languages',Path(__file__).with_name('adapt-pandamonium-explorer-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('terminal_languages',Path(__file__).with_name('adapt-pandamonium-terminal-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('terminal_editor_languages',Path(__file__).with_name('adapt-pandamonium-terminal-editor-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('terminal_txn_languages',Path(__file__).with_name('adapt-pandamonium-terminal-txn-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('terminal_reference_languages',Path(__file__).with_name('adapt-pandamonium-terminal-reference-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('terminal_help_languages',Path(__file__).with_name('adapt-pandamonium-terminal-help-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('eth_languages',Path(__file__).with_name('adapt-pandamonium-eth-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)
    spec=importlib.util.spec_from_file_location('entropy_languages',Path(__file__).with_name('adapt-pandamonium-entropy-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)
    spec=importlib.util.spec_from_file_location('filez_languages',Path(__file__).with_name('adapt-pandamonium-filez-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('vestr_languages',Path(__file__).with_name('adapt-pandamonium-vestr-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('mail_languages',Path(__file__).with_name('adapt-pandamonium-mail-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('utxo_languages',Path(__file__).with_name('adapt-pandamonium-utxo-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('utxo_wallet_languages',Path(__file__).with_name('adapt-pandamonium-utxo-wallet-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)
    spec=importlib.util.spec_from_file_location('utxo_history_languages',Path(__file__).with_name('adapt-pandamonium-utxo-history-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('nft_shell_languages',Path(__file__).with_name('adapt-pandamonium-nft-shell-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('nft_details_languages',Path(__file__).with_name('adapt-pandamonium-nft-details-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('nft_gallery_languages',Path(__file__).with_name('adapt-pandamonium-nft-gallery-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('nft_send_languages',Path(__file__).with_name('adapt-pandamonium-nft-send-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('nft_actions_languages',Path(__file__).with_name('adapt-pandamonium-nft-actions-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('nft_burial',Path(__file__).with_name('adapt-pandamonium-nft-burial.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('nft_departure',Path(__file__).with_name('adapt-pandamonium-nft-departure.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('nft_mint_languages',Path(__file__).with_name('adapt-pandamonium-nft-mint-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('nft_mint_guards',Path(__file__).with_name('adapt-pandamonium-nft-mint-guards.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('nft_wallet_languages',Path(__file__).with_name('adapt-pandamonium-nft-wallet-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('nft_tools_languages',Path(__file__).with_name('adapt-pandamonium-nft-tools-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('nft_transactions',Path(__file__).with_name('adapt-pandamonium-nft-transactions.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('nft_outcomes',Path(__file__).with_name('adapt-pandamonium-nft-outcomes.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('nft_journal',Path(__file__).with_name('adapt-pandamonium-nft-journal.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('nft_prepared_send',Path(__file__).with_name('adapt-pandamonium-nft-prepared-send.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('nft_consolidation',Path(__file__).with_name('adapt-pandamonium-nft-consolidation.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('nft_tool_review',Path(__file__).with_name('adapt-pandamonium-nft-tool-review.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('nft_history_languages',Path(__file__).with_name('adapt-pandamonium-nft-history-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('casino_languages',Path(__file__).with_name('adapt-pandamonium-casino-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)


    spec=importlib.util.spec_from_file_location('mall_shop_languages',Path(__file__).with_name('adapt-pandamonium-mall-shop-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    inbox_spec = importlib.util.spec_from_file_location("mall_inbox_languages", Path(__file__).with_name("adapt-pandamonium-mall-inbox-languages.py"))
    inbox_module = importlib.util.module_from_spec(inbox_spec)
    inbox_spec.loader.exec_module(inbox_module)
    inbox_module.adapt(root)

    spec=importlib.util.spec_from_file_location('shop_checkout',Path(__file__).with_name('adapt-pandamonium-shop-checkout.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('shop_order_context',Path(__file__).with_name('adapt-pandamonium-shop-order-context.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('shop_payment',Path(__file__).with_name('adapt-pandamonium-shop-payment.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('mall_studio_languages',Path(__file__).with_name('adapt-pandamonium-mall-studio-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('mall_studio_refresh',Path(__file__).with_name('adapt-pandamonium-mall-studio-refresh.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('mall_studio_export',Path(__file__).with_name('adapt-pandamonium-mall-studio-export.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('atomix_languages',Path(__file__).with_name('adapt-pandamonium-atomix-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)
    spec=importlib.util.spec_from_file_location('atomix_notices',Path(__file__).with_name('adapt-pandamonium-atomix-notices.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)
    spec=importlib.util.spec_from_file_location('atomix_otc',Path(__file__).with_name('adapt-pandamonium-atomix-otc.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)
    spec=importlib.util.spec_from_file_location('atomix_currency',Path(__file__).with_name('adapt-pandamonium-atomix-currency.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)
    spec=importlib.util.spec_from_file_location('atomix_amounts',Path(__file__).with_name('adapt-pandamonium-atomix-amounts.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)
    spec=importlib.util.spec_from_file_location('atomix_order_context',Path(__file__).with_name('adapt-pandamonium-atomix-order-context.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)
    spec=importlib.util.spec_from_file_location('atomix_publish_queue',Path(__file__).with_name('adapt-pandamonium-atomix-publish-queue.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)
    spec=importlib.util.spec_from_file_location('atomix_send_receipt',Path(__file__).with_name('adapt-pandamonium-atomix-send-receipt.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)
    spec=importlib.util.spec_from_file_location('atomix_chain_evidence',Path(__file__).with_name('adapt-pandamonium-atomix-chain-evidence.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)
    spec=importlib.util.spec_from_file_location('atomix_consent',Path(__file__).with_name('adapt-pandamonium-atomix-consent.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)
    spec=importlib.util.spec_from_file_location('atomix_responder',Path(__file__).with_name('adapt-pandamonium-atomix-responder.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)
    spec=importlib.util.spec_from_file_location('atomix_eth_context',Path(__file__).with_name('adapt-pandamonium-atomix-eth-context.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)
    spec=importlib.util.spec_from_file_location('atomix_market',Path(__file__).with_name('adapt-pandamonium-atomix-market.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)
    spec=importlib.util.spec_from_file_location('atomix_market_inputs',Path(__file__).with_name('adapt-pandamonium-atomix-market-inputs.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)
    spec=importlib.util.spec_from_file_location('atomix_history',Path(__file__).with_name('adapt-pandamonium-atomix-history.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)
    spec=importlib.util.spec_from_file_location('atomix_swap',Path(__file__).with_name('adapt-pandamonium-atomix-swap.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)
    spec=importlib.util.spec_from_file_location('atomix_operation',Path(__file__).with_name('adapt-pandamonium-atomix-operation.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)
    spec=importlib.util.spec_from_file_location('atomix_recovery',Path(__file__).with_name('adapt-pandamonium-atomix-recovery.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)
    spec=importlib.util.spec_from_file_location('atomix_report',Path(__file__).with_name('adapt-pandamonium-atomix-report.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)
    spec=importlib.util.spec_from_file_location('atomix_swap_inputs',Path(__file__).with_name('adapt-pandamonium-atomix-swap-inputs.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('pools_languages',Path(__file__).with_name('adapt-pandamonium-pools-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('pools_collection',Path(__file__).with_name('adapt-pandamonium-pools-collection.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('dex_languages',Path(__file__).with_name('adapt-pandamonium-dex-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('service_languages',Path(__file__).with_name('adapt-pandamonium-service-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('inbox_coin_evidence',Path(__file__).with_name('adapt-pandamonium-inbox-coin-evidence.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('inbox_storage',Path(__file__).with_name('adapt-pandamonium-inbox-storage.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('inbox_delivery',Path(__file__).with_name('adapt-pandamonium-inbox-delivery.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('inbox_drafts',Path(__file__).with_name('adapt-pandamonium-inbox-drafts.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('inbox_plain_delivery',Path(__file__).with_name('adapt-pandamonium-inbox-plain-delivery.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('nft_distribution_recovery',Path(__file__).with_name('adapt-pandamonium-nft-distribution-recovery.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('nft_local_history',Path(__file__).with_name('adapt-pandamonium-nft-local-history.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('token_metadata',Path(__file__).with_name('adapt-pandamonium-token-metadata.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('creative_languages',Path(__file__).with_name('adapt-pandamonium-creative-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('filtr_languages',Path(__file__).with_name('adapt-pandamonium-filtr-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('terminal_output',Path(__file__).with_name('adapt-pandamonium-terminal-output.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('salon_identity',Path(__file__).with_name('adapt-pandamonium-salon-identity.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('salon_transport',Path(__file__).with_name('adapt-pandamonium-salon-transport.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('creative_reads',Path(__file__).with_name('adapt-pandamonium-creative-reads.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('salon_setup_languages',Path(__file__).with_name('adapt-pandamonium-salon-setup-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('hosting_secrets',Path(__file__).with_name('adapt-pandamonium-hosting-secrets.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('salon_hosting_languages',Path(__file__).with_name('adapt-pandamonium-salon-hosting-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('atelier_hosting_languages',Path(__file__).with_name('adapt-pandamonium-atelier-hosting-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('atelier_manage_languages',Path(__file__).with_name('adapt-pandamonium-atelier-manage-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('atelier_create_languages',Path(__file__).with_name('adapt-pandamonium-atelier-create-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('atelier_mint_state',Path(__file__).with_name('adapt-pandamonium-atelier-mint-state.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('atelier_collection_languages',Path(__file__).with_name('adapt-pandamonium-atelier-collection-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('atelier_generative_languages',Path(__file__).with_name('adapt-pandamonium-atelier-generative-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('salon_social_languages',Path(__file__).with_name('adapt-pandamonium-salon-social-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('creative_web_validation',Path(__file__).with_name('adapt-pandamonium-creative-web-validation.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('salon_holdings_languages',Path(__file__).with_name('adapt-pandamonium-salon-holdings-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('salon_proof_safety',Path(__file__).with_name('adapt-pandamonium-salon-proof-safety.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('salon_proof_lifecycle',Path(__file__).with_name('adapt-pandamonium-salon-proof-lifecycle.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('salon_editor_languages',Path(__file__).with_name('adapt-pandamonium-salon-editor-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('salon_media_languages',Path(__file__).with_name('adapt-pandamonium-salon-media-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('salon_media_uploads',Path(__file__).with_name('adapt-pandamonium-salon-media-uploads.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('salon_chat_languages',Path(__file__).with_name('adapt-pandamonium-salon-chat-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('salon_settings_languages',Path(__file__).with_name('adapt-pandamonium-salon-settings-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('salon_backup_lifecycle',Path(__file__).with_name('adapt-pandamonium-salon-backup-lifecycle.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('atelier_gallery_languages',Path(__file__).with_name('adapt-pandamonium-atelier-gallery-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('atelier_viewer',Path(__file__).with_name('adapt-pandamonium-atelier-viewer.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('atelier_airdrop_languages',Path(__file__).with_name('adapt-pandamonium-atelier-airdrop-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('atelier_transfer',Path(__file__).with_name('adapt-pandamonium-atelier-transfer.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('atelier_burial',Path(__file__).with_name('adapt-pandamonium-atelier-burial.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('atelier_delivery',Path(__file__).with_name('adapt-pandamonium-atelier-delivery.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('atelier_images',Path(__file__).with_name('adapt-pandamonium-atelier-images.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('atelier_filtr_handoff',Path(__file__).with_name('adapt-pandamonium-atelier-filtr-handoff.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('atelier_exports',Path(__file__).with_name('adapt-pandamonium-atelier-exports.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('creative_images',Path(__file__).with_name('adapt-pandamonium-creative-images.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('hosting_verification',Path(__file__).with_name('adapt-pandamonium-hosting-verification.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('salon_page',Path(__file__).with_name('adapt-pandamonium-salon-page.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('salon_publishing',Path(__file__).with_name('adapt-pandamonium-salon-publishing.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('salon_metadata',Path(__file__).with_name('adapt-pandamonium-salon-metadata.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('salon_github_profile',Path(__file__).with_name('adapt-pandamonium-salon-github-profile.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('salon_restore',Path(__file__).with_name('adapt-pandamonium-salon-restore.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('salon_notices',Path(__file__).with_name('adapt-pandamonium-salon-notices.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('salon_spares',Path(__file__).with_name('adapt-pandamonium-salon-spares.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('salon_tips_languages',Path(__file__).with_name('adapt-pandamonium-salon-tips-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

    spec=importlib.util.spec_from_file_location('salon_backup_validation',Path(__file__).with_name('adapt-pandamonium-salon-backup-validation.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

if __name__=='__main__': adapt(Path(sys.argv[1]))
