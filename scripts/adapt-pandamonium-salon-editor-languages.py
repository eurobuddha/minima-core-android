"""Retain Salon editor drafts while translating controls and content dialogs."""
from pathlib import Path
import json,re

def adapt(root):
    p=root/'salon/src/main/java/com/eurobuddha/salon/MainActivity.java';s=p.read_text()
    def msg(key,args=''):
        return 'Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()+args+')'
    def change(old,new):
        nonlocal s
        assert old in s,('Salon editor drift',old);s=s.replace(old,new)
    change('private EditText fieldMulti(LinearLayout parent, String label, String value)',
           'private EditText fieldMulti(LinearLayout parent, CharSequence label, String value)')
    change('Design.text(this, label.toUpperCase(), 9.5f', 'Design.text(this, Language.upper(label), 9.5f')
    # The shared helper keeps the same inputs and callbacks. Existing literal callers still work.
    change('private void showDialog(String title, LinearLayout content, String ok, Runnable onOk)',
           'private void showDialog(CharSequence title, LinearLayout content, CharSequence ok, Runnable onOk)')
    change('new android.app.AlertDialog.Builder(this).setTitle(title).setView(content)',
           'new com.eurobuddha.minimacore.i18n.LanguageDialog(this).setTitle(title).setView(content)')
    change('.setPositiveButton(ok, (d, w) -> onOk.run()).setNegativeButton("Cancel", null)',
           '.setPositiveButton(ok, (d, w) -> onOk.run()).setNegativeButton('+msg('common.cancel')+', null)')
    labels=json.loads((Path(__file__).parent/'pandamonium-sources/salon/editor-language-labels.json').read_text());found=set()
    for begin,end in [('    private void renderEdit()', '    /* ================= media:'),('    private void renameGalleryItem(', '    /** A full-width media card:')]:
        a=s.index(begin);b=s.index(end,a);part=s[a:b]
        def replace(old,new):
            nonlocal part
            assert old in part,('Salon editor section drift',old);part=part.replace(old,new)
        if 'renderEdit' in begin:
            replace('"Handles aren\'t unique — anyone can call themselves @" + me.optString("handle") + ". To prove the real you, host a file on YOUR domain containing your token id, then paste its URL. A ✓ badge then shows on your card and page."',msg('salonEditor.verifyHelp',', me.optString("handle")'))
            replace('JSONObject l = links.optJSONObject(i); final int idx = i;', 'JSONObject l = links.optJSONObject(i); if (l == null) continue; final int idx = i;')
            replace('type.toUpperCase()', 'Language.upper(type.equals("audio") ? '+msg('salonEditor.music')+' : type.equals("video") ? '+msg('salonEditor.video')+' : type.equals("image") ? '+msg('chat.photo')+' : type)')
            part=re.sub(r'lph\((42|44|52), ', 'lp(',part)
            part=part.replace('weight(42,','weight(-2,').replace('dp(38)', '-2')
            # Reuse proof generation's exact identity/draft guard for modal edits.
            for method,collection in [('addLinkDialog','links'),('newPostDialog','posts')]:
                replace('    private void '+method+'() {', '    private void '+method+'() {\n        final java.util.function.BooleanSupplier current = editorDraftCurrent("'+collection+'");')
            replace('showDialog("Add link", box, "Add", () -> {','showDialog("Add link", box, "Add", () -> {\n            if (!current.getAsBoolean()) return;')
            replace('showDialog("New post", box, "Post", () -> {','showDialog("New post", box, "Post", () -> {\n            if (!current.getAsBoolean()) return;')
        else:
            replace('        JSONArray a = SalonStore.arr(this, "gallery");','        final java.util.function.BooleanSupplier current = editorDraftCurrent("gallery");\n        JSONArray a = SalonStore.arr(this, "gallery");')
            replace('box, "Save", () -> { try', 'box, "Save", () -> { if (!current.getAsBoolean()) return; try')
        for literal,key in labels.items():
            old=json.dumps(literal,ensure_ascii=False)
            if old in part:part=part.replace(old,msg(key));found.add(literal)
        s=s[:a]+part+s[b:]
    assert found==set(labels),('Unused editor labels',set(labels)-found)
    marker='    private void addLinkDialog() {'
    guard='''    /** A modal edit belongs to the identity and collection draft visible when opened. */
    private java.util.function.BooleanSupplier editorDraftCurrent(String collection) {
        final String owner = SalonStore.get(this, "tokenid");
        final String draft = SalonStore.arr(this, collection).toString();
        return () -> {
            boolean current = !isFinishing() && !isDestroyed()
                    && owner.equals(SalonStore.get(this, "tokenid"))
                    && draft.equals(SalonStore.arr(this, collection).toString());
            if (!current && !isFinishing() && !isDestroyed()) toast(STALE);
            return current;
        };
    }

'''.replace('STALE',msg('salonEditor.changed'))
    change(marker,guard+marker)
    p.write_text(s)
