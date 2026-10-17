"""Pool calculator and statement controls use the shared live language bindings."""
from pathlib import Path
import json,re

def adapt(root):
 project=Path(__file__).resolve().parents[1]
 spec=json.loads((project/'localization/pools-calculator-messages.json').read_text());fixed=spec['fixed'];keys=spec['keys']
 def resource(key):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()
 def msg(key,*args):return 'Language.msg('+resource(keys[key])+(''if not args else ', '+', '.join(args))+')'
 def change(s,old,new,count=1):
  assert s.count(old)==count,('Calculator/statement source drift',old,s.count(old));return s.replace(old,new)
 token=re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"')
 def labels(s):
  # Same literal tokenizer as the create forms: quoted '+' is data, not concatenation.
  tokens=list(token.finditer(s));out=[];pos=0;i=0
  while i<len(tokens):
   t=tokens[i];start=t.start();end=t.end();raw=t[0]
   if not raw.startswith('"'):i+=1;continue
   value=json.loads(raw);j=i+1
   while j<len(tokens) and tokens[j][0].startswith('"') and re.fullmatch(r'\s*\+\s*',s[end:tokens[j].start()]):
    value+=json.loads(tokens[j][0]);end=tokens[j].end();j+=1
   out.append(s[pos:start]);out.append('Language.msg('+resource(fixed[value])+')'if value in fixed else s[start:end]);pos=end;i=j
  out.append(s[pos:]);return ''.join(out)
 base=root/'pandapools/src/main/java/com/eurobuddha/pandapools'
 p=base/'PoolCalcDialog.java';s=p.read_text()
 s=change(s,'import android.app.AlertDialog;','import com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;')
 s=change(s,'private final Context ctx;','private final MainActivity ctx;')
 s=change(s,'private PoolCalcDialog(Context c)','private PoolCalcDialog(MainActivity c)')
 s=change(s,'public static void show(Context c, Pool seed)','public static void show(MainActivity c, Pool seed)')
 for old,new in [('tok + " you add"',msg('TokenAdd','tokenLabel()')),('"MINIMA price in " + tok',msg('Price','tokenLabel()')),('"Volume traded through the pool, in " + tok + " value"',msg('Volume','tokenLabel()')),('tok + " in pool"',msg('TokenPool','tokenLabel()')),('"Ratio MINIMA : " + tok',msg('Ratio','tokenLabel()'))]:
  s=change(s,old,new,2 if 'in pool"'in old or 'Ratio MINIMA'in old else 1)
 s=change(s,'tok = t.isEmpty() ? "token" : t;','tok = t;')
 s=change(s,'    private void setPriceFromMultiple(double m) {','    private CharSequence tokenLabel() { return tok.isEmpty() ? '+msg('Token')+' : tok; }\n\n    private void setPriceFromMultiple(double m) {')
 s=change(s,'        double x0 = num(inX), y0 = num(inY), p = num(inP), fee = num(inFee), vol = num(inVol);','''        // Relabel the existing controls; never rewrite raw amounts or the token-symbol field.
        Language.setText(labelOf(inY), '''+msg('TokenAdd','tokenLabel()')+''');
        Language.setText(labelOf(inP), '''+msg('Price','tokenLabel()')+''');
        Language.setText(labelOf(inVol), '''+msg('Volume','tokenLabel()')+''');
        double x0 = num(inX), y0 = num(inY), p = num(inP), fee = num(inFee), vol = num(inVol);''')
 s=change(s,'"Enter a MINIMA amount, a " + tok + " amount and a price above zero."',msg('Required','tokenLabel()'))
 s=change(s,'"Entry price " + PoolCalc.fmtPrice(r.entryPrice) + " " + tok + " per MINIMA  ·  K " + PoolCalc.fmt(r.k)\n                + "  ·  " + PoolCalc.fmt(x0 / y0) + " : 1 MINIMA per " + tok + "  ·  entry value " + PoolCalc.fmt(2 * y0) + " " + tok',msg('Derived','PoolCalc.fmtPrice(r.entryPrice)','tokenLabel()','PoolCalc.fmt(r.k)','PoolCalc.fmt(x0 / y0)','PoolCalc.fmt(2 * y0)'))
 s=change(s,'PoolCalc.fmt(r.minima) + "  (" + PoolCalc.fmt(r.minima / x0 * 100) + " % of what you added)"',msg('ReserveValue','PoolCalc.fmt(r.minima)','PoolCalc.fmt(r.minima / x0 * 100)'))
 s=change(s,'PoolCalc.fmt(r.token) + "  (" + PoolCalc.fmt(r.token / y0 * 100) + " % of what you added)"',msg('ReserveValue','PoolCalc.fmt(r.token)','PoolCalc.fmt(r.token / y0 * 100)'))
 s=change(s,'PoolCalc.fmt(r.ratio) + " : 1  (always 1 ÷ price)"',msg('RatioValue','PoolCalc.fmt(r.ratio)'))
 s=change(s,'PoolCalc.fmt(r.value) + " " + tok + "  =  " + PoolCalc.fmt(r.token) + " in MINIMA + " + PoolCalc.fmt(r.token) + " in " + tok',msg('PoolValue','PoolCalc.fmt(r.value)','tokenLabel()','PoolCalc.fmt(r.token)'))
 s=change(s,'(r.vsHoldPct > 0.005 ? "+" : "") + PoolCalc.fmt(r.vsHoldPct) + " %  (holding would be worth " + PoolCalc.fmt(r.hold) + " " + tok + ")"',msg('HoldValue','(r.vsHoldPct > 0.005 ? "+" : "") + PoolCalc.fmt(r.vsHoldPct)','PoolCalc.fmt(r.hold)','tokenLabel()'))
 s=change(s,'PoolCalc.fmt(r.feesKept) + " " + tok','Language.join(PoolCalc.fmt(r.feesKept), " ", tokenLabel())')
 s=change(s,'PoolCalc.fmt(r.breakEvenVolume) + " " + tok','Language.join(PoolCalc.fmt(r.breakEvenVolume), " ", tokenLabel())')
 s=change(s,'new AlertDialog.Builder(ctx)','ctx.languageDialog()')
 for old,new in [('text(String s,','text(CharSequence s,'),('section(String s)','section(CharSequence s)'),('field(LinearLayout box, String label, String value)','field(LinearLayout box, CharSequence label, String value)'),('kv(LinearLayout box, String label)','kv(LinearLayout box, CharSequence label)')]:s=change(s,old,new)
 # Canvas text is resolved on every draw; configuration changes invalidate without recalculating inputs.
 s=change(s,'        @Override protected void onDraw(Canvas cv) {','''        @Override protected void onConfigurationChanged(android.content.res.Configuration config) {
            super.onConfigurationChanged(config);
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {''')
 s=change(s,'"MINIMA in pool →"',msg('AxisMinima')+'.toString()')
 s=change(s,'tok + " in pool ↑"',msg('AxisToken','tok.isEmpty() ? '+msg('Token')+' : tok')+'.toString()')
 s=change(s,'"now " + PoolCalc.fmtPrice(s.p) + ": " + PoolCalc.fmt(s.x) + " · " + PoolCalc.fmt(s.y)',msg('Now','PoolCalc.fmtPrice(s.p)','PoolCalc.fmt(s.x)','PoolCalc.fmt(s.y)')+'.toString()')
 s=labels(s)
 # Bind display views only. e/inP are editable values and retain their existing handlers.
 s=re.sub(r'\b(derived|outMinima|outToken|outRatio|outValue|outHold|outMove|outFees|outK|outBreakEven|t)\.setText\(',r'Language.setText(\1, ',s)
 s=re.sub(r'labelOf\((outToken|outRatio)\)\.setText\(',r'Language.setText(labelOf(\1), ',s)
 s=change(s,'            c.setPadding(Ui.dp(ctx, 12),','            c.setMinHeight(Ui.dp(ctx, 48)); c.setMinWidth(Ui.dp(ctx, 48)); c.setGravity(Gravity.CENTER);\n            c.setPadding(Ui.dp(ctx, 12),')
 p.write_text(s)
 p=base/'MyLpView.java';s=p.read_text();start=s.index('    private void showStatementDialog()');end=s.index('    private void showRecoveryDialog()',start);part=s[start:end]
 a=part.index('        TextView t = text(');b=part.index('\n        box.addView(t);',a)
 part=part[:a]+'        TextView t = text('+msg('StatementBody')+', Design.text(), 13, false);'+part[b:]
 part=change(part,'"Export failed: " + message',msg('ExportFailure','PoolsMessages.message(message)'))
 part=change(part,'"Saved  ·  " + ExportWriter.describe(report)',msg('Saved','statementDescription(report)'))
 part=change(part,'ExportWriter.describe(report)','statementDescription(report).toString()')
 part=labels(part)
 # Serialize a share intent at the selected language; its raw CSV and protocol metadata stay unchanged.
 part=change(part,'i.putExtra(android.content.Intent.EXTRA_SUBJECT, '+msg('Subject')+');','i.putExtra(android.content.Intent.EXTRA_SUBJECT, '+msg('Subject')+'.toString());')
 part=change(part,'new AlertDialog.Builder(act)','act.languageDialog()')
 part=change(part,'.setView(box)','.setView(wrapScroll(box))')
 part+='''    private CharSequence statementDescription(PoolStatement.Report report) {
        return Language.join('''+msg('Summary','report.poolCount','report.tradeRows')+''',
                report.unreconciled > 0 ? '''+msg('Flagged','report.unreconciled')+''' : "");
    }

'''
 s=s[:start]+part+s[end:];p.write_text(s)
