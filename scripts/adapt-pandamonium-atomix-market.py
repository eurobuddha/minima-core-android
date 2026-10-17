"""AtomiX market/editor display bindings. Reuse the established dialog and diagnostic adapters."""
from pathlib import Path
import json,re,importlib.util

def adapt(root):
 project=Path(__file__).resolve().parents[1]
 labels=json.loads((project/'localization/atomix-market-labels.json').read_text());catalog=json.loads((project/'localization/catalog.json').read_text())
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

 def replace_expression(part,call,new):
  start=part.index(call)+len(call);end=part.index(');',start)
  return part[:start]+new+part[end:]
 def editor(part):
  part=replace_expression(part,'Language.setText(hint, ',msg('editorHelp','ccy()','sym','ccy()'))
  part=replace_expression(part,'Language.setText(pegHint, ','TradingContext.active().pricingParity ? '+msg('parityHelp')+' : '+msg('pegHelp','PriceOracle.source()','ccy()'))
  replacements={
   '"AUTO MARKET-MAKE — PEG TO " + PriceOracle.source()':msg('autoMarket','PriceOracle.source()'),
   '"Peg ladder to " + PriceOracle.source() + " (auto-reprice)"':msg('peg','PriceOracle.source()'),
   '"ASKS — you SELL " + ccy() + " (higher price)"':msg('asks','ccy()'),
   '"BIDS — you BUY " + ccy() + " (lower price)"':msg('bids','ccy()'),
   '"min " + ccy() + " / trade"':msg('minimum','ccy()'),
   '"Fetching " + PriceOracle.source() + " price…"':msg('fetching','PriceOracle.source()')}
  for a,b in replacements.items():part=rep(part,a,b)
  return part
 edit('editOrderDialog',editor)
 def preview(part):
  part=replace_expression(part,'Language.setText(pv, ',msg('crossed','fmtPrice(bestBid)','fmtPrice(bestAsk)'))
  start=part.index('        String bidS =');end=part.index('        pv.setTextColor(Design.DIM());',start)
  part=part[:start]+'        CharSequence bidS = nB > 0 ? '+msg('previewSide','nB','fmtPrice(bestBid)','abbrev(sumB)','ccy()')+' : '+msg('none')+';\n'+'        CharSequence askS = nA > 0 ? '+msg('previewSide','nA','fmtPrice(bestAsk)','abbrev(sumA)','ccy()')+' : '+msg('none')+';\n'+'        Language.setText(pv, '+msg('preview','bidS','askS')+');\n'+part[end:]
  return part
 edit('updateLadderPreview',preview)
 def market(part):return rep(part,'"Order book  ·  " + orderBook.size() + " live"',msg('book','orderBook.size()'))
 edit('renderMarketTab',market)
 def ladder(part):
  for a,b in {
   '"spread " + fmtPrice(bestAsk - bestBid) + " " + sym + "  ·  " + sym + " per " + ccy() + ", size in " + ccy()':msg('spread','fmtPrice(bestAsk - bestBid)','sym','sym','ccy()','ccy()'),
   '"SELL " + ccy() + " (bid)"':msg('sellBid','ccy()'),
   '"BUY " + ccy() + " (ask)"':msg('buyAsk','ccy()'),
   '"+ " + (rows - shown) + " more level" + (rows - shown == 1 ? "" : "s")':msg('more','rows - shown')}.items():part=rep(part,a,b)
  return part
 edit('orderLadder',ladder)
 def take(part):
  direction='sellMinima ? '+msg('takeSell','ccy()','symbol')+' : '+msg('takeBuy','ccy()','symbol')
  part=replace_expression(part,'Language.setText(info, ',msg('takeInfo',direction,'trim(price)','symbol','ccy()','abbrev(maxAmount)','ccy()','min > 0 ? '+msg('takeMin','trim(min)','ccy()')+' : ""'))
  part=rep(part,'ccy() + " to " + (sellMinima ? "sell" : "buy")','sellMinima ? '+msg('amountSell','ccy()')+' : '+msg('amountBuy','ccy()'))
  part=replace_expression(part,'Language.setText(out, ','usdt == null ? "" : (sellMinima ? '+msg('receive','usdt','symbol')+' : '+msg('pay','usdt','symbol')+')')
  part=rep(part,'"Counterparty\\n" + maker.signerPk',msg('counterparty','maker.signerPk'))
  part=rep(part,'(sellMinima ? "Sell " + ccy() : "Buy " + ccy()) + " · " + Util.shorten(maker.signerPk)','Language.join(sellMinima ? Language.msg(l10n_atomix_otc_sell, ccy()) : Language.msg(l10n_atomix_otc_buy, ccy()), " · ", Util.shorten(maker.signerPk))')
  for a,b in {
   '"Enter a valid " + ccy() + " amount"':msg('amountInvalid','ccy()'),
   '"This level takes up to " + abbrev(maxAmount) + " " + ccy() + " — more will be auto-declined"':msg('tooLarge','abbrev(maxAmount)','ccy()'),
   '"Below the maker\'s minimum (" + trim(min) + " " + ccy() + ")"':msg('tooSmall','trim(min)','ccy()')}.items():part=rep(part,a,b)
  part=rep(part,'.setView(box)','.setView(wrapScroll(box))')
  return part
 edit('takeOrderDialog',take)
 # Static phrase matching is confined to these reviewed UI methods.
 methods=['editOrderDialog','renderMarketTab','orderLadder','depthHalf','takeOrderDialog','numRow2','publishOrder','publishOrderNow']
 lookup={catalog[v]['en']:msg(k) for k,v in labels.items() if isinstance(catalog[v]['en'],str) and '{'not in catalog[v]['en'] and k not in {'publishing','updating','withdrawing','updated','withdrawn','published'}}
 lookup['My market / ladder']=msg('editor')
 lookup['AUTO-FILL (mid · step % · levels, then ask/bid size — seeds the rungs; edit any rung after)']=msg('autoFill')
 literal=r'"(?:\\.|[^"\\])*"'
 for name in methods:edit(name,lambda part:re.sub(r'//[^\n]*|/\*[\s\S]*?\*/|'+literal,lambda m:m[0] if m[0].startswith(('/', "'")) else lookup.get(json.loads(m[0]),m[0]),part))
 # The strings stored in status/oracle fields, logged, or used by the trading engine remain raw.
 change('Language.setText(st, orderStatus);','Language.setText(st, MarketMessages.message(orderStatus));')
 change('Language.setText(pegPx, PriceOracle.describe());','Language.setText(pegPx, MarketMessages.message(PriceOracle.describe()));')
 change('Language.setText(mexc, PriceOracle.describe());','Language.setText(mexc, MarketMessages.message(PriceOracle.describe()));')
 for old,new in [('numRow(LinearLayout parent, String label,','numRow(LinearLayout parent, CharSequence label,'),('genField(String hint)','genField(CharSequence hint)')]:change(old,new)
 # Longer translated hints need a full field width. Input values and watcher wiring are unchanged.
 def layout(part):
  for name in ['pegRow','gen','genS']:part=rep(part,name+'.setOrientation(LinearLayout.HORIZONTAL)',name+'.setOrientation(LinearLayout.VERTICAL)')
  for name in ['pr1','pr2','g1','g2','g4','gs1','gs2']:
   part=re.sub(r'(LinearLayout.LayoutParams '+name+r' = new LinearLayout.LayoutParams)\(0, LinearLayout.LayoutParams.WRAP_CONTENT, [\d.]+f\)',r'\1(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)',part)
  for parent,field,param,label in [('pegRow','biasE','pr1','skew'),('pegRow','repE','pr2','reprice'),('gen','midE','g1','mid'),('gen','stepE','g2','step'),('gen','levelsE','g4','levels'),('genS','askSizeE','gs1','askSize'),('genS','bidSizeE','gs2','bidSize')]:
   old=parent+'.addView('+field+', '+param+');'
   part=rep(part,old,parent+'.addView(fieldLabel('+msg(label)+')); '+old)
  return part
 edit('editOrderDialog',layout)
 def clear(part):
  part=rep(part,'        Design.pressable(clr);','        Language.description(clr, '+msg('clear','label')+');\n        clr.setMinimumWidth(dp(48)); clr.setMinimumHeight(dp(48));\n        Design.pressable(clr);')
  return rep(part,'new LinearLayout.LayoutParams(dp(30),','new LinearLayout.LayoutParams(dp(48),')
 edit('numRow2',clear)
 change('else { price.setPadding(dp(8), 0, 0, 0); h.addView(price); h.addView(size); }','else { price.setPadding(0, 0, dp(8), 0); h.addView(price); h.addView(size); }')
 p.write_text(s)
 spec=importlib.util.spec_from_file_location('market_message_builder',project/'scripts/adapt-pandamonium-wallet-error-languages.py')
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
 source=module.build_source(json.loads((project/'localization/atomix-market-messages.json').read_text()),catalog)
 source=source.replace('package com.eurobuddha.wallet;','package com.eurobuddha.atomix;').replace('WalletMessages','MarketMessages').replace('Explicit wallet failures only.','Explicit market status and oracle display only.').replace('adapt-pandamonium-wallet-error-languages.py from localization/wallet-errors.json','adapt-pandamonium-atomix-market.py from localization/atomix-market-messages.json')
 (folder/'MarketMessages.java').write_text(source)
