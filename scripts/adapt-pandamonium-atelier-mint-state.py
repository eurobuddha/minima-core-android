"""Bind mint verification and signing to the validated draft, preserving later edits."""
from pathlib import Path

def adapt(root):
    project=Path(__file__).resolve().parents[1]
    p=root/'atelier/src/main/java/com/eurobuddha/statenft/MainActivity.java';s=p.read_text()
    def change(old,new):
        nonlocal s
        assert old in s,('Atelier mint state drift',old)
        s=s.replace(old,new)
    change('    private boolean nftBusy = false;', '    private boolean nftBusy = false;\n    private boolean collectionBusy = false;\n    private long screenGeneration;')
    change('        screen = s;','        screenGeneration++;\n        screen = s;')
    change('    @Override protected void onDestroy() {','    @Override protected void onDestroy() {\n        verifyGateCancel = true;')
    a=s.index('    private boolean hostGatePassed');b=s.index('    /** At mint,',a)
    s=s[:a]+(project/'scripts/pandamonium-sources/atelier/MintVerification.java.inc').read_text()+'\n'+s[b:]
    # Confirmation authorizes the exact collection draft, not later URL edits.
    change('        saveCollectionDraft(name, desc, size, base, ext, icon, external, web);\n        String n =',
           '        if (collectionBusy) return;\n        saveCollectionDraft(name, desc, size, base, ext, icon, external, web);\n        String n =')
    change('!mintBaseConfirmed','!mintDraftSnapshot(2).equals(confirmedCollectionDraft)')
    change('            new android.app.AlertDialog.Builder(this)\n                    .setTitle("Use this Base URL?")',
           '            final String confirmationDraft = mintDraftSnapshot(2);\n            final long confirmationScreen = screenGeneration;\n            new android.app.AlertDialog.Builder(this)\n                    .setTitle("Use this Base URL?")')
    change('() -> { hostGatePassed = true;\n                                createCollection(name, desc, size, base, ext, icon, external, web); }',
           '() -> { hostGatePassed = true;\n                                try { createCollection(name, desc, size, base, ext, icon, external, web); }\n                                finally { hostGatePassed = false; } }')
    change('(dg, wch) -> { mintBaseConfirmed = true;\n                            createCollection(name, desc, size, base, ext, icon, external, web); }',
           '(dg, wch) -> {\n                            if (confirmationScreen != screenGeneration || !confirmationDraft.equals(mintDraftSnapshot(2))) { mintDraftChanged(); return; }\n                            confirmedCollectionDraft = confirmationDraft;\n                            createCollection(name, desc, size, base, ext, icon, external, web); }')
    change('        mintBaseConfirmed = false;','        confirmedCollectionDraft = null;')
    # Collection builds its record synchronously on the main-thread reply: abort if any input changed.
    change('        final int countF = count;\n        node.cmd("getaddress",',
           '        final int countF = count;\n        final String collectionDraft = mintDraftSnapshot(2);\n        final long collectionScreen = screenGeneration;\n        collectionBusy = true;\n        node.cmd("getaddress",')
    a=s.index('    private void createCollection(');b=s.index('    /* ---- single NFT',a);part=s[a:b]
    old='''                JSONObject r = json.optJSONObject("response");
                if (r == null) { toast("getaddress failed"); return; }'''
    new='''                collectionBusy = false;
                if (collectionScreen != screenGeneration || !collectionDraft.equals(mintDraftSnapshot(2))) { mintDraftChanged(); return; }
                JSONObject r = json == null || !Boolean.TRUE.equals(json.opt("status")) ? null : json.optJSONObject("response");
                if (r == null || r.optString("publickey").isEmpty() || r.optString("address").isEmpty()) { toast("getaddress failed"); return; }'''
    assert old in part;part=part.replace(old,new)
    part=part.replace('@Override public void onError(String message) { toast(message); }','@Override public void onError(String message) { collectionBusy = false; toast(message); }')
    s=s[:a]+part+s[b:]
    for method,kind,index,screen in [('mintNft','nft',0,'CREATE_NFT'),('mintToken','tok',1,'CREATE_TOKEN')]:
        a=s.index('    private void '+method+'(');b=s.index('\n    }',a)+6;part=s[a:b]
        old='() -> { hostGatePassed = true; '+method+'(go); }'
        assert old in part
        part=part.replace(old,'() -> { hostGatePassed = true; try { '+method+'(go); } finally { hostGatePassed = false; } }')
        # Materialize the entire unsigned command BEFORE the getaddress callback can observe edits.
        if kind=='nft':
            x=part.index('        NodeApi.Cb fin =');y=part.index('        final org.json.JSONArray attrs',x)
            fin=part[x:y];part=part[:x]+part[y:]
            old='        final String urlF = urlFit;'
            new='''        final String submittedDraft = mintDraftSnapshot(0);
        final String mintCommand = StateNft.nftCreateCommand(n, nftDesc.trim(), urlFit, nftOwner.trim(),
                nftExternal.trim(), nftWeb.trim(), attrs, editions, "");
'''+fin
            assert old in part;part=part.replace(old,new)
            old='''node.cmd(StateNft.nftCreateCommand(n, nftDesc.trim(), urlF, nftOwner.trim(),
                        nftExternal.trim(), nftWeb.trim(), attrs, editions, pk), fin);'''
            assert old in part;part=part.replace(old,'node.cmd(mintCommand + " signtoken:" + pk, fin);')
            part=part.replace('JSONObject r = json.optJSONObject("response");','JSONObject r = json == null || !Boolean.TRUE.equals(json.opt("status")) ? null : json.optJSONObject("response");')
            marker='                nftName = "";'
        else:
            old='''        final String iconFitF = iconFit;
        final String nF = n;
        final long supplyF = supply;
        final int decF = dec;'''
            new='''        final String submittedDraft = mintDraftSnapshot(1);
        final String mintCommand = StateNft.tokenCreateCommand(n, tokDesc.trim(), tokTicker.trim(), iconFit, supply, dec, tokPairs);'''
            assert old in part;part=part.replace(old,new)
            old='StateNft.tokenCreateCommand(nF, tokDesc.trim(), tokTicker.trim(), iconFitF, supplyF, decF, tokPairs)'
            assert old in part;part=part.replace(old,'mintCommand')
            part=part.replace('JSONObject gr = ga.optJSONObject("response");','JSONObject gr = ga == null || !Boolean.TRUE.equals(ga.opt("status")) ? null : ga.optJSONObject("response");')
            marker='                        tokName = "";'
        # Successful old writes must not erase a newer draft or navigate away from another screen.
        indent=marker[:len(marker)-len(marker.lstrip())]
        part=part.replace(marker,indent+'if (!submittedDraft.equals(mintDraftSnapshot('+str(index)+'))) {\n'+indent+'    if (screen == Screen.'+screen+') render'+('CreateNft' if kind=='nft' else 'CreateToken')+'();\n'+indent+'    return;\n'+indent+'}\n'+marker)
        part=part.replace('renderGallery();','if (screen == Screen.'+screen+') renderGallery();')
        part=part.replace('render'+('CreateNft' if kind=='nft' else 'CreateToken')+'();','if (screen == Screen.'+screen+') render'+('CreateNft' if kind=='nft' else 'CreateToken')+'();')
        part=part.replace('if (screen == Screen.'+screen+') if (screen == Screen.'+screen+')','if (screen == Screen.'+screen+')')
        s=s[:a]+part+s[b:]
    p.write_text(s)
