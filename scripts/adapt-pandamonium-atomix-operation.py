"""Localize AtomiX operation displays without changing stored status or callbacks."""
from pathlib import Path
import importlib.util,json,re

def adapt(root):
 project=Path(__file__).resolve().parents[1]
 labels=json.loads((project/'localization/atomix-operation-labels.json').read_text())
 catalog=json.loads((project/'localization/catalog.json').read_text())
 folder=root/'atomix/src/main/java/com/eurobuddha/atomix'
 p=folder/'MainActivity.java';s=p.read_text()
 def msg(key,*args):
  resource='l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',labels[key]).replace('.','_').lower()
  return 'Language.msg('+resource+('' if not args else ', '+', '.join(args))+')'
 def change(old,new):
  nonlocal s
  assert old in s,('AtomiX operation drift',old)
  s=s.replace(old,new)
 change('Language.setText(st, MarketMessages.message(orderStatus));','Language.setText(st, OperationMessages.message(orderStatus));')
 change('Language.setText(stt, orderStatus);','Language.setText(stt, OperationMessages.message(orderStatus));')
 change('toast("Wallet coins are being prepared — try the sell again after confirmation.");','toast('+msg('preparing')+');')
 # Only these two UI methods are edited. Error callback values stay literal.
 start=s.index('    private void consolidateDialog()');end=s.index('    // ---- Wallet tab ----',start)
 part=s[start:end]
 def rep(old,new):
  nonlocal part
  assert old in part,('AtomiX consolidation drift',old)
  part=part.replace(old,new)
 rep('"Consolidate " + label',msg('title','label'))
 a=part.index('.setMessage(')+len('.setMessage(');b=part.index(')\n                    .setNeutralButton',a)
 part=part[:a]+msg('body','b.coins','cap','runs > 0 ? '+msg('runs','runs')+' : '+msg('within'))+part[b:]
 for raw,key in [('Preview','preview'),('Run once','run'),('Cancel','cancel')]:rep('("'+raw+'",','('+msg(key)+',')
 rep('showText("Consolidate", e)','showText('+msg('consolidate')+', OperationMessages.message(e))')
 for raw,key in [('Trading currency changed; reopen Consolidate','changed'),("Halted: app keys don't match your node",'halted'),('Wallet busy — wait for the current lock or consolidation','busy')]:rep('toast("'+raw+'")','toast('+msg(key)+')')
 rep('showText("Consolidation preview — no transaction sent", r.toString())','showText('+msg('previewTitle')+', r.toString())')
 rep('showText("Consolidation submitted",','showText('+msg('submittedTitle')+',')
 rep('"Coins: " + b.coins + " → " + after.coins + " · waiting for new coins to age 3 blocks.\\n\\nTransaction: " + txpowid',msg('submitted','b.coins','after.coins','txpowid'))
 rep('"Refresh your balance after confirmation.\\nTransaction: " + txpowid',msg('refresh','txpowid'))
 s=s[:start]+part+s[end:];p.write_text(s)
 loader=importlib.util.spec_from_file_location('operation_message_builder',project/'scripts/adapt-pandamonium-wallet-error-languages.py')
 module=importlib.util.module_from_spec(loader);loader.loader.exec_module(module)
 source=module.build_source(json.loads((project/'localization/atomix-operation-messages.json').read_text()),catalog)
 source=source.replace('package com.eurobuddha.wallet;','package com.eurobuddha.atomix;').replace('WalletMessages','OperationMessages').replace('Explicit wallet failures only.','Explicit operation progress and consolidation display only.').replace('adapt-pandamonium-wallet-error-languages.py from localization/wallet-errors.json','adapt-pandamonium-atomix-operation.py from localization/atomix-operation-messages.json')
 # Reuse the existing market mapper after trying operation messages. Captured diagnostics stay literal.
 source=source.replace('        return value;\n    }','        return MarketMessages.message(value);\n    }')
 (folder/'OperationMessages.java').write_text(source)
