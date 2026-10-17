"""Reuse History/UTXO display bindings; leave NFT persistence and export payloads literal."""
from pathlib import Path
import json,re

def adapt(root):
 folder=root/'NFTwallet/src/main/java/com/eurobuddha/nftwallet'
 imports='\nimport com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;\n'
 def change(s,old,new,count=1):
  assert s.count(old)==count,('NFT history source drift',old,s.count(old))
  return s.replace(old,new)
 def msg(key):return 'Language.msg(l10n_'+key+')'
 p=folder/'HistoryView.java';s=p.read_text()
 s=change(s,'package com.eurobuddha.nftwallet;','package com.eurobuddha.nftwallet;'+imports)
 fixed={'Loading history…':'utxo_history_loading','No transactions yet.':'utxo_history_empty','Load older ▾':'utxo_history_older','⇩ EXPORT':'nft_history_export','Export history':'wallet_export_history','Shares the rows currently shown, using the active filter.':'nft_history_export_help','Export failed.':'filez_export_failed','No app can receive the export.':'nft_history_no_export_app','Direction':'history_direction','Amount':'history_amount','Block':'history_block','Time':'history_date_time','Txpow id':'history_txpow','From':'history_from','To':'history_to','Per-token effect':'history_per_token','Inputs':'history_inputs','Outputs':'history_outputs','Transaction':'history_transaction','Close':'utxo_close','Address':'history_address','YOURS':'history_yours','NOT YOURS':'nft_history_not_yours','checking…':'future_checking','UNVERIFIED':'nft_history_unverified'}
 for literal,key in fixed.items():
  raw=json.dumps(literal,ensure_ascii=False);assert raw in s,(literal,key);s=s.replace(raw,msg(key))
 s=change(s,'"No " + filter + " transactions."',msg('nft_history_empty_filter'))
 s=change(s,'c.setText(o[0]);','c.setText(value == null ? Language.upper(Language.msg(l10n_wallet_all)) : Language.upper(directionLabel(value)));')
 s=change(s,'            c.setTextSize(10f);','            c.setTextSize(10f);\n            c.setSingleLine(true);\n            c.setMinHeight(dp(48));\n            c.setGravity(Gravity.CENTER);')
 s=change(s,'        export.setTextSize(10f);','        export.setTextSize(10f);\n        export.setSingleLine(true);\n        export.setMinHeight(dp(48));\n        export.setGravity(Gravity.CENTER);')
 # Use the existing MintView horizontal-strip pattern for long translated filter names.
 s=change(s,'        return bar;','''        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(act);
        scroll.setHorizontalScrollBarEnabled(true);
        scroll.setFillViewport(true);
        scroll.addView(bar);
        return scroll;''')
 s=change(s,'"NFT Wallet history export (" + (csv ? "CSV" : "JSON") + ")"','Language.msg(l10n_nft_history_export_subject, csv ? "CSV" : "JSON").toString()')
 # Date localization applies only to display; the canonical export formatter stays unchanged.
 s=change(s,'new SimpleDateFormat("dd MMM yyyy  HH:mm:ss", Locale.ENGLISH).format(new Date(n.timemilli))','Language.stamp(n.timemilli, "yMMMdHms")')
 s=change(s,'Language.msg(l10n_history_direction), n.direction','Language.msg(l10n_history_direction), directionLabel(n.direction)')
 s=change(s,'(reshuffle ? n.reshuffleLabel() + "  ·  " : cp) + relative(n.timemilli)','Language.join(reshuffle ? Language.join(n.reshuffleLabel(), "  ·  ") : cp, relative(n.timemilli))')
 for old,new in [('String k, String v)','CharSequence k, CharSequence v)'),('String k, final String v','CharSequence k, final String v'),('String title, String json','CharSequence title, String json'),('String label, final String addr','CharSequence label, final String addr'),('boolean mine, String text','boolean mine, CharSequence text')]:s=change(s,old,new)
 s=change(s,'k + ":  " + v','Language.join(k, ":  ", v)')
 start=s.index('    private static String relative(long ms) {');end=s.index('\n    private int dp',start)
 s=s[:start]+'''    private static CharSequence relative(long ms) {
        if (ms <= 0) return "";
        return System.currentTimeMillis() - ms < 7 * 86400000L
                ? Language.since(ms) : Language.stamp(ms, "yMMMd");
    }

    private static CharSequence directionLabel(String direction) {
        switch (direction) {
            case "received": return Language.msg(l10n_history_received);
            case "sent": return Language.msg(l10n_history_sent);
            case "self": return Language.msg(l10n_history_self);
            default: return direction;
        }
    }
''' + s[end:]
 s=s.replace('setTextColor(Design.accent())','setTextColor(Design.accentText())')
 s=re.sub(r'\b([A-Za-z_][A-Za-z_0-9]*)\.setText\(',r'Language.setText(\1, ',s);p.write_text(s)
 p=folder/'NodeTx.java';s=p.read_text()
 s=change(s,'package com.eurobuddha.nftwallet;','package com.eurobuddha.nftwallet;'+imports)
 s=change(s,'public String reshuffleLabel()','public CharSequence reshuffleLabel()')
 s=change(s,'return isSplit() ? ("Split · " + count(outputs) + " coins") : ("Consolidation · " + count(inputs) + " coins");','return isSplit() ? Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_history_split, count(outputs)) : Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_history_consolidation, count(inputs));')
 p.write_text(s)
