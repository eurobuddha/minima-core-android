"""AtomiX OTC labels and retained inline drafts; financial/wire code stays canonical."""
from pathlib import Path
import json,re

def adapt(root):
 project=Path(__file__).resolve().parents[1]
 labels=json.loads((project/'localization/atomix-otc-labels.json').read_text());catalog=json.loads((project/'localization/catalog.json').read_text())
 p=root/'atomix/src/main/java/com/eurobuddha/atomix/MainActivity.java';s=p.read_text()
 def msg(key,*args):
  resource='l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',labels[key]).replace('.','_').lower()
  return 'Language.msg('+resource+('' if not args else ', '+', '.join(args))+')'
 def change(old,new):
  nonlocal s
  assert old in s,('AtomiX OTC drift',old);s=s.replace(old,new)
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
 methods=['renderOtcTab','renderOtcAvailability','renderOtcProviders','renderOtcDeals','otcStatusText','otcProposeDialog','otcCounterDialog','publishOtcOffer','withdrawOtcOffer']
 # Descriptor arguments are immutable copies of the original public values.
 replacements={
  '"I SELL " + ccy() + " up to"':msg('sellLimit','ccy()'),
  '"I BUY " + ccy() + " up to"':msg('buyLimit','ccy()'),
  '"max " + ccy() + " (blank = off)"':msg('maxHint','ccy()'),
  '"Buy " + ccy()':msg('buy','ccy()'),
  '"Sell " + ccy()':msg('sell','ccy()'),
  'ccy() + " amount (≤ " + trimSig(cap) + ")"':msg('cappedAmount','ccy()','trimSig(cap)'),
  '"Price (USDT per " + ccy() + ")"':msg('price','ccy()'),
  'ccy() + " amount"':msg('amount','ccy()'),
  '"OTC publish failed: " + message':msg('publishFailed','message')}
 for name in methods:
  def expressions(part):
   for old,new in replacements.items():part=part.replace(old,new)
   return part
  edit(name,expressions)
 def providers(part):
  start=part.index('            StringBuilder sides =');end=part.index('            TextView t =',start)
  part=part[:start]+'''            Language.TextBuilder sides = new Language.TextBuilder();
            if (o.sells()) sides.append('''+msg('sells','trimSig(o.sellSize)','ccy()')+''');
            if (o.buys()) { if (o.sells()) sides.append("   ·   "); sides.append('''+msg('buys','trimSig(o.buySize)','ccy()')+'''); }
'''+part[end:]
  part=rep(part,'sides.toString()','sides.build()')
  return rep(part,'"LP " + shortAddr(o.signerPk)',msg('provider','shortAddr(o.signerPk)'))
 edit('renderOtcProviders',providers)
 def deals(part):
  start=part.index('            String dir =');end=part.index('            t.setTextColor',start)
  part=part[:start]+'''            boolean buying = OtcOffer.LP_SELLS_MINIMA.equals(d.side)
                    ? OtcDb.ROLE_INSTIGATOR.equals(d.role) : !OtcDb.ROLE_INSTIGATOR.equals(d.role);
            TextView t = new TextView(this);
            Language.setText(t, buying ? '''+msg('buyTerms','d.amount','ccy()','d.price')+' : '+msg('sellTerms','d.amount','ccy()','d.price')+''');
'''+part[end:]
  # The three translated actions have enough room at normal and enlarged font sizes.
  part=part.replace('row.setOrientation(LinearLayout.HORIZONTAL); row.setPadding','row.setOrientation(LinearLayout.VERTICAL); row.setPadding')
  old='                row.addView(acc, m); row.addView(cnt, m); row.addView(rej); box.addView(row);'
  new='''                for (TextView action : new TextView[]{acc, cnt, rej}) {
                    action.setMinimumHeight(dp(48));
                    row.addView(action, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                }
                box.addView(row);'''
  return rep(part,old,new)
 edit('renderOtcDeals',deals)
 def funds(part):
  part=rep(part,'private String otcCheckFunds','private CharSequence otcCheckFunds')
  part=rep(part,'"Not enough " + ccy() + " — need " + trim(amt) + ", have " + trim(have)',msg('funds','ccy()','trim(amt)','trim(have)'))
  return rep(part,'"Not enough USDT — need " + trim(need) + ", have " + trim(have)',msg('funds','"USDT"','trim(need)','trim(have)'))
 edit('otcCheckFunds',funds)
 change('private TextView fieldLabel(String s)','private TextView fieldLabel(CharSequence s)')
 change('private void otcSectionHead(LinearLayout col, String s)','private void otcSectionHead(LinearLayout col, CharSequence s)')
 change('private OtcController.SendResult otcRes(String okMsg)','private OtcController.SendResult otcRes(CharSequence okMsg)')
 change('private String otcStatusText(OtcDb.Deal d)','private CharSequence otcStatusText(OtcDb.Deal d)')
 change('String fundErr = otcCheckFunds','CharSequence fundErr = otcCheckFunds')
 change('toast("OTC: " + m)','toast(Language.join("OTC: ", AtomixMessages.message(m)))')
 lookup={catalog[v]['en']:msg(k)for k,v in labels.items()if isinstance(catalog[v]['en'],str) and '{'not in catalog[v]['en']}
 literal=r'"(?:\\.|[^"\\])*"'
 for name in methods:edit(name,lambda part:re.sub(literal,lambda m:lookup.get(json.loads(m[0]),m[0]),part))
 # Same draft/focus pattern as the existing inline swap amount. Keep drafts separate from published prefs.
 change('    private String swapAmount = "";', '    private String otcSellDraft, otcBuyDraft;\n    private boolean otcInputFocused;\n    private String swapAmount = "";')
 change('        swapInputFocused = false;   // leaving the Swap tab clears the typing guard','        otcInputFocused = false;\n        swapInputFocused = false;   // leaving the Swap tab clears the typing guard')
 change('        if (swapInputFocused && currentTab == TAB_SWAP) return;', '        if (swapInputFocused && currentTab == TAB_SWAP) return;\n        if (otcInputFocused && currentTab == TAB_OTC && !IdentityWatch.halted()) return;')
 change('        // once when the dialog dismisses. (All text entry lives in guarded dialogs, never inline in a tab.)','        // once when the dialog dismisses. Inline swap/OTC drafts have their own focus guards below.')
 def availability(part):
  part=rep(part,'Language.setText(sellE, prefs.getString("otc_sell_size", ""));','if (otcSellDraft == null) otcSellDraft = prefs.getString("otc_sell_size", "");\n        Language.setText(sellE, otcSellDraft); rememberOtcDraft(sellE, true);')
  part=rep(part,'Language.setText(buyE, prefs.getString("otc_buy_size", ""));','if (otcBuyDraft == null) otcBuyDraft = prefs.getString("otc_buy_size", "");\n        Language.setText(buyE, otcBuyDraft); rememberOtcDraft(buyE, false);')
  part=rep(part,'            prefs.edit().putString("otc_sell_size",','            finishOtcEditing(box);\n            prefs.edit().putString("otc_sell_size",')
  part=rep(part,'wd.setOnClickListener(v -> withdrawOtcOffer());','wd.setOnClickListener(v -> { finishOtcEditing(box); withdrawOtcOffer(); });')
  part=rep(part,'Design.pressable(pubBtn); btns.addView(pubBtn);','Design.pressable(pubBtn); pubBtn.setMinimumHeight(dp(48)); btns.addView(pubBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));')
  part=rep(part,'new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT); wlp.leftMargin','new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); wd.setMinimumHeight(dp(48)); wlp.leftMargin')
  return part
 edit('renderOtcAvailability',availability)
 change('    private void renderOtcTab(','''    private void rememberOtcDraft(EditText field, boolean sell) {
        field.addTextChangedListener(new SimpleWatcher(() -> {
            if (sell) otcSellDraft = field.getText().toString(); else otcBuyDraft = field.getText().toString();
        }));
        field.setOnFocusChangeListener((view, focused) -> {
            otcInputFocused = focused;
            if (!focused) ui.postDelayed(this::render, 250);
        });
    }
    private void finishOtcEditing(View container) {
        container.setFocusableInTouchMode(true); container.requestFocus(); otcInputFocused = false;
    }

    private void renderOtcTab(''')
 p.write_text(s)
