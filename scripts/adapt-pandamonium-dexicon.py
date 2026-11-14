#!/usr/bin/env python3
"""Pandamonium 2.2.34 (owner, 14 Nov 2026): "pandamonium needs to have dexicon but with the host node wallets with the
direct plumbing of that build ... remove atomix and pp and pandadex and just have a dexicon tab".

Applied to integrated/dexicon after import (adapt-pandamonium-direct.py calls adapt()). The snapshot is PERMANENTLY in
Dexicon's host-node mode (Dexicon 0.3.51+ HostMode): every command goes to the embedded node's own wallet through the
host's in-process DirectNodeApi, exactly like every other bundled app. Nothing here touches Dexicon's financial logic.
"""
from pathlib import Path
import re, shutil, xml.etree.ElementTree as ET

ANDROID = '{http://schemas.android.com/apk/res/android}'
ET.register_namespace('android', ANDROID[1:-1])
ET.register_namespace('tools', 'http://schemas.android.com/tools')
EVENT_ACTION = '"com.eurobuddha.dexicon.NODE_EVENT"'


def replace_once(path, old, new):
    text = path.read_text()
    assert text.count(old) == 1, (str(path), old[:80])
    path.write_text(text.replace(old, new))


def adapt(root):
    app = root / 'dexicon'
    if not app.exists():
        return
    main, test = app / 'src/main/java', app / 'src/test/java'
    dex = main / 'com/eurobuddha/dexicon'

    # 1. The standalone IPC transport and Dexicon's private SDK copy go; the host's :minimaapi is the SDK (its
    #    EthereumTransactions is Dexicon's own superset since 2.2.34, so ETH Wallet and Dexicon share one nonce journal).
    for name in ('NodeTransport.java', 'NodeTransportService.java'):
        (dex / name).unlink()
    shutil.rmtree(main / 'com/eurobuddha/minimaapi')
    shutil.rmtree(test / 'com/eurobuddha/minimaapi', ignore_errors=True)
    for p in list(test.rglob('NodeTransport*Test.java')):
        p.unlink()
    for p in list(main.rglob('*.java')) + list(test.rglob('*.java')):
        s = p.read_text()
        if 'NodeTransportService.EVENT_ACTION' in s:
            p.write_text(s.replace('NodeTransportService.EVENT_ACTION', EVENT_ACTION))

    # 1b. Library-module fixes. The pandapools.R shim maps PandaPools' resource names onto Dexicon's: the importer
    #     prefixed every reference (R.id.x -> R.id.pm_dexicon_x), so the shim's own field names take the prefix too.
    shim = main / 'com/eurobuddha/pandapools/R.java'
    shim.write_text(re.sub(r'public static final int (\w+) =', r'public static final int pm_dexicon_\1 =', shim.read_text()))
    # A library has no APPLICATION_ID: the app is Pandamonium.
    for p in main.rglob('*.java'):
        t = p.read_text()
        if 'BuildConfig.APPLICATION_ID' in t:
            t = t.replace('com.eurobuddha.dexicon.BuildConfig.APPLICATION_ID', '"com.eurobuddha.pandamonium"')
            t = re.sub(r'(?<![\w.])BuildConfig\.APPLICATION_ID', '"com.eurobuddha.pandamonium"', t)
            p.write_text(t)
    # The deleted transport's reply-completeness check, inlined (same rule).
    replace_once(dex / 'NodeApi.java', 'if (!NodeTransport.isCompleteReply(zResponse)) {',
                 'if (!(zResponse != null && (zResponse.opt("status") instanceof Boolean || Boolean.FALSE.equals(zResponse.opt("enabled"))))) {')

    # 2. Host-node mode is permanent: the one NodeLink is the host's in-process DirectNodeApi.
    host = dex / 'HostMode.java'
    replace_once(host, '    public static boolean on() { return factory != null; }',
                 '    public static boolean on() { return true; }   // Pandamonium: always the host node')
    replace_once(host, '''        LinkFactory f = factory;
        return f != null ? f.open(ctx, registration) : new NodeTransport(ctx, registration);''',
                 '''        return new DirectLink(ctx, registration);   // Pandamonium: the embedded node, in-process''')
    replace_once(host, '    private static volatile String hostName = "";', '    private static volatile String hostName = "Pandamonium";')
    replace_once(host, '    static void reset() { factory = null; hostName = ""; }',
                 '    static void reset() { factory = null; hostName = "Pandamonium"; }')
    replace_once(host, '    /** Test seam. */', """    /** Pandamonium: a notification opens the HOST on its Dexicon tab - never a second Dexicon screen. Extras (e.g. the
     *  pools page) reach the tab's screen through the host's onNewIntent. */
    public static android.content.Intent screenIntent(Context c) {
        return new android.content.Intent().setClassName(c.getPackageName(), "com.eurobuddha.minimacore.main.MainActivity")
                .putExtra("pandamonium_tab", "dexicon")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    /** Test seam. */""")
    for p in main.rglob('*.java'):
        t = p.read_text()
        n = re.sub(r'new Intent\((\w+), (?:com\.eurobuddha\.dexicon\.)?DexiconActivity\.class\)',
                   r'com.eurobuddha.dexicon.HostMode.screenIntent(\1)', t)
        if n != t:
            p.write_text(n)
    (dex / 'DexiconActivity.java').unlink()   # the tab is the only Dexicon screen in Pandamonium
    (dex / 'DirectLink.java').write_text('''package com.eurobuddha.dexicon;

import android.content.Context;

import com.eurobuddha.minimaapi.MinimaAPIListener;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;
import com.eurobuddha.minimaapi.direct.DirectNodeEvents;

/** Pandamonium: Dexicon's one NodeLink is the host's in-process DirectNodeApi (EmbeddedNodeTransport, the node's own
 *  admin runner) - no IPC, pairing or approvals - and node events arrive through DirectNodeEvents. Generated by
 *  scripts/adapt-pandamonium-dexicon.py. */
final class DirectLink implements NodeLink {
    private final Context ctx;
    private final DirectNodeApi api;
    private final DirectNodeEvents.Listener events;

    DirectLink(Context c, MinimaAPIListener registration) {
        ctx = c.getApplicationContext();
        api = new DirectNodeApi(ctx, registration);
        events = e -> HostMode.nodeEvent(ctx, e.optString("event", ""));
        DirectNodeEvents.subscribe(events, "NEWBLOCK", "NEWBALANCE");
    }

    @Override public void Command(String command, MinimaAPIListener cb) { api.Command(command, cb); }
    @Override public void FileCommand(String action, String path, String newPath, android.net.Uri uri, MinimaAPIListener cb) {
        api.FileCommand(action, path, newPath, uri, cb);
    }
    @Override public void onDestroy() { DirectNodeEvents.unsubscribe(events); api.onDestroy(); }
}
''')

    # 3. No whole-package reset inside a node app: drop the (host-mode guarded) rescue buttons outright.
    doors = dex / 'DoorsTab.java'
    s = doors.read_text()
    start = s.index('        // Host-node mode (Pandamonium): clearing storage or uninstalling would wipe the whole node app - never offered.')
    end = s.index('    private TextView rescuePill(', start)
    tail = s[start:end]
    assert tail.rstrip().endswith('}\n    }') or tail.rstrip().endswith('}'), 'DoorsTab rescue block drift'
    # keep the method's closing brace: the block ends with "        }\n" then "    }\n"
    block_end = s.index('\n        }\n', start) + len('\n        }\n')
    assert 'ACTION_DELETE' in s[start:block_end], 'DoorsTab rescue block drift'
    s = s[:start] + s[block_end:]
    s = re.sub(r'\n    private void openAppInfo\(\) \{.*?\n    \}\n', '\n', s, flags=re.S)
    doors.write_text(s)

    # 3b. The hand-over's "Remove" uninstalled the standalone app; a node app never deletes packages (the gate is off in
    #     host-node mode anyway, so the hand-over is never offered).
    replace_once(dex / 'MainActivity.java',
                 '            if (remove) startActivity(new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + pkg)));',
                 '            if (remove) toast("Remove " + name + " from Android Settings › Apps.");   // Pandamonium never deletes packages')

    # 3c. Tests. Dexicon's own suite (standalone repo, all green) keeps full coverage. Tests that build the screen through
    #     the standalone launcher / IPC service, or that exercise the standalone-only gate and host-mode switch, cannot run
    #     here (DexiconActivity and NodeTransportService are gone, DirectNodeApi refuses a test package, host mode is
    #     permanent); every logic test stays. ScreenHostTest's fake-tab run lives in the standalone repo.
    STANDALONE_ONLY = ['CcySwitchTest', 'doors/DoorHostGateTest', 'DoorsTabSmokeTest', 'HostModeTest', 'MarketMakerSmokeTest',
                       'MarketTabSmokeTest', 'OtcTabSmokeTest', 'PoolHandoverGateTest', 'PoolsRoomSmokeTest', 'ScreenHostTest',
                       'TradingGateTest', 'TwoPagesTest', 'WalletActionsTest',
                       # these build a real NodeApi, whose DirectNodeApi refuses any package but Pandamonium / BlackBear
                       'doors/DoorHostWithdrawalTest', 'doors/DoorHostSwitchTest', 'doors/DoorHostMakerTest',
                       'maker/GateLivenessTest', 'maker/MakerServicePausedTest']
    for name in STANDALONE_ONLY:
        (test / 'com/eurobuddha/dexicon' / (name + '.java')).unlink()

    # The one keep-alive test that asserts the sibling-app GATE (off in host-node mode; custody - still on - keeps its tests).
    keep = test / 'com/eurobuddha/pandapools/DexiconPoolsKeepAliveTest.java'
    k = keep.read_text()
    k2 = re.sub(r'\n    @Test public void aConflictStopsCreateAndSwapButNotTheKeepAlive\(\) \{.*?\n    \}\n', '\n', k, count=1, flags=re.S)
    assert k2 != k, 'DexiconPoolsKeepAliveTest drift'
    keep.write_text(k2)

    # 4. Manifest: no launcher alias, restart process, IPC service or package-delete permission in a node app.
    manifest = app / 'src/main/AndroidManifest.xml'
    tree = ET.parse(manifest)
    root_el = tree.getroot()
    for perm in list(root_el.findall('uses-permission')):
        if perm.get(ANDROID + 'name') == 'android.permission.REQUEST_DELETE_PACKAGES':
            root_el.remove(perm)
    application = root_el.find('application')
    for component in list(application):
        name = component.get(ANDROID + 'name', '')
        if component.tag == 'activity-alias' or name.endswith(('.RestartActivity', '.NodeTransportService', '.DexiconActivity')):
            application.remove(component)
    ET.indent(tree, space='    ')
    tree.write(manifest, encoding='unicode', xml_declaration=True)
    restart = main / 'com/eurobuddha/dexicon/account/RestartActivity.java'
    restart.unlink()
    # its only caller runs after a Trading Account is set up, which host-node mode never offers
    replace_once(dex / 'MainActivity.java', '        com.eurobuddha.dexicon.account.RestartActivity.restart(activity());',
                 '        // Pandamonium: no Trading Account, so no restart after its setup (host-node mode never offers one).')

    # 5. Build. Bouncy Castle: the standalone app pins bcprov 1.69 STRICTLY; Pandamonium already resolves 1.70 for the node
    #    and every bundled app (web3j asks for it). 1.70 still has the Winternitz (gmss) classes - only 1.71+ dropped them -
    #    and in host-node mode the node signs everything anyway. A strict pin would fail the whole app's resolution.
    replace_once(app / 'build.gradle', "implementation('org.bouncycastle:bcprov-jdk15on') { version { strictly '1.69' } }",
                 "implementation 'org.bouncycastle:bcprov-jdk15on:1.69'   // resolves with the app (1.70, see adapt-pandamonium-dexicon.py)")
    # Dexicon's own unit-test options (a real org.json, Robolectric resources).
    replace_once(app / 'build.gradle', "    buildFeatures { buildConfig true }", """    buildFeatures { buildConfig true }
    testOptions {
        unitTests {
            returnDefaultValues = false
            includeAndroidResources = true
            all { maxHeapSize = '2g' }
        }
    }""")
    # The host's node jar at compile time (the app module ships it), like the old built-in PandaPools.
    gradle = app / 'build.gradle'
    replace_once(gradle, 'dependencies {', """dependencies {
    // The embedded node's jar is provided by the host app (app/libs/minima.jar).
    compileOnly files('../../app/libs/minima.jar')
    testImplementation files('../../app/libs/minima.jar')""")


if __name__ == '__main__':
    import sys
    adapt(Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1] / 'integrated')
    print('Adapted integrated/dexicon for the host node.')
