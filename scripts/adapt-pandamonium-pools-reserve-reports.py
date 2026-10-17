"""Reuse immutable AtomiX report composition; keep reserve command/validation code intact."""
from pathlib import Path

def adapt(root):
 base=root/'pandapools/src/main/java/com/eurobuddha/pandapools'
 def change(s,old,new,count=1):
  assert s.count(old)==count,('Reserve report source drift',old,s.count(old));return s.replace(old,new)
 p=base/'ReserveRecovery.java';s=p.read_text()
 s=change(s,'funds are gone. "','funds are gone.\\n"')
 s=change(s,'String.join(" ", notes)','String.join("\\n", notes)')
 # These callbacks carry raw node/archive diagnostics. Quote as one line, retaining a reversible payload.
 for prefix in ['Local lookup','Archive lookup','Archive status','Archive export','Coin import','Coin proof check']:
  s=change(s,'notes.add("'+prefix+': " + m)','notes.add("'+prefix+': " + JSONObject.quote(m))')
 p.write_text(s)
 p=base/'Recovery.java';s=p.read_text()
 s=change(s,'final String label = addr.isEmpty() ? "pool" : addr;','final String label = addr.isEmpty() ? "pool" : FundingCoins.hex(addr) ? addr : JSONObject.quote(addr);')
 p.write_text(s)
 p=base/'MyLpView.java';s=p.read_text()
 s=change(s,'final StringBuilder log = new StringBuilder();','final Language.TextBuilder log = new Language.TextBuilder();')
 s=change(s,'private void autoWithdraw(List<Pool> verified, StringBuilder log,','private void autoWithdraw(List<Pool> verified, Language.TextBuilder log,')
 s=change(s,'log.append(line).append(\'\\n\'); status(Language.msg(l10n_pools_recovery_restore_progress, PoolsMessages.message(line)));','CharSequence translated = PoolsMessages.report(line); log.append(translated).append(\'\\n\'); status(Language.msg(l10n_pools_recovery_restore_progress, translated));')
 s=change(s,'log.append(line).append(\'\\n\');','log.append(PoolsMessages.message(line)).append(\'\\n\');')
 s=change(s,'PoolsMessages.report(log.toString().trim())','log.build()',2)
 p.write_text(s)
