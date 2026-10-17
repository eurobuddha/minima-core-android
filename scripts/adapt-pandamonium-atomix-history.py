"""AtomiX history display bindings. Preserve records, visibility rules and CSV schema."""
from pathlib import Path
import json,re,importlib.util

def adapt(root):
 project=Path(__file__).resolve().parents[1]
 labels=json.loads((project/'localization/atomix-history-labels.json').read_text());catalog=json.loads((project/'localization/catalog.json').read_text())
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


 methods=['renderActiveSwaps','renderActivityTab','mySwapsList','historySwapCard','marketView','tradeRow','countByStatus','confirmHideSwap','swapCard','checkNow','showReport','statusDetail','statusLabel','countdown','exportSwapsCsv','doExportCsv']
 static={catalog[v]['en']:msg(k) for k,v in labels.items() if isinstance(catalog[v]['en'],str) and '{'not in catalog[v]['en']}
 static["Counterparty copied"]=msg("copied")
 static["This only removes the card. If any funds were locked on-chain they still auto-refund at the timelock — hiding doesn't touch them."]=msg('hideHelp')
 # Only the reviewed UI methods; protocol values in conditions/CSV remain literal.
 for name in methods:
  def transform(part):
   lookup={raw:value for raw,value in static.items() if raw not in ['complete','locked','claiming','refunded','error','open','filled','cancelled','waiting','done','reclaimed']}
   return re.sub(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"',lambda m:m[0] if m[0].startswith('/') else lookup.get(json.loads(m[0]),m[0]),part)
  edit(name,transform)
 replacements={
 'renderActiveSwaps': [('hidden + " finished swap" + (hidden == 1 ? "" : "s") + " hidden — tap for history"',msg('hidden','hidden'))],
 'mySwapsList': [('"No " + ccy() + " swaps yet — your completed and refunded swaps will appear here."',msg('empty','ccy()'))],
 'historySwapCard': [('String when = android.text.format.DateUtils.getRelativeTimeSpanString(\n                s.created, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString();','CharSequence when = Language.since(s.created);'),('when + "  ·  " + s.role.toLowerCase() + cp','Language.join(when, "  ·  ", historyRole(s.role), cp)')],
 'marketView': [('"last " + last + " USDT/" + ccy() + "  ·  " + countByStatus(recent) + "  ·  price only (buy/sell not shown)"',msg('stats','last','ccy()','countByStatus(recent)')),('"No market trades observed yet. AtomiX records " + ccy() + " swaps network-wide as they happen — it can\'t backfill, so history builds from now on."',msg('marketEmpty','ccy()'))],
 'tradeRow': [('android.text.format.DateUtils.getRelativeTimeSpanString(\n                t.observedAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()','Language.since(t.observedAt)'),('"  " + abbrev(parseD(t.sizeMinima, 0)) + " M"','"  " + abbrev(parseD(t.sizeMinima, 0)) + " " + ccy()'),('exec ? "filled" : ref ? "cancelled" : "open"','exec ? '+msg('filled')+' : ref ? '+msg('cancelled')+' : '+msg('open'))],
 'countByStatus': [('private String countByStatus','private CharSequence countByStatus'),('ex + " filled · " + op + " open"',msg('counts','ex','op'))],
 'swapCard': [('s.role.toLowerCase() + "  ·  " + countdown(s)','Language.join(historyRole(s.role), "  ·  ", countdown(s))'),('TextView x = Design.pill(this, "✕", Design.SURFACE2(), Design.DIM());','TextView x = Design.pill(this, "✕", Design.SURFACE2(), Design.DIM());\n            Language.description(x, '+msg('hideDescription')+'); x.setMinimumWidth(dp(48)); x.setMinimumHeight(dp(48));')],
 'statusDetail': [('private String statusDetail','private CharSequence statusDetail'),('"Locked your " + s.sellAmount + " " + s.sellToken + " — waiting for the counterparty to lock their side."',msg('started','s.sellAmount','s.sellToken')),('"Counterparty locked — claiming your " + s.buyAmount + " " + s.buyToken + " now."',msg('claimingDetail','s.buyAmount','s.buyToken')),('"Done — received " + s.buyAmount + " " + s.buyToken + "."',msg('completeDetail','s.buyAmount','s.buyToken')),('"Timed out — your " + s.sellAmount + " " + s.sellToken + " was refunded."',msg('refundedDetail','s.sellAmount','s.sellToken'))],
 'statusLabel': [('private String statusLabel','private CharSequence statusLabel')]+[('return "'+k+'";','return '+msg(k)+';')for k in ['waiting','locked','claiming','complete','refunded']]+[('default: return s.status.toLowerCase();','case SwapDb.ST_ERROR: return '+msg('error')+';\n            default: return s.status == null ? "" : s.status;')],
 'countdown': [('private String countdown','private CharSequence countdown'),('return "done";','return '+msg('done')+';'),('return "reclaimed";','return '+msg('reclaimed')+';'),('"refundable at block " + s.myTimelock',msg('block','s.myTimelock')),('"refundable in ~" + mins + " min"',msg('minutes','mins'))],
 'doExportCsv': [('"Exported " + n + " trades to CSV"',msg('exported','n')),('"CSV export failed: " + (e.getMessage() == null ? "error" : e.getMessage())',msg('exportFailed','e.getMessage() == null ? '+msg('error')+' : e.getMessage()'))]
 }
 for name,pairs in replacements.items():
  def transform(part):
   for a,b in pairs:part=rep(part,a,b)
   return part
  edit(name,transform)
 # Accessible, full-width actions fit the longest languages and preserve each callback.
 def tabs(part):
  part=rep(part,'tabs.setOrientation(LinearLayout.HORIZONTAL)','tabs.setOrientation(LinearLayout.VERTICAL)')
  part=part.replace('new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)','new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)')
  part=rep(part,'gap.rightMargin = dp(8);','gap.bottomMargin = dp(8);')
  part=rep(part,'gapx.leftMargin = dp(8);','gapx.topMargin = dp(8);')
  for n in ['t0','t1','tex']:part=rep(part,n+'.setOnClickListener',n+'.setMinimumHeight(dp(48));\n        '+n+'.setOnClickListener')
  return part
 edit('renderActivityTab',tabs)
 # Keep the proven numeric Canvas for real charts. Empty states use the existing wrapping text binding.
 def chart(part):
  a=part.index('        MarketChartView chart =');b=part.index('\n        TextView stats =',a)
  old=part[a:b];new='''        if (chartData.size() < 2) {
            TextView emptyChart = dimNote(chartData.isEmpty() ? '''+msg('collecting')+' : '+msg('oneTrade')+''');
            emptyChart.setGravity(Gravity.CENTER); emptyChart.setMinimumHeight(dp(180));
            emptyChart.setBackground(Design.card(this, 14)); col.addView(emptyChart);
        } else {
'''+''.join('    '+line+'\n' for line in old.splitlines())+'        }\n'
  return part[:a]+new+part[b:]
 edit('marketView',chart)
 s=s.replace('    private CharSequence statusDetail(SwapDb.Swap s) {','    private CharSequence statusDetail(SwapDb.Swap s) {\n        if (s.status == null) return "";')
 s=s.replace('    private CharSequence statusLabel(SwapDb.Swap s) {','    private CharSequence statusLabel(SwapDb.Swap s) {\n        if (s.status == null) return "";')
 s+=''
 anchor='    private static String roleLabel(String r)'
 helper='''    private CharSequence historyRole(String role) {
        if ("RESPONDER".equals(role)) return '''+msg('maker')+''';
        if ("INITIATOR".equals(role)) return '''+msg('taker')+''';
        return role == null ? "" : role;
    }

'''
 assert anchor in s;s=s.replace(anchor,helper+anchor)
 p.write_text(s)
