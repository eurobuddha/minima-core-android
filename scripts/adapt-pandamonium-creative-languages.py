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
