"""Packaging and embedded-node boundaries for the existing Atelier and Salon apps.
Financial engines, content schemas, art engines and crypto formats remain donor code.
"""
from pathlib import Path
import xml.etree.ElementTree as ET

A = '{http://schemas.android.com/apk/res/android}'

def prepare(root):
    def change(path, old, new):
        text = path.read_text()
        assert old in text, ('Creative app source drift', path, old)
        path.write_text(text.replace(old, new))

    for name, package in [('atelier', 'statenft'), ('salon', 'salon')]:
        base = root / name
        java = base / ('src/main/java/com/eurobuddha/' + package)
        # Reuse the Mall/Vestr main-handler dispatch and complete-result boundary.
        # Keep complete node rejections available to donor callers.
        change(java/'NodeApi.java', '    public void cmd(String command, long timeoutMs, Cb cb) {', """    public void cmd(String command, long timeoutMs, Cb cb) {
        mMain.post(() -> cmdOnMain(command, timeoutMs, cb));
    }

    private void cmdOnMain(String command, long timeoutMs, Cb cb) {""")
        change(java/'NodeApi.java', '                        noteEnabled(true);', '                        if (zResponse.opt("status") instanceof Boolean) noteEnabled(true);')
        change(java/'NodeApi.java', '                        if (cb != null) cb.onResult(zResponse);', """                        if (!(zResponse.opt("status") instanceof Boolean)) {
                            if (cb != null) cb.onError(zResponse.optString("transporterror", "Node result unavailable. Outcome unknown."));
                            return;
                        }
                        if (cb != null) cb.onResult(zResponse);""")
        change(base/'build.gradle', '    buildFeatures { buildConfig true }',
               '    buildFeatures { buildConfig true }\n    testOptions { unitTests.returnDefaultValues = true; unitTests.includeAndroidResources = true }')
        change(base/'build.gradle', 'dependencies {', "dependencies {\n    testImplementation 'org.mockito:mockito-core:5.12.0'")
        # The standalone apps intentionally shared names under different UIDs.
        # In one APK each retains its own credentials and cache ownership.
        change(java/'HostingStore.java', '"statenft_hosting"', '"pandamonium_'+name+'_hosting"')
        change(java/'Crypt.java', '"atelier_hosting"', '"pandamonium_'+name+'_hosting_key"')
        for path in java.glob('*.java'):
            text = path.read_text()
            text = text.replace('getPackageName() + ".files"', 'getPackageName() + ".'+name+'.fileprovider"')
            text = text.replace('getCacheDir(), "shared"', 'getCacheDir(), "pandamonium_'+name+'_shared"')
            path.write_text(text)
        p=base/'src/main/res/xml'/('pm_'+name+'_file_paths.xml')
        change(p, 'path="shared/"', 'path="pandamonium_'+name+'_shared/"')
        # Both MainActivities are native AppCompat screens; label bindings are added
        # in the separate language adapter, with no recreation or wallet reinitialization.
        main=java/'MainActivity.java'
        text=main.read_text()
        marker='appbarBox = vertical();' if name=='atelier' else 'appbar = new LinearLayout(this); appbar.setOrientation(LinearLayout.VERTICAL);'
        assert marker in text
        text=text.replace(marker, marker+'\n        '+('appbarBox' if name=='atelier' else 'appbar')+'.setTag("pandamonium.app.toolbar");')
        main.write_text(text)

    # Remove the donor stylesheet's single trailing space for the repository whitespace gate.
    change(root/'atelier/src/main/assets/filtr/styles.css', 'opacity: 0; \n', 'opacity: 0;\n')

    java=root/'atelier/src/main/java/com/eurobuddha/statenft'
    change(java/'MintService.java', 'int NOTE_ID = 41;', 'int NOTE_ID = 7601;')
    # Preserve the donor canonical art-parity regression in an isolated snapshot.
    change(java.parent.parent.parent.parent.parent/'test/java/com/eurobuddha/statenft/ArtAssetParityTest.java',
           'new File("../../minidapp/art.js")', 'new File("src/test/resources/canonical-art.js")')

    tests=root/'atelier/src/test/java/com/eurobuddha/statenft'
    change(tests/'HostingTest.java', '../../test/fixtures/hosting/parity.json', 'src/test/resources/fixtures/hosting/parity.json')
    change(tests/'Ans104Test.java', '../../test/fixtures/arweave/ans104.json', 'src/test/resources/fixtures/arweave/ans104.json')

    java=root/'salon/src/main/java/com/eurobuddha/salon'
    path=java/'SalonNotifyReceiver.java';text=path.read_text()
    text=text.replace('import android.content.BroadcastReceiver;', 'import com.eurobuddha.minimaapi.direct.DirectNodeEvents;')
    text=text.replace('import com.eurobuddha.minimaapi.MinimaAPI;\n','').replace('import com.eurobuddha.minimaapi.MinimaAPIMessages;\n','')
    text=text.replace('extends BroadcastReceiver', 'implements DirectNodeEvents.Listener')
    start=text.index('    @Override public void onReceive(');end=text.index('    private void process(',start)
    text=text[:start]+"""    private final Context context;
    public SalonNotifyReceiver(Context context) { this.context = context.getApplicationContext(); }
    // Host subscribes this listener on its bounded background executor, like Terminal IDE.
    @Override public void onEvent(JSONObject event) {
        // The host exists before Salon is used. Do not create messaging keys from
        // unrelated wallet events; keep the donor identity/seed ownership boundary.
        if (!SalonStore.hasIdentity(context) && SalonStore.get(context, "msgseed").isEmpty()) return;
        process(context, event.toString());
    }

"""+text[end:]
    text=text.replace('NotificationManagerCompat.from(ctx).notify((int)', 'NotificationManagerCompat.from(ctx).notify("pandamonium.salon", (int)')
    path.write_text(text)
    p=root/'salon/src/main/AndroidManifest.xml';tree=ET.parse(p);app=tree.getroot().find('application')
    for receiver in list(app.findall('receiver')):
        if receiver.get(A+'name')=='com.eurobuddha.salon.SalonNotifyReceiver':app.remove(receiver)
    ET.indent(tree,space='    ');tree.write(p,encoding='unicode',xml_declaration=True)
