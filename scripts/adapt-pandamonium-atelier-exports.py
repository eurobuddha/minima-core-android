"""Reuse Atelier's decoder and PandaApps pending MediaStore writes for owned exports."""
from pathlib import Path
import shutil

def adapt(root):
    src=Path(__file__).parent/'pandamonium-sources/atelier'
    java=root/'atelier/src/main/java/com/eurobuddha/statenft'
    shutil.copy2(src/'ArtworkExport.java',java/'ArtworkExport.java')
    p=java/'ViewerScreen.java';s=p.read_text()
    def change(old,new):
        nonlocal s
        assert s.count(old)==1,('Atelier export source drift',old,s.count(old))
        s=s.replace(old,new)
    change('        root.setClickable(true);','        root.setClickable(true);\n        final Exports exports=new Exports(act,root);')
    change('back.setOnClickListener(v -> host.onCloseViewer());','back.setOnClickListener(v -> { exports.close(); host.onCloseViewer(); });')
    change('            StateNft.Item it = items.get(idx[0]);','            final long selected=exports.select();\n            StateNft.Item it = items.get(idx[0]);')
    change('buildSheet(act, host, sheet, meta, it);','buildSheet(act, host, sheet, meta, it, exports, selected);')
    old='    private static void buildSheet(Activity act, Host host, LinearLayout sheet,\n                                   StateNft.Meta meta, StateNft.Item it) {'
    change(old,old.replace('StateNft.Item it) {','StateNft.Item it, Exports exports, long selected) {'))
    change('save.setOnClickListener(v -> saveToPhotos(act, meta, it));','save.setOnClickListener(v -> exports.start(false,selected,meta.name,meta.tokenid,it.index,it.imageUrl));')
    change('share.setOnClickListener(v -> shareImage(act, meta, it));','share.setOnClickListener(v -> exports.start(true,selected,meta.name,meta.tokenid,it.index,it.imageUrl));')
    a=s.index('    private static Bitmap bitmapFor(');b=s.index('    private static View space(',a)
    s=s[:a]+(src/'viewer-exports.inc').read_text()+s[b:]
    p.write_text(s)
    p=java/'FiltrActivity.java';s=p.read_text()
    a=s.index('        @JavascriptInterface public void saveImage(');b=s.index('        @JavascriptInterface public void sendToMint',a)
    s=s[:a]+'''        @JavascriptInterface public void saveImage(String name,String b64) {
            FiltrActivity.this.saveImage(name,b64);
        }

'''+s[b:]
    change('    private class Bridge {',(src/'filtr-save-image.inc').read_text()+'    private class Bridge {')
    p.write_text(s)
    assets=root/'atelier/src/main/assets/filtr'
    p=assets/'filtr.js';s=p.read_text()
    change('.replace(/[^A-Za-z0-9_-]+/g, "_") || "filtr";', '.trim() || "filtr";')
    change('      downloadBlob(base + "-filtr."', '      filtrStatus("filtr.ui.saving");\n      downloadBlob(base + "-filtr."')
    change('      filtrStatus("filtr.ui.downloaded");','      // Native completion reports whether the photo was actually saved.')
    change('function filtrExport(jpeg, cb) {','function filtrExport(jpeg, cb, failed) {')
    change('  if (!FILTR.src) { toast(FiltrI18n.t("filtr.ui.loadFirst")); return; }\n  filtrRenderFull(true, function (out) {',
           '  if (!FILTR.src) { toast(FiltrI18n.t("filtr.ui.loadFirst")); if (failed) failed(); return; }\n  filtrRenderFull(true, function (out) {')
    change('    if (!out) { filtrStatus("filtr.ui.exportFailed", true); return; }',
           '    if (!out) { filtrStatus("filtr.ui.exportFailed", true); if (failed) failed(); return; }')
    change('function filtrDownload(jpeg) {', '''var FILTR_SAVE_PENDING = false;
function filtrSaveFinished(success) {
  FILTR_SAVE_PENDING = false;
  $("filtr-save-png").disabled = false;
  $("filtr-save-jpg").disabled = false;
  filtrStatus(success ? "filtr.ui.saved" : "filtr.ui.exportFailed", !success);
}
function filtrDownload(jpeg) {
  if (FILTR_SAVE_PENDING) return;
  FILTR_SAVE_PENDING = true;
  $("filtr-save-png").disabled = true;
  $("filtr-save-jpg").disabled = true;''')
    change('      if (!blob) { filtrStatus("filtr.ui.encodeFailed", true); return; }',
           '      if (!blob) { filtrSaveFinished(false); return; }')
    change('    }, jpeg ? "image/jpeg" : "image/png", 0.92);\n  });',
           '    }, jpeg ? "image/jpeg" : "image/png", 0.92);\n  }, function () { filtrSaveFinished(false); });')
    change('  filtrExport(jpeg, function (cv) {','  var requestedName = FILTR.name || "filtr";\n  try { filtrExport(jpeg, function (cv) {')
    change('var base = (FILTR.name || "filtr").replace(', 'var base = requestedName.replace(')
    change('  }, function () { filtrSaveFinished(false); });','  }, function () { filtrSaveFinished(false); }); } catch (e) { filtrSaveFinished(false); }')
    p.write_text(s)
    p=assets/'filtr.html';s=p.read_text()
    change('  r.readAsDataURL(blob);','  r.onerror = function () { filtrSaveFinished(false); };\n  r.readAsDataURL(blob);')
    change('      AndroidFiltr.saveImage(name, ("" + r.result).split(",")[1]);',
           '      AndroidFiltr.saveImage(name, ("" + r.result).split(",")[1]);\n    else filtrSaveFinished(false);')
    p.write_text(s)
