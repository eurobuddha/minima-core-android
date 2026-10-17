"""Localize export presentation without changing its journal, ownership or provider writes."""
from pathlib import Path

def adapt(root):
 p=root/'pandadex/src/main/java/com/eurobuddha/pandadex/MainActivity.java';s=p.read_text()
 a=s.index('    private void renderExportSession()');b=s.index('    // ------------------------------------------------------------------ lifecycle',a)
 part=s[a:b]
 changes={
  'new AlertDialog.Builder(this,Design.dialogTheme()).setTitle("Trade export")':'new LanguageDialog(this,Design.dialogTheme()).setTitle(DexMessages.message("Trade export"))',
  '.setMessage(tradeExportSession.notice())':'.setMessage(DexMessages.message(tradeExportSession.notice()))',
  '.setPositiveButton("OK",':'.setPositiveButton(DexMessages.message("OK"),'
 }
 for old,new in changes.items():
  assert part.count(old)==1,('PandaDEX export source drift',old)
  part=part.replace(old,new)
 p.write_text(s[:a]+part+s[b:])
