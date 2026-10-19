"""Wire the shared Salon Keets destination after existing lifecycle/localization adapters."""
from pathlib import Path
import shutil

def adapt(root):
    source=Path(__file__).parent/'pandamonium-sources/salon/keets'
    target=root/'salon/src/main/java/com/eurobuddha/salon'
    for p in source.glob('*.java'): shutil.copy2(p,target/p.name)
    p=target/'Hosting.java';s=p.read_text()
    if 'TYPE_KEETS' not in s:
        s=s.replace('    static final String TYPE_SFTP','    static final String TYPE_KEETS = "keets";\n    static final String TYPE_SFTP',1)
        s=s.replace('            case TYPE_SFTP:','            case TYPE_KEETS: return new KeetsHostUploader(p);\n            case TYPE_SFTP:',1)
    p.write_text(s)
    p=target/'RelayResolver.java';s=p.read_text()
    if 'KeetsRef' not in s:
        s=s.replace('return isRelayRef(s) || MaximaLink.isMediaRef(s);','return isRelayRef(s) || MaximaLink.isMediaRef(s) || KeetsRef.isRef(s);')
        s=s.replace('    static Media resolveBytes(String ref) throws Exception {','    static Media resolveBytes(String ref) throws Exception {\n        if (KeetsRef.isRef(ref)) return new Media(KeetsLink.fetch(ref), KeetsRef.parse(ref).mime);')
    p.write_text(s)
    p=target/'NodeApi.java';s=p.read_text()
    if 'void file(' not in s:
        s=s.replace('mMain.post(() -> cmdOnMain(command, timeoutMs, cb));','mMain.post(() -> request(timeoutFor(command) == WRITE_TIMEOUT_MS, timeoutMs, listener -> mApi.Command(command, listener), cb));')
        s=s.replace('    private void cmdOnMain(String command, long timeoutMs, Cb cb) {', '''    public void file(String action, String path, String newPath, android.net.Uri uri, Cb cb) {
        mMain.post(() -> request(true, WRITE_TIMEOUT_MS, listener -> mApi.FileCommand(action, path, newPath, uri, listener), cb));
    }
    private void request(boolean isWrite, long timeoutMs, java.util.function.Consumer<MinimaAPIListener> sender, Cb cb) {''')
        s=s.replace('        final boolean isWrite = timeoutFor(command) == WRITE_TIMEOUT_MS;\n','')
        s=s.replace('mApi.Command(command, new MinimaAPIListener() {','sender.accept(new MinimaAPIListener() {')
    p.write_text(s)
    p=target/'ProfileHosting.java';s=p.read_text()
    if 'TYPE_KEETS' not in s:s=s.replace('Hosting.TYPE_MAXIMA.equals(host.type())','Hosting.TYPE_KEETS.equals(host.type()) || Hosting.TYPE_MAXIMA.equals(host.type())')
    p.write_text(s)
    p=target/'SalonStore.java';s=p.read_text()
    if 'KeetsRef' not in s:s=s.replace('    static boolean validRecoveryUrl(String value) {','    static boolean validRecoveryUrl(String value) {\n        if (KeetsRef.isRef(value)) return KeetsRef.parse(value).profile;')
    p.write_text(s)
    p=target/'MainActivity.java';s=p.read_text()
    if 'KeetsLink.connect' in s:
        polish(root)
        return
    line=next(x for x in s.splitlines() if 'node = new NodeApi(this,' in x)
    s=s.replace(line,line+'\n        KeetsLink.connect(this, node);')
    s=s.replace('        if (node != null) node.onDestroy();','        KeetsLink.release(node);\n        if (node != null) node.onDestroy();')
    s=s.replace('HTYPES = { Hosting.TYPE_BLOSSOM,','HTYPES = { Hosting.TYPE_KEETS, Hosting.TYPE_BLOSSOM,')
    s=s.replace('HLABELS = { "Blossom",','HLABELS = { "Host on this device", "Blossom",')
    s=s.replace('            case Hosting.TYPE_SFTP:\n',(source/'HostingUi.java.inc').read_text()+'            case Hosting.TYPE_SFTP:\n',1)
    marker='    /** One-tap: create + default a Blossom'
    assert marker in s;s=s.replace(marker,(source/'QuickStart.java.inc').read_text()+marker)
    s=s.replace('        List<Hosting.Profile> profiles = HostingStore.list(this);','        body.addView(btn("Host on this device · 24 MB", true, this::quickStartKeets), lph(48, 0, 0, 0, 12));\n        List<Hosting.Profile> profiles = HostingStore.list(this);',1)
    needle='                    String rel="_test/probe-"+java.util.UUID.randomUUID()+".txt";'
    assert needle in s
    s=s.replace(needle,'                    if (Hosting.TYPE_KEETS.equals(op.profile.type())) {\n                        KeetsLink.ready();\n                        runOnUiThread(() -> finishHostingCheck(op, "Keets ready — this device can host your Salon."));\n                        return;\n                    }\n'+needle)
    s=s.replace('if (pendingMediaPick != null || activeMediaUpload != null)', 'if (profilePublish != null || pendingMediaPick != null || activeMediaUpload != null)')
    s=s.replace('if (profilePublish != null || announcement != null)', 'if (activeMediaUpload != null || profilePublish != null || announcement != null)')
    s=s.replace('bytes = readCapped(uri, 60); kind', 'bytes = readCapped(uri, Hosting.TYPE_KEETS.equals(pick.host.type()) ? 24 : 60); kind')
    s=s.replace('bytes = readCapped(uri, 25); kind', 'bytes = readCapped(uri, Hosting.TYPE_KEETS.equals(pick.host.type()) ? 24 : 25); kind')
    # Snapshot/check the prospective public profile on main before dispatching IO.
    needle='        if (bytes.length == 0) { finishMedia(pick, null, new Exception("cannot read file")); return; }'
    assert needle in s
    s=s.replace(needle,needle+'''
        try {
            if (Hosting.TYPE_KEETS.equals(pick.host.type())) {
                if (pick.code == PICK_DM_IMG) throw new Exception("Public device hosting is not used for private photos. Connect Maxima or choose another host.");
                KeetsRef.checkAddition(buildProfileJson(), pick.code == PICK_AVATAR ? "avatar" : pick.code == PICK_BANNER ? "banner" : "gallery", pick.handle, bytes, mime);
            }
        } catch (Exception error) { finishMedia(pick, null, error); return; }
''')
    p.write_text(s)

    polish(root)

def polish(root):
    p=root/'salon/src/main/java/com/eurobuddha/salon/MainActivity.java'
    s=p.read_text()
    s=s.replace('                er.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_open_browser)', '                if (!KeetsRef.isRef(viewEntry.url)) er.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_social_open_browser)')
    s=s.replace('return s.startsWith("http://") || s.startsWith("https://")', 'return KeetsRef.isRef(s) || s.startsWith("http://") || s.startsWith("https://")')
    p.write_text(s)
    # Existing Language.msg/LanguageDialog retain translations across language changes.
    import json, re
    labels=json.loads((Path(__file__).parents[1]/'localization/salon-keets-labels.json').read_text())
    def msg(key): return 'Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_keets_'+key.split('.')[-1]+')'
    s=p.read_text()
    s=s.replace('"Profile usage: " + mibOf(KeetsRef.usage(buildProfileJson())) + " / 24 MB"', 'Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_keets_usage, mibOf(KeetsRef.usage(buildProfileJson())))')
    for literal,key in labels.items():
        expression=msg(key)
        if key in ['salonKeets.name','salonKeets.private']: expression+='.toString()'
        s=re.sub(re.escape(json.dumps(literal,ensure_ascii=False))+r'(?!\.equals\(detail\))', lambda match: expression, s)
    a=s.index('    private void quickStartKeets()');b=s.index('    /** One-tap: create + default a Blossom',a)
    part=s[a:b].replace('new android.app.AlertDialog.Builder(this)', 'new com.eurobuddha.minimacore.i18n.LanguageDialog(this)')
    part=part.replace('"Cancel"','Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_common_cancel)')
    s=s[:a]+part+s[b:]
    s=s.replace('this::quickStartKeets), lph(48, 0, 0, 0, 12)', 'this::quickStartKeets), lp(0, 0, 0, 12)')
    p.write_text(s)
    # Translate only at Salon's existing display boundary; validators stay pure Java.
    cases=''.join('        if ('+json.dumps(literal,ensure_ascii=False)+'.equals(detail)) return '+msg(key)+';\n' for literal,key in labels.items())
    if 'Keets hosting errors' not in s:
        s=s.replace('        String detail = error.getMessage();', '        String detail = error.getMessage();\n        // Keets hosting errors\n'+cases+'        if ("file too large (max 24 MB)".equals(detail)) return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_upload_size, 24);')
    s=s.replace('Design.note(this, full.getMessage())', 'Design.note(this, mediaError(full))')
    s=s.replace('l10n_creative_hosting_failed, error.getMessage()', 'l10n_creative_hosting_failed, mediaError(error)')
    p.write_text(s)
