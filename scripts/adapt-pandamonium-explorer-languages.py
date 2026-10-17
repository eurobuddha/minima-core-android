"""Reuse History's live native text/date bindings for the imported Explorer."""
from pathlib import Path
import json,re,xml.etree.ElementTree as ET

def adapt(root):
    project=Path(__file__).resolve().parents[1]
    labels=json.loads((project/'localization/explorer-labels.json').read_text())
    def resource(key):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',labels[key]).replace('.','_').lower()
    def msg(key,*args):return 'Language.msg('+resource(key)+('' if not args else ', '+', '.join(args))+')'
    folder=root/'blockexplorer/src/main/java/com/eurobuddha/blockexplorer'
    p=folder/'MainActivity.java';s=p.read_text()
    def change(old,new,count=1):
        nonlocal s
        assert s.count(old)==count,('Explorer localization drift',old,s.count(old))
        s=s.replace(old,new)
    imports='\nimport com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;\n'
    change('package com.eurobuddha.blockexplorer;','package com.eurobuddha.blockexplorer;\n'+imports)
    change('        tipChip = findViewById','        Language.apply(findViewById(R.id.pm_blockexplorer_main));\n        tipChip = findViewById')
    # Exact display expressions, before the constituent fixed labels.
    replacements={
        'String.format(java.util.Locale.ENGLISH, "%.1f s", 1.0 / speed)':msg('seconds','String.format(java.util.Locale.ENGLISH, "%.1f", 1.0 / speed)'),
        'Util.groupNum(db.optLong("size", 0)) + " txns"':msg('txnTotal','Util.groupNum(db.optLong("size", 0))'),
        '"Looking up block " + Util.groupNum(height) + "…"':msg('lookupBlock','Util.groupNum(height)'),
        'notFound("Block " + height)':'notFound('+msg('block','height')+')',
        'what + " not found on this node"':msg('notFound','what'),
        '"‹ " + backLabel':'Language.join("‹ ", backLabel)',
        'Util.dateTime(ms) + (ms > 0 ? "   ·   " + Util.relative(ms) : "")':'Language.join(Util.dateTime(ms), ms > 0 ? Language.join("   ·   ", Util.relative(ms)) : "")',
        'txpow.optString("size", "—") + " bytes"':msg('bytes','txpow.optString("size", "—")'),
        '"Port " + s.optString("port", "?")':msg('port','s.optString("port", "?")'),
        '"✓ on chain · block " + r.optString("block", "?")\n                            + " · " + r.optString("confirmations", "?") + " confirmations"':msg('onChain','r.optString("block", "?")','r.optString("confirmations", "?")'),
        'title + (coins != null ? "  ·  " + coins.length() : "")':'Language.join(title, coins != null ? "  ·  " + coins.length() : "")',
        'Util.shorten(addr) + "   (tap to copy)"':msg('tapCopy','Util.shorten(addr)'),
        'arr.length() + " TxPoW" + (arr.length() == 1 ? "" : "s") + " on this node  ·  tap address to copy"':msg('addressCount','arr.length()'),
        '"block " + blockNo + (ms > 0 ? "  ·  " + Util.relative(ms) : "")':'Language.join('+msg('block','blockNo')+', ms > 0 ? Language.join("  ·  ", Util.relative(ms)) : "")',
        'key + "   (tap to copy)"':msg('tapCopy','key'),
        'Util.shorten(txpow.optString("txpowid", "")) + "  ·  " + Util.relative(ms)':'Language.join(Util.shorten(txpow.optString("txpowid", "")), "  ·  ", Util.relative(ms))',
        'n + (n == 1 ? " TXN" : " TXNS")':msg('transactionsBadge','n'),
        '"· end of retained chain ·"':'Language.join("· ", '+msg('chainEnd')+', " ·")',
    }
    for old,new in replacements.items():
        assert old in s,old;s=s.replace(old,new)
    fixed={
        'TIP HEIGHT':'tipHeight','BLOCK TIME':'blockTime','TXPOW DB':'txpowDb','LATEST BLOCKS':'latest',
        'node unreachable':'unreachable','loading…':'loading','end of retained chain':'chainEnd',
        'Enter a block number, TxPoW ID or address':'searchHelp','Searching…':'searching','Back':'back',
        'Block detail':'blockDetail','TxPoW ID':'txpowId','Superblock level':'superblock','Size':'size','Burn':'burn',
        'Difficulty':'difficulty','Cascade levels':'cascadeLevels','Parent block':'parentBlock','TRANSACTIONS':'transactions',
        'No transactions in this block':'noTransactions','⛏ carried in this TxPoW':'carried','Loading transaction…':'loadingTransaction',
        'Transaction':'transaction','confirmations: …':'confirmPending','Is block':'isBlock','yes — also a block':'alsoBlock','no':'no',
        'INPUTS':'inputs','OUTPUTS':'outputs','STATE VARIABLES':'state','not found on chain (mempool or pruned)':'offChain','none':'none',
        'Address':'address','BLK':'blockBadge','TXN':'txnBadge','{ }  View raw JSON  ▾':'rawShow',
        '{ }  Hide raw JSON  ▴   (long-press to copy)':'rawHide','Copied':'copied','Load older blocks  ▾':'older','EMPTY':'empty',
        "Minima Core isn't installed.":'coreMissing',
    }
    for raw,key in fixed.items():
        literal=json.dumps(raw,ensure_ascii=False);assert literal in s,raw;s=s.replace(literal,msg(key))
    # Keep text descriptors live through the existing view builders and callbacks.
    for old,new in [
        ('String label, boolean hero','CharSequence label, boolean hero'),('notFound(String what)','notFound(CharSequence what)'),
        ('toastProgress(String msg)','toastProgress(CharSequence msg)'),('newPage(String backLabel, String title)','newPage(CharSequence backLabel, CharSequence title)'),
        ('coinSection(LinearLayout parent, String title,','coinSection(LinearLayout parent, CharSequence title,'),
        ('sectionHeader(LinearLayout parent, String text)','sectionHeader(LinearLayout parent, CharSequence text)'),
        ('kv(LinearLayout parent, String key, String value)','kv(LinearLayout parent, CharSequence key, CharSequence value)'),
        ('copyRow(LinearLayout parent, String key, final String value)','copyRow(LinearLayout parent, CharSequence key, final String value)'),
        ('linkRow(LinearLayout parent, String label, String id,','linkRow(LinearLayout parent, CharSequence label, String id,'),
        ('copyToClip(String label, String value)','copyToClip(CharSequence label, String value)')]:change(old,new)
    change('        theme.setText(Design.DARK ? "☀" : "☾");','        theme.setText(Design.DARK ? "☀" : "☾");\n        Language.description(theme, Design.DARK ? '+msg('light')+' : '+msg('dark')+');')
    s=re.sub(r'\b([A-Za-z_][A-Za-z_0-9]*(?:\.[A-Za-z_][A-Za-z_0-9]*)*)\.setText\(',r'Language.setText(\1, ',s)
    p.write_text(s)
    p=folder/'Util.java';s=p.read_text().replace('package com.eurobuddha.blockexplorer;','package com.eurobuddha.blockexplorer;\n'+imports)
    start=s.index('    public static String relative(long ms) {');end=s.index('    /** timemilli',start)
    # Same timestamp descriptors used in HistoryScreen; token/amount parsing stays literal.
    s=s[:start]+'''    public static CharSequence relative(long ms) {
        if (ms <= 0) return "";
        return System.currentTimeMillis() - ms < 7 * 86400000L
                ? Language.since(ms) : Language.stamp(ms, "yMMMd");
    }

    public static CharSequence dateTime(long ms) {
        return ms <= 0 ? "—" : Language.stamp(ms, "yMMMdHms");
    }

'''+s[end:];p.write_text(s)
    p=root/'blockexplorer/src/main/res/layout/pm_blockexplorer_activity_main.xml';tree=ET.parse(p);a='{http://schemas.android.com/apk/res/android}'
    mapping={'Block № · TxPoW ID · 0x / Mx address':'searchHint','Search':'search','Minima Explorer is not enabled yet':'nodeUnavailable',
        'The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry.':'nodeRetry','Open Minima Core':'openCore'}
    for view in tree.iter():
        for prop in ['text','hint']:
            value=view.get(a+prop)
            if value in mapping:
                res=resource(mapping[value]);view.set(a+prop,'@string/'+res);view.set(a+'tag','l10n:'+prop+'='+res)
    ET.register_namespace('android',a[1:-1]);ET.indent(tree,space='    ');tree.write(p,encoding='unicode',xml_declaration=True)

if __name__=='__main__':
    import sys
    adapt(Path(sys.argv[1]))
