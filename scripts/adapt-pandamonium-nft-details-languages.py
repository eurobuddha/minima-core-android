"""NFT balance and coin-detail labels; preserve chain values and reuse UTXO name provenance."""
from pathlib import Path
import json,re,xml.etree.ElementTree as E

def adapt(root):
 project=Path(__file__).resolve().parents[1];labels=json.loads((project/'localization/nft-details-labels.json').read_text());catalog=json.loads((project/'localization/catalog.json').read_text());folder=root/'NFTwallet/src/main/java/com/eurobuddha/nftwallet'
 def res(k):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',labels[k]).replace('.','_').lower()
 def msg(k,*args):return 'Language.msg('+res(k)+('' if not args else ', '+', '.join(args))+')'
 def count(k,arg):return 'Language.count(com.eurobuddha.minimacore.i18n.R.plurals.'+res(k)+', '+arg+')'
 def change(s,old,new):
  assert old in s,('NFT detail source drift',old)
  return s.replace(old,new)
 def load(n):
  p=folder/(n+'.java');s=p.read_text()
  if 'import com.eurobuddha.minimacore.i18n.Language;' not in s:s=s.replace('package com.eurobuddha.nftwallet;','package com.eurobuddha.nftwallet;\nimport com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;')
  return p,s
 def fixed(s,keys):
  for k in keys:s=change(s,json.dumps(catalog[labels[k]]['en'],ensure_ascii=False),msg(k))
  return s
 def save(p,s):
  s=re.sub(r'\b([A-Za-z_][A-Za-z_0-9]*(?:\.[A-Za-z_][A-Za-z_0-9]*)*)\.setText\(',r'Language.setText(\1, ',s).replace('Language.setText(Language, ','Language.setText(')
  p.write_text(s)
 # Copy the tested UTXO display-only name helper, after its own pinned-source adaptation.
 utxo=root/'utxo/src/main/java/com/eurobuddha/utxo'
 helper=(utxo/'Util.java').read_text();start=helper.index('    /** Same provenance rule as core TokenMeta:');end=helper.index('    /** Trim trailing zeros',start)
 p,s=load('Util');s=change(s,'    /** Trim trailing zeros',helper[start:end]+'    /** Trim trailing zeros')
 s=fixed(s,['unsafeLink','openQuestion','noLinkApp','cancel','open'])
 s=change(s,'url + "\\n\\nThis address comes from the token\'s metadata, not from Minima."',msg('metadataLink','url'))
 save(p,s)
 for name in ['Coin','TokenMeta']:
  p=folder/(name+'.java');s=p.read_text()
  if name=='Coin':
   s=change(s,'    public String tokenName;','    public String tokenName;\n    public boolean tokenNameProvided = true;')
   s=change(s,'        return x;','        x.tokenNameProvided = Util.tokenNameProvided(c.opt("token"), x.tokenid);\n        return x;')
  else:
   s=change(s,'    public String name = "Token";','    public String name = "Token";\n    public boolean nameProvided;')
   s=change(s,'        TokenMeta m = new TokenMeta();','        TokenMeta m = new TokenMeta();\n        m.nameProvided = Util.tokenNameProvided(token, tokenid);')
  p.write_text(s)
 (folder/'NftText.java').write_text((utxo/'UtxoText.java').read_text().replace('com.eurobuddha.utxo','com.eurobuddha.nftwallet').replace('UtxoText','NftText'))
 p,s=load('BalancesView')
 s=change(s,'"Nothing matches “" + q + "”."',msg('nothingMatches','q'))
 s=change(s,'hiddenCount + (hiddenCount == 1 ? " hidden token · show" : " hidden tokens · show")',msg('hiddenCount','hiddenCount'))
 s=change(s,'mono(symbol.toUpperCase(),','mono(Language.upper(notEmpty(b.meta.ticker) ? b.meta.ticker : NftText.token(b)),')
 s=change(s,'String secondary = (notEmpty(b.name) && !b.name.equalsIgnoreCase(symbol)) ? b.name : null;','CharSequence secondary = (notEmpty(b.name) && !b.name.equalsIgnoreCase(symbol)) ? NftText.token(b) : null;')
 s=change(s,'secondary + "  ·  NFT"','Language.join(secondary, "  ·  NFT")')
 s=change(s,'(b.coins + (b.coins == 1 ? " coin" : " coins")).toUpperCase()','Language.upper('+count('coinCount','b.coins')+')')
 s=change(s,'b.name + (b.isNft() ? "  ·  NFT" : "")','Language.join(NftText.token(b), b.isNft() ? "  ·  NFT" : "")')
 s=change(s,'"Token ID  ·  tap to copy"',msg('tokenCopy'))
 s=change(s,'"“" + b.name + "” disappears from your balances list. "\n                                    + "You can unhide it from Settings or the hidden-tokens row."',msg('hideHelp','NftText.token(b)'))
 s=change(s,'"Coins (" + live.size() + ")  ·  tap for full detail"',msg('coinDetails','live.size()'))
 s=change(s,'"Showing " + n + " of " + live.size() + " coins."',msg('showingCoins','n','live.size()'))
 s=change(s,'String sub = Util.shorten(c.coinid) + (age >= 0 ? "  ·  " + age + " blk" : "");','CharSequence sub = Language.join(Util.shorten(c.coinid), age >= 0 ? Language.join("  ·  ", '+msg('ageBlocks','age')+') : "");')
 old='''        StringBuilder chips = new StringBuilder();
        if (!c.state.isEmpty()) chips.append("STATE ").append(c.state.size());
        if (!c.sendable) chips.append(chips.length() > 0 ? " · " : "").append("LOCKED");
        if (chips.length() > 0) {
            TextView chip = mono(chips.toString(),'''
 new='''        CharSequence chips = Language.join(c.state.isEmpty() ? "" : %s,
                !c.state.isEmpty() && !c.sendable ? " · " : "", c.sendable ? "" : %s);
        if (!c.state.isEmpty() || !c.sendable) {
            TextView chip = mono(chips,'''%(msg('stateCount','c.state.size()'),msg('lockedCaps'))
 s=change(s,old,new)
 s=change(s,'"CONFIRMED"','Language.upper('+msg('confirmed')+')');s=change(s,'"UNCONFIRMED"','Language.upper('+msg('unconfirmed')+')')
 s=fixed(s,['noBalances','nothingShown','hideHidden','sendableCaps','lockedCaps','fullscreenHint','sendable','confirmed','unconfirmed','lockedContracts','coinsLabel','supply','ticker','decimalsLabel','owner','tokenId','description','tokenUrl','website','webValidation','unhideToken','hideToken','hideQuestion','cancel','hideAction','close','receive','send','tokenScript','standardToken','stateContract','customContract','viewContract','hideContract','sendCollection','buryCollection','tooManyCoins','openLink','blockedLink'])
 for a,b in [('mono(String s','mono(CharSequence s'),('splitRow(String label','splitRow(CharSequence label'),('addKv(LinearLayout box, final String label','addKv(LinearLayout box, final CharSequence label'),('sectionLabel(String s)','sectionLabel(CharSequence s)'),('linkRow(String label','linkRow(CharSequence label')]:s=change(s,a,b)
 # Accent paint for small labels, preserving the existing accent fill and borders.
 s=change(s,'Format.amount(b.sendable), Design.accent())','Format.amount(b.sendable), Design.accentText())')
 s=s.replace('? Design.accent() :','? Design.accentText() :').replace('setTextColor(Design.accent())','setTextColor(Design.accentText())')
 s=change(s,'        search.setTextColor(Design.text());','        search.setTextColor(Design.text());\n        search.setHintTextColor(Design.dim());')
 save(p,s)
 p,s=load('CoinDetailDialog')
 s=change(s,'Util.tidyAmount(c.amount) + "  " + c.tokenName','Language.join(Util.tidyAmount(c.amount), "  ", NftText.token(c))')
 s=change(s,'StringBuilder st = new StringBuilder(c.sendable ? "SENDABLE" : "LOCKED / TRACK-ONLY");','Language.TextBuilder st = new Language.TextBuilder().append(c.sendable ? '+msg('sendableCaps')+' : '+msg('trackedOnly')+');')
 s=change(s,'st.append("  ·  STORES STATE")','st.append("  ·  ").append('+msg('storesState')+')')
 s=change(s,'st.append("  ·  SPENT")','st.append("  ·  ").append('+msg('spent')+')')
 s=change(s,'st.toString()','st.build()')
 s=change(s,'String detail = c.created + (age >= 0 ? "   (" + age + " blocks ago)" : "");','CharSequence detail = Language.join(c.created, age >= 0 ? Language.join("   (", '+msg('ageBlocks','age')+', ")") : "");')
 s=change(s,'"STATE VARIABLES (" + c.state.size() + ")"',msg('stateVariables','c.state.size()'))
 s=change(s,'"Port " + port + (embedded ? "  ·  embedded image" : "")','Language.join('+msg('port','port')+', embedded ? Language.join("  ·  ", '+msg('embeddedImage')+') : "")')
 s=change(s,'final String portLabel = "Port " + port;','final CharSequence portLabel = '+msg('port','port')+';')
 s=change(s,'NftViewer.openSingle(act, uri, portLabel)','NftViewer.openSingle(act, uri, portLabel.toString())')
 s=fixed(s,['coinId','addressLabel','mxAddress','tokenId','rawAmount','createdBlock','mmr','close','transfer','bury'])
 s=change(s,'actionBtn(MainActivity act, String label','actionBtn(MainActivity act, CharSequence label')
 s=change(s,'LinearLayout box, String label','LinearLayout box, CharSequence label')
 s=change(s,'String shown','CharSequence shown');s=change(s,'String full','CharSequence full')
 s=change(s,'copy(Context ctx, CharSequence label, String value)','copy(Context ctx, CharSequence label, CharSequence value)')
 s=change(s,'value.isEmpty()','value.length() == 0')
 s=change(s,'ClipData.newPlainText(label, value)','ClipData.newPlainText(label.toString(), value.toString())')
 save(p,s)
 p=folder/'Design.java';s=p.read_text();s=change(s,'return mode == Mode.CLEAN_LIGHT ? 0xFFFFFFFF : 0xFF000000;', 'return 0xFF000000;');p.write_text(s)
 # Hidden-token rows in Settings share the same display-name provenance as balance cards.
 p,s=load('SettingsDialog');s=change(s,'String name = tid;','CharSequence name = tid;');s=change(s,'name = b.name + "  ·  " + Util.shorten(tid);','name = Language.join(NftText.token(b), "  ·  ", Util.shorten(tid));');save(p,s)
 a='{http://schemas.android.com/apk/res/android}';E.register_namespace('android',a[1:-1]);p=root/'NFTwallet/src/main/res/layout/pm_nftwallet_view_balances.xml';tree=E.parse(p)
 for v in tree.iter():
  if v.get(a+'hint')==catalog[labels['balanceSearch']]['en']:
   assert v.get(a+'tag') is None
   v.set(a+'hint','@string/'+res('balanceSearch'));v.set(a+'tag','l10n:hint='+res('balanceSearch'))
 E.indent(tree,space='    ');tree.write(p,encoding='unicode',xml_declaration=True)
