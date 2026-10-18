"""NFT coin-list display adaptation, reusing retained bindings and token-name provenance."""
from pathlib import Path
import json,re,xml.etree.ElementTree as E

def adapt(root):
 project=Path(__file__).resolve().parents[1];labels=json.loads((project/'localization/utxo-labels.json').read_text());catalog=json.loads((project/'localization/catalog.json').read_text());folder=root/'NFTwallet/src/main/java/com/eurobuddha/nftwallet'
 def res(k):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',labels[k]).replace('.','_').lower()
 def msg(k,*args):return 'Language.msg('+res(k)+('' if not args else ', '+', '.join(args))+')'
 def count(k,arg):return 'Language.count(com.eurobuddha.minimacore.i18n.R.plurals.'+res(k)+', '+arg+')'
 def change(s,old,new):
  assert old in s,('NFT wallet source drift',old)
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
 # Reuse the complete tested UTXO popup lifecycle helper after its own adaptation.
 utxo=(root/'utxo/src/main/java/com/eurobuddha/utxo/MainActivity.java').read_text()
 start=utxo.index('    private final java.util.Map<androidx.appcompat.widget.PopupMenu,')
 end=utxo.index('    private final java.util.Set<androidx.appcompat.app.AlertDialog>',start)
 p,s=load('MainActivity')
 s=change(s,'    private final java.util.Set<android.app.Dialog>',utxo[start:end]+'    private final java.util.Set<android.app.Dialog>')
 s=change(s,'        Language.configurationChanged(this);','        Language.configurationChanged(this);\n        relabelMenus();')
 s=change(s,'        languageDialogs.clear();','        languageDialogs.clear();\n        for (androidx.appcompat.widget.PopupMenu m : new java.util.ArrayList<>(languageMenus.keySet())) m.dismiss();\n        languageMenus.clear();')
 s=change(s,'x > slack ? View.VISIBLE : View.GONE','x > slack ? View.VISIBLE : View.INVISIBLE')
 s=change(s,'x < overflow - slack ? View.VISIBLE : View.GONE','x < overflow - slack ? View.VISIBLE : View.INVISIBLE')
 start=s.index('        // These flips happen from a layout-change listener');end=s.index('\n    }',start)
 s=s[:start]+s[end:]
 save(p,s)
 p,s=load('WalletView');s=change(s,'        menu.show();','        act.showLanguageMenu(menu);')
 s=change(s,'        clearBtn.setTextColor(Design.accent());','        clearBtn.setTextColor(Design.accent());\n        for (Button button : new Button[]{toolsBtn, clearBtn, sendBtn})\n            button.setPaddingRelative(dp(6), button.getPaddingTop(), dp(6), button.getPaddingBottom());')

 s=change(s,'sel.size() + (sel.size() == 1 ? " coin selected" : " coins selected")',count('selectedCount','sel.size()'))
 s=change(s,'act.selectedTotalString() + " " + sel.get(0).tokenName','Language.join(act.selectedTotalString(), " ", NftText.token(sel.get(0)))')
 s=change(s,'Util.tidyAmount(minimaAmt.toPlainString()) + " MINIMA  ·  "\n                + count + (count == 1 ? " coin" : " coins")','Language.join(Util.tidyAmount(minimaAmt.toPlainString()), " MINIMA  ·  ", '+count('coinCount','count')+')')
 s=change(s,'c.tokenName == null ? "" : c.tokenName.toUpperCase()','Language.upper(NftText.token(c))')
 s=change(s,'label + " copied"',msg('labelCopied','label'))
 s=fixed(s,['splitSelected','consolidate','distributeSelected','untrackSelected','pickCoins','noAddresses','empty','copy','addressLabel','contract','watch','pendingCaps'])
 for a,b in [('setStatus(String msg','setStatus(CharSequence msg'),('copyToClipboard(String label','copyToClipboard(CharSequence label'),('addNote(String text','addNote(CharSequence text')]:s=change(s,a,b)
 s=s.replace('setTextColor(Design.accent())','setTextColor(Design.accentText())')
 # A long translated badge/name must not consume the whole amount column.
 s=change(s,'LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(WRAP, WRAP);','LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, WRAP, 1f);')
 s=change(s,'row.setMinimumHeight(dp(24));','row.setMinimumHeight(dp(48));')
 s=change(s,'copy.setPadding(dp(6), dp(2), dp(6), dp(2));','copy.setPadding(dp(6), dp(2), dp(6), dp(2));\n        copy.setMinHeight(dp(48)); copy.setGravity(Gravity.CENTER);')
 # Resolve clipboard payloads before passing them across the Android process boundary.
 s=change(s,'ClipData.newPlainText(label, text)','ClipData.newPlainText(label.toString(), text)')
 save(p,s)
 # Two-row selection bar: summaries keep full width; translated actions share the second row.
 a='{http://schemas.android.com/apk/res/android}';E.register_namespace('android',a[1:-1]);p=root/'NFTwallet/src/main/res/layout/pm_nftwallet_view_wallet.xml';tree=E.parse(p);bar=tree.getroot()[0];summary=bar[0]
 bar.set(a+'orientation','vertical');summary.set(a+'layout_width','match_parent');summary.attrib.pop(a+'layout_weight',None)
 actions=E.Element('LinearLayout',{a+'layout_width':'match_parent',a+'layout_height':'wrap_content',a+'orientation':'horizontal'})
 for button in list(bar)[1:]:
  bar.remove(button);button.set(a+'layout_width','0dp')
  weights={'@+id/pm_nftwallet_toolsBtn':'1.4','@+id/pm_nftwallet_clearBtn':'1','@+id/pm_nftwallet_sendBtn':'1.3'}
  button.set(a+'layout_weight',weights[button.get(a+'id')]);button.set(a+'minHeight','48dp')
  button.set(a+'textSize','14sp');button.set(a+'paddingStart','6dp');button.set(a+'paddingEnd','6dp');actions.append(button)
 bar.append(actions)
 mapping={catalog[labels[k]]['en']:res(k) for k in ['pickCoins','tools','clear','sendAction']}
 for v in tree.iter():
  raw=v.get(a+'text')
  if raw in mapping:v.set(a+'text','@string/'+mapping[raw]);v.set(a+'tag','l10n:text='+mapping[raw])
 E.indent(tree,space='    ');tree.write(p,encoding='unicode',xml_declaration=True)
