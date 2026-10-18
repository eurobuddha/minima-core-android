"""Translate the retained collection wizard and Studio entry points."""
from pathlib import Path
import json,re

def adapt(root):
    p=root/'atelier/src/main/java/com/eurobuddha/statenft/MainActivity.java';s=p.read_text()
    labels=json.loads((Path(__file__).parent/'pandamonium-sources/atelier/collection-language-labels.json').read_text())
    def msg(key,args=''):
        resource='l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()
        return 'Language.msg(com.eurobuddha.minimacore.i18n.R.string.'+resource+args+')'
    sections=[('    private void renderStudio()', '    /* ---- single NFT'),('    private LinearLayout imageSlotGrid()', '    /* ---- recovery'),('    private void offerUploadedBaseRecovery(', '    private void pickHostSingle(')]
    found=set()
    for begin,end in sections:
        a=s.index(begin);b=s.index(end,a);part=s[a:b]
        def replace(old,new):
            nonlocal part
            assert old in part,('Atelier collection drift',old);part=part.replace(old,new)
        if 'renderStudio' in begin:
            replace('private View draftRow(String kind, String name','private View draftRow(CharSequence kind, String name')
            replace('private View studioCard(String no, String title, String descText, String pillText,','private View studioCard(String no, CharSequence title, CharSequence descText, CharSequence pillText,')
            replace('kind + " draft"',msg('atelierCollection.draft',', kind'))
            replace('"Uploaded plates live at: " + createUploadedBase + "1" + createUploadedExt',msg('atelierCollection.uploadedAt',', createUploadedBase + "1" + createUploadedExt'))
            replace('"Image missing for lot " + (i + 1)',msg('atelierCollection.missingImage',', i + 1'))
            replace('''"These images weren't uploaded for this collection here — the Base URL may point at a different set:\\n\\n"
                            + bs + "1" + x
                            + "\\n\\nTo use your own pictures, tap “Upload plates” first. Mint with this URL anyway?"''',msg('atelierCollection.confirmBody',', bs + "1" + x'))
            replace('''MintEngine.jointGate(metaJ.toString().length(), 0)
                                + " — even without an icon; trim the description or trait slots"''',msg('atelierCollection.metadataTooLarge',', metaJ.toString().length(), MintEngine.META_MAX'))
            replace('''"plate " + (ii + 1) + " (" + im.length() + "B SVG) cannot be "
                                    + "shrunk to the " + room + "B image room the signed record "
                                    + "leaves - lighten the record, or tap plate " + (ii + 1)
                                    + " on the proof sheet to replace it"''',msg('atelierCollection.svgTooLarge',', ii + 1, im.length(), room'))
            replace('''"plate " + (ii + 1) + " cannot be shrunk to the " + room
                                    + "B image room - lighten the record, or tap plate "
                                    + (ii + 1) + " on the proof sheet to replace it"''',msg('atelierCollection.imageTooLarge',', ii + 1, room'))
            replace('row.addView(res, new LinearLayout.LayoutParams(dp(88), dp(38)));','res.setMinHeight(dp(48));\n        row.addView(res, new LinearLayout.LayoutParams(dp(88), -2));')
            replace('''        mode.addView(mUrl, weight(44, 0, 4));
        mode.addView(mEmb, weight(44, 4, 0));''','''        mUrl.setMinHeight(dp(48)); mEmb.setMinHeight(dp(48));
        LinearLayout.LayoutParams urlLp = weight(48, 0, 4); urlLp.height = -2;
        LinearLayout.LayoutParams embedLp = weight(48, 4, 0); embedLp.height = -2;
        mode.addView(mUrl, urlLp);
        mode.addView(mEmb, embedLp);''')
            # Same minimum-height/wrap-content pattern already proven by NFT/token forms.
            part=re.sub(r'(?m)^([ \t]*)body.addView\((restore|refresh|pick|resetAll|go), lph\((\d+), ([^\n]+)\)\);',lambda m:m[1]+m[2]+'.setMinHeight(dp('+str(max(48,int(m[3])))+'));\n'+m[1]+'body.addView('+m[2]+', lpm('+m[4]+'));',part)
        if 'imageSlotGrid' in begin:
            replace('"Plate " + String.format(Locale.US, "%02d", idx + 1)',msg('atelierCollection.plate',', String.format(Locale.US, "%02d", idx + 1)'))
            replace('new String[]{','new CharSequence[]{')
        if 'offerUploadedBaseRecovery' in begin:
            replace('''"Nothing is hosted at:\\n" + typedBase + "1" + typedExt
                                + "\\n\\nYour uploaded plates ARE live at:\\n" + probe
                                + "\\n\\nUse the uploaded base URL for this mint?"''',msg('atelierCollection.recoveryBody',', typedBase + "1" + typedExt, probe'))
            # Recovery applies the same verified-draft rule as the preceding mint gate.
            replace('        final String probe = createUploadedBase + "1" + createUploadedExt;','''        final String uploadedBase = createUploadedBase, uploadedExt = createUploadedExt;
        final String recoveryDraft = mintDraftSnapshot(2);
        final long recoveryScreen = screenGeneration;
        final String probe = uploadedBase + "1" + uploadedExt;''')
            replace('''            main.post(() -> {
                if (!good)''','''            main.post(() -> {
                if (isFinishing() || isDestroyed() || recoveryScreen != screenGeneration
                        || !recoveryDraft.equals(mintDraftSnapshot(2))) return;
                if (!good)''')
            replace('''                            createBase = createUploadedBase; createExt = createUploadedExt;''','''                            if (recoveryScreen != screenGeneration || !recoveryDraft.equals(mintDraftSnapshot(2))) { mintDraftChanged(); return; }
                            createBase = uploadedBase; createExt = uploadedExt;''')
        for literal,key in labels.items():
            old=json.dumps(literal,ensure_ascii=False)
            if old in part:part=part.replace(old,msg(key));found.add(literal)
        part=part.replace('new android.app.AlertDialog.Builder(this)','new com.eurobuddha.minimacore.i18n.LanguageDialog(this)')
        s=s[:a]+part+s[b:]
    assert found==set(labels),('Unused collection labels',set(labels)-found)
    p.write_text(s)
