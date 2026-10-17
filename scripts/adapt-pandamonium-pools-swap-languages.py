"""PandaPools Swap display bindings; preserve quote math, node calls and stored activity."""
from pathlib import Path
import json,xml.etree.ElementTree as E,re

def adapt(root):
 folder=root/'pandapools/src/main/java/com/eurobuddha/pandapools'
 def change(s,old,new,count=1):
  assert s.count(old)==count,('PandaPools Swap source drift',old,s.count(old));return s.replace(old,new)
 p=folder/'PoolsMessages.java';s=p.read_text()
 s=change(s,'    public static CharSequence message(String value)', '''    public static CharSequence message(CharSequence value) {
        return value instanceof String ? message((String)value) : value == null ? "" : value;
    }
    public static CharSequence activityStatus(ActivityLog.Entry entry, int block) {
        if (!entry.failed && entry.verifiedDepth >= 0)
            return Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_pools_confirmations,
                    entry.verifiedDepth, entry.verifiedDepth);
        return message(entry.statusText(block));
    }
    public static CharSequence message(String value)''');p.write_text(s)
 p=folder/'MainActivity.java';s=p.read_text()
 hook='''    private final java.util.Set<androidx.appcompat.app.AlertDialog> languageDialogs = new java.util.HashSet<>();
    com.eurobuddha.minimacore.i18n.LanguageDialog languageDialog() {
        return new com.eurobuddha.minimacore.i18n.LanguageDialog(this) {
            @Override public androidx.appcompat.app.AlertDialog create() {
                androidx.appcompat.app.AlertDialog value = super.create();
                languageDialogs.add(value);
                value.setOnDismissListener(d -> languageDialogs.remove(value));
                return value;
            }
        };
    }
'''
 s=change(s,'    @Override public void onConfigurationChanged(',hook+'    @Override public void onConfigurationChanged(')
 s=change(s,'        super.onDestroy();','        for (androidx.appcompat.app.AlertDialog value : new java.util.ArrayList<>(languageDialogs)) value.dismiss();\n        languageDialogs.clear();\n        super.onDestroy();');p.write_text(s)
 p=folder/'SwapView.java';s=p.read_text()
 s=change(s,'import android.app.AlertDialog;','import com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;')
 s=change(s,'setBusy("Scan error: " + msg)','setBusy(Language.msg(l10n_pools_scan_error, msg))')
 s=change(s,'poolLine.setText("MINIMA / " + pairLabel + "   ·   " + n + (n == 1 ? " pool" : " pools")\n                + "   ·   depth " + trim(depth) + " MINIMA");','Language.setText(poolLine, Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_pools_swap_pool_summary, n, pairLabel, n, trim(depth)));')
 s=change(s,'balanceLine.setText("Your balance: " + fmt6s(m) + " MINIMA  ·  " + fmt6s(t) + " " + pairLabel);','Language.setText(balanceLine, Language.msg(l10n_pools_swap_balance, fmt6s(m), fmt6s(t), pairLabel));')
 s=change(s,'String verdict;','CharSequence verdict;')
 s=change(s,'verdict = "at market";','verdict = Language.msg(l10n_pools_at_market);')
 s=change(s,'verdict = mag + "% " + (pct.signum() > 0 ? "above market" : "below market");','verdict = Language.msg(pct.signum() > 0 ? l10n_pools_above_market : l10n_pools_below_market, mag);')
 s=change(s,'marketLine.setText("Market ≈ " + fmt6(mid) + " USDT/MINIMA (MEXC)  ·  Pool " + fmt6(poolSpot)\n                + "  ·  " + verdict);','Language.setText(marketLine, Language.msg(l10n_pools_market_compare, fmt6(mid), fmt6(poolSpot), verdict));')
 s=change(s,'fromTok.setText(minimaToToken ? "MINIMA" : pairLabel);','Language.setText(fromTok, minimaToToken ? "MINIMA" : pairLabel);')
 s=change(s,'toTok.setText(minimaToToken ? pairLabel : "MINIMA");','Language.setText(toTok, minimaToToken ? pairLabel : "MINIMA");')
 s=change(s,'addRow2("Routed across", r.poolsUsed + " of " + r.poolsAvailable + " pools"\n                    + (r.capped ? " (top " + PoolRouter.MAX_POOLS + ")" : ""));','addRow2("Routed across", Language.join(Language.msg(l10n_pools_route_count, r.poolsUsed, r.poolsAvailable),\n                    r.capped ? Language.msg(l10n_pools_route_cap, PoolRouter.MAX_POOLS) : ""));')
 s=change(s,'String routed = r.poolsUsed > 1 ? "\\nRouted across " + r.poolsUsed + " pools in one transaction." : "";','CharSequence routed = r.poolsUsed > 1 ? Language.msg(l10n_pools_route_review, r.poolsUsed) : "";')
 s=change(s,'new AlertDialog.Builder(act)','act.languageDialog()')
 s=change(s,'.setMessage("Pay  " + pay + "\\nReceive  " + recv + "\\n\\nPrice impact " + impact(r) + "%." + routed\n                        + "\\n\\nThis posts a real on-chain transaction — if a pool moves before it confirms the "\n                        + "swap is rejected (you keep your funds).")','.setMessage(Language.msg(l10n_pools_swap_review, pay, recv, impact(r), routed))')
 for raw,key in [('Confirm swap','confirm_swap'),('Swap','swap_action'),('Cancel','cancel')]:
  method={'Confirm swap':'Title','Swap':'PositiveButton','Cancel':'NegativeButton'}[raw]
  s=change(s,'.set'+method+'("'+raw+'"','.set'+method+'(Language.msg(l10n_pools_'+key+')')
 s=change(s,'"Swap submitted. Check Activity for confirmation. " + txpowid','Language.msg(l10n_pools_swap_submitted, txpowid)')
 s=change(s,'"Swap: " + message','Language.msg(l10n_pools_swap_failure, PoolsMessages.message(message))')
 s=change(s,'private void addRow2(String k, String v)','private void addRow2(CharSequence k, CharSequence v)')
 s=change(s,'kk.setText(k);','Language.setText(kk, PoolsMessages.message(k));')
 s=change(s,'vv.setText(v);','Language.setText(vv, PoolsMessages.message(v));')
 s=change(s,'kk.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));','kk.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));\n        kk.setMaxWidth(Ui.dp(act, 190));')
 s=change(s,'        vv.setGravity(Gravity.END);','        vv.setGravity(Gravity.END);\n        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);\n        vlp.leftMargin = Ui.dp(act, 8);\n        vv.setLayoutParams(vlp);')
 s=change(s,'private void addRow(String s,','private void addRow(CharSequence s,')
 s=change(s,'t.setText(s);','Language.setText(t, PoolsMessages.message(s));')
 s=change(s,'        desc.setMaxLines(3);','        // Preserve the full identifier when a summary wraps over several lines.')
 s=change(s,'st.setText(e.statusText(cb));','Language.setText(st, PoolsMessages.activityStatus(e, cb));')
 s=change(s,'        st.setPadding(Ui.dp(act, 8), 0, 0, 0);','        st.setPadding(Ui.dp(act, 8), 0, 0, 0);\n        st.setMaxWidth(Ui.dp(act, 150));')
 s=change(s,'private void setBusy(String s) { poolLine.setText(s);','private void setBusy(CharSequence s) { Language.setText(poolLine, PoolsMessages.message(s));')
 s=change(s,'private void setStatus(String s,','private void setStatus(CharSequence s,')
 s=change(s,'status.setVisibility(s.isEmpty()','status.setVisibility(s.length() == 0')
 s=change(s,'status.setText(s);','Language.setText(status, PoolsMessages.message(s));')
 s=change(s,'private void toast(String s)','private void toast(CharSequence s)')
 for target in ['poolLine','marketLine','h','all']:
  s=re.sub(r'\b'+target+r'\.setText\(("[^"\n]*")\);',r'Language.setText('+target+r', PoolsMessages.message(\1));',s)
 for raw in ['"SWAP"','"SWAPPING…"','posting ? "SWAPPING…" : "SWAP"']:
  s=s.replace('swapBtn.setText('+raw+');','Language.setText(swapBtn, PoolsMessages.message('+raw+'));')
 s=change(s,'        flip.setTypeface(Design.typefaceBold());','        flip.setTypeface(Design.typefaceBold());\n        Language.description(flip, Language.msg(l10n_pools_flip_description));')
 s=change(s,'        all.setOnClickListener','        all.setMinHeight(Ui.dp(act, 48));\n        all.setGravity(Gravity.CENTER_VERTICAL);\n        all.setOnClickListener')
 p.write_text(s)
 project=Path(__file__).resolve().parents[1];fixed=json.loads((project/'localization/pools-messages.json').read_text())['fixed']
 a='{http://schemas.android.com/apk/res/android}';E.register_namespace('android',a[1:-1]);p=root/'pandapools/src/main/res/layout/pm_pandapools_view_swap.xml';tree=E.parse(p)
 for v in tree.iter():
  text=v.get(a+'text')
  if text in fixed:
   key='l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',fixed[text]).replace('.','_').lower();assert not v.get(a+'tag');v.set(a+'text','@string/'+key);v.set(a+'tag','l10n:text='+key)
  if v.get(a+'id','').endswith(('swapFlip','swapBtn','swapAmount')):v.set(a+'minHeight','48dp');v.set(a+'minWidth','48dp')
 tree.write(p,encoding='utf-8',xml_declaration=True)
 p.write_text('\n'.join(line.rstrip() for line in p.read_text().splitlines())+'\n')
