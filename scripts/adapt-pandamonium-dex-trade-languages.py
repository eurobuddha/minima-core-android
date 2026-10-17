"""PandaDEX TradeView display only; retain decimal drafts, quote/routing math and raw receipts."""
from pathlib import Path
import re

def adapt(root):
 p=root/'pandadex/src/main/java/com/eurobuddha/pandadex/TradeView.java';s=p.read_text()
 def change(old,new,count=1):
  nonlocal s
  assert s.count(old)==count,('PandaDEX trade source drift',old,s.count(old));s=s.replace(old,new)
 change('package com.eurobuddha.pandadex;','package com.eurobuddha.pandadex;\nimport com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;')
 change('private TextView tv(String s,','private TextView tv(CharSequence s,')
 change('        t.setText(s);','        Language.setText(t, DexMessages.message(s));')
 change('        e.setHint(hint);','        Language.hint(e, DexMessages.message(hint));')
 for name in ['gtcTv','advTv','ctaBtn','totalTv','syncDot','priceKindTv']:
  pattern=r'\b'+name+r'\.setText\((.*?)\);'
  s,count=re.subn(pattern,lambda m:'Language.setText('+name+', DexMessages.message('+m[1]+'));',s,flags=re.S)
  assert count,(name,'No display bindings')
 change('centerPriceTv.setContentDescription("Size-weighted price of the best displayed bid and offer, including pool and limit liquidity");','Language.description(centerPriceTv, Language.msg(l10n_dex_weighted_price_help));')
 change('''            String label = (o.sell ? "SELL " : "BUY ") + PriceMath.fmt(o.minimaAmount())
                    + " @ " + PriceMath.fmtPrice(o.price()) + (o.gtc ? "  ∞" : "");''','''            CharSequence label = Language.join(DexMessages.message(o.sell ? "SELL" : "BUY"), " ",
                    PriceMath.fmt(o.minimaAmount()), " @ ", PriceMath.fmtPrice(o.price()), o.gtc ? "  ∞" : "");''')
 # Keep translated heading and four exact tick choices visible on a narrow Fold viewport.
 change('        head.setGravity(Gravity.CENTER_VERTICAL);','        head.setOrientation(VERTICAL);\n        head.setGravity(Gravity.CENTER_VERTICAL);')
 change('''        head.addView(tv("ORDER BOOK", 10f, Design.DIM(), Design.sansBold()),
                new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));''','''        head.addView(tv("ORDER BOOK", 10f, Design.DIM(), Design.sansBold()),
                new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));''')
 change('        groupRow = new LinearLayout(getContext());','        groupRow = new LinearLayout(getContext());\n        groupRow.setPadding(0, dp(4), 0, dp(4));')
 change('            LayoutParams glp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);','            LayoutParams glp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);')
 change('        head.addView(groupRow);','        head.addView(groupRow, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));')
 for old,new in [
 ('            g.setOnClickListener','            g.setMinHeight(dp(48));\n            g.setGravity(Gravity.CENTER);\n            g.setOnClickListener'),
 ('            tabs.addView(t,','            t.setMinHeight(dp(48));\n            tabs.addView(t,'),
 ('            b.setOnClickListener','            b.setMinHeight(dp(48));\n            b.setOnClickListener'),
 ('        gtcTv.setOnClickListener','        gtcTv.setMinHeight(dp(48)); gtcTv.setGravity(Gravity.CENTER_VERTICAL);\n        gtcTv.setOnClickListener'),
 ('        advTv.setOnClickListener','        advTv.setMinHeight(dp(48)); advTv.setGravity(Gravity.CENTER_VERTICAL);\n        advTv.setOnClickListener'),
 ('        ctaBtn.setGravity(Gravity.CENTER);','        ctaBtn.setMinHeight(dp(48));\n        ctaBtn.setGravity(Gravity.CENTER);'),
 ('        e.setHintTextColor','        e.setMinHeight(dp(48));\n        e.setHintTextColor'),
 ('                edit.setOnClickListener','                edit.setMinWidth(dp(48)); edit.setMinHeight(dp(48)); edit.setGravity(Gravity.CENTER);\n                Language.description(edit, Language.msg(l10n_dex_edit_order));\n                edit.setOnClickListener'),
 ('                cancel.setOnClickListener','                cancel.setMinWidth(dp(48)); cancel.setMinHeight(dp(48)); cancel.setGravity(Gravity.CENTER);\n                Language.description(cancel, Language.msg(l10n_dex_cancel_order));\n                cancel.setOnClickListener')]:change(old,new)
 p.write_text(s)
