"""Explicit retained display bindings for Atelier and Salon; never translate user content."""
from pathlib import Path
import re

def adapt(root):
    def change(text, old, new):
        assert old in text, ('Creative language source drift', old)
        return text.replace(old, new)
    def msg(key):
        return 'Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()+')'
    for app,package in [('atelier','statenft'),('salon','salon')]:
        folder=root/app/('src/main/java/com/eurobuddha/'+package)
        p=folder/'Design.java';text=p.read_text()
        text=change(text, 'package com.eurobuddha.'+package+';', 'package com.eurobuddha.'+package+';\nimport com.eurobuddha.minimacore.i18n.Language;')
        text=text.replace('Context c, String s', 'Context c, CharSequence s').replace('Context c, String label', 'Context c, CharSequence label')
        text=text.replace('s.toUpperCase()', 'Language.upper(s)').replace('label.toUpperCase()', 'Language.upper(label)')
        text=change(text,'t.setText(s);','Language.setText(t, s);')
        p.write_text(text)
        p=folder/'MainActivity.java';text=p.read_text()
        text=change(text,'package com.eurobuddha.'+package+';', 'package com.eurobuddha.'+package+';\nimport com.eurobuddha.minimacore.i18n.Language;')
        marker='    @Override protected void onCreate(Bundle b) {'
        text=change(text,marker,"""    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
    }

"""+marker)
        if app=='atelier':
            text=change(text,'private void appbar(String title,','private void appbar(CharSequence title,')
            text=change(text,'private LinearLayout sectionHead(String title,','private LinearLayout sectionHead(CharSequence title,')
            text=change(text,'String[] labels = {"Gallery", "Studio", "Manage"};','CharSequence[] labels = {'+', '.join(msg(x) for x in ['nftWallet.gallery','creative.studio','creative.manage'])+'};')
            text=change(text,'labels[i].toUpperCase()','Language.upper(labels[i])')
            for literal,key in [('Collections','creative.collections'),('Single NFTs','creative.singleNfts'),('Tokens','coreWallet.tokens')]:
                text=change(text,'sectionHead("'+literal+'",','sectionHead('+msg(key)+',')
            text=change(text,'for (String[] f : fs) {','int filterIndex = 0;\n        CharSequence[] filterLabels = {'+', '.join(msg(k) for k in ['wallet.all','creative.collections','creative.nfts','coreWallet.tokens','creative.mine'])+'};\n        for (String[] f : fs) {')
            text=change(text,'Design.chip(this, f[1],','Design.chip(this, filterLabels[filterIndex++],')
            for literal,key in [('Empty catalogue','creative.emptyCatalogue'),('No lots yet','creative.noLots'),('Open the studio','creative.openStudio'),('Enter the studio','creative.enterStudio'),('Scan wallet','creative.scanWallet'),('Open Minima Core','history.openCore'),('Collections with sealed on-chain identities. Single NFTs. Custom tokens. Every mint a numbered lot.','creative.welcome'),('Create your first piece in the Studio, or adopt existing StateNFT collections from your wallet.','creative.firstPiece')]:
                pattern=r'(Design\.\w+\(this, )'+re.escape('"'+literal+'"')
                text,count=re.subn(pattern,lambda m:m[1]+msg(key),text)
                assert count,(app,literal)
            text=change(text,'on ? "Connected" : "Not paired yet"','on ? '+msg('wallet.connected')+' : '+msg('mail.nodeNotReady'))
            text=change(text,'"Enable Atelier in Minima Core → Apps, then return here."',msg('mail.nodeRetry'))
            text=change(text,'"The Minima NFT studio · № " + BuildConfig.VERSION_NAME',msg('creative.subtitle')[:-1]+', BuildConfig.VERSION_NAME)')
        else:
            text=change(text,'private View navTab(String label,','private View navTab(CharSequence label,')
            text=change(text,'private View navTabBadge(String label,','private View navTabBadge(CharSequence label,')
            # Only the nav helper uses this expression; unrelated user-owned labels remain literal.
            start=text.index('    private View navTab(');end=text.index('    private void masthead(',start)
            text=text[:start]+text[start:end].replace('label.toUpperCase()','Language.upper(label)')+text[end:]
            for literal,key in [('Feed','creative.feed'),('Discover','creative.discover'),('Salon','creative.salon'),('Messages','creative.messages'),('Settings','wallet.settings')]:
                text=change(text,('navTabBadge' if literal=='Messages' else 'navTab')+'("'+literal+'",',('navTabBadge' if literal=='Messages' else 'navTab')+'('+msg(key)+',')
            text=change(text,'nodeUp ? "connected" : "no node"','nodeUp ? '+msg('wallet.connected')+' : '+msg('mail.nodeNotReady'))
            text=change(text,'nodeChip.setText(', 'Language.setText(nodeChip, ')
        p.write_text(text)
    adapt_notifications(root)
    adapt_viewer(root)
    adapt_editor(root)


def adapt_notifications(root):
    def change(text, old, new):
        assert text.count(old) == 1, ('Creative notification source drift', old)
        return text.replace(old, new)
    def text(key, context='this', args=''):
        return 'Language.text('+context+', com.eurobuddha.minimacore.i18n.R.string.l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()+args+')'
    p=root/'atelier/src/main/java/com/eurobuddha/statenft/MintService.java'
    s=p.read_text()
    s=change(s, 'package com.eurobuddha.statenft;', 'package com.eurobuddha.statenft;\nimport com.eurobuddha.minimacore.i18n.Language;')
    s=change(s, '    private NodeApi node;', '    private boolean foregroundActive;\n    private final Runnable languageChanged = this::refreshLanguageNotification;\n    private NodeApi node;')
    for literal,key in [('Mint engine','creative.mintChannel'),('Live progress while collections seal on-chain','creative.mintChannelHelp'),('Atelier — mint engine','creative.mintTitle'),('Watching the chain…','creative.watchingChain')]:
        s=change(s, '"'+literal+'"', text(key))
    s=change(s, '        LocalStore.logEvent(this, "Engine service engaged', '        foregroundActive = true;\n        Language.addListener(languageChanged);\n        com.eurobuddha.minimacore.i18n.NotificationLabels.update(this);\n        LocalStore.logEvent(this, "Engine service engaged')
    s=change(s, '    @Override public void onDestroy() {', '    @Override public void onDestroy() {\n        foregroundActive = false;\n        Language.removeListener(languageChanged);')
    s=change(s, '    private void updateNotification() {', '    @Override public void onConfigurationChanged(android.content.res.Configuration config) {\n        super.onConfigurationChanged(config);\n        Language.configurationChanged(this);\n        refreshLanguageNotification();\n    }\n\n    private void refreshLanguageNotification() {\n        if (!foregroundActive) return;\n        com.eurobuddha.minimacore.i18n.NotificationLabels.update(this);\n        updateNotification();\n    }\n\n    private void updateNotification() {\n        if (!foregroundActive) return;')
    s=change(s, '        if (nm != null) nm.notify(NOTE_ID, buildNotification());', '        if (nm != null) {\n            try { nm.notify(NOTE_ID, buildNotification()); }\n            catch (SecurityException denied) { /* Notification permission changed. */ }\n        }')
    old = '''                text = "Sealing “" + r.optString("name", "collection") + "” — "
                        + r.optInt("minted", 0) + "/" + r.optInt("size", 0)
                        + " · " + p.toLowerCase();'''
    s=change(s, old, '                text = '+text('creative.sealing',args=', r.optString("name", '+text('nft.collection')+'), r.optInt("minted", 0), r.optInt("size", 0), phaseLabel(p)')+';')
    s=change(s, '"“" + r.optString("name", "collection") + "” needs images to continue"',text('creative.needsImages',args=', r.optString("name", '+text('nft.collection')+')'))
    marker='    private Notification buildNotification() {'
    method='    private String phaseLabel(String phase) {\n        switch (phase) {\n'
    for phase,key in [('CREATE','phaseCreate'),('MOVE','phaseMove'),('SPLIT','phaseSplit'),('STAMP','phaseStamp')]:
        method+='            case "'+phase+'": return '+text('creative.'+key)+';\n'
    method+='            default: return phase;\n        }\n    }\n\n'
    s=change(s,marker,method+marker)
    p.write_text(s)
    p=root/'salon/src/main/java/com/eurobuddha/salon/SalonNotifyReceiver.java'
    s=p.read_text()
    s=change(s,'package com.eurobuddha.salon;','package com.eurobuddha.salon;\nimport com.eurobuddha.minimacore.i18n.Language;')
    s=change(s,'"💰 Tip received", "@" + from.replaceFirst("^@", "") + " tipped you " + amt + " " + tok',text('creative.tipReceived','ctx')+', '+text('creative.tipFrom','ctx',', "@" + from.replaceFirst("^@", ""), amt, tok'))
    s=change(s,'"Salon activity"',text('creative.salonChannel','ctx'))
    # The stored contact preview retains the donor representation. Only the notification is localized.
    s=change(s,'notifyStatic(ctx, "💬 @" + from.replaceFirst("^@", ""), preview);', 'notifyStatic(ctx, "💬 " + (m.has("from") ? "@" + from.replaceFirst("^@", "") : '+text('creative.sender','ctx')+'),\n                        !body.isEmpty() ? body : (!media.isEmpty() ? '+text('creative.media','ctx')+' : ""));')
    p.write_text(s)


def adapt_viewer(root):
    def msg(key, args=''):
        return 'Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()+args+')'
    p=root/'atelier/src/main/java/com/eurobuddha/statenft/ViewerScreen.java'
    s=p.read_text()
    def change(old,new):
        nonlocal s
        assert old in s, ('Atelier viewer source drift',old)
        s=s.replace(old,new)
    change('package com.eurobuddha.statenft;','package com.eurobuddha.statenft;\nimport com.eurobuddha.minimacore.i18n.Language;')
    for old,new in [('Context c, String k, String v','Context c, CharSequence k, CharSequence v'),('Context c, String s','Context c, CharSequence s'),('Activity act, String k, String','Activity act, CharSequence k, String'),('String url, String pendingText','String url, CharSequence pendingText')]:
        change(old,new)
    change('k.toUpperCase()','Language.upper(k)')
    change('s.toUpperCase()','Language.upper(s)')
    fixed={'LOT ⓘ':'creative.lotInfo','checking…':'future.checking','not yet validated':'creative.notValidated',
        'Links':'creative.links','External':'apps.external','Web validate':'creative.webValidate','Image base':'creative.imageBase','Icon':'creative.icon',
        'none set':'creative.noneSet','Details':'nft.details','Mode':'nft.mint.mode','URL-hosted':'creative.urlHosted','Embedded':'creative.embedded',
        'Editions':'creative.editions','File type':'creative.fileType','State port 0':'creative.portZero','State port 1':'creative.portOne',
        'Address':'history.address','Creator key':'creative.creatorKey','Creator address':'creative.creatorAddress','Token id':'terminalTxn.tokenId',
        'Contract':'vestr.contract','Locked edition':'creative.lockedEdition','Locked · web-verified':'creative.lockedVerified','Locked + web validate':'creative.lockedValidation',
        'Transfer':'nftWallet.transfer','Save':'common.save','Share':'logs.share','Copied':'history.copied',"Couldn't open link":'creative.openLinkFailed',
        'Saved to Photos':'creative.savedPhotos','Could not save image':'creative.saveImageFailed','Could not share image':'creative.shareImageFailed'}
    for literal,key in fixed.items():change('"'+literal+'"',msg(key))
    change('lotLabel.setText(String.format(java.util.Locale.US, "LOT %03d / %03d", it.index, Math.max(meta.size, items.size())));',
        'Language.setText(lotLabel, '+msg('creative.lotPosition',', String.format(java.util.Locale.US, "%03d", it.index), String.format(java.util.Locale.US, "%03d", Math.max(meta.size, items.size()))')+');')
    change('String.format(java.util.Locale.US, "Lot %03d · %s", it.index,\n                it.owned ? "in your custody" : (it.coin != null ? "on-chain" : "unseen"))',
        msg('creative.lotCustody',', String.format(java.util.Locale.US, "%03d", it.index), it.owned ? '+msg('creative.inCustody')+' : (it.coin != null ? '+msg('creative.onChain')+' : '+msg('creative.unseen')+')'))
    change('"token " + Util.shorten(meta.tokenid)',msg('creative.tokenHash',', Util.shorten(meta.tokenid)'))
    change('"coin " + Util.shorten(it.coin.optString("coinid"))',msg('creative.coinHash',', Util.shorten(it.coin.optString("coinid"))'))
    change('"embedded image (" + s1.length() + " ch)"',msg('creative.embeddedLength',', s1.length()'))
    change('String label = valid ? "✓ WEB-VERIFIED"\n                : "○ WEB-VALIDATE" + (pendingText == null ? "" : " · " + pendingText);',
        'CharSequence label = valid ? '+msg('creative.verifiedBadge')+'\n                : Language.join('+msg('creative.validationBadge')+', pendingText == null ? "" : Language.join(" · ", pendingText));')
    change('meta.name + " — Lot " + it.index + " · Minima StateNFT " + meta.tokenid',msg('creative.shareLotCaption',', meta.name, it.index, meta.tokenid')+'.toString()')
    change('"Share lot"',msg('creative.shareLot'))
    p.write_text(s)
    # Native FILTR picker and export notices share the viewer's strings. WebView UI follows separately.
    p=root/'atelier/src/main/java/com/eurobuddha/statenft/FiltrActivity.java';s=p.read_text()
    change('package com.eurobuddha.statenft;','package com.eurobuddha.statenft;\nimport com.eurobuddha.minimacore.i18n.Language;')
    change('"Choose image"',msg('creative.chooseImage'))
    change('toastMsg("Saved to Photos")','toastMsg('+msg('creative.savedPhotos')+'.toString())')
    change('toastMsg("Could not save image")','toastMsg('+msg('creative.saveImageFailed')+'.toString())')
    p.write_text(s)


def adapt_editor(root):
    def msg(key):
        return 'Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()+')'
    p=root/'atelier/src/main/java/com/eurobuddha/statenft/ImageEditor.java';s=p.read_text()
    def change(old,new):
        nonlocal s
        assert old in s, ('Atelier editor source drift',old)
        s=s.replace(old,new)
    change('package com.eurobuddha.statenft;','package com.eurobuddha.statenft;\nimport com.eurobuddha.minimacore.i18n.Language;')
    change('Context c, String s','Context c, CharSequence s')
    change('Context c, String label','Context c, CharSequence label')
    change('s.toUpperCase()','Language.upper(s)');change('label.toUpperCase()','Language.upper(label)')
    for literal,key in [('Cancel','common.cancel'),('THE PLATE ROOM','creative.plateRoom'),('SEAL CROP','creative.sealCrop'),('Free','creative.freeCrop'),('Bright','creative.brightness'),('Contrast','creative.contrast'),('Colour','creative.colour')]:
        change('"'+literal+'"',msg(key))
    change('        final TextView[] lookChips', '        CharSequence[] lookLabels = {'+', '.join(msg('creative.'+key) for key in ['lookNone','lookNoir','lookVivid','lookWarm','lookFade'])+'};\n        final TextView[] lookChips')
    change('chipBtn(c, looks[i][0])','chipBtn(c, lookLabels[i])')
    # Fixed-height controls retain their layout and the crop stage when text changes.
    change('        title.setGravity(Gravity.CENTER);','        title.setGravity(Gravity.CENTER);\n        title.setSingleLine(true);\n        title.setEllipsize(android.text.TextUtils.TruncateAt.END);')
    change('        apply.setGravity(Gravity.CENTER);','        apply.setGravity(Gravity.CENTER);\n        apply.setSingleLine(true);')
    change('        t.setGravity(Gravity.CENTER);','        t.setGravity(Gravity.CENTER);\n        t.setSingleLine(true);\n        t.setEllipsize(android.text.TextUtils.TruncateAt.END);\n        Language.description(t, s);')
    change('        l.setLetterSpacing(0.14f);','        l.setLetterSpacing(0.14f);\n        l.setSingleLine(true);\n        l.setEllipsize(android.text.TextUtils.TruncateAt.END);')
    change('        sb.setMax(100);','        Language.description(sb, label);\n        sb.setMax(100);')
    change('        selectChip(a11, true);', '        Language.description(rot, '+msg('creative.rotate90')+');\n        selectChip(a11, true);')
    p.write_text(s)
