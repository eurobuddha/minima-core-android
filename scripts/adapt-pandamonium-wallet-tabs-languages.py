"""Live labels for the donor wallet tabs; transactions, addresses and exports remain literal."""
from pathlib import Path
import json
import re


def adapt(root):
    folder=root/'wallet/src/main/java/com/eurobuddha/wallet'
    catalog=json.loads((Path(__file__).resolve().parents[1]/'localization/catalog.json').read_text())
    imports='''package com.eurobuddha.wallet;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.LanguageDialog;
import static com.eurobuddha.minimacore.i18n.R.string.*;'''
    def msg(key,args=''):
        if '.' not in key:key='wallet.'+key
        assert key in catalog,key
        return 'Language.msg(l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()+(', '+args if args else '')+')'
    def quantity(key,count,args):
        return msg(key,args).replace('Language.msg(l10n_', 'Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_').replace(', '+args+')', ', '+count+', '+args+')')
    def change(old,new,count=1):
        nonlocal s
        assert s.count(old)==count,(name,old,s.count(old))
        s=s.replace(old,new)
    def literal(old,key):
        old=json.dumps(old,ensure_ascii=False)
        assert old in s,(name,old)
        change(old,msg(key),s.count(old))
    def block(pattern,replacement):
        nonlocal s
        s,count=re.subn(pattern,lambda _:replacement,s,flags=re.S)
        assert count==1,(name,pattern,count)
    def start(filename):
        nonlocal name,s
        name=filename;s=(folder/name).read_text()
        change('package com.eurobuddha.wallet;',imports)
    def save(): (folder/name).write_text(s)
    def dialogs():
        nonlocal s
        s=s.replace('new androidx.appcompat.app.AlertDialog.Builder(act)','new LanguageDialog(act)')
    def bind(*views):
        nonlocal s
        for view in views:s=re.sub(r'\b'+re.escape(view)+r'\.setText\(', 'Language.setText('+view+', ',s)
    name=s=''

    start('BalancesView.java')
    change('(b.coins + (b.coins == 1 ? " coin" : " coins")).toUpperCase()',msg('coinCount','b.coins'))
    for old,key in [('No balances yet.','noBalances'),('SENDABLE','sendable'),('LOCKED','locked'),('PENDING','pending'),
        ('Sendable','sendable'),('Confirmed','confirmed'),('Pending','pending'),('Locked','locked'),('Coins','coins'),
        ('Supply','supply'),('Ticker','ticker'),('Decimals','decimals'),('Owner','owner'),('Token ID','history.token'),
        ('Description','description'),('Website','website'),('Web validation','webValidation'),('↗ open','openLink'),
        ('Tap image for full resolution','fullImage'),('Tap image to enlarge','enlargeImage'),('Receive','receive'),('Close','language.close')]:literal(old,key)
    for old,new in [('mono(String s,','mono(CharSequence s,'),('splitRow(String label,','splitRow(CharSequence label,'),
        ('LinearLayout box, String label,','LinearLayout box, CharSequence label,'),('sectionLabel(String s)','sectionLabel(CharSequence s)'),
        ('linkRow(String label,','linkRow(CharSequence label,')]:change(old,new)
    change('symbol.toUpperCase()', 'symbol.toUpperCase(java.util.Locale.ROOT)')
    bind('tv','t','l','v','hint');dialogs();save()

    start('ReceiveView.java')
    for old,key in [('New address','newAddress'),('Your Minima address','yourAddress'),('Deriving address…','derivingAddress'),('Address copied','addressCopied')]:literal(old,key)
    change('"Your Minima address — #" + (mIndex + 1) + " of " + AddressBook.SIZE',msg('addressIndex','mIndex + 1, AddressBook.SIZE'))
    change('"Deriving your " + AddressBook.SIZE + " addresses… "\n                    + avail + " ready so far"',msg('addressesReady','AddressBook.SIZE, avail'))
    change('copy.setOnClickListener(v -> copyAddress());','Language.setText(copy, '+msg('copyAddress')+');\n        copy.setOnClickListener(v -> copyAddress());')
    bind('nextBtn','label','address');save()
    # Runtime labels are bound above; remove unused English layout defaults as well.
    p=root/'wallet/src/main/res/layout/pm_wallet_view_receive.xml';xml=p.read_text()
    for old,res in [('Your Minima address','your_address'),('Copy address','copy_address'),('Get address','new_address')]:
        assert 'android:text="'+old+'"' in xml
        xml=xml.replace('android:text="'+old+'"','android:text="@string/l10n_wallet_'+res+'"')
    p.write_text(xml)

    start('HistoryView.java')
    for old,key in [('Load older','loadOlder'),('EXPORT','export'),('Loading…','history.loading'),
        ("No transactions at this wallet's addresses yet",'noTransactions'),('Pair with Minima Core to load history','historyNodeUnavailable'),
        ('Nothing to export','nothingToExport'),('Export history','exportHistory'),('Transaction','history.transaction'),
        ('Copy txpowid','copyTxpow'),('Cancel','common.cancel'),('Close','language.close')]:literal(old,key)
    change('chip.setText(def[1]);','Language.setText(chip, directionLabel(value));')
    change('"History unavailable: " + message',msg('historyUnavailable','message'))
    change('"No " + mFilter.toLowerCase(Locale.UK) + " transactions in the loaded range"\n                    + (mEnd ? "" : " — try Load older")',
        'Language.join('+msg('noFilteredTransactions','directionLabel(mFilter)')+', mEnd ? "" : Language.join(" ", '+msg('loadOlderHint')+'))')
    change('shown.size() + (mFilter == null ? " transaction(s)" :\n                    " " + mFilter.toLowerCase(Locale.UK) + " transaction(s)") + " at this wallet\'s addresses"',msg('historyCount','shown.size(), directionLabel(mFilter)'))
    change('"Export " + shown.size() + " transaction(s)"',msg('exportCount','shown.size()'))
    change('dirView.setText(dir);','Language.setText(dirView, directionLabel(dir));')
    change('FMT.format(new Date(tx.timemilli)) + "  ·  block " + tx.block',msg('dateBlock','Language.stamp(tx.timemilli, "yMMMdHm"), tx.block'))
    # Build only the visible detail as messages. The CSV/JSON serializers keep their original fields/date format.
    block(r'        StringBuilder sb = new StringBuilder\(\);\n        sb.append\(tx.direction\(\)\).*?sb.append\("\\ntxpowid:\\n"\).append\(tx.txpowid\);', '''        CharSequence detail = Language.join(directionLabel(tx.direction()), "\\n",
                '''+msg('dateBlock','Language.stamp(tx.timemilli, "yMMMdHm"), tx.block')+''', "\\n\\n");
        for (Map.Entry<String, BigDecimal> e : tx.diff.entrySet()) {
            detail = Language.join(detail, Util.tidyAmount(e.getValue().toPlainString()), "  ", tx.tokenName(e.getKey()), "\\n");
            if (!Util.isMinima(e.getKey())) detail = Language.join(detail, '''+msg('history.token')+''', ": ", e.getKey(), "\\n");
        }
        if (!tx.counterparty.isEmpty()) detail = Language.join(detail, "\\n", '''+msg('counterparty')+''', ":\\n", tx.counterparty, "\\n");
        detail = Language.join(detail, "\\n", '''+msg('history.txpow')+''', ":\\n", tx.txpowid);''')
    change('msg.setText(sb.toString());','Language.setText(msg, detail);')
    bind('older','export','status','sub');dialogs()
    marker='    private static int dp(MainActivity a, int v) {'
    change(marker,'''    private static CharSequence directionLabel(String direction) {
        if (direction == null) return '''+msg('all')+''';
        switch (direction) {
            case "SENT": return '''+msg('history.sent')+''';
            case "RECEIVED": return '''+msg('history.received')+''';
            case "SELF": return '''+msg('history.self')+''';
            default: return direction;
        }
    }

'''+marker)
    save()

    start('SendView.java')
    for old,key in [('Send','send'),('Split','split'),('Consolidate','consolidate'),('TOKEN','token'),
        ('No coins yet — fund your address on the Receive tab.','fundAddress'),('RECIPIENT (Mx… or 0x…)','recipient'),
        ('Paste','paste'),('Scan QR','scanQr'),('AMOUNT','history.amount'),('MAX','max'),('BURN (optional)','burnOptional'),
        ('Review send','reviewSend'),('Split coins of the selected token into N equal self-coins (plus change).','splitBody'),
        ('AMOUNT TO SPLIT','amountToSplit'),('Review split','reviewSplit'),('Review consolidate','reviewConsolidate'),
        ('Confirm — review carefully','confirmTransaction'),('Sign & broadcast','signBroadcast'),('Cancel','common.cancel'),
        ('Broadcasting…','broadcasting'),('Sending is blocked','sendingBlocked'),('Open Settings','openSettings')]:literal(old,key)
    change('"NUMBER OF COINS (1.." + TxnFactory.MAX_SPLIT_COINS + ")"',msg('splitCount','TxnFactory.MAX_SPLIT_COINS'))
    change('"HOW MANY COINS (3.." + CoinSelector.MAX_INPUTS + ")"',msg('consolidateCount','CoinSelector.MAX_INPUTS'))
    change('"Merge many small coins of the selected token into one. Available coins: "\n                + available + ". Picks the smallest first."',msg('consolidateBody','available'))
    block(r'"This seed has no trusted signatures-used history,.*?"keyuses backup\."',msg('sendingBlockedBody'))
    block(r'"Raw 0x address — no typo protection\..*?scanned QR code\."',msg('rawAddressWarning'))
    # Exceptions require String; resolve only these immediate validation errors, never persisted data.
    for old,key,args in [('"Invalid recipient address"','invalidRecipient',''),('"N must be 1.." + TxnFactory.MAX_SPLIT_COINS','splitRange','TxnFactory.MAX_SPLIT_COINS'),
        ('"Consolidate needs at least 3 coins"','consolidateMinimum',''),('"Wallet session changed. Review again."','reviewAgain',''),
        ('"Coin address is not in our wallet: " + addr','foreignCoin','addr'),('"No coins of this token"','noTokenCoins',''),
        ('"Amount must be > 0"','positiveAmount',''),('"Burn cannot be negative"','negativeBurn','')]:
        change(old,msg(key,args).replace('Language.msg(', 'Language.text(act, '))
    change('"Could not prepare the coins: " + message',msg('prepareCoinsFailed','message'))
    change('"Sent. txpowid " + txpowid',msg('broadcastSent','txpowid'))
    change('"Broadcast failed: " + message\n                        + "\\n(Signature already consumed — that leaf is safely skipped.)"',msg('broadcastFailed','message'))
    # Capture immutable display values for the review; keep the original transaction builder and signing gate.
    block(r'            StringBuilder rev = new StringBuilder\(\);.*?if \(isMinima\(\)\) rev.append\("Burn: "\).append\(burn\).append\("\\n"\);', '''            CharSequence rev = Language.join('''+msg('token')+''', ": ", tokenLabel(), "\\n",
                    '''+msg('history.to')+''', ": ", recipient, "\\n",
                    '''+msg('history.amount')+''', ": ", mAmount.getText().toString(), "\\n",
                    '''+quantity('reviewInputs','sel.size()','sel.size(), inSum.toString()')+''', "\\n",
                    change.isMore(MiniNumber.ZERO) ? Language.join('''+msg('reviewChange','change.toString()')+''', "\\n") : "",
                    isMinima() ? Language.join('''+msg('reviewBurn','burn.toString()')+''', "\\n") : "");''')
    change('confirmAndSend(rev.toString(), sel,', 'confirmAndSend(rev, sel,')
    # Match the split and merge separately, since both begin with the same token label.
    block(r'            String rev = "Token: " \+ tokenLabel\(\) \+ "\\n"\n                    \+ "Split .*?\+ \(isMinima\(\) \? "Burn: " \+ burn \+ "\\n" : ""\);', '''            CharSequence rev = Language.join('''+msg('token')+''', ": ", tokenLabel(), "\\n",
                    '''+quantity('reviewSplitLine','n','mAmount.getText().toString(), n')+''', "\\n",
                    '''+quantity('reviewInputs','sel.size()','sel.size(), inSum.toString()')+''', "\\n",
                    change.isMore(MiniNumber.ZERO) ? Language.join('''+msg('reviewChange','change.toString()')+''', "\\n") : "",
                    isMinima() ? Language.join('''+msg('reviewBurn','burn.toString()')+''', "\\n") : "");''')
    block(r'            String rev = "Token: " \+ tokenLabel\(\) \+ "\\n"\n                    \+ "Merge .*?\+ \(isMinima\(\) \? "Burn: " \+ burn \+ "\\n" : ""\);', '''            CharSequence rev = Language.join('''+msg('token')+''', ": ", tokenLabel(), "\\n",
                    '''+quantity('reviewMergeLine','sel.size()','sel.size(), inSum.toString()')+''', "\\n",
                    '''+msg('reviewOutput','out.toString()')+''', "\\n",
                    isMinima() ? Language.join('''+msg('reviewBurn','burn.toString()')+''', "\\n") : "");''')
    change('confirmAndSend(String review,','confirmAndSend(CharSequence review,')
    change('String full = review\n                + keyUsageLines(sel)\n                + "\\n\\nThis is IRREVERSIBLE. Once broadcast it cannot be undone.";',
           'CharSequence full = Language.join(review, keyUsageLines(sel), "\\n\\n", '+msg('irreversible')+');')
    change('private String keyUsageLines(List<JSONObject> zSel)', 'private CharSequence keyUsageLines(List<JSONObject> zSel)')
    change('StringBuilder sb = new StringBuilder();','CharSequence lines = "";')
    change('sb.append("\\nKey ").append(ki).append(": using signature #")\n              .append(act.keyUses().currentUses(ki)).append(" of ").append(Util.WOTS_MAX_USES);',
           'lines = Language.join(lines, "\\n", '+msg('keyUsage','ki, act.keyUses().currentUses(ki), Util.WOTS_MAX_USES')+');')
    change('return sb.toString();','return lines;')
    for helper in ['label','hint']:change(helper+'(String s)',helper+'(CharSequence s)')
    for helper in ['modeChip','smallButton','reviewButton']:change(helper+'(String label,',helper+'(CharSequence label,')
    bind('t','b','d','go');dialogs();save()

if __name__=='__main__':
    import sys
    adapt(Path(sys.argv[1]))
