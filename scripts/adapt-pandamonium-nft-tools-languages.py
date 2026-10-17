"""NFT wallet-tool display adaptation, reusing retained bindings and token-name provenance."""
from pathlib import Path
import json,re,xml.etree.ElementTree as E

def adapt(root):
 project=Path(__file__).resolve().parents[1];labels=json.loads((project/'localization/utxo-labels.json').read_text());catalog=json.loads((project/'localization/catalog.json').read_text());folder=root/'NFTwallet/src/main/java/com/eurobuddha/nftwallet'
 def res(k):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',labels[k]).replace('.','_').lower()
 def msg(k,*args):return 'Language.msg('+res(k)+('' if not args else ', '+', '.join(args))+')'
 def count(k,arg):return 'Language.count(com.eurobuddha.minimacore.i18n.R.plurals.'+res(k)+', '+arg+')'
 def change(s,old,new):
  assert old in s,('NFT tools source drift',old)
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
 p,s=load('WalletTools')
 s=change(s,'void show(String message','void show(CharSequence message')
 s=change(s,'input(String hint','input(CharSequence hint');s=change(s,'e.setHint(hint);','Language.hint(e, hint);')
 s=change(s,'String internalid, String okLabel','String internalid, CharSequence okLabel')
 s=change(s,'okLabel + " — sent!"',msg('toolSent','okLabel'))
 s=change(s,'"Failed: " + message',msg('failed','NftMessages.message(message)'))
 s=change(s,'"Amount too small to split into " + n + "."',msg('smallSplit','n'))
 s=change(s,'"Split into " + fn + " coins"',count('splitDone','fn'))
 s=change(s,'"Merge the " + sel.size() + " selected " + tokenName + " coins into one?"',msg('mergePrompt','sel.size()','NftText.token(sel.get(0))'))
 # Translation placeholders follow their catalog order: count, token.
 s=change(s,'"Merged " + sel.size() + " coins"',count('mergedDone','sel.size()'))
 s=change(s,'"This token supports at most " + dec + " decimal place" + (dec == 1 ? "" : "s") + "."',msg('decimals','dec'))
 s=change(s,'"Need " + Util.tidyAmount(required.toPlainString()) + " but only "\n                    + Util.tidyAmount(total.toPlainString()) + " selected."',msg('needSelected','Util.tidyAmount(required.toPlainString())','Util.tidyAmount(total.toPlainString())'))
 s=change(s,'"Fetching " + n + " addresses…"',msg('fetchCount','n'))
 s=change(s,'"Untrack " + sel.size() + " coin(s)?"',count('untrackTitle','sel.size()'))
 s=change(s,'"Untracked " + sel.size() + " coin(s)."',count('untracked','sel.size()'))
 s=change(s,'"Untracking " + (i + 1) + "/" + sel.size() + "…"',msg('untracking','i + 1','sel.size()'))
 s=change(s,'"Untrack failed: " + message',msg('untrackFailed','NftMessages.message(message)'))
 s=fixed(s,['posting','selectSplit','coinNumber','splitTitle','split','cancel','validNumber','coinRange','fetchAddresses','consolidateMinima','consolidateHelp','consolidate','selectMerge','consolidateSelected','merge','consolidating','consolidateSubmitted','fetching','noAddress','selectDistribute','distributeBusy','addressNumber','amountEach','distributeTitle','distribute','addressRange','validAmount','positiveAmount','distributeStarted','selectUntrack','untrackHelp','untrack'])
 s=change(s,'"Nothing to consolidate."','Language.msg(l10n_nft_tools_nothing_consolidate)')
 s=change(s,'        EditText e = new EditText(act);','        EditText e = new EditText(act);\n        e.setTextColor(Design.text());\n        e.setHintTextColor(Design.dim());')
 save(p,s)
 imports='\nimport com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;\n'
 # All engine strings stay raw at their source; toast translates only at the display boundary.
 p=folder/'DistributeManager.java';s=p.read_text().replace('package com.eurobuddha.nftwallet;','package com.eurobuddha.nftwallet;'+imports)
 s=change(s,'public String statusLine()','public CharSequence statusLine()')
 s=change(s,'''        String s = "Distribute running · " + job.batchesDone + " batch(es) done · "
                + job.remaining.size() + " address(es) left"
                + (job.waiting ? " · waiting for confirmation…" : " · posting…");
        if (job.retryCount > 0) s += " · retry " + job.retryCount + "/" + MAX_RETRIES;''','''        CharSequence s = Language.join(Language.msg(l10n_utxo_distribution_progress, job.batchesDone, job.remaining.size()),
                " · ", job.waiting ? Language.msg(l10n_utxo_distribution_waiting) : Language.msg(l10n_utxo_status_posting));
        if (job.retryCount > 0) s = Language.join(s, " · ", Language.msg(l10n_utxo_distribution_retry, job.retryCount, MAX_RETRIES));''')
 s=change(s,'Toast.makeText(act, msg, Toast.LENGTH_SHORT)','Toast.makeText(act, NftMessages.message(msg), Toast.LENGTH_SHORT)');p.write_text(s)

 # The shared status view must stop showing running progress when its job ends.
 # Keep unrelated tool messages: only clear a label previously owned by distribution.
 p=folder/'WalletView.java';s=p.read_text()
 s=change(s,'    private final WalletTools tools;', '    private final WalletTools tools;\n    private boolean distributionStatus;')
 s=change(s,'    private void setStatus(CharSequence msg, boolean ok) {','    private void setStatus(CharSequence msg, boolean ok) {\n        distributionStatus = false;')
 s=change(s,'        if (dm != null && dm.isActive()) {','        if (dm != null && dm.isActive()) {\n            distributionStatus = true;')
 s=change(s,'            statusView.setTextColor(Design.accentText());\n        }','            statusView.setTextColor(Design.accentText());\n        } else if (distributionStatus) {\n            distributionStatus = false;\n            statusView.setVisibility(View.GONE);\n        }')
 p.write_text(s)
