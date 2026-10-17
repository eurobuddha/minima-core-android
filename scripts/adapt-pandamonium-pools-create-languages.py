"""Create-pool display bindings. Keep amounts, guards, commands and receipts literal."""
from pathlib import Path
import json,re

def adapt(root):
 project=Path(__file__).resolve().parents[1]
 fixed=json.loads((project/'localization/pools-create-messages.json').read_text())['fixed']
 keys=json.loads((project/'localization/pools-labels.json').read_text())
 def resource(key):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()
 def msg(key,*args):return 'Language.msg('+resource(keys['create'+key])+(''if not args else ', '+', '.join(args))+')'
 def change(s,old,new,count=1):
  assert s.count(old)==count,('Create-pool source drift',old,s.count(old));return s.replace(old,new)
 token=re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"')
 def labels(s):
  # Join only actual adjacent Java literals; a quoted '+' is data, never an operator.
  tokens=list(token.finditer(s));out=[];pos=0;i=0
  while i<len(tokens):
   t=tokens[i];start=t.start();end=t.end();raw=t[0]
   if not raw.startswith('"'):i+=1;continue
   value=json.loads(raw);j=i+1
   while j<len(tokens) and tokens[j][0].startswith('"') and re.fullmatch(r'\s*\+\s*',s[end:tokens[j].start()]):
    value+=json.loads(tokens[j][0]);end=tokens[j].end();j+=1
   out.append(s[pos:start]);out.append('Language.msg('+resource(fixed[value])+')'if value in fixed else s[start:end]);pos=end;i=j
  out.append(s[pos:]);return ''.join(out)
 p=root/'pandapools/src/main/java/com/eurobuddha/pandapools/MyLpView.java';s=p.read_text()
 start=s.index('    // ---- create pool ----');end=s.index('    // ---- add liquidity ----',start);part=s[start:end]
 part=change(part,'"Your MINIMA / " + pendingLabel + " pool is still confirming (block " + Math.min(el, 3)\n                    + " of ~3) — wait for it to go live before creating another."',msg('Pending','pendingLabel','Math.min(el, 3)'))
 a=part.index('        one.setText(');b=part.index('\n        one.setPadding',a)
 part=part[:a]+'        Language.setText(one, '+msg('KeepRunning','PoolRefresher.REFRESH_BLOCKS')+');'+part[b:]
 a=part.index('        two.setText(');b=part.index('\n        two.setPadding',a)
 part=part[:a]+'        Language.setText(two, '+msg('KeepBackups')+');'+part[b:]
 a=part.index('            batt.setText(ok ?');b=part.index('\n            batt.setTextColor',a)
 part=part[:a]+'            Language.setText(batt, ok ? '+msg('BatteryExempt')+' : '+msg('BatteryRestricted')+');'+part[b:]
 part=change(part,'info("Get mxUSDT first", "PandaPools creates MINIMA / USDT pools — the pair with a live market price, so a pool always opens at the true rate. Your wallet holds no mxUSDT; receive some first, then create a pool.")','info("Get mxUSDT first", '+msg('GetTokenBody')+')')
 part=change(part,'String[] labels = new String[toks.size()];','CharSequence[] labels = new CharSequence[toks.size()];')
 part=change(part,'labels[i] = toks.get(i)[1];','labels[i] = '+msg('AvailableToken','toks.get(i)[3]','trim(Util.decOr(toks.get(i)[4], BigDecimal.ZERO))')+';')
 part=change(part,'"Could not read your balance: " + m',msg('BalanceError','PoolsMessages.message(m)'))
 part=change(part,'tokenName + " to provide  (≈ US$)"',msg('ProvideToken','tokenName'))
 part=change(part,'StringBuilder sb = new StringBuilder();','Language.TextBuilder sb = new Language.TextBuilder();')
 part=change(part,'sb.append("Price: ").append(trim8(p)).append(" USDT/MINIMA (").append(source[0]).append(")\\n");','sb.append('+msg('PricePreview','trim8(p)','createPriceSource(source[0])')+');')
 part=change(part,'sb.append("You have: ").append(trim(minimaAvail)).append(" MINIMA · ").append(trim(tokAvail)).append(" ").append(tokenName).append("\\n");','sb.append('+msg('BalancePreview','trim(minimaAvail)','trim(tokAvail)','tokenName')+');')
 part=change(part,'sb.append("Enter the USDT amount to provide (max ≈ ").append(trim(usdtCap)).append(").");','sb.append('+msg('EnterUpTo','trim(usdtCap)')+');')
 part=change(part,'sb.append("MINIMA required:  ").append(trim(minima)).append("\\n");','sb.append('+msg('MinimaRequired','trim(minima)')+');')
 part=change(part,'sb.append("Total pool value:  ≈ US$ ").append(trim2(u.multiply(TWO)));','sb.append('+msg('TotalValue','trim2(u.multiply(TWO))')+');')
 part=change(part,'sb.append("\\n⚠ Not enough MINIMA — reduce USDT to ≤ ").append(trim(usdtCap)).append(".");','sb.append('+msg('MinimaShort','trim(usdtCap)')+');')
 part=change(part,'sb.append("\\n⚠ You only have ").append(trim(tokAvail)).append(" ").append(tokenName).append(".");','sb.append('+msg('TokenShort','trim(tokAvail)','tokenName')+');')
 part=change(part,'info.setText(sb.toString());','Language.setText(info, sb.build());')
 part=change(part,'"Create MINIMA / " + tokenName + " pool"',msg('PoolTitle','tokenName'),3)
 # The third occurrence belongs to a persisted failed receipt: preserve its original English schema.
 part=change(part,'ActivityLog.recordFailed(act, ActivityLog.CREATE, '+msg('PoolTitle','tokenName')+', message);','ActivityLog.recordFailed(act, ActivityLog.CREATE, "Create MINIMA / " + tokenName + " pool", message);')
 a=part.index('        String priceLine = bootstrap');b=part.index('\n        new AlertDialog.Builder(act)',a)
 part=part[:a]+'''        CharSequence priceLine = bootstrap
                ? '''+msg('ManualPriceWarning')+'''
                : '''+msg('ReferencePrice','trim8(price)','createPriceSource(source)')+''';
        CharSequence msg = '''+msg('Review','tokenName','trim(minima)','trim(usdt)','priceLine')+';'+part[b:]
 a=part.index('        TextView intro = text("No market price is available');b=part.index('\n        intro.setPadding',a)
 part=part[:a]+'        TextView intro = text('+msg('ManualIntro','tokenName')+', Design.dim(), 12, false);'+part[b:]
 part=change(part,'tokenName + " to deposit"',msg('DepositToken','tokenName'))
 part=change(part,'preview.setText("Opening price:  " + trim8(price) + " " + tokenName + " / MINIMA\\n"\n                    + "Product floor (KMIN):  " + PoolCovenant.kmin(x, yc));','Language.setText(preview, '+msg('ManualPreview','trim8(price)','tokenName','PoolCovenant.kmin(x, yc)')+');')
 part=change(part,'"Creating your MINIMA / " + tokenName + " pool…"',msg('Creating','tokenName'))
 part=change(part,'"Submitted ✓ " + txpowid + " — your pool is confirming on-chain (usually 1–3 blocks). "\n                            + "It will appear below automatically; no need to resubmit."',msg('Submitted','txpowid'))
 part=change(part,'"Create: " + message',msg('Failure','PoolsMessages.message(message)'))
 part=change(part,'// The pool now exists on chain and its recipe is on this phone only. Force the off-device\n                    // export immediately: the window where the pool is unrecoverable starts NOW, and a user who\n                    // closes the app here has a live pool with no backup and no prompt telling them so.','// Save the new recipe immediately while creation is awaiting confirmation.')
 part=labels(part)
 # Bind labels and hints, never EditText contents or checkbox state.
 for target in ['fix','ack','manual','info','preview']:
  part=re.sub(r'\b'+target+r'\.setText\((Language\.msg\(l10n_\w+\))\);',r'Language.setText('+target+r', \1);',part)
 part=part.replace('final AlertDialog dlg = new AlertDialog.Builder(act)','final androidx.appcompat.app.AlertDialog dlg = act.languageDialog()')
 part=part.replace('new AlertDialog.Builder(act)','act.languageDialog()')
 helper='''    /** Only known application source labels translate; oracle values and unknown labels stay literal. */
    private CharSequence createPriceSource(String source) {
        if ("MEXC market".equals(source)) return '''+msg('MarketSource')+''';
        if ("live pools".equals(source)) return '''+msg('PoolSource')+''';
        return source;
    }

'''
 part=change(part,'    private static final class Anchor',helper+'    private static final class Anchor')
 s=s[:start]+part+s[end:]
 s=change(s,'private EditText field(LinearLayout box, String label, String hint)','private EditText field(LinearLayout box, CharSequence label, CharSequence hint)')
 s=change(s,'e.setHint(hint);','Language.hint(e, hint);')
 s=change(s,'private void toast(String s)','private void toast(CharSequence s)')
 p.write_text(s)
