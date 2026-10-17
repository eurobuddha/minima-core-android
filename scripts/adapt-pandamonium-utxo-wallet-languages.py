"""UTXO wallet/balance/tool display adaptation, reusing retained bindings and token-name provenance."""
from pathlib import Path
import json,re,xml.etree.ElementTree as E

def adapt(root):
 project=Path(__file__).resolve().parents[1];labels=json.loads((project/'localization/utxo-labels.json').read_text());catalog=json.loads((project/'localization/catalog.json').read_text());folder=root/'utxo/src/main/java/com/eurobuddha/utxo'
 def res(k):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',labels[k]).replace('.','_').lower()
 def msg(k,*args):return 'Language.msg('+res(k)+('' if not args else ', '+', '.join(args))+')'
 def count(k,arg):return 'Language.count(com.eurobuddha.minimacore.i18n.R.plurals.'+res(k)+', '+arg+')'
 def change(s,old,new):
  assert old in s,('UTXO wallet source drift',old)
  return s.replace(old,new)
 def load(n):
  p=folder/(n+'.java');s=p.read_text()
  if 'import com.eurobuddha.minimacore.i18n.Language;' not in s:s=s.replace('package com.eurobuddha.utxo;','package com.eurobuddha.utxo;\nimport com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;')
  return p,s
 def fixed(s,keys):
  for k in keys:s=change(s,json.dumps(catalog[labels[k]]['en'],ensure_ascii=False),msg(k))
  return s
 def save(p,s):
  s=re.sub(r'\b([A-Za-z_][A-Za-z_0-9]*(?:\.[A-Za-z_][A-Za-z_0-9]*)*)\.setText\(',r'Language.setText(\1, ',s).replace('Language.setText(Language, ','Language.setText(')
  p.write_text(s)
 # Retain the original name/metadata fields used by transaction/history code. Only add provenance.
 p=folder/'Util.java';s=p.read_text();mark='    /** Trim trailing zeros'
 helper='''    /** Same provenance rule as core TokenMeta: literal Token is distinct from absent metadata. */
    public static boolean tokenNameProvided(Object token, String tokenid) {
        if (isMinima(tokenid)) return true;
        if (token instanceof String) return !((String)token).isEmpty();
        if (token instanceof JSONObject) {
            Object name = ((JSONObject)token).opt("name");
            if (name instanceof JSONObject) {
                JSONObject meta = (JSONObject)name;
                return meta.has("name") && !meta.isNull("name") && !meta.optString("name", "").isEmpty();
            }
            return name instanceof String && !((String)name).isEmpty();
        }
        return false;
    }

''';s=change(s,mark,helper+mark);p.write_text(s)
 p=folder/'Coin.java';s=p.read_text();s=change(s,'    public String tokenName;','    public String tokenName;\n    public boolean tokenNameProvided = true;');s=change(s,'        return x;','        x.tokenNameProvided = Util.tokenNameProvided(c.opt("token"), x.tokenid);\n        return x;');p.write_text(s)
 p=folder/'TokenMeta.java';s=p.read_text();s=change(s,'    public String name = "Token";','    public String name = "Token";\n    public boolean nameProvided;');s=change(s,'        TokenMeta m = new TokenMeta();','        TokenMeta m = new TokenMeta();\n        m.nameProvided = Util.tokenNameProvided(token, tokenid);');p.write_text(s)
 (folder/'UtxoText.java').write_text('''package com.eurobuddha.utxo;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Display-only name selection; raw metadata and stored transaction names remain unchanged. */
final class UtxoText {
    private UtxoText() {}
    static CharSequence token(Coin coin) {
        return coin.tokenNameProvided ? (coin.tokenName == null ? "" : coin.tokenName) : Language.msg(l10n_wallet_token);
    }
    static CharSequence token(TokenBalance balance) {
        return balance.meta != null && balance.meta.nameProvided ? balance.name : Language.msg(l10n_wallet_token);
    }
}
''')
 p,s=load('MainActivity')
 s=change(s,'private String coinsNote =','private CharSequence coinsNote =');s=change(s,'public String coinsNote()','public CharSequence coinsNote()')
 s=change(s,'missing + (missing == 1 ? " coin is" : " coins are") + " not shown: this Minima Core (<1.3.0) caps "\n                                + "replies and one slice was still too big. Update Minima Core to see everything."',msg('missingCoins','missing'))
 s=fixed(s,['slicedCoins'])
 # Filez menu pattern: retain original descriptors/items; remeasure only the popup window.
 hook='''    private final java.util.Map<androidx.appcompat.widget.PopupMenu, java.util.Map<android.view.MenuItem, CharSequence>> languageMenus = new java.util.LinkedHashMap<>();
    void showLanguageMenu(androidx.appcompat.widget.PopupMenu menu) {
        java.util.Map<android.view.MenuItem, CharSequence> labels = new java.util.LinkedHashMap<>();
        for (int i=0;i<menu.getMenu().size();i++) {
            android.view.MenuItem item=menu.getMenu().getItem(i); labels.put(item,item.getTitle());
        }
        languageMenus.put(menu,labels);
        menu.setOnDismissListener(m -> languageMenus.remove(m));
        menu.show();
    }
    private void relabelMenus() {
        for (androidx.appcompat.widget.PopupMenu menu : new java.util.ArrayList<>(languageMenus.keySet())) {
            java.util.Map<android.view.MenuItem,CharSequence> labels=languageMenus.get(menu);
            menu.setOnDismissListener(null); menu.dismiss();
            for (java.util.Map.Entry<android.view.MenuItem,CharSequence> entry : labels.entrySet())
                entry.getKey().setTitle(entry.getValue().toString());
            menu.setOnDismissListener(m -> languageMenus.remove(m)); menu.show();
        }
    }
'''
 s=change(s,'    private final java.util.Set<androidx.appcompat.app.AlertDialog>',hook+'    private final java.util.Set<androidx.appcompat.app.AlertDialog>')
 s=change(s,'        Language.configurationChanged(this);','        Language.configurationChanged(this);\n        relabelMenus();')
 s=change(s,'        languageDialogs.clear();','        languageDialogs.clear();\n        for (androidx.appcompat.widget.PopupMenu m : new java.util.ArrayList<>(languageMenus.keySet())) m.dismiss();\n        languageMenus.clear();');save(p,s)
 p,s=load('WalletView');s=change(s,'        menu.show();','        act.showLanguageMenu(menu);')
 s=change(s,'        clearBtn.setTextColor(Design.accent());','        clearBtn.setTextColor(Design.accent());\n        for (Button button : new Button[]{toolsBtn, clearBtn, sendBtn})\n            button.setPaddingRelative(dp(6), button.getPaddingTop(), dp(6), button.getPaddingBottom());')

 s=change(s,'sel.size() + (sel.size() == 1 ? " coin selected" : " coins selected")',count('selectedCount','sel.size()'))
 s=change(s,'act.selectedTotalString() + " " + sel.get(0).tokenName','Language.join(act.selectedTotalString(), " ", UtxoText.token(sel.get(0)))')
 s=change(s,'!act.coinsNote().isEmpty()','act.coinsNote().length() > 0')
 s=change(s,'Util.tidyAmount(minimaAmt.toPlainString()) + " MINIMA  ·  "\n                + count + (count == 1 ? " coin" : " coins")','Language.join(Util.tidyAmount(minimaAmt.toPlainString()), " MINIMA  ·  ", '+count('coinCount','count')+')')
 s=change(s,'c.tokenName == null ? "" : c.tokenName.toUpperCase()','Language.upper(UtxoText.token(c))')
 s=change(s,'label + " copied"',msg('labelCopied','label'))
 s=fixed(s,['splitSelected','consolidate','distributeSelected','untrackSelected','pickCoins','noAddresses','empty','copy','addressLabel','coinId','contract','watch'])
 for a,b in [('setStatus(String msg','setStatus(CharSequence msg'),('copyToClipboard(String label','copyToClipboard(CharSequence label'),('addNote(String text','addNote(CharSequence text')]:s=change(s,a,b)
 s=s.replace('setTextColor(Design.accent())','setTextColor(Design.accentText())')
 # A long translated badge/name must not consume the whole amount column.
 s=change(s,'LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(WRAP, WRAP);','LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, WRAP, 1f);')
 s=change(s,'row.setMinimumHeight(dp(24));','row.setMinimumHeight(dp(48));')
 s=change(s,'copy.setPadding(dp(6), dp(2), dp(6), dp(2));','copy.setPadding(dp(6), dp(2), dp(6), dp(2));\n        copy.setMinHeight(dp(48)); copy.setGravity(Gravity.CENTER);')
 save(p,s)
 p,s=load('BalancesView')
 s=change(s,'mono(symbol.toUpperCase(),','mono(Language.upper(notEmpty(b.meta.ticker) ? b.meta.ticker : UtxoText.token(b)),')
 s=change(s,'(b.coins + (b.coins == 1 ? " coin" : " coins")).toUpperCase()','Language.upper('+count('coinCount','b.coins')+')')
 s=change(s,'b.name + (b.isNft() ? "   ·  NFT" : "")','Language.join(UtxoText.token(b), b.isNft() ? "   ·  NFT" : "")')
 s=change(s,'String secondary = (notEmpty(b.name) && !b.name.equalsIgnoreCase(symbol)) ? b.name : null;', 'CharSequence secondary = (notEmpty(b.name) && !b.name.equalsIgnoreCase(symbol)) ? UtxoText.token(b) : null;')
 s=change(s,'secondary + "  ·  NFT"','Language.join(secondary, "  ·  NFT")')
 s=change(s,'b.isNft() ? Design.accent() : Design.dim()', 'b.isNft() ? Design.accentText() : Design.dim()')
 s=change(s,'Util.tidyAmount(b.sendable), Design.accent())','Util.tidyAmount(b.sendable), Design.accentText())')

 s=fixed(s,['noBalances','sendableCaps','lockedCaps','pendingCaps','fullResolution','enlargeImage','sendable','confirmed','pending','locked','coinsLabel','supply','ticker','decimalsLabel','owner','tokenId','description','website','webValidation','receive','close','open'])
 for a,b in [('mono(String s','mono(CharSequence s'),('splitRow(String label','splitRow(CharSequence label'),('addKv(LinearLayout box, String label','addKv(LinearLayout box, CharSequence label'),('sectionLabel(String s)','sectionLabel(CharSequence s)'),('linkRow(String label','linkRow(CharSequence label')]:s=change(s,a,b)
 s=s.replace('setTextColor(Design.accent())','setTextColor(Design.accentText())');save(p,s)
 p,s=load('WalletTools')
 s=change(s,'void show(String message','void show(CharSequence message')
 s=change(s,'input(String hint','input(CharSequence hint');s=change(s,'e.setHint(hint);','Language.hint(e, hint);')
 s=change(s,'String internalid, String okLabel','String internalid, CharSequence okLabel')
 s=change(s,'okLabel + " — sent!"',msg('toolSent','okLabel'))
 s=change(s,'okLabel + " — no reply from the node in time; it may still have posted. See \'posted?\' in History."',msg('toolUnknown','okLabel'))
 s=change(s,'"Failed: " + message',msg('failed','UtxoMessages.message(message)'))
 s=change(s,'"Amount too small to split into " + n + "."',msg('smallSplit','n'))
 s=change(s,'"Split into " + fn + " coins"',count('splitDone','fn'))
 s=change(s,'"Merge the " + sel.size() + " selected " + tokenName + " coins into one?"',msg('mergePrompt','sel.size()','UtxoText.token(sel.get(0))'))
 # Translation placeholders follow their catalog order: count, token.
 s=change(s,'"Merged " + sel.size() + " coins"',count('mergedDone','sel.size()'))
 s=change(s,'"This token supports at most " + dec + " decimal place" + (dec == 1 ? "" : "s") + "."',msg('decimals','dec'))
 s=change(s,'"Need " + Util.tidyAmount(required.toPlainString()) + " but only "\n                    + Util.tidyAmount(total.toPlainString()) + " selected."',msg('needSelected','Util.tidyAmount(required.toPlainString())','Util.tidyAmount(total.toPlainString())'))
 s=change(s,'"Fetching " + n + " addresses…"',msg('fetchCount','n'))
 s=change(s,'"Untrack " + sel.size() + " coin(s)?"',count('untrackTitle','sel.size()'))
 s=change(s,'"Untracked " + sel.size() + " coin(s)."',count('untracked','sel.size()'))
 s=change(s,'"Untracking " + (i + 1) + "/" + sel.size() + "…"',msg('untracking','i + 1','sel.size()'))
 s=change(s,'"Untrack failed: " + message',msg('untrackFailed','UtxoMessages.message(message)'))
 s=fixed(s,['posting','selectSplit','coinNumber','splitTitle','split','cancel','validNumber','coinRange','fetchAddresses','consolidateMinima','consolidateHelp','consolidate','selectMerge','consolidateSelected','merge','consolidating','consolidateSubmitted','fetching','noAddress','selectDistribute','distributeBusy','addressNumber','amountEach','distributeTitle','distribute','addressRange','validAmount','positiveAmount','distributeStarted','selectUntrack','untrackHelp','untrack'])
 save(p,s)
 # Send's display uses name provenance; its stored tokenName and command inputs remain raw.
 p,s=load('SendView')
 s=change(s,'Util.tidyAmount(total.toPlainString()) + " " + tokenName','Language.join(Util.tidyAmount(total.toPlainString()), " ", UtxoText.token(sel.get(0)))')
 for amount in ['amountStr','changeStr']:s=change(s,'Util.tidyAmount('+amount+') + " " + tokenName','Language.join(Util.tidyAmount('+amount+'), " ", UtxoText.token(sel.get(0)))')
 s=change(s,'Language.msg(l10n_utxo_posted, Util.tidyAmount(amountStr), tokenName)','Language.msg(l10n_utxo_posted, Util.tidyAmount(amountStr), UtxoText.token(sel.get(0)))')
 s=change(s,'row(CharSequence left, String right','row(CharSequence left, CharSequence right');s=change(s,'String tag, String amount)','String tag, CharSequence amount)');save(p,s)
 # Two-row selection bar: summaries keep full width; translated actions share the second row.
 a='{http://schemas.android.com/apk/res/android}';E.register_namespace('android',a[1:-1]);p=root/'utxo/src/main/res/layout/pm_utxo_view_wallet.xml';tree=E.parse(p);bar=tree.getroot()[0];summary=bar[0]
 bar.set(a+'orientation','vertical');summary.set(a+'layout_width','match_parent');summary.attrib.pop(a+'layout_weight',None)
 actions=E.Element('LinearLayout',{a+'layout_width':'match_parent',a+'layout_height':'wrap_content',a+'orientation':'horizontal'})
 for button in list(bar)[1:]:
  bar.remove(button);button.set(a+'layout_width','0dp')
  weights={'@+id/pm_utxo_toolsBtn':'1.4','@+id/pm_utxo_clearBtn':'1','@+id/pm_utxo_sendBtn':'1.3'}
  button.set(a+'layout_weight',weights[button.get(a+'id')]);button.set(a+'minHeight','48dp')
  button.set(a+'textSize','14sp');button.set(a+'paddingStart','6dp');button.set(a+'paddingEnd','6dp');actions.append(button)
 bar.append(actions)
 mapping={catalog[labels[k]]['en']:res(k) for k in ['pickCoins','tools','clear','sendAction']}
 for v in tree.iter():
  raw=v.get(a+'text')
  if raw in mapping:v.set(a+'text','@string/'+mapping[raw]);v.set(a+'tag','l10n:text='+mapping[raw])
 E.indent(tree,space='    ');tree.write(p,encoding='unicode',xml_declaration=True)
 p=folder/'Design.java';s=p.read_text();s=change(s,'pick(0xFF8A8A8A, 0xFF5A5A5A, 0xFF6A6A78)','pick(0xFF666666, 0xFF9A9AA8, 0xFF9A9AA8)');p.write_text(s)
