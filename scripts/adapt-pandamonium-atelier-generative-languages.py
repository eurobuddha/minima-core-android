"""Translate generator display labels, preserving the art engine's literal config."""
from pathlib import Path
import json,re,shutil

def adapt(root):
    source=Path(__file__).parent/'pandamonium-sources/atelier'
    java=root/'atelier/src/main/java/com/eurobuddha/statenft'
    p=java/'MainActivity.java';s=p.read_text()
    def resource(key):
        return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()
    def msg(key,args=''):
        return 'Language.msg(com.eurobuddha.minimacore.i18n.R.string.'+resource(key)+args+')'
    labels=json.loads((source/'art-language-labels.json').read_text())
    cases='\n'.join('            case '+json.dumps(label,ensure_ascii=False)+': return Language.msg('+resource(key)+');' for label,key in labels.items() if key)
    (java/'ArtLabels.java').write_text('''package com.eurobuddha.statenft;

import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Display-only names. Never write these values into art configs or NFT metadata. */
final class ArtLabels {
    private ArtLabels() {}
    static CharSequence display(String literal) {
        switch (literal) {
'''+cases+'''
            default: return literal;
        }
    }
    static CharSequence error(String literal) {
        if ("a slot has no enabled variants".equals(literal)) return Language.msg(l10n_atelier_art_no_variants);
        java.util.regex.Matcher match = java.util.regex.Pattern.compile(
                "could not find a unique combo for item #([0-9]+) - enable more variants or shrink the collection").matcher(literal);
        if (match.matches()) return Language.msg(l10n_atelier_art_unique_failed, match.group(1));
        return Language.msg(l10n_atelier_art_generate_failed);
    }
}
''')
    resources=root/'atelier/src/test/resources';resources.mkdir(parents=True,exist_ok=True)
    for name in ['art-style-fixtures.json','art-language-labels.json']:
        shutil.copyfile(source/name,resources/name)
    s=s.replace('private LinearLayout sectionHead(CharSequence title, String count)', 'private LinearLayout sectionHead(CharSequence title, CharSequence count)')
    a=s.index('    private void renderGenerative()');b=s.index('    private static String artRgbaJson(',a)
    part=s[a:b]
    def replace(old,new):
        nonlocal part
        assert old in part,('Atelier generative drift',old);part=part.replace(old,new)
    replace('''"Deterministic: the seed and style reproduce every "
                + "plate byte-for-byte — the same design mints identically in the MiniDapp."''',msg('atelierArt.deterministic'))
    replace('''"Photo loaded — every plate derives from its cartoon. "
                      + "The original never leaves the phone."''',msg('atelierArt.photoLoaded'))
    replace('''"Pick a photo: it is cartoonized on-device (48px, 8 flat "
                      + "colors) and becomes the base for every plate. Without "
                      + "one the pack draws a placeholder bust that never mints."''',msg('atelierArt.photoHelp'))
    for expr in ['st.optString("label")','slot.optString("label", slotKey)','variant.optString("name")']:
        replace(expr,'ArtLabels.display('+expr+')')
    replace('nm.setSingleLine(true);','nm.setSingleLine(false);')
    replace('on + "/" + total + " on"',msg('atelierArt.enabled',', on, total'))
    replace('''studio.capacity(cfg, n -> capNote.setText("trait space: "
                + String.format(Locale.US, "%,d", n) + " distinct combinations"));''','studio.capacity(cfg, n -> Language.setText(capNote, '+msg('atelierArt.capacity',', String.format(Locale.US, "%,d", n)')+'));')
    replace('artItems.length() + " plates"',msg('atelierArt.plates',', artItems.length()'))
    replace('        dice.setOnClickListener(v -> {','        Language.description(dice, '+msg('atelierArt.randomSeed')+');\n        dice.setOnClickListener(v -> {')
    # Use the existing collection/NFT minimum-height plus wrapping layout pattern.
    part=re.sub(r'(?m)^(\s*)(row|shuffleRow|genRow).addView\((pick|clear|shufR|shufP|reset|gen), weight\(\d+, ([^\n]+)\)\);',lambda m:m[1]+m[3]+'.setMinHeight(dp(48));\n'+m[1]+'LinearLayout.LayoutParams '+m[3]+'Lp = weight(48, '+m[4]+'); '+m[3]+'Lp.height = -2;\n'+m[1]+m[2]+'.addView('+m[3]+', '+m[3]+'Lp);',part)
    replace('artSheetBox.addView(use, lph(52, 0, 12, 0, 8));','use.setMinHeight(dp(52));\n            artSheetBox.addView(use, lpm(0, 12, 0, 8));')
    # Retain translated trait text in the dialog; keep literal traits in the mint handoff.
    c=part.index('    private void showArtItem(');d=part.index('    private void sendArtToCollection()',c)
    item=part[c:d].replace('StringBuilder sb = new StringBuilder();','Language.TextBuilder sb = new Language.TextBuilder();')
    item=item.replace('t.optString("label")','ArtLabels.display(t.optString("label"))').replace('t.optString("value")','ArtLabels.display(t.optString("value"))')
    old='''sb.append("\\nrarity score r").append(item.opt("score"))
          .append(" · ").append(item.optInt("bytes")).append("B raw");'''
    assert old in item
    item=item.replace(old,'sb.append("\\n").append('+msg('atelierArt.rarityBytes',', item.optString("score"), item.optInt("bytes")')+');')
    item=item.replace('"Plate " + String.format(Locale.US, "%02d", idx + 1)',msg('atelierCollection.plate',', String.format(Locale.US, "%02d", idx + 1)')).replace('sb.toString()','sb.build()').replace('new android.app.AlertDialog.Builder(this)','new com.eurobuddha.minimacore.i18n.LanguageDialog(this)')
    part=part[:c]+item+part[d:]
    replace('toast(err);','toast(ArtLabels.error(err));')
    replace('String err = "";','CharSequence err = "";')
    replace('"Plate " + (i + 1) + " will not fit the on-chain budget"',msg('atelierArt.plateBudget',', i + 1'))
    replace('!err.isEmpty()', 'err.length() != 0')
    replace('err.isEmpty()', 'err.length() == 0')
    replace('err = gate3;', 'err = '+msg('atelierArt.signedBudget')+';')
    replace('final String errF = err, iconF = iconB64;', 'final CharSequence errF = err;\n            final String iconF = iconB64;')
    replace('!errF.isEmpty()','errF.length() != 0')
    replace('images.length + " editions drawn — finish the collection details"',msg('atelierArt.editionsReady',', images.length'))
    # Existing screen/draft validation pattern also applies to asynchronous art results.
    replace('''        ArtStudio.with(this, studio -> {
            if (screen != Screen.CREATE_GENERATIVE) return;''','''        final long requestedScreen = screenGeneration;
        final String requestedStyle = artStyle;
        ArtStudio.with(this, studio -> {
            if (isFinishing() || isDestroyed() || requestedScreen != screenGeneration) return;''')
    replace('''                    ART_STYLE_LIST = list;
                    if (screen == Screen.CREATE_GENERATIVE) renderGenerative();''','''                    if (isFinishing() || isDestroyed() || requestedScreen != screenGeneration) return;
                    ART_STYLE_LIST = list;
                    renderGenerative();''')
    replace('''                studio.defaultConfig(artStyle, c2 -> {
                    artCfgs.put(artStyle, c2);
                    artMigrated.add(artStyle);
                    if (screen == Screen.CREATE_GENERATIVE) renderGenerative();''','''                studio.defaultConfig(requestedStyle, c2 -> {
                    if (isFinishing() || isDestroyed() || requestedScreen != screenGeneration) return;
                    artCfgs.put(requestedStyle, c2);
                    artMigrated.add(requestedStyle);
                    renderGenerative();''')
    replace('''                artMigrated.add(artStyle);
                studio.migrate(cfg, m -> {
                    if (m != null && m.length() > 0) { artCfgs.put(artStyle, m); artSaveDraft(); }
                    if (screen == Screen.CREATE_GENERATIVE) renderGenerative();''','''                studio.migrate(cfg, m -> {
                    if (isFinishing() || isDestroyed() || requestedScreen != screenGeneration) return;
                    artMigrated.add(requestedStyle);
                    if (m != null && m.length() > 0) { artCfgs.put(requestedStyle, m); artSaveDraft(); }
                    renderGenerative();''')
    replace('''    private void artGenerate(ArtStudio studio, JSONObject cfg) {
        int n''','''    private void artGenerate(ArtStudio studio, JSONObject cfg) {
        if (genBusy) return;
        int n''')
    replace('''        String s = artSeed.isEmpty() ? "atelier" : artSeed;
        studio.generate''','''        final long requestedScreen = screenGeneration;
        final String requestedDraft = artDraftSnapshot();
        String s = artSeed.isEmpty() ? "atelier" : artSeed;
        studio.generate''')
    replace('''            genBusy = false;
            if (screen != Screen.CREATE_GENERATIVE) return;
            String err = result.optString''','''            genBusy = false;
            if (!artRequestCurrent(requestedScreen, requestedDraft)) return;
            String err = result.optString''')
    replace('''        final JSONArray itemsF = artItems;
        new Thread''','''        final JSONArray itemsF = artItems;
        final String requestedStyle = artStyle, requestedDraft = artDraftSnapshot();
        final String collectionDraft = mintDraftSnapshot(2);
        final long requestedScreen = screenGeneration;
        new Thread''')
    replace('java.util.HashSet<String> sealed = "photo".equals(artStyle)','java.util.HashSet<String> sealed = "photo".equals(requestedStyle)')
    replace('''                genBusy = false;
                if (errF.length()''','''                genBusy = false;
                if (!artRequestCurrent(requestedScreen, requestedDraft)) return;
                if (artItems != itemsF || !collectionDraft.equals(mintDraftSnapshot(2))) { renderGenerative(); return; }
                if (errF.length()''')
    # Only display literals are replaced. Config keys, seed, SVG and raw metadata stay literal.
    for literal,key in json.loads((source/'generative-language-labels.json').read_text()).items():
        replace(json.dumps(literal,ensure_ascii=False),msg(key))
    helpers='''    private String artDraftSnapshot() {
        JSONObject snapshot = new JSONObject();
        put(snapshot, "style", artStyle); put(snapshot, "seed", artSeed);
        put(snapshot, "count", genCount); put(snapshot, "photo", artPhotoLoaded);
        put(snapshot, "config", artCfgs.get(artStyle));
        return snapshot.toString();
    }

    private boolean artRequestCurrent(long generation, String draft) {
        if (isFinishing() || isDestroyed()) return false;
        if (generation == screenGeneration && draft.equals(artDraftSnapshot())) return true;
        if (screen == Screen.CREATE_GENERATIVE) renderGenerative();
        return false;
    }

'''
    s=s[:a]+helpers+part+s[b:];p.write_text(s)
