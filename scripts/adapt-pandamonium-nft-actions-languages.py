"""StateNFT action presentation: retained Sheet bindings, literal names and unchanged chain commands."""
from pathlib import Path
import json,re

def adapt(root):
 project=Path(__file__).resolve().parents[1];catalog=json.loads((project/'localization/catalog.json').read_text());labels=json.loads((project/'localization/nft-actions-labels.json').read_text());folder=root/'NFTwallet/src/main/java/com/eurobuddha/nftwallet'
 def resource(k):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',labels[k]).replace('.','_').lower()
 def msg(k,*args):return 'Language.msg('+resource(k)+('' if not args else ', '+', '.join(args))+')'
 def change(s,old,new):
  assert old in s,('NFT state-action source drift',old)
  return s.replace(old,new)
 p=folder/'StateNftActions.java';s=p.read_text().replace('package com.eurobuddha.nftwallet;','package com.eurobuddha.nftwallet;\nimport com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;')
 # Fold adjacent literal strings for exact message matching; no evaluated state or command values change.
 literal=r'"(?:[^"\\]|\\.)*"'
 while True:
  s,n=re.subn('('+literal+r')\s*\+\s*('+literal+')',lambda m:json.dumps(json.loads(m[1])+json.loads(m[2]),ensure_ascii=False),s)
  if not n:break
 dynamic={
 '"Transfer " + displayName':msg('transferName','displayName'),
 '"Transferring " + displayName':msg('transferringName','displayName'),
 '"Sending " + collectionName':msg('sendingName','collectionName'),
 '"Burying " + displayName':msg('buryingName','displayName'),
 '"Burying " + collectionName':msg('buryingName','collectionName'),
 '"Bury " + displayName + "?"':msg('buryName','displayName'),
 '"✓ Transferred — " + displayName + " now belongs to " + Util.shorten(to) + "."':msg('transferred','displayName','to'),
 '"✝ Buried — " + displayName + " is at the graveyard, unspendable forever."':msg('buriedName','displayName'),
 '"Burying sends " + displayName + " to the graveyard — an address whose contract is RETURN FALSE. Nobody, ever, can spend it again.\\n\\nType the collection name to confirm:\\n"\n                + collectionName':msg('buryHelp','displayName','collectionName'),
 '"Sends every stamped item of “" + collectionName + "” that you still hold to one address.\\n\\nEach item is its own transaction — the chain requires that, because every item carries a different sealed identity — so this takes a few minutes and the items arrive one by one.\\n\\nUnstamped items and items you have already sent are skipped."':msg('collectionTransferHelp','collectionName'),
 '"This buries EVERY item of “" + collectionName + "” that you still hold — one transaction each, all to the graveyard address whose contract is RETURN FALSE. They can never be spent, moved or recovered by anyone, including you.\\n\\nItems already transferred to someone else are not affected.\\n\\nType the collection name to confirm:\\n" + collectionName':msg('collectionBuryHelp','collectionName','collectionName'),
 '"Nothing sendable here — " + skipped + " item(s) were skipped as unstamped or unsafe to replay."':msg('skippedUnsafe','skipped'),
 '"Could not list the items: " + message':msg('listItemsFailed','NftMessages.message(message)'),
 '"Could not list the coins: " + message':msg('listCoinsFailed','NftMessages.message(message)'),
 '"Failed: " + message':msg('failed','NftMessages.message(message)'),
 '"Sending " + (i + 1) + " of " + coins.size()\n                + (idx == null ? "" : "  (#" + idx + ")") + "…"':msg('sendingItem','i + 1','coins.size()','idx == null ? "" : "  (#" + idx + ")"'),
 '"Burying " + (i + 1) + " of " + coins.size() + "…"':msg('buryingItem','i + 1','coins.size()'),
 '"Posted " + done[0] + " of " + coins.size() + " burials.\\n\\nThe chain has the last word — the items disappear from your wallet as each spend confirms, over the next few blocks."':msg('postedBurials','done[0]','coins.size()'),
 '"Still unconfirmed after " + WATCH_TRIES\n                            + " checks — the spend may have been rejected. The coin is still yours if it stays in the coin list."':msg('unconfirmedChecks','WATCH_TRIES'),
 }
 for old,new in dynamic.items():s=change(s,old,new)
 start=s.index('            setMessage(progress, "Posted " + done[0]');end=s.index('            makeDismissable(progress);',start)
 s=s[:start]+'''            setMessage(progress, Language.join(%s,
                    skipped > 0 ? %s : "", "\\n\\n",
                    failed > 0 && firstError[0] != null ? Language.join(%s, "\\n\\n") : "",
                    %s));
'''%(msg('postedTransfers','done[0]','coins.size()'),msg('skippedCount','skipped'),msg('failedFirst','failed','NftMessages.message(firstError[0])'),msg('chainDecides'))+s[end:]
 for k,key in labels.items():
  en=catalog[key]['en']
  if '{' not in en and json.dumps(en,ensure_ascii=False) in s:
   s=change(s,json.dumps(en,ensure_ascii=False),msg(k))
 for old,new in [('String displayName','CharSequence displayName'),('Sheet.Progress progress, String successMsg','Sheet.Progress progress, CharSequence successMsg'),('String title, String msg','CharSequence title, CharSequence msg'),('Sheet.Progress d, String msg','Sheet.Progress d, CharSequence msg')]:s=change(s,old,new)
 s=change(s,'        scan.setTextColor(Design.accent());','        scan.setTextColor(Design.accentText());\n        scan.setMinHeight(dp(act,48)); scan.setMinWidth(dp(act,48)); scan.setGravity(Gravity.CENTER);\n        Language.description(scan, '+msg('scan')+');')
 s=change(s,'setHintTextColor(Design.dim2())','setHintTextColor(Design.dim())')
 s=re.sub(r'\b([A-Za-z_][A-Za-z_0-9]*)\.setText\(',r'Language.setText(\1, ',s)
 s=re.sub(r'\b([A-Za-z_][A-Za-z_0-9]*)\.setHint\(',r'Language.hint(\1, ',s)
 p.write_text(s)
 # Keep the typed collection-name confirmation raw; use provenance only for single-item display names.
 p=folder/'NftViewer.java';s=p.read_text();s=change(s,'StateNftActions.transferDialog(act, it.tokenid, it.name, it.coin.raw)','StateNftActions.transferDialog(act, it.tokenid, NftText.galleryName(it), it.coin.raw)');p.write_text(s)
 p=folder/'CoinDetailDialog.java';s=p.read_text();s=change(s,'String display = collection + (idx != null ? " #" + idx : "");','CharSequence display = Language.join(NftText.collection(meta, tok), idx != null ? " #" + idx : "");');p.write_text(s)
