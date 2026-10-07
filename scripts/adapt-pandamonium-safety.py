"""Reviewed safety fixes at the integrated-app boundary; keep source snapshots reproducible."""
from pathlib import Path
import shutil

def change(path, old, new):
    s=path.read_text()
    if old not in s: raise ValueError(f'Expected source changed: {path}: {old[:70]}')
    path.write_text(s.replace(old,new))

def adapt_services(root):
    def java(app, name): return next((root/app/'src/main/java').rglob(name+'.java'))
    p=java('casino','CasinoService')
    change(p, '        startForegroundCompat();', '        if (!startForegroundCompat()) { stopSelf(); return; }')
    change(p, 'private void startForegroundCompat()', 'private boolean startForegroundCompat()')
    change(p, '''        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(FG_ID, n);
        }
    }

    private void notifyAlert''', '''        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(FG_ID, n);
            }
            return true;
        } catch (Exception unavailable) { return false; }
    }

    @Override public void onTimeout(int startId) { stopGracefully(); }
    @Override public void onTimeout(int startId, int type) { stopGracefully(); }
    private void stopGracefully() {
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
        stopSelf();
    }

    private void notifyAlert''')
    import xml.etree.ElementTree as ET
    a='{http://schemas.android.com/apk/res/android}'
    ET.register_namespace('android',a[1:-1])
    p=root/'casino/src/main/AndroidManifest.xml';tree=ET.parse(p)
    ET.SubElement(tree.getroot(),'uses-permission',{a+'name':'android.permission.FOREGROUND_SERVICE_SPECIAL_USE'})
    service=tree.getroot().find('application/service');service.set(a+'foregroundServiceType','specialUse|dataSync')
    ET.SubElement(service,'property',{a+'name':'android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE',a+'value':'Watches the embedded chain to reveal and resolve the user’s outstanding casino bets before their deadlines'})
    ET.indent(tree,space='    ');tree.write(p,encoding='unicode',xml_declaration=True)
    # Reuse TimeoutAlerts' (tag,id) identity; notification channels do not isolate numeric IDs.
    for app,name,old,new in (
        ('casino','CasinoService','nm.notify(alertId++, n)','nm.notify("pandamonium.casino", alertId++, n)'),
        ('futurecash-next','Notifier','nm.notify(alertId++, n)','nm.notify("pandamonium.futurecashnext", alertId.getAndIncrement(), n)'),
        ('atomix','MainActivity','nm.notify(notifId.incrementAndGet(),','nm.notify("pandamonium.atomix.ui", notifId.incrementAndGet(),'),
        ('atomix','SwapService','nm.notify(OFFLINE_ID,','nm.notify("pandamonium.atomix.service", OFFLINE_ID,'),
        ('atomix','SwapService','nm.cancel(OFFLINE_ID)','nm.cancel("pandamonium.atomix.service", OFFLINE_ID)'),
        ('atomix','SwapService','nm.notify(id,','nm.notify("pandamonium.atomix.service", id,'),
        ('atomix','SwapService','nm.cancel(id)','nm.cancel("pandamonium.atomix.service", id)'),
        ('pandapools','StrandingWatch','nm.notify(idFor(p.address),','nm.notify("pandamonium.pandapools", idFor(p.address),'),
        ('pandapools','StrandingWatch','nm.cancel(idFor(address))','nm.cancel("pandamonium.pandapools", idFor(address))'),
        ('pandapools','PoolKeepAliveService','nm.cancel(StrandingWatch.idFor(pool.address))','nm.cancel("pandamonium.pandapools", StrandingWatch.idFor(pool.address))'),
    ): change(java(app,name),old,new)
    change(java('futurecash-next','Notifier'), 'private static int alertId = 3100;', 'private static final java.util.concurrent.atomic.AtomicInteger alertId = new java.util.concurrent.atomic.AtomicInteger(3100);')

def adapt(root):
    def java(app, name): return next((root/app/'src/main/java').rglob(name+'.java'))
    # Mail's scanner calls from its IO executor; reuse the main-thread dispatch used below.
    change(java('mail','NodeApi'), '    public void cmd(String command, Cb cb) {\n        if (mReleased) return;', '''    public void cmd(String command, Cb cb) {
        mMain.post(() -> dispatch(command, cb));
    }

    private void dispatch(String command, Cb cb) {
        if (mReleased) return;''')
    change(java('mail','NodeApi'), '                    if (cb != null) cb.onResult(zResponse);', '''                    if (!(zResponse.opt("status") instanceof Boolean)) {
                        if (cb != null) cb.onError(zResponse.optString("transporterror", "Node result unavailable. Outcome unknown."));
                        return;
                    }
                    if (cb != null) cb.onResult(zResponse);''')
    change(java('mail','MainActivity'), 'NotificationManagerCompat.from(this).notify(42, n)',
           'NotificationManagerCompat.from(this).notify("pandamonium.mail", 42, n)')
    # Same strict Boolean result check used by PandaDEX's NodeApi.isCompleteReply.
    change(java('futurecash-next','Node'), 'return r != null && r.length() == 0;',
           'return r != null && !(r.opt("status") instanceof Boolean);')
    change(java('futurecash-next','Tx'), 'if (!r.optBoolean("status", isPost))', 'if (!r.optBoolean("status", false))')
    p=java('futurecash-next','Tx');s=p.read_text();start=s.index(' * things about running');end=s.index(' * <p>The sibling',start)
    s=s[:start]+''' * things remain important with the embedded node: each build step is checked before the next
 * command is sent, and async mining is not confirmation. A complete command result carries a
 * Boolean status. Missing/malformed status is an unknown outcome, never a successful post.
 *
'''+s[end:]
    s=s.replace('                final boolean isPost = cmd.startsWith("txnpost");\n','').replace('// Build steps must be status:true. A post may omit status while it is being mined.', '// Both build and post must return a complete, successful node envelope.')
    p.write_text(s)
    p=java('futurecash-next','Node');s=p.read_text();start=s.index('    /**\n     * True when a reply');end=s.index('    public static boolean unreadable',start)
    s=s[:start]+'''    /** Missing or malformed status means an unknown outcome, including direct transport errors. */
'''+s[end:];p.write_text(s)
    # The guardian invokes this wrapper from a worker. Keep all pending/done state on its Handler.
    change(java('futurecash-next','NodeApi'), '    public void cmd(String command, Cb cb) {\n        if (mReleased) return;', '''    public void cmd(String command, Cb cb) {
        mMain.post(() -> dispatch(command, cb));
    }

    private void dispatch(String command, Cb cb) {
        if (mReleased) { if (cb != null) cb.onError("Node connection closed."); return; }''')
    # Preserve ciphertext and the alias on any opening error. Never infer that a failure is permanent.
    p=java('ethwallet','KeyVault');s=p.read_text();start=s.index('/**');end=s.index('public final class',start)
    s=s[:start]+'''/** Keystore-backed imported key storage. Opening failures preserve all existing key material. */
'''+s[end:]
    start=s.index('        boolean reset = false;');end=s.index('    private static SharedPreferences open',start)
    s=s[:start]+'''        try {
            opened = open(ctx);
        } catch (Exception failure) {
            Log.w(TAG, "Secure store unavailable; existing keys preserved.");
        }
        this.enc = opened;
        this.wasReset = false;
    }

'''+s[end:]
    start=s.index('    /** Drop the undecryptable');end=s.index('    /** False when',start);s=s[:start]+s[end:]
    s=s.replace('import java.io.File;\n','').replace('import java.security.KeyStore;\n','')
    p.write_text(s)
    p=java('ethwallet','MainActivity');s=p.read_text();start=s.index('            // The stored key is gone');end=s.index('            prefs.edit().remove("source").apply();',start)
    s=s[:start]+'''            // Preserve the selected source while storage is unavailable; a later launch can retry.
            ethErr = "The imported key could not be read. Existing encrypted data has been preserved. "
                    + "Unlock the device and restart Minima Core to retry.";
            render();
            return;
'''+s[end+len('            prefs.edit().remove("source").apply();\n'):];p.write_text(s)
    # Fail closed for new bets. Read legacy plaintext secrets so an earlier fallback does not strand a bet.
    p=java('casino','SecretStore');s=p.read_text().replace('Falls back to plain prefs only if the keystore is unavailable.', 'New writes require encryption; old plaintext fallback secrets remain readable for recovery.')
    s=s.replace('    private final SharedPreferences prefs;', '    private final SharedPreferences prefs;\n    private final SharedPreferences legacy;')
    s=s.replace('        prefs = open(ctx);', '        prefs = open(ctx);\n        legacy = ctx.getSharedPreferences(FILE + "_plain", Context.MODE_PRIVATE);')
    s=s.replace('// Keystore unavailable (rare) — degrade to plain prefs rather than crash.\n            return ctx.getSharedPreferences(FILE + "_plain", Context.MODE_PRIVATE);', '// Preserve encrypted data and refuse new commitments until the keystore is available.\n            return null;')
    s=s.replace('        prefs.edit().putString(key, value).apply();', '        if (prefs == null) return;\n        try { prefs.edit().putString(key, value).apply(); } catch (RuntimeException unavailable) { /* Preserve existing history. */ }')
    s=s.replace('        try {\n            boolean ok = prefs.edit()', '        if (prefs == null) return false;\n        try {\n            boolean ok = prefs.edit()')
    s=s.replace('        return prefs.getString(key, null);', '''        try {
            String value = prefs == null ? null : prefs.getString(key, null);
            if (value != null) return value;
        } catch (RuntimeException unavailable) { /* Try only the existing recovery copy. */ }
        try { return legacy.getString(key, null); }
        catch (RuntimeException unavailable) { return null; }''')
    s=s.replace('        return prefs.contains(key);', '        return get(key) != null;');p.write_text(s)
    # Preserve the existing authorization predicate across the additional shared worker queue.
    change(java('pandadex','NodeApi'), 'mApi.Command(command, new MinimaAPIListener()', 'mApi.Command(command, authorized, new MinimaAPIListener()')
    change(java('pandadex','NodeApi'), 'private static Object connectionGeneration', 'private static volatile Object connectionGeneration')
    change(java('pandadex','CommandSession'), 'private Object generation', 'private volatile Object generation')
    change(java('pandadex','CommandSession'), 'private Object boundConnection', 'private volatile Object boundConnection')
    change(java('pandadex','NodeApi'), '\n                            + " chars=" + (zResponse == null ? 0 : zResponse.toString().length())', '')
    adapt_services(root)
    for app in ('futurecash-next','ethwallet','casino','vestr'):
        p=root/app/'build.gradle'
        change(p, '    buildFeatures { buildConfig true }', '    buildFeatures { buildConfig true }\n    testOptions { unitTests.returnDefaultValues = true }')
        if 'org.mockito:mockito-core:' not in p.read_text():
            change(p, 'dependencies {', "dependencies {\n    testImplementation 'org.mockito:mockito-core:5.12.0'")
    for p in Path(__file__).with_name('pandamonium-tests').glob('*/**/*.java'):
        dest=root/p.relative_to(Path(__file__).with_name('pandamonium-tests'))
        dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,dest)

if __name__=='__main__':
    import sys
    adapt(Path(sys.argv[1]))
