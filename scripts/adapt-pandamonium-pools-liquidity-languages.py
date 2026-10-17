"""Liquidity forms reuse create-pool bindings without rebuilding inputs or changing transaction math."""
from pathlib import Path
import json,re

def adapt(root):
 project=Path(__file__).resolve().parents[1]
 fixed=json.loads((project/'localization/pools-liquidity-messages.json').read_text())['fixed']
 keys=json.loads((project/'localization/pools-labels.json').read_text())
 def resource(key):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()
 def msg(key,*args):return 'Language.msg('+resource(keys['liquidity'+key])+(''if not args else ', '+', '.join(args))+')'
 def change(s,old,new,count=1):
  assert s.count(old)==count,('Liquidity form source drift',old,s.count(old));return s.replace(old,new)
 token=re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"')
 def labels(s):
  def replace(m):
   if not m[0].startswith('"'):return m[0]
   value=json.loads(m[0]);return 'Language.msg('+resource(fixed[value])+')'if value in fixed else m[0]
  return token.sub(replace,s)
 p=root/'pandapools/src/main/java/com/eurobuddha/pandapools/MyLpView.java';s=p.read_text()
 start=s.index('    private void showAddDialog(');end=s.index('    // ---- backup & restore (recovery) ----',start);part=s[start:end]
 part=change(part,'"Balanced at the pool price: this also adds " + trim(t) + " " + p.tokenLabel()\n                        + ".\\nBoth sides are added in the pool\'s ratio so the price doesn\'t move."',msg('AddPreview','trim(t)','p.tokenLabel()'))
 part=change(part,'"Enter the MINIMA to add; the " + p.tokenLabel()\n                        + " side is set automatically at the pool price so the deposit stays balanced."',msg('AddPrompt','p.tokenLabel()'))
 part=change(part,'        box.addView(note);','        Language.setText(note, '+msg('AddPrompt','p.tokenLabel()')+');\n        box.addView(note);')
 part=change(part,'"Liquidity added ✓ " + txpowid + " — confirming on-chain."',msg('AddSubmitted','txpowid'))
 part=change(part,'"Add: " + message',msg('AddFailure','PoolsMessages.message(message)'))
 a=part.index('        blurb.setText(');b=part.index('\n        blurb.setPadding',a)
 part=part[:a]+'        Language.setText(blurb, '+msg('MigrateBody','Language.msg(l10n_pools_recovery_collect)')+');'+part[b:]
 part=change(part,'"New " + p.tokenLabel() + " reserve"',msg('NewToken','p.tokenLabel()'))
 part=change(part,'"Migrated ✓ " + txpowid + " — confirming on-chain."',msg('MigrateSubmitted','txpowid'))
 part=change(part,'"Migrate: " + message',msg('MigrateFailure','PoolsMessages.message(message)'))
 a=part.index('                .setMessage("This closes');b=part.index('\n                .setPositiveButton',a)
 part=part[:a]+'                .setMessage('+msg('CloseBody','p.tokenLabel()','trim(p.reserveM)','trim(p.reserveT)','p.oadr')+')'+part[b:]
 part=change(part,'"Pool closed ✓ " + txpowid + " — moving funds to a wallet address (confirming on-chain)…"',msg('CloseSubmitted','txpowid'))
 part=change(part,'"Close: " + message',msg('CloseFailure','PoolsMessages.message(message)'))
 part=change(part,'status(why.message())','status(PoolsMessages.message(why.message()))',2)
 part=change(part,'"Nothing collected.\\n\\n" + OwnerKeyRecovery.worst(blocked).message()',msg('CollectBlocked','PoolsMessages.message(OwnerKeyRecovery.worst(blocked).message())'))
 part=change(part,'"Submitted collection from " + addressesForwarded + " pool"\n                            + (addressesForwarded == 1 ? "" : "s") + " to your wallet. Check Activity for confirmation." + skipped','Language.join('+msg('CollectSubmitted','addressesForwarded')+', skipped)')
 part=change(part,'"No available, stateless coins to collect at your pool payout addresses." + skipped','Language.join('+msg('NothingAvailable')+', skipped)')
 part=change(part,'"Collection could not finish: " + error',msg('CollectFailure','PoolsMessages.message(error)'))
 part=change(part,'"Submitted collection from " + addressesForwarded\n                        + " address(es). Other collection attempts failed: " + error',msg('CollectPartial','addressesForwarded','PoolsMessages.message(error)'))
 part=change(part,'"Re-published " + posted + " pool" + (posted == 1 ? "" : "s")\n                    + " to the registry so fresh nodes can find " + (posted == 1 ? "it" : "them") + "."',msg('ReannounceSubmitted','posted'))
 part=change(part,'"Refreshed " + posted + " pool" + (posted == 1 ? "" : "s")\n                    + " so " + (posted == 1 ? "it stays" : "they stay") + " visible to other devices."',msg('RefreshSubmitted','posted'))
 part=labels(part)
 part=re.sub(r'\bnote\.setText\((Language\.msg\([^;]+)\);',r'Language.setText(note, \1);',part)
 part=part.replace('new AlertDialog.Builder(act)','act.languageDialog()')
 part=change(part,'.setView(box)','.setView(wrapScroll(box))',2)
 # This comment describes the completion rule introduced by the reviewed collection adapter.
 part=change(part,' * an entry once the address reads back EMPTY. The immediate attempt below is an optimisation, not',' * an entry after verified withdrawal confirmation and a fresh empty-address read. This attempt is an optimisation, not')
 s=s[:start]+part+s[end:];p.write_text(s)
