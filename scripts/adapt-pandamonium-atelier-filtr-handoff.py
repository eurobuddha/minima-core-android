"""Bind FILTR's existing export/AI pipeline to an owned draft and bounded private file."""
from pathlib import Path
import shutil

def adapt(root):
    src=Path(__file__).parent/'pandamonium-sources/atelier'
    java=root/'atelier/src/main/java/com/eurobuddha/statenft'
    shutil.copy2(src/'FiltrHandoff.java',java/'FiltrHandoff.java')
    p=java/'MainActivity.java';s=p.read_text()
    def change(old,new):
        nonlocal s
        assert s.count(old)==1,('FILTR handoff source drift',old,s.count(old))
        s=s.replace(old,new)
    change('v -> startActivity(new android.content.Intent(this, FiltrActivity.class))','v -> launchFiltr()')
    a=s.index('    /** The AI-photo pipeline from a 512 square bitmap');b=s.index('    private void artPhotoFromBitmap(android.graphics.Bitmap big, ImagePick pick)',a)
    s=s[:a]+(src/'filtr-handoff.inc').read_text()+s[b:]
    change('Screen screen; String lane, target, slug, hostJson;', 'Screen screen; String lane, target, slug, hostJson, filtrId;')
    change('    private String imageTarget(int context, long recovery) {', '    private String imageTarget(int context, long recovery) {\n        if (context == -1) return new JSONArray().put(mintDraftSnapshot(0)).put(artDraftSnapshot()).toString();')
    change('        if (requestCode != 7001) return;', '        if (requestCode == FiltrHandoff.REQUEST) { receiveFiltr(resultCode,data); return; }\n        if (requestCode != 7001 || (pendingImagePick != null && pendingImagePick.filtrId != null)) return;')
    change('            pick.cancelled = true;','            pick.cancelled = true;\n            if (pick.filtrId != null) FiltrHandoff.discard(this,pick.filtrId);')
    # Cancellation before worker execution and executor rejection also release cached artwork.
    change('if (pick.cancelled) { main.post(() -> acceptImageResult(pick)); return; }','if (pick.cancelled) { FiltrHandoff.discard(this,pick.filtrId); main.post(() -> acceptImageResult(pick)); return; }')
    change('        } catch (java.util.concurrent.RejectedExecutionException stopped) {','        } catch (java.util.concurrent.RejectedExecutionException stopped) {\n            FiltrHandoff.discard(this,pick.filtrId);')
    s=s.replace('pick != null && !acceptImageResult(pick)','!acceptImageResult(pick)').replace('pick != null && !imageCurrent(pick)','!imageCurrent(pick)')
    p.write_text(s)
    p=java/'FiltrActivity.java';s=p.read_text()
    change('    /** Handoffs for MainActivity (b64, no data: prefix). */\n    static volatile String pendingMint = null;\n    static volatile String pendingPhoto = null;', '''    private String requestId;
    private volatile boolean handoffClosed;
    private boolean handoffSent;
    private final java.util.concurrent.atomic.AtomicBoolean sending = new java.util.concurrent.atomic.AtomicBoolean();''')
    change('        super.onCreate(savedInstanceState);','        super.onCreate(savedInstanceState);\n        requestId = getIntent().getStringExtra(FiltrHandoff.ID);\n        if (!FiltrHandoff.validId(requestId)) { finish(); return; }')
    change('        Language.removeListener(languageChanged);','        handoffClosed = true;\n        if (!handoffSent && requestId != null) FiltrHandoff.discard(this,requestId);\n        Language.removeListener(languageChanged);')
    change('pack. MainActivity consumes the pending handoffs in onResume.','pack. The result belongs only to the Activity request that opened this editor.')
    change('            pendingMint = b64;\n            runOnUiThread(FiltrActivity.this::finish);', '            sendResult(FiltrHandoff.MINT,b64);')
    change('            pendingPhoto = b64;\n            runOnUiThread(FiltrActivity.this::finish);', '            sendResult(FiltrHandoff.PHOTO,b64);')
    change('    private class Bridge {', (src/'filtr-send-result.inc').read_text()+'    private class Bridge {')
    p.write_text(s)
