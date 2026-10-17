"""Gallery/viewer text bindings; preserve raw names, image state, favourites and transaction inputs."""
from pathlib import Path
import json,re

def adapt(root):
 project=Path(__file__).resolve().parents[1];labels=json.loads((project/'localization/nft-gallery-labels.json').read_text());folder=root/'NFTwallet/src/main/java/com/eurobuddha/nftwallet'
 def res(k):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',labels[k]).replace('.','_').lower()
 def msg(k,*args):return 'Language.msg('+res(k)+('' if not args else ', '+', '.join(args))+')'
 def upper(k,*args):return 'Language.upper('+msg(k,*args)+')'
 def change(s,old,new,n=1):
  assert s.count(old)==n,('NFT gallery source drift',old,s.count(old),n)
  return s.replace(old,new)
 def load(n):
  p=folder/(n+'.java');s=p.read_text()
  if 'import com.eurobuddha.minimacore.i18n.Language;' not in s:s=s.replace('package com.eurobuddha.nftwallet;','package com.eurobuddha.nftwallet;\nimport com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;')
  return p,s
 def label(s,old,key,caps=False):
  literal=json.dumps(old,ensure_ascii=False);n=s.count(literal);assert n>0,("NFT gallery missing label",old)
  return change(s,literal,upper(key) if caps else msg(key),n)
 def save(p,s):
  s=re.sub(r'\b([A-Za-z_][A-Za-z_0-9]*(?:\.[A-Za-z_][A-Za-z_0-9]*)*)\.setText\(',r'Language.setText(\1, ',s).replace('Language.setText(Language, ','Language.setText(')
  p.write_text(s)
 p,s=load('NftText')
 s=change(s,'\n}', '''
    static CharSequence collection(StateNft.Meta meta, org.json.JSONObject record) {
        Object token = record != null && record.opt("token") instanceof org.json.JSONObject
                ? record.opt("token") : record;
        return meta != null && Util.tokenNameProvided(token, meta.tokenid)
                ? meta.name : Language.msg(l10n_nft_collection);
    }
    static CharSequence galleryName(GalleryView.GItem item) {
        return item.displayName != null && item.displayName.length() > 0 ? item.displayName
                : item.name == null || item.name.isEmpty() ? "NFT" : item.name;
    }
}''');p.write_text(s)
 # UTXO's existing renderer sanitizes arbitrary placeholder seeds before hex parsing.
 p=folder/'Identicon.java';s=p.read_text()
 donor=(root/'utxo/src/main/java/com/eurobuddha/utxo/Identicon.java').read_text()
 start=donor.index('        // Keep hex digits only:');end=donor.index('        StringBuilder sb',start)
 start_old=s.index('        String hex =');end_old=s.index('        StringBuilder sb',start_old)
 s=s[:start_old]+donor[start:end]+s[end_old:];p.write_text(s)
 p,s=load('GalleryView')
 # Apply the existing collection boundary before either token branch, as wallet's Group does.
 s=change(s,'                // Drilled in: every other collection is out of scope.\n                if (openCollection != null && !openCollection.equals(b.tokenid)) continue;\n','')
 s=change(s,'            if (!isNftCandidate(b)) continue;', '            if (!isNftCandidate(b)) continue;\n            // A drilled-in collection must exclude unrelated regular NFTs too.\n            if (openCollection != null && !openCollection.equals(b.tokenid)) continue;')
 s=change(s,'private String openCollectionName = "";', 'private String openCollectionName = "";\n    private CharSequence openCollectionDisplayName = "";')
 s=change(s,'String searchBlob;', 'CharSequence displayName, displaySub;\n        String searchBlob;')
 s=change(s,'it.name = meta.name + (idx != null ? " #" + idx : " (unstamped)");',
 '''it.name = meta.name + (idx != null ? " #" + idx : " (unstamped)");
                    it.displayName = Language.join(NftText.collection(meta, record), idx != null ? " #" + idx : Language.join(" (", '''+msg('unstamped')+''', ")"));
                    it.displaySub = Language.join('''+msg('stateLabel')+''', " · ", idx != null ? '''+msg('edition','idx','meta.size')+' : '+msg('unstamped')+');')
 s=change(s,'it.name = b.name;', 'it.name = b.name;\n                it.displayName = NftText.token(b);')
 s=change(s,'it.name = meta.name;', 'it.name = meta.name;\n        it.displayName = NftText.collection(meta, tokenRecords.get(b.tokenid));\n        it.displaySub = Language.join('+msg('collection')+', " · ", '+msg('held','mine.size()','meta.size')+');')
 s=change(s,'openCollectionName = card.name == null ? "Collection" : card.name;', 'openCollectionName = card.name == null ? "Collection" : card.name;\n        openCollectionDisplayName = NftText.galleryName(card);')
 s=change(s,'        openCollectionName = "";', '        openCollectionName = "";\n        openCollectionDisplayName = "";')
 s=change(s,'Sheet.create(act, name)', 'Sheet.create(act, openCollectionDisplayName)')
 s=change(s,'Sheet.create(act, it.name)', 'Sheet.create(act, NftText.galleryName(it))')
 s=change(s,'"Actions that apply to every item of this collection you still hold. "\n                        + "Each item is its own transaction — the chain requires that."',msg('collectionActions'))
 for old,key in [('COLLECTED','collected'),('FAVOURITES','favourites'),('Search by name, creator or tokenid','search'),('No favourites yet — open an NFT and tap ♥.','noFavourites'),('You no longer hold any items of this collection.','noCollectionItems'),('No NFTs yet — mint one from the Mint tab.','noNfts'),('Send the whole collection','sendCollection'),('Bury the whole collection','buryCollection'),('Close','close'),('←  All NFTs','allNfts'),('tap image to open the fullscreen viewer','fullscreenHint'),('Token ID','tokenId'),('Coin ID','coinId'),('Creator','creator'),('Identity (state 0)','identity'),('Held at address','heldAddress'),('Amount','amount'),('Created block','createdBlock'),('External URL','externalUrl'),('Web validation','webValidation'),('Transfer','transfer'),('✝  Bury this item (irreversible)','buryItem'),('→  Send the WHOLE collection (every item you hold)','sendCollection'),('✝  Bury the WHOLE collection (every item you hold)','buryCollection'),('Send','send'),('↗ open','openLink'),('blocked','blockedLink')]:s=label(s,old,key)
 s=change(s,'"COLLECTION ⌄"','Language.join('+upper('collection')+', " ⌄")')
 s=change(s,'tabBtn(String label','tabBtn(CharSequence label')
 s=change(s,'search.setHint(', 'Language.hint(search, ')
 s=change(s,'search.setHintTextColor(Design.dim2())','search.setHintTextColor(Design.dim())')
 s=change(s,'hh.title.setText(heroCard.name == null ? "Collection" : heroCard.name);','hh.title.setText(NftText.galleryName(heroCard));')
 s=change(s,'heroCard.owned + " OF "\n                        + (heroCard.meta == null ? "?" : heroCard.meta.size) + " HELD"',upper('held','heroCard.owned','heroCard.meta == null ? "?" : String.valueOf(heroCard.meta.size)'))
 s=change(s,'it.name == null ? "NFT" : it.name','NftText.galleryName(it)')
 s=change(s,'it.sub == null ? "" : it.sub.toUpperCase()','Language.upper(it.displaySub != null ? it.displaySub : it.sub == null ? "" : it.sub)')
 s=change(s,'"▣ " + it.owned + (it.owned == 1 ? " ITEM" : " ITEMS")','Language.join("▣ ", Language.upper(Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_nft_items, it.owned)))')
 s=change(s,'h.heart.setText(fav ? "♥" : "♡");','h.heart.setText(fav ? "♥" : "♡");\n            Language.description(h.heart, fav ? '+msg('unfavourite')+' : '+msg('favourite')+');')
 s=change(s,'StringBuilder chips = new StringBuilder();','Language.TextBuilder chips = new Language.TextBuilder();')
 s=change(s,'it.idx > 0 ? "#" + it.idx + " of " + (it.meta == null ? "?" : it.meta.size) : "unstamped"','it.idx > 0 ? '+msg('edition','it.idx','it.meta == null ? "?" : String.valueOf(it.meta.size)')+' : '+msg('unstamped'))
 s=change(s,'chips.append("  ·  LOCKED EDITION");','chips.append("  ·  ").append('+upper('lockedEdition')+');')
 s=change(s,'"1".equals(it.balance.total) ? "1 OF 1" : it.balance.total + " EDITIONS"','"1".equals(it.balance.total) ? '+upper('oneOfOne')+' : '+upper('editions','it.balance.total'))
 s=change(s,'chips.append("  ·  ✓ WEB VALIDATED");','chips.append("  ·  ").append('+upper('webValidated')+');')
 s=change(s,'chips.toString()', 'chips.build()')
 s=change(s,'it.idx + " · sealed"',msg('sealed','it.idx'))
 s=change(s,'String shown = "1".equals(e[0])', 'CharSequence shown = "1".equals(e[0])')
 s=change(s,'"[embedded artwork, " + val.length() + " chars]"',msg('embeddedArtwork','val.length()'))
 s=change(s,'"State " + e[0]',msg('state','e[0]'))
 s=change(s,'fav ? "♥ Unfavourite" : "♡ Favourite"','fav ? Language.join("♥ ", '+msg('unfavourite')+') : Language.join("♡ ", '+msg('favourite')+')')
 s=change(s,'kvCopy(LinearLayout box, String label, String value)', 'kvCopy(LinearLayout box, CharSequence label, CharSequence value)')
 s=change(s,'kvCopy(LinearLayout box, final String label, final String value, String shown)', 'kvCopy(LinearLayout box, final CharSequence label, final CharSequence value, CharSequence shown)')
 s=change(s,'linkRow(LinearLayout box, String label,', 'linkRow(LinearLayout box, CharSequence label,')
 s=s.replace('setTextColor(Design.accent())','setTextColor(Design.accentText())').replace('web ? Design.accent()','web ? Design.accentText()')
 # Expand small controls to existing 48dp touch-target convention; text may wrap naturally.
 for control,after in [('t','\n        t.setGravity(Gravity.CENTER);'),('back','back.setTextColor(0xFFFFFFFF);'),('actions','actions.setTextColor(0xFFFFFFFF);'),('heart','heart.setTextSize(14f);')]:
  s=change(s,after,after+'\n            '+control+'.setMinHeight(dp(48));\n            '+control+'.setMinWidth(dp(48));')
 save(p,s)
 p,s=load('NftViewer')
 s=change(s,'String url, String title)', 'String url, CharSequence title)')
 s=change(s,'it.name = title == null ? "" : title;', 'it.name = title == null ? "" : title.toString();\n        it.displayName = title;')
 s=change(s,'final Dialog d = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);', 'final Dialog d = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);\n        act.trackLanguageDialog(d);')
 s=change(s,'back.setContentDescription("Close viewer");','Language.description(back, '+msg('closeViewer')+');')
 s=change(s,'heart.setContentDescription("Favourite");','Language.description(heart, '+msg('favourite')+');')
 for old,key in [('DETAILS','details'),('OPEN COLLECTION','openCollection'),('UNSTAMPED','unstamped'),('TRANSFER','transfer'),('SEND','send'),('LOCKED EDITION','lockedEdition'),('✓ VALIDATED','validated')]:s=label(s,old,key,True)
 s=change(s,'it.name == null || it.name.isEmpty() ? "NFT" : it.name','NftText.galleryName(it)')
 s=change(s,'String info = infoLine(', 'CharSequence info = infoLine(')
 s=change(s,'info.isEmpty()', 'info.length() == 0')
 s=change(s,'heartF.setText(fav ? "♥" : "♡");', 'heartF.setText(fav ? "♥" : "♡");\n                Language.description(heartF, fav ? '+msg('unfavourite')+' : '+msg('favourite')+');')
 s=change(s,'private static String infoLine(', 'private static CharSequence infoLine(')
 s=change(s,'java.util.List<String> parts', 'java.util.List<CharSequence> parts')
 s=change(s,'"COLLECTION · " + it.owned + " HELD"', upper('collectionHeld','it.owned'))
 s=change(s,'"#" + it.idx + " OF " + it.meta.size',upper('edition','it.idx','it.meta.size'))
 s=change(s,'"1".equals(it.balance.total) ? "1 OF 1" : it.balance.total + " EDITIONS"','"1".equals(it.balance.total) ? '+upper('oneOfOne')+' : '+upper('editions','it.balance.total'))
 s=change(s,'return android.text.TextUtils.join("  ·  ", parts);','Language.TextBuilder text = new Language.TextBuilder();\n        for (int i = 0; i < parts.size(); i++) text.append(i == 0 ? "" : "  ·  ").append(parts.get(i));\n        return text.build();')
 for control,after in [('back','back.setTextSize(24f);'),('heart','heart.setTextSize(20f);'),('details','details.setTextSize(12f);'),('action','action.setTextSize(12f);')]:
  s=change(s,after,after+'\n            '+control+'.setMinHeight(dp(act, 48));\n            '+control+'.setMinWidth(dp(act, 48));')
 # Reuse SystemBars.pad's inset maths on the overlays; padding the artwork would reset zoom.
 s=change(s,'        final LinearLayout bottom = bottomBar;', '''        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(frame, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            top.setPadding(dp(act, 8) + bars.left, dp(act, 8) + bars.top,
                    dp(act, 8) + bars.right, dp(act, 8));
            bottomGroup.setPadding(bars.left, 0, bars.right, bars.bottom);
            return insets;
        });
        androidx.core.view.ViewCompat.requestApplyInsets(frame);
        final LinearLayout bottom = bottomBar;''')
 # Both labels share available width, allowing the longer translations to wrap without clipping Details.
 s=change(s,'bottomBar.addView(action);','bottomBar.addView(action, new LinearLayout.LayoutParams(\n                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f));')
 save(p,s)
 # Retain a live, display-only caption when an embedded state image is opened from its sheet.
 p=folder/'CoinDetailDialog.java';s=p.read_text();s=change(s,'NftViewer.openSingle(act, uri, portLabel.toString())', 'NftViewer.openSingle(act, uri, portLabel)');p.write_text(s)
 # Balance icons have no financial action in the viewer; keep the fallback name live there too.
 p=folder/'BalancesView.java';s=p.read_text();s=change(s,'NftViewer.openSingle(act, b.meta.iconUrl, b.name)', 'NftViewer.openSingle(act, b.meta.iconUrl, NftText.token(b))');p.write_text(s)

if __name__=='__main__':
 import sys
 adapt(Path(sys.argv[1]))
