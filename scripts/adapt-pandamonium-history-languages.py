"""Bind the existing History UI to the shared native catalog; keep its stored/exported data literal."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET


def adapt(root):
    folder = root/'history/src/main/java/com/eurobuddha/history'
    p = folder/'HistoryScreen.java'
    s = p.read_text()
    def change(old, new, count=1):
        nonlocal s
        assert s.count(old) == count, ('History localization drift', old, s.count(old))
        s = s.replace(old, new)
    imports = '''
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.LanguageDialog;
import static com.eurobuddha.minimacore.i18n.R.string.*;
import static com.eurobuddha.minimacore.i18n.R.plurals.*;
'''
    change('package com.eurobuddha.history;','package com.eurobuddha.history;\n'+imports)
    change('        header = findViewById', '        Language.apply(screen);\n        header = findViewById')
    # Only UI literals. Database columns, raw direction IDs and export formats stay unchanged.
    literals = {
        '"Syncing…"':'Language.msg(l10n_history_syncing)',
        '"Syncing…  " + totalNew + " captured"':'Language.msg(l10n_history_captured, totalNew)',
        '"⟳ Sync"':'Language.msg(l10n_history_sync)',
        '"Couldn\'t read history — the node may be busy or a page too large. Tap ⟳ to retry."':'Language.msg(l10n_history_read_failed)',
        '"Direction"':'Language.msg(l10n_history_direction)', '"Amount"':'Language.msg(l10n_history_amount)',
        '"Block"':'Language.msg(l10n_history_block)', '"Time"':'Language.msg(l10n_history_date_time)',
        '"Txpow id"':'Language.msg(l10n_history_txpow)', '"Tokenid"':'Language.msg(l10n_history_token)',
        '"From"':'Language.msg(l10n_history_from)', '"To"':'Language.msg(l10n_history_to)',
        '"No wallet address involved — contract/external activity"':'Language.msg(l10n_history_foreign_activity)',
        '"Inputs"':'Language.msg(l10n_history_inputs)', '"Outputs"':'Language.msg(l10n_history_outputs)',
        '"Transaction"':'Language.msg(l10n_history_transaction)', '"Close"':'Language.msg(l10n_language_close)',
        '"Copied"':'Language.msg(l10n_history_copied)', '"Per-token effect"':'Language.msg(l10n_history_per_token)',
        '"Minima Core isn\'t installed."':'Language.msg(l10n_history_core_missing)',
        '"Export as JSON (backup)"':'Language.msg(l10n_history_export_json)',
        '"Export as CSV (spreadsheet)"':'Language.msg(l10n_history_export_csv)',
        '"Import history (restore / merge)"':'Language.msg(l10n_history_import_history)',
        '"Hide foreign contract activity"':'Language.msg(l10n_history_hide_foreign)',
        '"Exported " + all.size() + " transactions"':'Language.count(l10n_history_exported, all.size())',
        '"Export failed: " + e.getMessage()':'Language.msg(l10n_history_export_failed, e.getMessage())',
        '"Imported " + ft + " · " + fn + " new"':'Language.msg(l10n_history_imported, ft, fn)',
        '"Import failed: " + e.getMessage()':'Language.msg(l10n_history_import_failed, e.getMessage())',
        '"Exported " + all.size() + " rows to CSV"':'Language.count(l10n_history_csv_rows, all.size())',
        '"CSV export failed: " + e.getMessage()':'Language.msg(l10n_history_csv_failed, e.getMessage())'
    }
    for old,new in literals.items():
        assert old in s, ('Missing History UI literal', old)
        s = s.replace(old,new)
    change('String s = total + (total == 1 ? " transaction" : " transactions");',
           'CharSequence s = Language.count(l10n_history_transactions, total);')
    change('s += " · synced to block " + chainBlock;',
           's = Language.join(s, " · ", Language.msg(l10n_history_synced_block, chainBlock));')
    change('s += " · " + relative(Long.parseLong(last));',
           's = Language.join(s, " · ", relative(Long.parseLong(last)));')
    change('Language.msg(l10n_history_direction), e.direction',
           'Language.msg(l10n_history_direction), directionLabel(e.direction)')
    change('new SimpleDateFormat("dd MMM yyyy  HH:mm:ss", Locale.ENGLISH).format(new Date(e.timemilli))',
           'Language.stamp(e.timemilli, "yMMMdHms")')
    change('new AlertDialog.Builder(this)', 'new LanguageDialog(this)')
    change('        t.setTextColor(HistoryDesign.DIM);\n', '')  # Native dialog colour remains legible in both themes.
    s=s.replace('String k, String v)', 'CharSequence k, CharSequence v)')
    s=s.replace('String k, final String v', 'CharSequence k, final String v')
    s=s.replace('String title)', 'CharSequence title)').replace('String title, String json)', 'CharSequence title, String json)')
    change('k + ":  " + v + "   (tap to copy)"','Language.msg(l10n_history_copy_row, k, v)')
    change('k + ":  " + v','Language.join(k, ":  ", v)')
    change('Ownership.label(kind) + (mine && kind == Ownership.Kind.CONTRACT ? " · YOURS" : "")',
           'Language.join(Ownership.label(kind), mine && kind == Ownership.Kind.CONTRACT ? Language.join(" · ", Language.msg(l10n_history_yours)) : "")')
    change('copyRow(p, "token", tid, 14)', 'copyRow(p, Language.msg(l10n_history_token), tid, 14)',2)
    change('copyRow(p, "addr", c.optString("addr", ""), 14)', 'copyRow(p, Language.msg(l10n_history_address), c.optString("addr", ""), 14)')
    change('String tag = ck == Ownership.Kind.UNKNOWN ? "" : Ownership.label(ck) + "  ·  ";',
           'CharSequence tag = ck == Ownership.Kind.UNKNOWN ? "" : Language.join(Ownership.label(ck), "  ·  ");')
    change('String cp = e.counterparty.isEmpty() ? "" : Util.shorten(e.counterparty) + "  ·  " + tag;',
           'CharSequence cp = e.counterparty.isEmpty() ? "" : Language.join(Util.shorten(e.counterparty), "  ·  ", tag);')
    change('(reshuffle ? e.reshuffleLabel() + "  ·  " : cp) + relative(e.timemilli)',
           'Language.join(reshuffle ? Language.join(e.reshuffleLabel(), "  ·  ") : cp, relative(e.timemilli))')
    start=s.index('    private static String relative(long ms) {')
    end=s.index('\n    private int dp',start)
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
    s=re.sub(r'\b(status|syncBtn|warn|t|h|h\.glyph|h\.line1|h\.line2|h\.right)\.setText\(',r'Language.setText(\1, ',s)
    p.write_text(s)
    # Preserve the existing ownership classification and split/consolidation count algorithms.
    p=folder/'Ownership.java';s=p.read_text().replace('public static String label(Kind k)', 'public static CharSequence label(Kind k)')
    s=s.replace('package com.eurobuddha.history;', 'package com.eurobuddha.history;\n'+imports)
    for english,key in [('YOURS','yours'),('CONTRACT','contract'),('EXTERNAL','external')]:
        s=s.replace('return "'+english+'";', 'return Language.msg(l10n_history_'+key+');')
    p.write_text(s)
    p=folder/'HistoryEntry.java';s=p.read_text().replace('package com.eurobuddha.history;', 'package com.eurobuddha.history;\n'+imports)
    s=s.replace('public String reshuffleLabel()', 'public CharSequence reshuffleLabel()')
    old='return isSplit() ? ("Split · " + count(outputs) + " coins") : ("Consolidation · " + count(inputs) + " coins");'
    assert old in s
    s=s.replace(old,'return isSplit() ? Language.count(l10n_history_split, count(outputs)) : Language.count(l10n_history_consolidation, count(inputs));')
    p.write_text(s)
    p=root/'history/src/main/res/layout/pm_history_activity_main.xml';tree=ET.parse(p);a='{http://schemas.android.com/apk/res/android}'
    mapping={'⟳ Sync':'sync','Loading…':'loading','Minima History is not enabled yet':'nodeUnavailable',
        'The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry.':'nodeRetry',
        'Open Minima Core':'openCore','Search address, id, token, amount…':'search'}
    def resource(key):return 'l10n_history_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).lower()
    for view in tree.iter():
        for prop in ('text','hint'):
            value=view.get(a+prop)
            if value in mapping:
                res=resource(mapping[value]);view.set(a+prop,'@string/'+res);view.set(a+'tag','l10n:'+prop+'='+res)
        if view.get(a+'id')=='@+id/pm_history_menuBtn':
            view.set(a+'contentDescription','@string/l10n_history_menu');view.set(a+'tag','l10n:description=l10n_history_menu')
    ET.register_namespace('android',a[1:-1]);ET.indent(tree,space='    ');tree.write(p,encoding='unicode',xml_declaration=True)

if __name__=='__main__':
    import sys
    adapt(Path(sys.argv[1]))
