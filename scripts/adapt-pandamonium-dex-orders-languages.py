"""Retained PandaDEX order/operation labels and dialogs; no changes to owner write guards."""
from pathlib import Path

def adapt(root):
 base=root/'pandadex/src/main/java/com/eurobuddha/pandadex'
 def change(s,old,new,count=1):
  assert s.count(old)==count,('PandaDEX order source drift',old,s.count(old))
  return s.replace(old,new)
 imports='package com.eurobuddha.pandadex;\nimport com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;'
 p=base/'OrdersTab.java';s=p.read_text();s=change(s,'package com.eurobuddha.pandadex;',imports)
 s=change(s,'    private final SimpleDateFormat fmt = new SimpleDateFormat("dd MMM HH:mm", Locale.US);\n','')
 s=change(s,'            t.setText(names[i]);','            Language.setText(t, DexMessages.message(names[i]));\n            t.setMinHeight(Design.dp(act, 48));')
 s=change(s,'private TextView line(String s,','private TextView line(CharSequence s,')
 s=change(s,'        t.setText(s);','        Language.setText(t, DexMessages.message(s));')
 s=change(s,'(o.sell ? "SELL " : "BUY ") + PriceMath.fmt(o.minimaAmount())\n                            + " MINIMA @ " + PriceMath.fmtPrice(o.price())','Language.join(DexMessages.message(o.sell ? "SELL" : "BUY"), " ", PriceMath.fmt(o.minimaAmount()), " MINIMA @ ", PriceMath.fmtPrice(o.price()))')
 start=s.index('            String meta = ');end=s.index('            // the ladder',start)
 s=s[:start]+'''            card.addView(line(orderMetadata(o, block, act.makerBookReady()), Design.DIM2(), 9.5f));
'''+s[end:]
 s=change(s,'fmt.format(new Date(time)) + (ChainReview.accounted((String)t[6]) ? "" : (((String)t[6]).startsWith("SUPERSEDED_") ? "\\nCorrected — see Corrections" : "\\nRecheck required"))','tradeTime(time, (String)t[6])')
 s=change(s,'(buy ? "BUY " : "SELL ") + PriceMath.fmt(size)','Language.join(DexMessages.message(buy ? "BUY" : "SELL"), " ", PriceMath.fmt(size))')
 s=change(s,'PriceMath.fmtPrice(price) + (maker ? "  M" : "  T")','Language.join(PriceMath.fmtPrice(price), "\\n", DexMessages.message(maker ? "MAKER" : "TAKER"))')
 for name in ['edit','cancel']:
  s=change(s,'                '+name+'.setOnClickListener','                '+name+'.setMinWidth(Design.dp(act, 48)); '+name+'.setMinHeight(Design.dp(act, 48)); '+name+'.setGravity(Gravity.CENTER);\n                Language.description('+name+', Language.msg(l10n_dex_'+name+'_order));\n                '+name+'.setOnClickListener')
 for name,indent in [('cancelAll','            '),('export','        ')]:
  s=change(s,indent+name+'.setGravity(Gravity.CENTER);',indent+name+'.setGravity(Gravity.CENTER);\n'+indent+name+'.setMinHeight(Design.dp(act, 48));')
 s=change(s,'    private void renderTrades() {','''    static CharSequence orderMetadata(Order5 order, long block, boolean current) {
        return Language.msg(l10n_dex_order_meta, DexMessages.message(order.gtc ? "GTC ∞" : "expires"),
                DexMessages.message(order.ageLabel(block, current)), PriceMath.fmt(order.minRem), PriceMath.fmt(order.usdtAmount()));
    }

    static CharSequence tradeTime(long time, String status) {
        return Language.join(Language.stamp(time, "MMMdHm"), ChainReview.accounted(status) ? ""
                : Language.join("\\n", DexMessages.message(status.startsWith("SUPERSEDED_") ? "Corrected — see Corrections" : "Recheck required")));
    }

    private void renderTrades() {''')
 for line in ['import java.text.SimpleDateFormat;\n','import java.util.Date;\n']:s=s.replace(line,'')
 p.write_text(s)
 p=base/'OwnerHistoryView.java';s=p.read_text();s=change(s,'package com.eurobuddha.pandadex;',imports)
 s=change(s,'    private final SimpleDateFormat fmt=new SimpleDateFormat("dd MMM HH:mm",Locale.US);\n','')
 s=change(s,'private TextView line(String text,','private TextView line(CharSequence text,')
 s=change(s,'v.setText(text);','Language.setText(v, DexMessages.message(text));')
 s=change(s,'(r.blockTimeMs>0?"Block time ":"First observed ")+fmt.format(new Date(r.blockTimeMs>0?r.blockTimeMs:r.recordedAt))','operationTime(r)')
 s=change(s,'"Verified inclusion block "+r.block+(r.checkedAt>0?" · checked "+fmt.format(new Date(r.checkedAt)):"")','operationInclusion(r)')
 s=change(s,'"TxPoW "+r.txpowid','Language.join("TxPoW ", r.txpowid)')
 s=change(s,'    void render(){','''    static CharSequence operationTime(OwnerReceipt.Entry r) {
        return Language.join(DexMessages.message(r.blockTimeMs > 0 ? "Block time" : "First observed"), " ",
                Language.stamp(r.blockTimeMs > 0 ? r.blockTimeMs : r.recordedAt, "MMMdHm"));
    }
    static CharSequence operationInclusion(OwnerReceipt.Entry r) {
        return Language.join(Language.msg(l10n_dex_owner_inclusion, r.block), r.checkedAt > 0
                ? Language.msg(l10n_dex_owner_checked, Language.stamp(r.checkedAt, "MMMdHm")) : "");
    }
    void render(){''')
 s=s.replace('import java.text.SimpleDateFormat;\n','');p.write_text(s)
 p=base/'MainActivity.java';s=p.read_text()
 start=s.index('    public void cancelAll(');end=s.index('    private void cancelSequentially',start);part=s[start:end]
 a=part.index('        String msg = mine.isEmpty()');b=part.index('        new AlertDialog.Builder',a)
 part=part[:a]+'''        CharSequence msg = cancelAllMessage(mine.size(), PriceMath.fmt(totalMinima), PriceMath.fmt(totalUsdt), stopMaker, rungs);
'''+part[b:]
 part=change(part,'new AlertDialog.Builder(this, Design.dialogTheme())','new LanguageDialog(this, Design.dialogTheme())')
 part=change(part,'.setTitle("Cancel all orders")','.setTitle(DexMessages.message("Cancel all orders"))')
 part=change(part,'.setPositiveButton(stopMaker ? "Cancel all & stop" : "Cancel them",','.setPositiveButton(DexMessages.message(stopMaker ? "Cancel all & stop" : "Cancel them"),')
 part=change(part,'.setNegativeButton("Keep them",','.setNegativeButton(DexMessages.message("Keep them"),')
 part+='''    static CharSequence cancelAllMessage(int orders, String minima, String mxusd, boolean stopMaker, int rungs) {
        return Language.join(orders == 0 ? Language.msg(l10n_dex_cancel_empty_body)
                : Language.msg(l10n_dex_cancel_all_body, orders, minima, mxusd),
                stopMaker ? Language.msg(l10n_dex_cancel_pause,
                        rungs > 0 ? Language.msg(l10n_dex_cancel_rungs, rungs) : "") : "");
    }

'''
 s=s[:start]+part+s[end:]
 start=s.index('    public void editOrder(');end=s.index('    private void recordFill(',start);part=s[start:end]
 part=change(part,'in.setHint("New price (MxUSD per MINIMA)");','Language.hint(in, DexMessages.message("New price (MxUSD per MINIMA)"));')
 part=change(part,'new AlertDialog.Builder(this, Design.dialogTheme())','new LanguageDialog(this, Design.dialogTheme())')
 for method,text in [('setTitle','Edit order price'),('setPositiveButton','Update'),('setNegativeButton','Cancel')]:
  part=change(part,'.'+method+'("'+text+'"','.'+method+'(DexMessages.message("'+text+'")')
 s=s[:start]+part+s[end:];p.write_text(s)
