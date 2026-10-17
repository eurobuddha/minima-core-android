"""Reuse History's retained display bindings; keep UTXO persistence and distribution scheduling intact."""
from pathlib import Path
import json,re

def adapt(root):
 project=Path(__file__).resolve().parents[1];folder=root/'utxo/src/main/java/com/eurobuddha/utxo'
 catalog=json.loads((project/'localization/catalog.json').read_text());labels=json.loads((project/'localization/utxo-history-labels.json').read_text())
 def resource(key):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()
 def msg(key,*args):return 'Language.msg('+resource(key)+('' if not args else ', '+', '.join(args))+')'
 def local(key,*args):return msg(labels[key],*args)
 def change(s,old,new):
  assert old in s,('UTXO history source drift',old)
  return s.replace(old,new)
 imports='\nimport com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;\n'
 p=folder/'HistoryView.java';s=p.read_text().replace('package com.eurobuddha.utxo;','package com.eurobuddha.utxo;'+imports)
 fixed={key:labels[key] for key in ['historyLoading','historyEmpty','historyOlder','statusPosting','statusPosted','statusUnknown','statusFailed','local','status','error','note','action','historyChange','historyBurn','spentInputs','failedTransaction','pendingTransaction','dismiss','explorer','noBrowser']}
 for key,full in fixed.items():s=change(s,json.dumps(catalog[full]['en'],ensure_ascii=False),msg(full))
 for raw,key in [('Direction','direction'),('Amount','amount'),('Block','block'),('Time','dateTime'),('Txpow id','txpow'),('Tokenid','token'),('From','from'),('To','to'),('Inputs','inputs'),('Outputs','outputs'),('Transaction','transaction'),('Copied','copied'),('Per-token effect','perToken')]:s=change(s,json.dumps(raw),'Language.msg('+resource('history.'+key)+')')
 s=change(s,'"Close"',msg('utxo.close'))
 s=change(s,'"Show more (" + (stored - rows.size()) + " stored) ▾"',local('historyMore','stored - rows.size()'))
 s=change(s,'(reshuffle ? n.reshuffleLabel() + "  ·  " : cp) + relative(n.timemilli)','Language.join(reshuffle ? Language.join(n.reshuffleLabel(), "  ·  ") : cp, relative(n.timemilli))')
 s=change(s,'private static String statusWord','private static CharSequence statusWord')
 s=change(s,'String to = r.recipient == null ? "" : (Util.isValidAddress(r.recipient) ? Util.shorten(r.recipient) : r.recipient);','CharSequence to = r.recipient == null ? "" : (Util.isValidAddress(r.recipient) ? Util.shorten(r.recipient) : UtxoMessages.message(r.recipient));')
 s=change(s,'to + "  ·  " + statusWord(r) + "  ·  " + relative(r.ts)','Language.join(to, "  ·  ", statusWord(r), "  ·  ", relative(r.ts))')
 s=change(s,'Language.msg(l10n_utxo_note), r.note)','Language.msg(l10n_utxo_note), UtxoMessages.message(r.note))')
 s=change(s,'Language.msg(l10n_utxo_action), r.recipient)','Language.msg(l10n_utxo_action), UtxoMessages.message(r.recipient))')
 for value in ['r.ts','n.timemilli']:s=change(s,'new SimpleDateFormat("dd MMM yyyy  HH:mm:ss", Locale.ENGLISH).format(new Date('+value+'))','Language.stamp('+value+', "yMMMdHms")')
 s=change(s,'Language.msg(l10n_history_direction), n.direction','Language.msg(l10n_history_direction), directionLabel(n.direction)')
 s=change(s,'copyRow(p, idKey, c.optString(idKey, ""), 14)','copyRow(p, "coinid".equals(idKey) ? '+msg('utxo.coinId')+' : idKey, c.optString(idKey, ""), 14)')
 s=change(s,'copyRow(p, "addr",','copyRow(p, '+msg('history.address')+',')
 s=change(s,'copyRow(p, "token",','copyRow(p, '+msg('history.token')+',')
 for old,new in [('String k, String v)','CharSequence k, CharSequence v)'),('String k, final String v','CharSequence k, final String v'),('String title, String json','CharSequence title, String json'),('String title)','CharSequence title)')]:s=change(s,old,new)
 s=change(s,'k + ":  " + v + "   (tap to copy)"',msg('history.copyRow','k','v'))
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
 # Only the descriptive label changes; cached classification and gross/net calculations stay intact.
 p=folder/'NodeTx.java';s=p.read_text().replace('package com.eurobuddha.utxo;','package com.eurobuddha.utxo;'+imports)
 s=change(s,'public String reshuffleLabel()','public CharSequence reshuffleLabel()')
 s=change(s,'return isSplit() ? ("Split · " + outs() + " coins") : ("Consolidation · " + ins() + " coins");','return isSplit() ? Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_history_split, outs()) : Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_history_consolidation, ins());');p.write_text(s)
 # All engine strings stay raw at their source; toast translates only at the display boundary.
 p=folder/'DistributeManager.java';s=p.read_text().replace('package com.eurobuddha.utxo;','package com.eurobuddha.utxo;'+imports)
 s=change(s,'public String statusLine()','public CharSequence statusLine()')
 s=change(s,'''        String s = "Distribute running · " + job.batchesDone + " batch(es) done · "
                + job.remaining.size() + " address(es) left"
                + (job.waiting ? " · waiting for confirmation…" : " · posting…");
        if (job.retryCount > 0) s += " · retry " + job.retryCount + "/" + MAX_RETRIES;''','''        CharSequence s = Language.join(Language.msg(l10n_utxo_distribution_progress, job.batchesDone, job.remaining.size()),
                " · ", job.waiting ? Language.msg(l10n_utxo_distribution_waiting) : Language.msg(l10n_utxo_status_posting));
        if (job.retryCount > 0) s = Language.join(s, " · ", Language.msg(l10n_utxo_distribution_retry, job.retryCount, MAX_RETRIES));''')
 s=change(s,'Toast.makeText(act, msg, Toast.LENGTH_SHORT)','Toast.makeText(act, UtxoMessages.message(msg), Toast.LENGTH_SHORT)');p.write_text(s)

 # The shared status view must stop showing running progress when its job ends.
 # Keep unrelated tool messages: only clear a label previously owned by distribution.
 p=folder/'WalletView.java';s=p.read_text()
 s=change(s,'    private final WalletTools tools;', '    private final WalletTools tools;\n    private boolean distributionStatus;')
 s=change(s,'    private void setStatus(CharSequence msg, boolean ok) {','    private void setStatus(CharSequence msg, boolean ok) {\n        distributionStatus = false;')
 s=change(s,'        if (dm != null && dm.isActive()) {','        if (dm != null && dm.isActive()) {\n            distributionStatus = true;')
 s=change(s,'            statusView.setTextColor(Design.accentText());\n        }','            statusView.setTextColor(Design.accentText());\n        } else if (distributionStatus) {\n            distributionStatus = false;\n            statusView.setVisibility(View.GONE);\n        }')
 p.write_text(s)
