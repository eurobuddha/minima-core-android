"""Keep original quote assembly and execution callbacks; bind its application-owned text."""
from pathlib import Path

def adapt(root):
 base=root/'pandadex/src/main/java/com/eurobuddha/pandadex'
 def change(s,old,new,count=1):
  assert s.count(old)==count,('PandaDEX quote source drift',old,s.count(old))
  return s.replace(old,new)
 p=base/'DexMessages.java';s=p.read_text()
 # Reuse the exact PandaPools report separator/line binding for app-generated quotes only.
 pools=(root/'pandapools/src/main/java/com/eurobuddha/pandapools/PoolsMessages.java').read_text()
 start=pools.index('    public static CharSequence report(String value) {');end=pools.index('    public static CharSequence confirmation(',start)
 helper=pools[start:end].replace('report(String value)','quote(String value)')
 s=change(s,'    public static CharSequence message(CharSequence value)',
          '    /** Internally generated quote lines only; never split unknown diagnostics or user text. */\n'+helper+'    public static CharSequence message(CharSequence value)')
 p.write_text(s)
 p=base/'MainActivity.java';s=p.read_text()
 for method,planType,nextMarker in [('confirmSweep','SweepPlanner.Plan','    public void confirmComposite'),('confirmComposite','CompositeRouter.Plan','    /**\n     * Record MY taker fills')]:
  start=s.index('    public void '+method);end=s.index(nextMarker,start);part=s[start:end]
  a=part.index('        StringBuilder sb = new StringBuilder();');b=part.index('        new AlertDialog.Builder',a)
  body=part[a:b]
  name='sweepMessage'if method=='confirmSweep'else'compositeMessage'
  helper='    static CharSequence '+name+'('+planType+' plan, boolean buy, BigDecimal rest, BigDecimal price) {\n'+body+'        return DexMessages.quote(sb.toString());\n    }\n\n'
  part=part[:a]+part[b:]
  part=change(part,'new AlertDialog.Builder(this, Design.dialogTheme())','new LanguageDialog(this, Design.dialogTheme())')
  part=change(part,'.setTitle(buy ? "Confirm buy" : "Confirm sell")','.setTitle(DexMessages.message(buy ? "Confirm buy" : "Confirm sell"))')
  part=change(part,'.setMessage(sb.toString())','.setMessage('+name+'(plan, buy, rest, price))')
  part=change(part,'.setPositiveButton("Execute",','.setPositiveButton(DexMessages.message("Execute"),')
  part=change(part,'.setNegativeButton("Cancel",','.setNegativeButton(DexMessages.message("Cancel"),')
  s=s[:start]+helper+part+s[end:]
 start=s.index('    public void reviewUnresolvedTrade()');end=s.index('    private void failAwaitingFill()',start);part=s[start:end]
 part=change(part,'new AlertDialog.Builder(this, Design.dialogTheme())','new LanguageDialog(this, Design.dialogTheme())')
 part=change(part,'.setTitle("Trade still being checked")','.setTitle(DexMessages.message("Trade still being checked"))')
 a=part.index('                .setMessage(');b=part.index('                .setPositiveButton',a)
 part=part[:a]+'                .setMessage(Language.msg(l10n_dex_trade_checking_body, awaitingTxpowid))\n'+part[b:]
 for label in ['Check now','Close']:part=change(part,'("'+label+'",','(DexMessages.message("'+label+'"),')
 s=s[:start]+part+s[end:];p.write_text(s)
