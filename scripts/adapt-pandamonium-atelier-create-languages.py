"""Translate native NFT/token forms without changing metadata or node commands."""
from pathlib import Path
import json,re

def adapt(root):
    p=root/'atelier/src/main/java/com/eurobuddha/statenft/MainActivity.java';s=p.read_text()
    labels=json.loads((Path(__file__).parent/'pandamonium-sources/atelier/create-language-labels.json').read_text())
    def msg(key,args=''):
        name='l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()
        return 'Language.msg(com.eurobuddha.minimacore.i18n.R.string.'+name+args+')'
    def change(old,new):
        nonlocal s
        assert old in s,('Atelier creation language drift',old);s=s.replace(old,new)
    change('private View hostingUploadRow(String lane, String label, Runnable onTap)',
           'private View hostingUploadRow(String lane, CharSequence label, Runnable onTap)')
    change('private LinearLayout iconUploadRow(String iconB64, String caption,',
           'private LinearLayout iconUploadRow(String iconB64, CharSequence caption,')
    found=set()
    sections=[('    private void renderCreateNft()', '    private String previewNftJson()'),
              ('    private void mintNft(', '    /* ---- custom token ---- */'),
              ('    private void renderCreateToken()', '    private String previewTokenJson()'),
              ('    private View hostingUploadRow(', '    private boolean hostGatePassed'),
              ('    private LinearLayout iconUploadRow(', '    private EditText fieldInto(')]
    for begin,end in sections:
        a=s.index(begin);b=s.index(end,a);part=s[a:b]
        for literal,key in labels.items():
            old=json.dumps(literal,ensure_ascii=False)
            if old in part:part=part.replace(old,msg(key));found.add(literal)
        if 'renderCreateNft' in begin:
            # Same wrap-content/minimum-height pattern as the wallet backup action.
            old='        modeRow.addView(mEmb, weight(44, 0, 4));\n        modeRow.addView(mUrl, weight(44, 4, 0));'
            new='        mEmb.setMinHeight(dp(48)); mUrl.setMinHeight(dp(48));\n        LinearLayout.LayoutParams embedLp = weight(48, 0, 4); embedLp.height = -2;\n        LinearLayout.LayoutParams urlLp = weight(48, 4, 0); urlLp.height = -2;\n        modeRow.addView(mEmb, embedLp);\n        modeRow.addView(mUrl, urlLp);'
            assert old in part; part=part.replace(old,new)
            old='''"ed. " + (nftEditions.trim().isEmpty() ? "1" : nftEditions.trim())
                + (nftOwner.trim().isEmpty() ? "" : " · " + nftOwner.trim())
                + (nftTraits.isEmpty() ? "" : " · " + nftTraits.size() + " traits")'''
            new='Language.join('+msg('atelierCreate.editionsPreview',', nftEditions.trim().isEmpty() ? "1" : nftEditions.trim()')+', (nftOwner.trim().isEmpty() ? "" : " · " + nftOwner.trim()), nftTraits.isEmpty() ? "" : Language.join(" · ", '+msg('atelierCreate.traitsPreview',', nftTraits.size()')+'))'
            assert old in part,'NFT preview summary drift';part=part.replace(old,new)
        if 'mintNft' in begin or 'renderCreateToken' in begin:
            old='"Mint failed: " + json.optString("error", "node refused")'
            assert old in part,'Mint error drift'
            part=part.replace(old,msg('salonSetup.mintFailed',', json.isNull("error") ? '+msg('atelierCreate.nodeRefused')+' : json.optString("error")'))
            assert 'go.setText("MINTING…")' in part
            part=part.replace('go.setText("MINTING…")','Language.setText(go, Language.upper('+msg('atelierCreate.minting')+'))')
        if 'mintNft' in begin:
            old='''"this SVG (" + nftImage.length() + "B) cannot be shrunk to the "
                            + artBudget + "B the signed record allows"
                            + (editions > 1 ? " with " + editions + " editions" : "")'''
            assert old in part
            part=part.replace(old,'Language.join('+msg('atelierCreate.svgTooLarge',', nftImage.length(), artBudget')+', editions > 1 ? Language.join(" ", '+msg('atelierCreate.svgEditions',', editions')+') : "")')
            old='''"artwork cannot be shrunk to the " + artBudget
                            + "B the signed record allows - shorten the text or reduce editions"'''
            assert old in part;part=part.replace(old,msg('atelierCreate.artTooLarge',', artBudget'))
        if 'renderCreateToken' in begin:
            old='"\'" + k + "\' is a reserved field"';assert old in part
            part=part.replace(old,msg('atelierCreate.reserved',', k'))
        if 'iconUploadRow' in begin:
            old='        row.addView(btn, new LinearLayout.LayoutParams(0, dp(40), 1));'
            new='        btn.setMinHeight(dp(48));\n        row.addView(btn, new LinearLayout.LayoutParams(0, -2, 1));'
            assert old in part;part=part.replace(old,new)
        s=s[:a]+part+s[b:]
    assert found==set(labels),('Unused creation labels',set(labels)-found)
    p.write_text(s)
