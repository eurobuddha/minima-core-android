"""Translate AtomiX diagnostic displays; preserve chain evidence and literal data."""
from pathlib import Path
import json,re,importlib.util

def adapt(root):
 project=Path(__file__).resolve().parents[1]
 labels=json.loads((project/'localization/atomix-report-labels.json').read_text())
 catalog=json.loads((project/'localization/catalog.json').read_text())
 folder=root/'atomix/src/main/java/com/eurobuddha/atomix'
 def msg(key,*args):
  resource='l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',labels[key]).replace('.','_').lower()
  return 'Language.msg('+resource+('' if not args else ', '+', '.join(args))+')'
 p=folder/'MainActivity.java';s=p.read_text()
 start=s.index('    private void minimaCoinDump()');end=s.index('    private void showText(',start);part=s[start:end]
 def rep(old,new):
  nonlocal part
  assert old in part,('Coin diagnostic drift',old);part=part.replace(old,new)
 rep('toast("Reading coins…")','toast('+msg('reading')+')')
 rep('StringBuilder sb = new StringBuilder();','Language.TextBuilder sb = new Language.TextBuilder();')
 a=part.index('                sb.append("sendable ")');b=part.index('                java.math.BigDecimal sum',a)
 part=part[:a]+'                sb.append('+msg('balance','AtomixMessages.message(Util.tidyAmount(minimaBal))','Util.tidyAmount(minimaConfirmed)','coins.length()')+');\n'+part[b:]
 rep('String tag = "";','CharSequence tag = "";')
 rep('" (swap HTLC)"',msg('htlc'));rep('" (order book)"',msg('book'))
 rep('sb.append("\\nsum of relevant coins ").append(Util.tidyAmount(sum.toPlainString()));','sb.append('+msg('sum','Util.tidyAmount(sum.toPlainString())')+');')
 rep('"Coin breakdown"',msg('breakdown'));rep('sb.toString()','sb.build()')
 rep('"Failed: " + m',msg('coinFailed','m'))
 a=part.index('.setMessage(')+len('.setMessage(');b=part.index('\n                    .setPositiveButton',a)
 part=part[:a]+msg('oversized','balance.sendable','balance.confirmed','balance.unconfirmed','balance.coins','MinimaHtlc.maxSafeCoinRows(token)')+')'+part[b:]
 rep('"Consolidate"',msg('consolidate'));rep('"Close"',msg('close'))
 s=s[:start]+part+s[end:]
 start=s.index('    private void showReport(');end=s.index('    /** Plain-language description',start);part=s[start:end]
 old='StringBuilder sb = new StringBuilder();\n        for (String l : lines) sb.append(l).append("\\n\\n");'
 assert old in part
 part=part.replace(old,'Language.TextBuilder sb = new Language.TextBuilder();\n        boolean first = true;\n        for (String l : lines) {\n            if (!first) sb.append("\\n\\n");\n            sb.append(ReportMessages.message(l)); first = false;\n        }')
 assert 'sb.toString().trim()'in part;part=part.replace('sb.toString().trim()','sb.build()');s=s[:start]+part+s[end:];p.write_text(s)
 loader=importlib.util.spec_from_file_location('report_message_builder',project/'scripts/adapt-pandamonium-wallet-error-languages.py')
 module=importlib.util.module_from_spec(loader);loader.loader.exec_module(module)
 source=module.build_source(json.loads((project/'localization/atomix-report-messages.json').read_text()),catalog)
 source=source.replace('package com.eurobuddha.wallet;','package com.eurobuddha.atomix;').replace('WalletMessages','ReportMessages').replace('Explicit wallet failures only.','Explicit inspection report lines only.').replace('adapt-pandamonium-wallet-error-languages.py from localization/wallet-errors.json','adapt-pandamonium-atomix-report.py from localization/atomix-report-messages.json')
 (folder/'ReportMessages.java').write_text(source)
 p=folder/'swap/SwapEngine.java';s=p.read_text()
 start=s.index('    /** The recorded-transaction line,');end=s.index('    /** Body of the "Add ETH for gas"',start)
 expected=s[start:end];assert 'Nothing is locked' in expected
 s=s[:start]+'''    /** A missing local acknowledgement cannot distinguish an unposted write from a lost reply.
     * The swap and secret are saved before broadcast; only PostCb.ok records the transaction id. */
    static String recordedTxnSummary(int recordedCount) {
        if (recordedCount > 0) return "Recorded transactions: " + recordedCount + " (listed above).";
        return '''+json.dumps(catalog['atomixReport.none']['en'],ensure_ascii=False)+''';
    }

'''+s[end:];p.write_text(s)
