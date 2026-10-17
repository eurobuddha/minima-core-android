"""AtomiX swap display bindings. Preserve quote, routing, amounts and submission callbacks."""
from pathlib import Path
import json,re,importlib.util

def adapt(root):
 project=Path(__file__).resolve().parents[1]
 labels=json.loads((project/'localization/atomix-swap-labels.json').read_text());catalog=json.loads((project/'localization/catalog.json').read_text())
 folder=root/'atomix/src/main/java/com/eurobuddha/atomix';p=folder/'MainActivity.java';s=p.read_text()
 def msg(key,*args):
  resource='l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',labels[key]).replace('.','_').lower()
  return 'Language.msg('+resource+('' if not args else ', '+', '.join(args))+')'
 def change(old,new):
  nonlocal s
  assert old in s,('AtomiX market drift',old);s=s.replace(old,new)
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


 def expression(part,start,new):
  a=part.index(start)+len(start);b=part.index(');',a);return part[:a]+new+part[b:]
 def screen(part):
  pairs=[('"Swap " + ccy() + " ⇄ USDT"',msg('title','ccy()')),('"Sell " + ccy()',msg('sell','ccy()')),('"Buy " + ccy()',msg('buy','ccy()')),('"No one is quoting a " + (swapSell ? "buy" : "sell") + " price right now."','swapSell ? '+msg('noBuy')+' : '+msg('noSell'))]
  for a,b in pairs:part=rep(part,a,b)
  part=expression(part,'Language.setText(bp, ',msg('best','fmtPrice(price)','ccy()','abbrev(bestCap)','depth > bestCap + 1e-9 ? '+msg('depth','abbrev(depth)')+' : ""','sellMinima ? "" : '+msg('gasSuffix')))
  part=rep(part,'int fs = dp(38);','Language.description(flip, '+msg('flip')+');\n        int fs = dp(48);')
  part=rep(part,'decimalInput(amt);','decimalInput(amt); Language.description(amt, '+msg('send')+');')
  part=rep(part,'decimalInput(recv);','decimalInput(recv); Language.description(recv, '+msg('receive')+');')
  part=rep(part,'slip.setOrientation(LinearLayout.HORIZONTAL); slip.setGravity(Gravity.CENTER_VERTICAL);','slip.setOrientation(LinearLayout.VERTICAL);\n            LinearLayout options = new LinearLayout(this); options.setOrientation(LinearLayout.HORIZONTAL);')
  part=part.replace('slip.addView(slipPill(', 'options.addView(slipPill(')
  part=rep(part,'            card.addView(slip);','            slip.addView(options);\n            card.addView(slip);')
  return part
 edit('renderSwapTab',screen)
 edit('coinChip',lambda p:expression(p,'Language.setText(sub, ',msg('available','minima ? abbrev(parseD(minimaBal, 0)) : (usdt != null ? Util.tidyAmount(usdt) : "—")')))
 def single(part):
  part=rep(part,'"Enter an amount to " + (sellMinima ? "sell" : "spend")','sellMinima ? '+msg('enterSell')+' : '+msg('enterSpend'))
  part=rep(part,'"Best level takes up to " + abbrev(maxAmount) + " " + ccy() + " — reduce the amount"',msg('bestCap','abbrev(maxAmount)','ccy()'))
  a=part.index('        String msg = sellMinima');b=part.index('        modalOpen = true;',a)
  part=part[:a]+'        CharSequence msg = sellMinima ? '+msg('sellBody','minima','ccy()','usdt','fmtPrice(price)','ccy()','maker.signerPk','ccy()')+'\n                : '+msg('buyBody','minima','ccy()','usdt','fmtPrice(price)','ccy()','maker.signerPk')+';\n'+part[b:]
  return rep(part,'sellMinima ? "Review — Sell " + ccy() : "Review — Buy " + ccy()','sellMinima ? '+msg('reviewSell','ccy()')+' : '+msg('reviewBuy','ccy()'))
 edit('reviewSwap',single)
 def cta(part):
  for a,b in [
   ('"Enter how much " + ccy() + " to " + (sell ? "sell" : "buy")','sell ? '+msg('enterCoinSell','ccy()')+' : '+msg('enterCoinBuy','ccy()')),
   ('"Enter a valid " + ccy() + " amount."',msg('validCoin','ccy()')),
   ('"You only have ~" + abbrev(parseD(minimaBal, 0)) + " " + ccy() + " available to sell."',msg('onlyHave','abbrev(parseD(minimaBal, 0))','ccy()')),
   ('"Need ≈ " + trimSig(plan.totalUsdt) + " USDT for " + abbrev(plan.filledMinima) + " " + ccy() + " (within "\n                    + pctStr(buySlippage() * 100) + "%) — you have " + trimSig(haveUsdt) + "."',msg('need','trimSig(plan.totalUsdt)','abbrev(plan.filledMinima)','ccy()','pctStr(buySlippage() * 100)','trimSig(haveUsdt)'))]:part=rep(part,a,b)
  return part
 edit('onSwapCta',cta)
 def sweep(part):
  part=expression(part,'Language.setText(head, ','sell ? '+msg('sweepSellHead','abbrev(plan.filledMinima)','ccy()','plan.legs.size()')+' : '+msg('sweepBuyHead','abbrev(plan.filledMinima)','ccy()','trimSig(plan.totalUsdt)','plan.legs.size()'))
  part=expression(part,'Language.setText(metrics, ',msg('metrics','fmtPrice(plan.avgPrice)','fmtPrice(plan.worstPrice)','ccy()','!sell && plan.slippagePct > 0 ? '+msg('within','pctStr(plan.slippagePct)')+' : ""'))
  part=expression(part,'Language.setText(r, ',msg('part','i + 1','leg.minima','ccy()','fmtPrice(leg.price)','leg.usdt','shortAddr(leg.maker.signerPk)'))
  part=expression(part,'Language.setText(totals, ','sell ? '+msg('totalSell','trimSig(plan.filledMinima)','ccy()','trimSig(plan.totalUsdt)')+' : '+msg('totalBuy','trimSig(plan.totalUsdt)','trimSig(plan.filledMinima)','ccy()'))
  a=part.index('            String msg = "Fills');b=part.index('            Language.setText(pw, msg);',a)
  part=part[:a]+'            CharSequence msg = '+msg('partial','abbrev(plan.filledMinima)','abbrev(plan.target)','ccy()','!sell && "slippage".equals(plan.stopReason) ? '+msg('partialSlip','pctStr(plan.slippagePct)')+' : '+msg('partialBook'))+';\n'+part[b:]
  part=expression(part,'Language.setText(gas, ',msg('sweepGas','plan.legs.size()'))
  return part
 edit('reviewSweep',sweep)
 def stages(part):
  for a,b in [('ccy() + " ready to sell"',msg('readySell','ccy()')),('"Timed out — " + sw.sellAmount + " " + sw.sellToken + " refunded"',msg('stageRefund','sw.sellAmount','sw.sellToken')),('"Locked your " + sw.sellAmount + " " + sw.sellToken',msg('stageLock','sw.sellAmount','sw.sellToken')),('"Claim your " + sw.buyAmount + " " + sw.buyToken',msg('stageClaim','sw.buyAmount','sw.buyToken')),('"+ " + more + " more swap" + (more == 1 ? "" : "s") + " in flight — see Activity"',msg('more','more'))]:part=rep(part,a,b)
  return expression(part,'Language.setText(which, ','Language.join(sw.sellAmount, " ", sw.sellToken, "  →  ", sw.buyAmount, " ", sw.buyToken, "   ·   ", historyRole(sw.role))')
 edit('swapStages',stages)
 edit('customSlippageDialog',lambda p:expression(p,'Language.setText(h, ',msg('slippageHelp','ccy()')))
 # Source-specific typography aliases; no storage or protocol strings are translated.
 aliases={"Enter an amount — see exactly what you'll get at the best price.":'intro',"That's below the makers' minimum trade size.":'belowMin',"Parts run one at a time. If one can't start we stop and you keep the rest; unmatched parts auto-refund after the timelock.":'sweepNote','Counterparty key copied':'copied'}
 lookup={catalog[v]['en']:msg(k)for k,v in labels.items()if isinstance(catalog[v]['en'],str)and'{'not in catalog[v]['en']};lookup.update({raw:msg(key)for raw,key in aliases.items()})
 for name in ['renderSwapTab','reviewSwap','onSwapCta','sweepEmptyMsg','reviewSweep','swapStages','customSlippageDialog']:
  edit(name,lambda p:re.sub(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"',lambda m:m[0]if m[0].startswith('/')else lookup.get(json.loads(m[0]),m[0]),p))
 for name in ['segTab','slipPill','stagePill']:
  old=name+'(String label,';assert old in s;s=s.replace(old,name+'(CharSequence label,')
 s=s.replace('private String sweepEmptyMsg(', 'private CharSequence sweepEmptyMsg(')
 edit('slipPill',lambda p:rep(p,'p.setOnClickListener','p.setMinimumHeight(dp(48)); p.setMinimumWidth(dp(48));\n        p.setOnClickListener'))
 edit('stagePill',lambda p:rep(p,'row.addView(t);','row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));'))
 p.write_text(s)
