"""Reuse checked decimal input and retained dialogs for guided swap amounts."""
from pathlib import Path
import re

def adapt(root):
 p=root/'atomix/src/main/java/com/eurobuddha/atomix/MainActivity.java';s=p.read_text()
 def edit(name,transform):
  nonlocal s
  m=re.search(r'^    private [^\n=;]+\b'+name+r'\([^\n]*',s,re.M);assert m,name
  start=m.start();brace=s.index('{',m.start())
  token=re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[{}]')
  depth=0;end=None
  for t in token.finditer(s,brace):
   if t[0]=='{':depth+=1
   elif t[0]=='}':
    depth-=1
    if depth==0:end=t.end();break
  assert end,name
  part=s[start:end];updated=transform(part);s=s[:start]+updated+s[end:]

 def rep(part,old,new):
  assert old in part,('OTC method drift',old);return part.replace(old,new)


 def quotes(part,field,var,result,expression):
  part=rep(part,'        try {','        if (!positiveSwapAmount('+field+') || !Double.isFinite(price) || price <= 0) return null;\n        try {')
  part=rep(part,'new BigDecimal('+field+'.trim())','new BigDecimal(MinimaHtlc.grain('+field+'))')
  part=rep(part,expression,expression+'\n            if ('+result+'.signum() <= 0 || !Double.isFinite('+result+'.doubleValue())) return null;')
  return part
 edit('computeUsdt',lambda p:quotes(p,'minimaAmount','m','usdt','            BigDecimal usdt = m.multiply(BigDecimal.valueOf(price)).setScale(6, RoundingMode.DOWN);'))
 edit('computeMinima',lambda p:quotes(p,'usdtAmount','u','m','            BigDecimal m = u.divide(BigDecimal.valueOf(price), 6, RoundingMode.DOWN);'))
 def ceil(part):
  part=rep(part,'minima == null ||','!positiveSwapAmount(minima) ||')
  part=rep(part,'if (u.signum() <= 0)','if (u.signum() <= 0 || !Double.isFinite(u.doubleValue()))')
  return part
 edit('ceilUsdt',ceil)
 def review(part):
  part=rep(part,'        final String minima, usdt;','        if (!positiveSwapAmount(sendStr)) { toast(Language.msg(l10n_atomix_swap_valid)); return; }\n        final String minima, usdt;')
  return rep(part,'else { usdt = sendStr; minima = computeMinima(sendStr, price); }','else { usdt = MinimaHtlc.grain(sendStr); minima = computeMinima(usdt, price); }')
 edit('reviewSwap',review)
 def cta(part):
  part=rep(part,'        scanOrderBook();','        if (!positiveSwapAmount(minimaStr)) { toast(Language.msg(l10n_atomix_swap_valid_coin, ccy())); return; }\n        scanOrderBook();')
  return rep(part,'bestMaker == null || bestPrice <= 0','bestMaker == null || !Double.isFinite(bestPrice) || bestPrice <= 0')
 edit('onSwapCta',cta)
 def plan(part):
  part=rep(part,'        plan.slippagePct = slippage * 100;','        if (!positiveSwapAmount(targetStr) || !Double.isFinite(slippage) || slippage < 0 || slippage > 0.5) { plan.stopReason = "book-exhausted"; return plan; }\n        plan.slippagePct = slippage * 100;')
  part=rep(part,'            if (price <= 0) continue;','            if (!Double.isFinite(price) || price <= 0) continue;')
  part=rep(part,'            if (capMinima <= EPS) continue;','            if (!Double.isFinite(capMinima) || capMinima <= EPS) continue;')
  part=rep(part,'            if (bestPrice <= 0) bestPrice = price;', '            if (!Double.isFinite(plan.filledMinima + leg.minimaD) || !Double.isFinite(plan.totalUsdt + leg.usdtD)) { plan.partial = true; break; }\n            if (bestPrice <= 0) bestPrice = price;')
  return part
 edit('buildSweepPlan',plan)
 def read_slip(part):
  return rep(part,'return prefs.getFloat("buy_slippage_pct", 4.2f) / 100.0;', '''double pct = prefs.getFloat("buy_slippage_pct", 4.2f);
        return Double.isFinite(pct) && pct >= 0.1f && pct <= 50 ? pct / 100.0 : 0.042;''')
 edit('buySlippage',read_slip)
 edit('setBuySlippage',lambda p:rep(p,'        prefs.edit()', '        if (!Double.isFinite(pct)) return;\n        prefs.edit()'))
 def custom(part):
  part=rep(part,'        dialog()','        final AlertDialog editor = dialog()')
  part=rep(part,'.setPositiveButton(Language.msg(l10n_atomix_swap_set), (d, w) -> setBuySlippage(parseD(e.getText().toString(), 4.2)))','.setPositiveButton(Language.msg(l10n_atomix_swap_set), null)')
  return rep(part,'                .show();','''                .show();
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            double pct = MarketDraft.number(e.getText().toString(), false, Double.NaN);
            if (!Double.isFinite(pct)) {
                Language.setText(h, Language.msg(l10n_atomix_market_invalid_number));
                h.setTextColor(Design.RED());
                return;
            }
            setBuySlippage(pct);
            editor.dismiss();
        });''')
 edit('customSlippageDialog',custom)
 def take(part):
  part=rep(part,'if (price <= 0) return;', 'if (!Double.isFinite(price) || price <= 0) return;')
  part=rep(part,'        dialog()','        final AlertDialog editor = dialog()')
  begin=part.index('                .setPositiveButton(Language.msg(l10n_atomix_market_start), (d, w) -> {')
  end=part.index('                })\n                .setNegativeButton',begin)
  callback=part[begin+len('                .setPositiveButton(Language.msg(l10n_atomix_market_start), (d, w) -> {'):end]
  callback=rep(callback,'                    String minima = amt.getText().toString().trim();','''                    String raw = amt.getText().toString();
                    if (!positiveSwapAmount(raw)) { Language.setText(out, Language.msg(l10n_atomix_market_amount_invalid, ccy())); return; }
                    String minima = MinimaHtlc.grain(raw);''')
  callback=rep(callback,'                    if (usdt == null) { toast(Language.msg(l10n_atomix_market_amount_invalid, ccy())); return; }','                    if (usdt == null) { Language.setText(out, Language.msg(l10n_atomix_market_amount_invalid, ccy())); return; }')
  part=part[:begin]+'                .setPositiveButton(Language.msg(l10n_atomix_market_start), null)\n'+part[end+len('                })\n'):]
  callback='\n'.join(line[8:] if line.startswith('        ') else line for line in callback.splitlines())
  return rep(part,'                .show();','                .show();\n        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {'+callback+'\n            editor.dismiss();\n        });')
 edit('takeOrderDialog',take)
 helper='''    /** Match the existing six-decimal trade grain before a quote or review is offered. */
    private static boolean positiveSwapAmount(String raw) {
        double value = MarketDraft.number(raw, false, Double.NaN);
        return Double.isFinite(value) && value > 0 && new BigDecimal(MinimaHtlc.grain(raw)).signum() > 0;
    }

'''
 anchor='    /** USDT side for a mxUSDT amount at a given price (USDT per mxUSDT). */'
 assert s.count(anchor)==1;s=s.replace(anchor,helper+anchor)
 p.write_text(s)
