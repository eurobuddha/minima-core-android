"""Retained PandaPools Wallet/Activity displays; preserve stored data and transaction evidence."""
from pathlib import Path
import json,re,xml.etree.ElementTree as E

def adapt(root):
 project=Path(__file__).resolve().parents[1];folder=root/'pandapools/src/main/java/com/eurobuddha/pandapools';fixed=json.loads((project/'localization/pools-messages.json').read_text())['fixed']
 def resource(key):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()
 def msg(k,*args):return 'Language.msg(l10n_pools_'+k+('' if not args else ', '+', '.join(args))+')'
 def change(s,old,new,count=1):
  assert s.count(old)==count,('PandaPools Wallet/Activity drift',old,s.count(old));return s.replace(old,new)
 def read(n):
  p=folder/(n+'.java');return p,change(p.read_text(),'package com.eurobuddha.pandapools;','package com.eurobuddha.pandapools;\nimport com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;')
 def labels(s):
  # Only application strings. Lowercase clipboard-label IDs are deliberately left raw for Ui.copy.
  def replace(m):
   try:v=json.loads(m[0])
   except ValueError:return m[0]
   if v in fixed and v not in ['address','coins','token','pool','From','To']:return 'Language.msg('+resource(fixed[v])+')'
   return m[0]
  return re.sub(r'"(?:\\.|[^"\\])*"',replace,s)
 p=folder/'Ui.java';s=p.read_text()
 a=s.index('        v.setOnClickListener(view -> {\n            Context c = view.getContext();');b=s.index('\n    /** A monospaced',a)
 old=s[a:b]
 body=old[old.index('            Context c'):old.index('\n        });')].replace('view.getContext()','v.getContext()')
 s=s[:a]+'''        v.setOnClickListener(view -> copy(view, label, fullValue));
    }

    /** Copy a current public value without replacing the caller's click listener. */
    public static void copy(View v, String label, String fullValue) {
        if (v == null || fullValue == null || fullValue.isEmpty()) return;
'''+body+'''
    }
''' +s[b:];p.write_text(s)
 p=folder/'PoolsMessages.java';s=p.read_text();s=change(s,'    public static CharSequence activityStatus(',r'''    /** Translate only the known local receipt format for its recorded action.
     * Stored summaries and node evidence are never modified. Captures are literal data. */
    public static CharSequence activitySummary(ActivityLog.Entry entry) {
        String value = entry.summary;
        if (value == null) return "";
        java.util.regex.Matcher match;
        switch (entry.type) {
            case ActivityLog.CREATE:
                match = RECEIPT_CREATE.matcher(value);
                if (match.matches()) return receiptAmounts(match, l10n_pools_receipt_create);
                return entry.failMsg == null ? value : receiptName(value, "Create MINIMA / ", " pool", l10n_pools_receipt_create_attempt);
            case ActivityLog.DEPOSIT:
                match = RECEIPT_ADD.matcher(value);
                if (match.matches()) return receiptAmounts(match, l10n_pools_receipt_add);
                return entry.failMsg == null ? value : receiptName(value, "Add to MINIMA / ", "", l10n_pools_receipt_add_attempt);
            case ActivityLog.MIGRATE:
                match = RECEIPT_MIGRATE.matcher(value);
                if (match.matches()) return receiptAmounts(match, l10n_pools_receipt_migrate);
                return entry.failMsg == null ? value : receiptName(value, "Migrate MINIMA / ", " pool", l10n_pools_receipt_migrate_attempt);
            case ActivityLog.CLOSE:
                match = RECEIPT_WITHDRAW.matcher(value);
                if (match.matches()) return receiptAmounts(match, l10n_pools_receipt_withdraw);
                return receiptName(value, "Automatic withdrawal after restore  ·  ", "", l10n_pools_receipt_restore);
            case ActivityLog.CONSOLIDATE:
                return receiptName(value, "Consolidate ", " coins", l10n_pools_consolidate_token);
            default: return value;
        }
    }
    private static CharSequence receiptName(String value, String prefix, String suffix, int resource) {
        return value.startsWith(prefix) && value.endsWith(suffix) && value.length() > prefix.length() + suffix.length()
                ? Language.msg(resource, value.substring(prefix.length(), value.length() - suffix.length())) : value;
    }
    private static CharSequence receiptAmounts(java.util.regex.Matcher match, int resource) {
        return Language.msg(resource, match.group(1), match.group(2), match.group(3));
    }
    private static java.util.regex.Pattern receiptPattern(String prefix, String middle, String suffix) {
        String amount = "(-?[0-9]+(?:\\.[0-9]+)?|—)";
        // The same token label occurs twice. A back-reference keeps delimiters inside token names literal.
        return java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(prefix) + "((?s:.*))"
                + java.util.regex.Pattern.quote(middle) + amount + " MINIMA \\+ " + amount + " \\1"
                + java.util.regex.Pattern.quote(suffix));
    }
    private static final java.util.regex.Pattern RECEIPT_CREATE = receiptPattern("Create MINIMA / ", " pool  ·  ", "");
    private static final java.util.regex.Pattern RECEIPT_ADD = receiptPattern("Add to MINIMA / ", "  ·  ", "");
    private static final java.util.regex.Pattern RECEIPT_MIGRATE = receiptPattern("Migrate MINIMA / ", " pool  ·  new size ", "");
    private static final java.util.regex.Pattern RECEIPT_WITHDRAW = receiptPattern("Withdraw MINIMA / ", "  ·  ", " back to wallet");
    public static CharSequence confirmation(int depth) {
        return depth < 0 ? Language.msg(l10n_pools_not_found_chain)
                : Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_pools_confirmations, depth, depth);
    }
    public static CharSequence reshuffle(HistoryEntry entry) {
        String label = entry.reshuffleLabel();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:Split|Consolidation) · ([0-9]+) coins").matcher(label);
        if (!m.matches()) return label;
        int count = Integer.parseInt(m.group(1));
        return Language.count(entry.isSplit() ? com.eurobuddha.minimacore.i18n.R.plurals.l10n_history_split
                : com.eurobuddha.minimacore.i18n.R.plurals.l10n_history_consolidation, count);
    }
    public static CharSequence direction(String direction) {
        switch (direction) {
            case "sent": return Language.msg(l10n_history_sent);
            case "received": return Language.msg(l10n_history_received);
            case "self": return Language.msg(l10n_history_self);
            default: return direction;
        }
    }
    public static CharSequence activityStatus(''');p.write_text(s)
 p=folder/'SwapView.java';s=p.read_text();s=change(s,'desc.setText(e.summary);','Language.setText(desc, PoolsMessages.activitySummary(e));');p.write_text(s)
 p,s=read('WalletView')
 s=change(s,'"Balance refresh failed: " + balanceError\n                + (balances.isEmpty() ? "" : " Showing the last successful read.")',msg('balance_error','PoolsMessages.message(balanceError)','balances.isEmpty() ? "" : '+msg('last_read')))
 s=change(s,'receiveAddr.isEmpty() ? "…" : "Receive:  " + receiveAddr','receiveAddr.isEmpty() ? "…" : '+msg('receive_address','receiveAddr'))
 s=change(s,'(b.coins + (b.coins == 1 ? " coin" : " coins")).toUpperCase()','Language.upper(Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_pools_coin_count, b.coins, b.coins))')
 s=change(s,'mono(b.breakdown(lastBalanceUpdate, System.currentTimeMillis()),','mono(PoolsMessages.message(b.breakdown(lastBalanceUpdate, System.currentTimeMillis())),')
 s=change(s,'"Consolidate " + b.name + " coins"',msg('consolidate_token','b.name'))
 s=change(s,'            Ui.copyable(copyAll, "coins", all.toString());\n            copyAll.performClick();','            Ui.copy(copyAll, "coins", all.toString());')
 s=change(s,'"The node reports " + count + " coins. Full coin listing is paused to protect this phone\'s node connection. "\n                            + "Balances above remain available. Use Consolidate to combine wallet coins."',msg('coin_list_paused','count'))
 s=change(s,'"Could not load coins: " + m',msg('load_coins_failed','PoolsMessages.message(m)'),2)
 s=change(s,'"Couldn\'t load coins: " + m',msg('load_coins_failed','PoolsMessages.message(m)'))
 s=change(s,'StringBuilder sb = new StringBuilder();','Language.TextBuilder sb = new Language.TextBuilder();\n        boolean first = true;')
 s=change(s,'sb.append("(the node\'s reply was over its size cap — sendable coins only)\\n\\n");','sb.append('+msg('sendable_only')+').append("\\n\\n");')
 s=change(s,'            sb.append(Util.tidyAmount(c.amount))','            if (!first) sb.append("\\n");\n            first = false;\n            sb.append(Util.tidyAmount(c.amount))')
 s=change(s,".append('\\n').append(c.coinid).append('\\n');",".append('\\n').append(c.coinid);")
 s=change(s,'return sb.toString().trim();','return sb.build();')
 s=s.replace('private String state(Coin c,','private CharSequence state(Coin c,').replace('private String tag(Coin c)','private CharSequence tag(Coin c)')
 s=s.replace('private TextView mono(String s,','private TextView mono(CharSequence s,').replace('String label, String value)','CharSequence label, String value)').replace('private TextView sectionLabel(String s)','private TextView sectionLabel(CharSequence s)').replace('private LinearLayout linkRow(String label,','private LinearLayout linkRow(CharSequence label,')
 s=s.replace('new AlertDialog.Builder(act)','act.languageDialog()')
 s=labels(s)
 s=change(s, 'j.optString("message", Language.msg(l10n_pools_balance_unavailable))', 'j.optString("message", "Balance unavailable.")')
 s=re.sub(r'\b(t|tv|hint|title|coinsView|copyAll|idv|d|out|l|v)\.setText\(',r'Language.setText(\1, ',s)
 # Apply a useful touch target to the existing public actions, without changing their listeners.
 for name in ['tools','check','resolve','consolidate','copyAll']:
  marker=name+'.setOnClickListener';s=s.replace(marker,name+'.setMinHeight(dp(48));\n        '+marker)
 p.write_text(s)
 p,s=read('ActivityView')
 s=change(s,'line(e.summary,','line(PoolsMessages.activitySummary(e),')
 s=change(s,'        refreshTv.setTextColor(Design.accent());','        refreshTv.setTextColor(Design.accent());\n        Language.description(refreshTv, '+msg('refresh_activity')+');')
 s=change(s,'String state = ActivityLog.checkStatus();','CharSequence state = PoolsMessages.message(ActivityLog.checkStatus());')
 s=change(s,'String publicState = publicSync.status();','CharSequence publicState = PoolsMessages.message(publicSync.status());')
 s=change(s,'if (!publicState.isEmpty()) state = publicState + (state.isEmpty() ? "" : " · " + state);','if (publicState.length() > 0) state = Language.join(publicState, state.length() == 0 ? "" : Language.join(" · ", state));')
 s=change(s,'state = "Syncing node history…" + (state.isEmpty() ? "" : " · " + state);','state = Language.join('+msg('syncing_history')+', state.length() == 0 ? "" : Language.join(" · ", state));')
 s=change(s,'state.isEmpty() ? View.GONE','state.length() == 0 ? View.GONE')
 s=change(s,'"Show more (" + (total - shown) + " remaining) ▾"',msg('show_more','total - shown'))
 s=change(s,'String action;','CharSequence action;')
 s=change(s,'action + " · " + trim(event.minima) + " MINIMA / " + trim(event.token) + " " + tokenName','Language.join(action, " · ", trim(event.minima), " MINIMA / ", trim(event.token), " ", tokenName)')
 s=change(s,'event.pool + " · Transaction " + absolute(tx.timemilli)','Language.join(event.pool, " · ", '+msg('transaction_when','absolute(tx.timemilli)')+')')
 for v in ['tx','n']:
  s=change(s,'ActivityLog.confirmationText('+v+'.verifiedDepth)','PoolsMessages.confirmation('+v+'.verifiedDepth)')
 for v in ['tx','e','n']:s=change(s,'"Checked " + relative('+v+'.verifiedAt)',msg('checked_when','relative('+v+'.verifiedAt)'))
 s=change(s,'e.statusText(cb)','PoolsMessages.activityStatus(e, cb)',2)
 a=s.index('        String meta =');b=s.index('        TextView sub =',a)
 s=s[:a]+'''        CharSequence meta = history != null && history.timemilli > 0
                ? '''+msg('transaction_when','absolute(eventTime)')+''' : '''+msg('submitted_when','absolute(eventTime)')+''';
        if (e.failed && e.failMsg != null && !e.failMsg.isEmpty()) meta = Language.join(meta, " · ", PoolsMessages.message(e.failMsg));
        else if (e.txpowid != null && !e.txpowid.isEmpty()) meta = Language.join(e.txpowid, "  ·  ", meta);
'''+s[b:]
 a=s.index('                .setMessage("TxPoW: ');b=s.index('\n                .setPositiveButton',a)
 s=s[:a]+'''                .setMessage(Language.join(
                        '''+msg('receipt_txpow','e.txpowid == null ? '+msg('value_unavailable')+' : e.txpowid')+''',
                        e.originalTxpowid != null && !e.originalTxpowid.equalsIgnoreCase(e.txpowid)
                                ? Language.join("\\n\\n", '''+msg('original_id','e.originalTxpowid')+''') : "",
                        "\\n\\n", history != null ? '''+msg('transaction_time','absolute(eventTime)')+''' : '''+msg('submitted_time','absolute(eventTime)')+''',
                        "\\n\\n", '''+msg('transaction_id_detail','e.transactionId.isEmpty() ? '+msg('older_id_missing')+' : e.transactionId')+''',
                        e.verifiedAt > 0 ? Language.join("\\n\\n", '''+msg('last_node_check','Language.stamp(e.verifiedAt, "yMMMdHmsz")')+''') : "",
                        e.transactionId.isEmpty() && e.verifiedDepth < 0 ? Language.join("\\n\\n", '''+msg('receipt_match_note')+''') : ""))'''+s[b:]
 s=change(s,'(reshuffle ? n.reshuffleLabel() + "  ·  " : cp) + absolute(n.timemilli)','Language.join(reshuffle ? Language.join(PoolsMessages.reshuffle(n), "  ·  ") : cp, absolute(n.timemilli))')
 s=change(s,'String glyphStr, desc, line2Str;','String glyphStr;\n        CharSequence desc, line2Str;')
 s=change(s,'String poolTime = ev.pool + "  ·  Observed on this phone " + absolute(ev.ts)\n                + " · Transaction time unknown";','CharSequence poolTime = '+msg('observation_time','ev.pool','absolute(ev.ts)')+';')
 for raw,key,amount in [('Pool first seen (unverified) · ','observed_create','m'),('Reserve increase observed (unverified) · +','observed_add','m'),('Reserve decrease or disappearance observed (unverified) · ','observed_withdraw','m')]:
  old=json.dumps(raw,ensure_ascii=False)+' + m + " MINIMA / '+('+'if key=='observed_add'else'')+'" + t + " " + tl'
  new='Language.join('+msg(key)+', " · '+('+'if key=='observed_add'else'')+'", m, " MINIMA / '+('+'if key=='observed_add'else'')+'", t, " ", tl)'
  s=change(s,old,new)
 for raw,key,arrow in [('Reserve change observed (unverified): sold ','observed_sold','→'),('Reserve change observed (unverified): bought ','observed_bought','←')]:
  s=change(s,json.dumps(raw,ensure_ascii=False)+' + m + " MINIMA  '+arrow+'  " + t + " " + tl','Language.join('+msg(key)+', " ", m, " MINIMA  '+arrow+'  ", t, " ", tl)')
 s=change(s,'trim8(ev.price) + " " + tl + "/MINIMA  ·  " + poolTime','Language.join(trim8(ev.price), " ", tl, "/MINIMA  ·  ", poolTime)')
 s=change(s,'kv(box, "Direction", n.direction);','kv(box, "Direction", PoolsMessages.direction(n.direction));')
 s=change(s,'new SimpleDateFormat("dd MMM yyyy  HH:mm:ss", Locale.ENGLISH).format(new Date(n.timemilli))','Language.stamp(n.timemilli, "yMMMdHms")')
 s=change(s,'String k, String v)','CharSequence k, CharSequence v)');s=change(s,'t.setText(k + ":  " + v);','t.setText(Language.join(k, ":  ", v));')
 s=change(s,'String title, String json)','CharSequence title, String json)')
 for helper in ['header','empty','statusChip','line']:s=s.replace(helper+'(String s',helper+'(CharSequence s')
 s=change(s,'private static String prettyType','private static CharSequence prettyType')
 a=s.index('    private static String absolute(');b=s.index('    private static String trim(',a)
 s=s[:a]+'''    private static CharSequence absolute(long ms) {
        return ms <= 0 ? '''+msg('unknown')+''' : Language.stamp(ms, "yMMMdHmsz");
    }
    private static CharSequence relative(long ms) {
        if (ms <= 0) return "";
        return System.currentTimeMillis() - ms < 7 * 86400000L ? Language.since(ms) : Language.stamp(ms, "yMMMd");
    }

'''+s[b:]
 s=s.replace('new androidx.appcompat.app.AlertDialog.Builder(act)','act.languageDialog()').replace('new AlertDialog.Builder(act)','act.languageDialog()')
 s=labels(s)
 # Identifier labels remain raw Ui IDs and are translated by that shared copy helper.
 s=change(s,'copyRow(box, Language.msg(l10n_pools_txpow_id), n.txpowid);','copyRow(box, "Txpow id", n.txpowid);')
 s=re.sub(r'\b(statusTv|line1|line2|t|h)\.setText\(',r'Language.setText(\1, ',s)
 s=change(s,'        more.setOnClickListener','        more.setMinHeight(dp(48));\n        more.setOnClickListener')
 s=change(s,'        t.setPadding(dp(8), dp(3), dp(8), dp(3));','        t.setPadding(dp(8), dp(3), dp(8), dp(3));\n        t.setMaxWidth(dp(160));')
 p.write_text(s)
 a='{http://schemas.android.com/apk/res/android}';E.register_namespace('android',a[1:-1]);p=root/'pandapools/src/main/res/layout/pm_pandapools_view_activity.xml';tree=E.parse(p)
 for v in tree.iter():
  text=v.get(a+'text')
  if text in fixed:
   key=resource(fixed[text]);assert not v.get(a+'tag');v.set(a+'text','@string/'+key);v.set(a+'tag','l10n:text='+key)
  if v.get(a+'id','').endswith(('actPersonal','actGlobal')):v.set(a+'layout_width','0dp');v.set(a+'layout_weight','1');v.set(a+'minHeight','48dp');v.set(a+'gravity','center')
  if v.get(a+'id','').endswith('actRefresh'):v.set(a+'minHeight','48dp');v.set(a+'minWidth','48dp');v.set(a+'gravity','center')
 # Remove the old weighted spacer so both translated scope tabs share available width.
 for parent in tree.iter():
  for child in list(parent):
   if child.tag=='TextView'and child.get(a+'layout_weight')=='1'and not child.get(a+'id'):parent.remove(child)
 tree.write(p,encoding='utf-8',xml_declaration=True);p.write_text('\n'.join(line.rstrip()for line in p.read_text().splitlines())+'\n')
