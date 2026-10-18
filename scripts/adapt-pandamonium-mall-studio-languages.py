"""Studio display bindings. Editable catalog strings and .shop schema stay literal."""
from pathlib import Path
import json,re

def adapt(root):
 project=Path(__file__).resolve().parents[1];labels=json.loads((project/'localization/mall-studio-labels.json').read_text());catalog=json.loads((project/'localization/catalog.json').read_text())
 folder=root/'merchstudio/src/main/java/com/eurobuddha/merchstudio';p=folder/'MainActivity.java';s=p.read_text();dynamic=set()
 def resource(key):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',labels[key]).replace('.','_').lower()
 def msg(key,*args):
  dynamic.add(key)
  return 'Language.msg('+resource(key)+('' if not args else ', '+', '.join(args))+')'
 def change(old,new):
  nonlocal s
  assert old in s,('Studio display drift',old);s=s.replace(old,new)
 change('package com.eurobuddha.merchstudio;','package com.eurobuddha.merchstudio;\nimport com.eurobuddha.minimacore.i18n.Language;\nimport com.eurobuddha.minimacore.i18n.LanguageDialog;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;')
 shop=(root/'merchshop/src/main/java/com/eurobuddha/merchshop/MainActivity.java').read_text();begin=shop.index('    private final java.util.Set<AlertDialog> languageDialogs');end=shop.index('    private static final String CH',begin)
 change('    private String vendorPublicId',shop[begin:end].replace('        ensureChannel();\n','')+'    private String vendorPublicId')
 change('super.onDestroy();','for (AlertDialog dialog : new java.util.ArrayList<>(languageDialogs)) dialog.dismiss(); languageDialogs.clear(); super.onDestroy();')
 change('new AlertDialog.Builder(this)','languageDialog()')
 changes=[
 ('"Identity error: " + e.getMessage()',msg('identityError','StudioMessages.error(e)')),
 ('"Couldn\'t read wallet: " + m',msg('walletError','StudioMessages.message(m)')),
 ('"Export failed: " + e.getMessage()',msg('exportError','StudioMessages.error(e)')),
 ('"Products" + (typedProducts.isEmpty() ? "" : " · " + typedProducts.size())','Language.join('+msg('products')+', typedProducts.isEmpty() ? "" : " · " + typedProducts.size())'),
 ('"Continue · " + total + " in shop  →"',msg('continueShop','total')),
 ('(p.price.isEmpty() ? "no price" : Util.tidyAmount(p.price) + " " + currencyLabel()) + " · ×" + p.maxUnits','Language.join(p.price.isEmpty() ? '+msg('noPrice')+' : Util.tidyAmount(p.price) + " " + currencyLabel(), " · ×", p.maxUnits)'),
 ('"A shop holds at most " + MAX_PRODUCTS + " listings."',msg('cap','MAX_PRODUCTS')),
 ('("Your wallet · " + walletCount + (walletCount == 1 ? " NFT" : " NFTs")).toUpperCase()','Language.upper('+msg('walletCount','walletCount')+')'),
 ('"◈ " + col.meta.size + " pieces · you hold " + col.ownedCoins.size()',msg('collectionHeld','col.meta.size','col.ownedCoins.size()')),
 ('listed + " listed"',msg('listed','listed')),
 ('"×" + p.maxUnits + " available"',msg('available','p.maxUnits')),
 ('"Edition #" + l.stateIdx + " of " + l.snMeta.size',msg('edition','l.stateIdx','l.snMeta.size')),
 ('"  of " + held + " you hold"',msg('held','held.toString()')),
 ('pieces.size() + " of " + col.meta.size + " pieces in your wallet"',msg('piecesHeld','pieces.size()','col.meta.size')),
 ('"complete collection listed · " + Util.tidyAmount(bundle.price) + " " + currencyLabel()',msg('bundlePrice','Util.tidyAmount(bundle.price)','currencyLabel()')),
 ('listed + " pieces listed · " + totalListingCount() + "/" + MAX_PRODUCTS + " shop slots"',msg('slots','listed','totalListingCount()','MAX_PRODUCTS')),
 ('col.unstampedCount + " unstamped — not listable until stamped."',msg('unstamped','col.unstampedCount')),
 ('"Done · " + (bundleListed ? "complete collection listed" : '+msg('listed','listed')+') + "  →"',msg('doneSummary','bundleListed ? '+msg('bundleListed')+' : '+msg('listed','listed'))),
 ('"Price per piece (" + currencyLabel() + ")"',msg('pricePerPiece','currencyLabel()')),
 ('"Listed " + added + " — shop cap is " + MAX_PRODUCTS',msg('listedCapped','added','MAX_PRODUCTS')),
 ('"Listed " + added + " pieces at " + p + " " + currencyLabel()',msg('listedPrice','added','p','currencyLabel()')),
 ('n + " piece listing" + (n == 1 ? "" : "s") + " removed."',msg('piecesRemoved','n')),
 ('"Price for all " + col.ownedCoins.size() + " pieces (" + currencyLabel() + ")"',msg('bundlePriceHint','col.ownedCoins.size()','currencyLabel()')),
 ('"One listing for every piece you hold (" + col.ownedCoins.size() + "). The buyer receives them one by one — each piece is its own on-chain transfer."',msg('bundleHelp','col.ownedCoins.size()')),
 ('"Shop is full (" + MAX_PRODUCTS + ")."',msg('full','MAX_PRODUCTS')),
 ('(typedProducts.isEmpty() ? "NFT SHOP · " : "") + "pays in " + currencyLabel()','typedProducts.isEmpty() ? '+msg('nftPays','currencyLabel()')+' : '+msg('paysCurrency','currencyLabel()')),
 ('"Set a price for " + p.name.trim() + "."',msg('priceFor','p.name.trim()')),
 ('"Set a price for " + displayName(l) + "."',msg('priceFor','displayName(l)')),
 ('displayName(l) + " is no longer sellable — refresh the gallery."',msg('notSellable','displayName(l)')),
 ('displayName(l) + " has no pieces left — refresh the gallery."',msg('noPiecesLeft','displayName(l)')),
 ('"◈ all " + (col == null ? "" : col.ownedCoins.size() + " ") + "pieces"','col == null ? '+msg('allPieces')+' : '+msg('allPiecesCount','col.ownedCoins.size()')),
 ('"◈ ×" + supply + " eds"',msg('editions','supply.toString()')),
 ('"Enable this app in Minima Core → Apps to read your wallet."',msg('nodeUnavailable')),
 ('"The embedded node is unavailable. Open Minima Core from the menu and check its status."',msg('nodeUnavailable')),
 ('"Connecting to your node…"',msg('connecting')),
 ('"Connect your node first."',msg('connectFirst')),
 ]
 for old,new in changes:change(old,new)
 # Same label appears in multiple expressions; all are UI only.
 change('"complete collection listed"',msg('bundleListed'))
 begin=s.index('        String parts = ');end=s.index('        info.setText(parts);',begin)
 s=s[:begin]+'        CharSequence parts = Language.join(nTyped > 0 ? '+msg('productCount','nTyped')+' : "", nTyped > 0 && nNft > 0 ? " + " : "", nNft > 0 ? '+msg('nftCount','nNft')+' : "");\n'+s[end:]
 for key in ['back','minus','plus','refresh','website']:dynamic.add(key)
 for key in labels:
  if key in dynamic:continue
  literal=json.dumps(catalog[labels[key]]['en'],ensure_ascii=False)
  change(literal,msg(key))
 for old,new in [('editionChip(NftListing l)','editionChip(NftListing l)'),('private String editionChip','private CharSequence editionChip'),('header(String title','header(CharSequence title'),('sectionLabel(String s)','sectionLabel(CharSequence s)'),('hint(String s)','hint(CharSequence s)'),('String hintText,','CharSequence hintText,'),('seg(String text','seg(CharSequence text'),('button(String text','button(CharSequence text'),('toast(String s)','toast(CharSequence s)'),('s.toUpperCase()','Language.upper(s)')]:change(old,new)
 s=re.sub(r'\b([A-Za-z_][A-Za-z_0-9]*)\.setText\(',r'Language.setText(\1, ',s);s=re.sub(r'\b([A-Za-z_][A-Za-z_0-9]*)\.setHint\(',r'Language.hint(\1, ',s)
 change('        h.addView(t); return h;', '        h.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); return h;')
 change('            bk.setPadding(0, 0, dp(14), 0);','            bk.setMinWidth(dp(48)); bk.setMinHeight(dp(48)); bk.setGravity(Gravity.CENTER);\n            Language.description(bk, '+msg('back')+');')
 change('        refresh.setPadding(dp(12), dp(6), dp(12), dp(6));','        refresh.setPadding(dp(12), dp(6), dp(12), dp(6)); refresh.setMinWidth(dp(48)); refresh.setMinHeight(dp(48));\n        Language.description(refresh, '+msg('refresh')+');')
 change('            link.setPadding(dp(8), 0, 0, 0);','            link.setPadding(dp(8), 0, 0, 0); link.setMinWidth(dp(48)); link.setMinHeight(dp(48)); link.setGravity(Gravity.CENTER);\n            Language.description(link, '+msg('website')+');')
 change('        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(38));','        Language.description(b, "+".equals(s) ? '+msg('plus')+' : '+msg('minus')+');\n        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48));')
 change('        idRow.addView(stats);','        idRow.addView(stats, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));')
 p.write_text(s)
 p=folder/'Design.java';s=p.read_text();change('String text,','CharSequence text,');change('0xFF6A6A78','0xFF9A9AA8');change('        t.setText(text);','        com.eurobuddha.minimacore.i18n.Language.setText(t, text);');p.write_text(s)
 source=(root/'merchshop/src/main/java/com/eurobuddha/merchshop/ShopMessages.java').read_text().replace('com.eurobuddha.merchshop','com.eurobuddha.merchstudio').replace('ShopMessages','StudioMessages').replace('Shop local','Studio local').replace('mall-shop-languages.py','mall-studio-languages.py')
 # Export failures use the existing bounded diagnostic lookup at the UI boundary.
 anchor='            case "The embedded node did not respond. Check its status in Minima Core.":'
 assert source.count(anchor)==1
 cases=''
 for raw,key in [('Invalid shop filename','mallStudio.errorShopFilename'),('Cannot create export folder','mallStudio.errorExportFolder')]:
  assert catalog[key]['en']==raw
  name='l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()
  cases+='            case '+json.dumps(raw)+': return Language.msg('+name+');\n'
 source=source.replace(anchor,cases+anchor)
 (folder/'StudioMessages.java').write_text(source)
 for res in ['values','values-night']:
  p=root/f'merchstudio/src/main/res/{res}/themes.xml';s=p.read_text();donor=(root/f'merchinbox/src/main/res/{res}/themes.xml').read_text()
  # Preserve this module's resource name; reuse Inbox's reviewed fixed-dark palette.
  s=re.sub(r'<style name="([^"]+)"[^>]*>.*?</style>',lambda m:re.search(r'<style .*?</style>',donor,re.S).group(0).replace(re.search(r'<style name="([^"]+)"',donor).group(1),m.group(1)),s,flags=re.S);p.write_text(s)
