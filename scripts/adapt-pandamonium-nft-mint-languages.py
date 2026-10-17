"""NFT mint presentation: shared live bindings and the established diagnostic generator."""
from pathlib import Path
import json,re,importlib.util,shutil

def adapt(root):
 project=Path(__file__).resolve().parents[1];folder=root/'NFTwallet/src/main/java/com/eurobuddha/nftwallet'
 catalog=json.loads((project/'localization/catalog.json').read_text());spec=json.loads((project/'localization/nft-mint-messages.json').read_text())
 loader=importlib.util.spec_from_file_location('mint_messages',project/'scripts/adapt-pandamonium-wallet-error-languages.py');module=importlib.util.module_from_spec(loader);loader.loader.exec_module(module)
 text=module.build_source(spec,catalog).replace('com.eurobuddha.wallet','com.eurobuddha.nftwallet').replace('WalletMessages','MintMessages').replace('Explicit wallet failures only.','Explicit mint UI labels and local diagnostics only.').replace('adapt-pandamonium-wallet-error-languages.py from localization/wallet-errors.json','adapt-pandamonium-nft-mint-languages.py from localization/nft-mint-messages.json')
 text=text.replace('        return value;\n    }\n    private static final','        return NftMessages.message(value);\n    }\n    private static final')
 (folder/'MintMessages.java').write_text(text)
 def change(s,old,new,n=None):
  count=s.count(old);assert count>0 and (n is None or count==n),('NFT mint source drift',old,count,n)
  return s.replace(old,new)
 p=folder/'MintView.java';s=p.read_text()
 # Labels supplied by the program are translated; custom metadata labels and values remain literal.
 s=change(s,'private void addConfirmRow(LinearLayout body, String label, String value) {','''private void addConfirmRow(LinearLayout body, CharSequence label, CharSequence value) {
        addConfirmRow(body, label, value, false);
    }

    private void addConfirmRow(LinearLayout body, CharSequence label, CharSequence value, boolean missing) {''',1)
 start=s.index('    private void addConfirmRow(');end=s.index('    private void showConfirm(',start);part=s[start:end]
 part=part.replace('l.setText(label);','Language.setText(l, label);').replace('v.setText(value);','Language.setText(v, value);').replace('"Not set".equals(value) || "None set".equals(value)','missing');s=s[:start]+part+s[end:]
 s=re.sub(r'addConfirmRow\(body, ("[^"\n]+"),',r'addConfirmRow(body, MintMessages.message(\1),',s)
 s=re.sub(r'addConfirmRow\(body, (.+), (\w+)\.isEmpty\(\) \? "(Not set|None set)" : \2\);',lambda m:'addConfirmRow(body, '+m[1]+', '+m[2]+'.isEmpty() ? MintMessages.message("'+m[3]+'") : '+m[2]+', '+m[2]+'.isEmpty());',s)
 s=change(s,'nftEmbedMode ? "embedded on-chain (" + nftImageB64.length() + " b64)" : imageValue','nftEmbedMode ? MintMessages.message("embedded on-chain (" + nftImageB64.length() + " b64)") : imageValue')
 s=change(s,'addConfirmRow(body, MintMessages.message("Creator signature"), "yes — always (signtoken)");','addConfirmRow(body, MintMessages.message("Creator signature"), MintMessages.message("yes — always (signtoken)"));')
 s=change(s,'addConfirmRow(body, MintMessages.message("Mode"), colEmbedMode ? "embedded on-chain images" : "URL base + index");','addConfirmRow(body, MintMessages.message("Mode"), MintMessages.message(colEmbedMode ? "embedded on-chain images" : "URL base + index"));')
 s=change(s,'addConfirmRow(body, MintMessages.message("Wallet icon"), iconDescF);','addConfirmRow(body, MintMessages.message("Wallet icon"), MintMessages.message(iconDescF));')
 s=change(s,'addConfirmRow(body, MintMessages.message("Contract"), "SAMESTATE locked edition — immutable once stamped");','addConfirmRow(body, MintMessages.message("Contract"), MintMessages.message("SAMESTATE locked edition — immutable once stamped"));')
 s=change(s,'n.setText(row.optString("name", "Collection") + "  ·  " + row.optInt("size") + " items");','Language.setText(n, Language.join(collectionName(row), MintMessages.message("  ·  " + row.optInt("size") + " items")));')
 s=change(s,'back.setText("‹  " + row.optString("name", "Collection"));','Language.setText(back, Language.join("‹  ", collectionName(row)));')
 s=change(s,'t.setText("‹  " + title);','Language.setText(t, Language.join("‹  ", MintMessages.message(title)));')
 s=change(s,'tid.setText(Util.shorten(tokenid) + "  ·  tap to copy");','Language.setText(tid, Language.join(Util.shorten(tokenid), MintMessages.message("  ·  tap to copy")));')
 s=change(s,'CoinDetailDialog.copy(act, "Token ID", tokenid)','CoinDetailDialog.copy(act, MintMessages.message("Token ID"), tokenid)')
 s=change(s,': "✝  Bury this entire collection");',': MintMessages.message("✝  Bury this entire collection"));')
 s=change(s,'    private View collectionRow(','''    private CharSequence collectionName(JSONObject row) {
        return row.opt("name") instanceof String ? row.optString("name") : MintMessages.message("Collection");
    }

    private View collectionRow(''',1)
 # Each remaining TextView assignment is either a known program label/diagnostic or the explicit
 # literal exceptions below. Balanced scanning preserves multiline ternaries and nested calls.
 matches=list(re.finditer(r'\b(\w+)\.setText\(',s))
 for m in reversed(matches):
  obj=m[1]
  if obj=='Language':continue
  start=m.end();i=start;depth=1;quoted=False;escaped=False
  while depth:
   ch=s[i]
   if quoted:
    if escaped:escaped=False
    elif ch=='\\':escaped=True
    elif ch=='"':quoted=False
   elif ch=='"':quoted=True
   elif ch=='(':depth+=1
   elif ch==')':depth-=1
   i+=1
  expr=s[start:i-1]
  literal=obj=='nCreator' or (obj=='n' and expr.startswith('"#"'))
  replacement='Language.setText('+obj+', '+(expr if literal else 'MintMessages.message('+expr+')')+')'
  s=s[:m.start()]+replacement+s[i:]
 s=change(s,'e.setHint(hint);','Language.hint(e, MintMessages.message(hint));')
 # Dialog constructors and transient progress callbacks are the remaining display boundaries.
 s=change(s,'Sheet.create(act, title)','Sheet.create(act, MintMessages.message(title))')
 s=change(s,'.subtitle(message)','.subtitle(MintMessages.message(message))')
 for raw in ['Close','View balances','Back','Wait longer','Re-post']:
  s=s.replace('.action("'+raw+'",','.action(MintMessages.message("'+raw+'"),')
 s=change(s,'.action(action, Sheet.Style.PRIMARY','.action(MintMessages.message(action), Sheet.Style.PRIMARY')
 s=change(s,'Sheet.create(act, "Re-post the create?")','Sheet.create(act, MintMessages.message("Re-post the create?"))')
 s=change(s,'.subtitle("Only do this if the first create was definitely lost. If it later "\n                            + "confirms too, you end up with TWO identical collections and no way to "\n                            + "merge them — you would have to bury one.")','.subtitle(MintMessages.message("Only do this if the first create was definitely lost. If it later "\n                            + "confirms too, you end up with TWO identical collections and no way to "\n                            + "merge them — you would have to bury one."))')
 # Wrap Toast payloads using their final LENGTH argument, preserving the message construction.
 s=re.sub(r'Toast\.makeText\(act, (.*?),\s*(Toast\.LENGTH_(?:LONG|SHORT))\)',lambda m:'Toast.makeText(act, MintMessages.message('+m[1]+'), '+m[2]+')',s,flags=re.S)
 # Longer translated phase names need horizontal scrolling; form mode buttons retain full labels.
 s=change(s,'        container.addView(chips);','''        android.widget.HorizontalScrollView phaseScroll = new android.widget.HorizontalScrollView(act);
        phaseScroll.setHorizontalScrollBarEnabled(true);
        phaseScroll.addView(chips);
        container.addView(phaseScroll);''',1)
 s=change(s,'        t.setOnClickListener(click);','        t.setMinHeight(dp(48));\n        t.setOnClickListener(click);',1)
 s=change(s,'        } else {\n            TextView doneNote = new TextView(act);','        } else if (\"DONE\".equals(phase)) {\n            TextView doneNote = new TextView(act);',1)
 p.write_text(s)
 p=folder/'MintDriver.java';s=p.read_text();start=s.index('    public static String explain(');end=s.index('    // ---- progress',start);part=s[start:end];part=re.sub(r'return ("[^"\n]+");',r'return MintMessages.message(\1).toString();',part);s=s[:start]+part+s[end:]
 s=change(s,'return r.optString("name", "Collection");','return r.optString("name", MintMessages.message("Collection").toString());');p.write_text(s)
 p=folder/'Notifier.java';s=p.read_text()
 for raw in ['Minting in the background','Mint updates']:s=change(s,'"'+raw+'"','MintMessages.message("'+raw+'").toString()')
 p.write_text(s)
 p=folder/'MintService.java';s=p.read_text();s=change(s,'    private String fgText = "";','    private String fgText = "";\n    private boolean foregroundActive;')
 s=change(s,'Notifier.alert(this, "Minting stopped",','Notifier.alert(this, MintMessages.message("Minting stopped").toString(),')
 s=change(s,'"limit. Open the wallet for details.");','"limit. Open the wallet for details.").toString());')
 s=change(s,'                    "A collection can\'t be completed','                    MintMessages.message("A collection can\'t be completed')
 s=change(s,'Notifier.alert(this, "Minting finished", "Your collection is fully stamped.");','Notifier.alert(this, MintMessages.message("Minting finished").toString(), MintMessages.message("Your collection is fully stamped.").toString());')
 s=change(s,'.setContentTitle("Minting your collection")','.setContentTitle(MintMessages.message("Minting your collection"))')
 s=change(s,'.setContentText(text)','.setContentText(MintMessages.message(text))')
 s=change(s,'            return true;\n        } catch (Exception e)', '            foregroundActive = true;\n            return true;\n        } catch (Exception e)',1)
 s=change(s,'    private void stopGracefully() {','    private void stopGracefully() {\n        foregroundActive = false;')
 s=change(s,'    @Override public void onDestroy() {','''    // Reuse Future Cash GuardianService's locale/configuration refresh without starting node work.
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Notifier.ensureChannels(this);
        if (foregroundActive) { fgText = ""; updateFg(stateText()); }
    }

    @Override public void onDestroy() {
        foregroundActive = false;''')
 p.write_text(s)
 for name in ['MintMessagesTest']:
  test=project/'scripts/pandamonium-tests/NFTwallet/src/test/java/com/eurobuddha/nftwallet'/(name+'.java')
  if test.exists():shutil.copy2(test,root/'NFTwallet/src/test/java/com/eurobuddha/nftwallet'/test.name)
