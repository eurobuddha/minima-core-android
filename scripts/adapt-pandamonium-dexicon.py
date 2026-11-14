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



# ---- host language: route the texts Dexicon shows outside its tab's views through HostMode.tr ----
WRAP = {  # method -> (argument index, wrapper)
    'setTitle': (0, 'tr'), 'setMessage': (0, 'tr'), 'setPositiveButton': (0, 'tr'), 'setNegativeButton': (0, 'tr'),
    'setNeutralButton': (0, 'tr'), 'setItems': (0, 'tr'), 'setContentTitle': (0, 'tr'), 'setContentText': (0, 'tr'),
    'bigText': (0, 'tr'), 'setTicker': (0, 'tr'), 'makeText': (1, 'tr'), 'setView': (0, 'translated'),
}


def _args(src, start):
    """Spans [(a, b)] of the top-level arguments of the call whose '(' is at start, and the ')' index."""
    depth, i, a, spans = 0, start + 1, start + 1, []
    while i < len(src):
        c = src[i]
        if src.startswith('//', i):
            i = src.index('\n', i); continue
        if src.startswith('/*', i):
            i = src.index('*/', i) + 2; continue
        if c == '"':
            i += 1
            while src[i] != '"': i += 2 if src[i] == '\\' else 1
        elif c == "'":
            i += 1
            while src[i] != "'": i += 2 if src[i] == '\\' else 1
        elif c in '([{': depth += 1
        elif c in ')]}':
            if depth == 0: spans.append((a, i)); return spans, i
            depth -= 1
        elif c == ',' and depth == 0: spans.append((a, i)); a = i + 1
        i += 1
    raise ValueError('unbalanced call')


def wrap_texts(src):
    out, pos = [], 0
    for m in re.finditer(r'\.(' + '|'.join(WRAP) + r')\(', src):
        if m.start() < pos: continue
        idx, fn = WRAP[m.group(1)]
        spans, _ = _args(src, m.end() - 1)
        if idx >= len(spans): continue
        a, b = spans[idx]
        arg = src[a:b].strip()
        if not arg or arg.startswith('com.eurobuddha.dexicon.HostMode.') or re.fullmatch(r'(android\.)?R\.[\w.]+|\d+|null', arg): continue
        lead = src[a:b][:len(src[a:b]) - len(src[a:b].lstrip())]
        out.append(src[pos:a] + lead + 'com.eurobuddha.dexicon.HostMode.' + fn + '(' + arg + ')')
        pos = b
    out.append(src[pos:])
    return ''.join(out)

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
    #     A library build STRIPS every class named R from its packaged code (the app generates them), so the shim compiles
    #     and passes unit tests yet is missing from the APK - opening the tab crashed (NoClassDefFoundError
    #     com.eurobuddha.pandapools.R$layout, 2.2.36 on the Fold). Point every reference at Dexicon's real generated R
    #     (same, already-prefixed names) and drop the shim.
    shim = main / 'com/eurobuddha/pandapools/R.java'
    shim.unlink()
    for p in main.rglob('*.java'):
        t = p.read_text()
        u = t.replace('com.eurobuddha.pandapools.R.', 'com.eurobuddha.dexicon.R.')
        if p.parent.name == 'pandapools' and 'com/eurobuddha/pandapools' in p.as_posix():
            u = re.sub(r'(?<![\w.])R\.(layout|id|raw|drawable|string|color|style|dimen|xml|menu|anim|mipmap)\.',
                       r'com.eurobuddha.dexicon.R.\1.', u)
        if u != t: p.write_text(u)
    assert not list(app.rglob('R.java')), 'a hand-written R class is stripped from a library build'
    for p in main.rglob('*.java'):
        if p.name == 'HostMode.java' or '/minimaapi/' in p.as_posix(): continue
        t = p.read_text(); u = wrap_texts(t)
        if u != t: p.write_text(u)
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
    replace_once(host, '    public static void reset() { factory = null; hostName = ""; exitAction = null; translator = null; }',
                 '    public static void reset() { factory = null; hostName = "Pandamonium"; exitAction = null; translator = null; }')
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
    // ONE process-wide event subscription however many links are open (screen, DoorHost, MakerService, backups):
    // each NEWBLOCK / NEWBALANCE is rebroadcast once, not once per link (2.2.34 review).
    private static int open;
    private static DirectNodeEvents.Listener events;

    private final DirectNodeApi api;
    private boolean closed;

    DirectLink(Context c, MinimaAPIListener registration) {
        Context app = c.getApplicationContext();
        api = new DirectNodeApi(app, registration);
        synchronized (DirectLink.class) {
            if (open++ == 0) {
                events = e -> HostMode.nodeEvent(app, e.optString("event", ""));
                DirectNodeEvents.subscribe(events, "NEWBLOCK", "NEWBALANCE");
            }
        }
    }

    @Override public void Command(String command, MinimaAPIListener cb) { api.Command(command, cb); }
    @Override public void FileCommand(String action, String path, String newPath, android.net.Uri uri, MinimaAPIListener cb) {
        api.FileCommand(action, path, newPath, uri, cb);
    }
    @Override public void onDestroy() {
        synchronized (DirectLink.class) {
            if (!closed && --open == 0) { DirectNodeEvents.unsubscribe(events); events = null; }
            closed = true;
        }
        api.onDestroy();
    }
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
                       'doors/DoorHostFreshIdentityTest',
                       # the sibling-app gate, which host mode turns off by design
                       'doors/DoorHostMakerTest', 'maker/GateLivenessTest', 'maker/MakerServicePausedTest']
    for name in STANDALONE_ONLY:
        (test / 'com/eurobuddha/dexicon' / (name + '.java')).unlink()
    # The money-path tests that build a real NodeApi (DoorHost withdrawal, market switch) run here as the host package,
    # which DirectNodeApi serves (2.2.35 review: they had been dropped).
    for name in ('doors/DoorHostWithdrawalTest', 'doors/DoorHostSwitchTest'):
        replace_once(test / 'com/eurobuddha/dexicon' / (name + '.java'), '@Config(sdk = 34)',
                     '@Config(sdk = 34, application = com.eurobuddha.dexicon.HostTestApp.class)')
    (test / 'com/eurobuddha/dexicon/HostTestApp.java').write_text('''package com.eurobuddha.dexicon;
/** Generated by scripts/adapt-pandamonium-dexicon.py: module tests run as the host package, which DirectNodeApi serves. */
public class HostTestApp extends android.app.Application {
    @Override public String getPackageName() { return "com.eurobuddha.pandamonium"; }
}
''')

    # The one keep-alive test that asserts the sibling-app GATE (off in host-node mode; custody - still on - keeps its tests).
    keep = test / 'com/eurobuddha/pandapools/DexiconPoolsKeepAliveTest.java'
    k = keep.read_text()
    k2 = re.sub(r'\n    @Test public void aConflictStopsCreateAndSwapButNotTheKeepAlive\(\) \{.*?\n    \}\n', '\n', k, count=1, flags=re.S)
    assert k2 != k, 'DexiconPoolsKeepAliveTest drift'
    keep.write_text(k2)

    # 3b. The generated DirectLink gets its own test (2.2.35 review: it was the one untested piece of the host plumbing).
    (test / 'com/eurobuddha/dexicon/DirectLinkTest.java').write_text('''package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;

import com.eurobuddha.minimaapi.direct.DirectNodeApi;
import com.eurobuddha.minimaapi.direct.DirectNodeEvents;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

/** Generated by scripts/adapt-pandamonium-dexicon.py: Dexicon's NodeLink inside Pandamonium. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DirectLinkTest {
    /** DirectNodeApi only serves the host's own package. */
    private static final class AsHost extends ContextWrapper {
        AsHost(Context base) { super(base); }
        @Override public String getPackageName() { return "com.eurobuddha.pandamonium"; }
        @Override public Context getApplicationContext() { return this; }
    }

    @After public void down() { DirectNodeApi.install(null); }

    private int nodeEvents(Application app) {
        int n = 0;
        for (Intent i : Shadows.shadowOf(app).getBroadcastIntents())
            if ("com.eurobuddha.dexicon.NODE_EVENT".equals(i.getAction())) n++;
        return n;
    }

    @Test public void hostModeIsAlwaysOnAndEveryCommandGoesToTheEmbeddedNode() throws Exception {
        assertTrue(HostMode.on());
        List<String> ran = new ArrayList<>();
        DirectNodeApi.install(new DirectNodeApi.Backend() {
            public boolean ready() { return true; }
            public void command(String command, DirectNodeApi.Request reply) {
                ran.add(command);
                try { reply.accept(new JSONObject().put("status", true).put("response", new JSONObject())); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
            public void file(String a, String p, String n, android.net.Uri u, DirectNodeApi.Request reply) {}
        });
        NodeLink link = HostMode.open(new AsHost(RuntimeEnvironment.getApplication()), null);
        assertTrue(link instanceof DirectLink);
        JSONObject[] got = new JSONObject[1];
        link.Command("balance", r -> got[0] = r);
        for (int i = 0; i < 50 && got[0] == null; i++) { ShadowLooper.idleMainLooper(); Thread.sleep(20); }
        assertNotNull("the embedded node answered", got[0]);
        assertTrue(got[0].optBoolean("status"));
        assertEquals(java.util.Collections.singletonList("balance"), ran);
        link.onDestroy();
    }

    @Test public void eachNodeEventIsRebroadcastOnceHoweverManyLinksAreOpen() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Context host = new AsHost(app);
        NodeLink a = new DirectLink(host, null), b = new DirectLink(host, null), c = new DirectLink(host, null);
        int before = nodeEvents(app);
        DirectNodeEvents.publish(new JSONObject().put("event", "NEWBLOCK"));
        assertEquals("three links, one broadcast", before + 1, nodeEvents(app));
        DirectNodeEvents.publish(new JSONObject().put("event", "MINING"));
        assertEquals("only block and balance events", before + 1, nodeEvents(app));
        a.onDestroy(); a.onDestroy(); b.onDestroy();   // a double close never unsubscribes the others
        DirectNodeEvents.publish(new JSONObject().put("event", "NEWBALANCE"));
        assertEquals(before + 2, nodeEvents(app));
        c.onDestroy();
        DirectNodeEvents.publish(new JSONObject().put("event", "NEWBLOCK"));
        assertEquals("all closed: nothing subscribed", before + 2, nodeEvents(app));
    }
}
''')

    # 3c-bis. What the standalone-only gate tests (DoorHostMakerTest, GateLivenessTest, MakerServicePausedTest) become here:
    # the sibling-app gate never shuts trading inside the host, whatever is installed.
    (test / 'com/eurobuddha/dexicon/HostGateOffTest.java').write_text('''package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/** Generated by scripts/adapt-pandamonium-dexicon.py: inside Pandamonium the maker / take gate is never shut by sibling
 *  apps or an unread MiniHub (the standalone gate tests cover the standalone build). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class HostGateOffTest {
    private static void install(Application app, String pkg) {
        PackageInfo pi = new PackageInfo(); pi.packageName = pkg;
        pi.applicationInfo = new ApplicationInfo(); pi.applicationInfo.packageName = pkg;
        Shadows.shadowOf(app.getPackageManager()).installPackage(pi);
    }

    @Test public void siblingAppsAndAnUnreadMiniHubNeverGateTheHost() {
        Application app = RuntimeEnvironment.getApplication();
        assertTrue(HostMode.on());
        assertFalse("MiniHub never read", TradingGate.miniHubChecked(app));
        assertFalse(TradingGate.blocked(app));
        install(app, TradingGate.ATOMIX);
        install(app, TradingGate.PANDAPOOLS);
        assertFalse(TradingGate.conflict(app));
        assertFalse("AtomiX + PandaPools installed: still open", TradingGate.blocked(app));
    }
}
''')

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
