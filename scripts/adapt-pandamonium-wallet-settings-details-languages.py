"""Remaining Settings labels and live diagnostic/recovery reports; commands stay literal."""
from pathlib import Path
import json
import re


def adapt(root):
    path=root/'wallet/src/main/java/com/eurobuddha/wallet/SettingsView.java'
    s=path.read_text()
    catalog=json.loads((Path(__file__).resolve().parents[1]/'localization/catalog.json').read_text())
    def msg(key,args=''):
        if '.' not in key:key='wallet.'+key
        assert key in catalog,key
        return 'Language.msg(l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()+(', '+args if args else '')+')'
    def change(old,new,count=1):
        nonlocal s
        assert s.count(old)==count,(old,s.count(old))
        s=s.replace(old,new)
    def label(old,key):
        old=json.dumps(old,ensure_ascii=False)
        assert old in s,old
        change(old,msg(key),s.count(old))
    def block(pattern,value):
        nonlocal s
        s,count=re.subn(pattern,lambda _:value,s,flags=re.S)
        assert count==1,(pattern,count)
    for old,key in [('SECURITY','security'),('Signatures used','signaturesUsed'),
        ('Keyuses record is NOT trusted','untrustedTitle'),
        ('This seed is BRAND-NEW (never signed anywhere)','brandNewSeed'),
        ('This restored backup is my MOST-RECENT state','latestBackup'),('Restore keyuses backup…','restoreKeyUses'),
        ('Confirm brand-new seed','confirmNewSeed'),('Yes, it is brand-new','yesUnused'),
        ('Could not update classic counters. Check the node and unlock again.','counterUpdateFailed'),
        ('Signing enabled (uses start at 0)','signingEnabled'),('Is this backup up to date?','backupCurrent'),
        ('I have NOT signed since this backup — enable signing','confirmLatest'),
        ('Signing enabled from the restored counter','signingRestored'),
        ('NODE','tab.node'),('Pairing','nodeConnection'),('Enabled (non-admin)','connected'),
        ('Not enabled','notConnected'),('Chain tip','chainTip'),('Our address','yourAddress'),
        ('DIAGNOSTICS','diagnostics'),('Run diagnostics','runDiagnostics'),('APP','appHeading'),
        ('Design','design'),('Toggle Light / Dark','toggleTheme'),('Wallet diagnostics','walletDiagnostics'),
        ('Copy','copy'),('Diagnostics copied','diagnosticsCopied'),
        ('[!] Node link is not available (wallet not started). Aborting.','nodeLinkUnavailable'),
        ('  (node commands below query the 0x form)','addressHexQuery'),
        ('=== end of diagnostics ===','endDiagnostics'),('(field not present — see raw below)','fieldAbsent'),
        ('RESTORE HISTORIC COINS','historicCoins'),('Paste the coinexport blob (0x…)','coinExportHint'),
        ('Import coin export','importCoinExport'),('Your address (Mx)','yourAddress'),
        ('Advanced — full node re-sync (optional):','advancedResync'),('Copy re-sync command','copyResync'),
        ('Advanced — needs admin, run it on your node yourself','resyncWarningTitle'),
        ('Paste a coin export blob first','pasteCoinExport'),
        ("That doesn't look like a coin export — expected a 0x… hex string",'invalidCoinExport'),
        ('Node link not available — open the wallet while paired first','nodeDisconnectedHelp'),
        ('Restore historic coin','restoreHistoricCoin'),
        ("[1/2] coincheck — validating the proof against your node's MMR…",'checkingCoinProof'),
        ('[2/2] coinimport track:true — inserting the coin and tracking it…','importingCoin'),
        ('Done. Refreshing balances…','coinsImported'),('Copied','history.copied')]:label(old,key)
    for pattern,key in [
        (r'"Each send consumes one one-time.*?MAX\)\."','signatureHelp'),
        (r'"The number of one-time \(WOTS\) signatures.*?TRUE for you:"','untrustedBody'),
        (r'"Only confirm if this seed has NEVER.*?starting from 0\?"','confirmNewSeedBody'),
        (r'"Each send uses a one-time \(WOTS\) signature.*?most-recent wallet\."','backupCurrentBody'),
        (r'"Makes the wallet↔node interaction visible.*?share it\."','diagnosticsHelp'),
        (r'"Coins sent to your address BEFORE.*?MegaMMR node\."','historicHelp'),
        (r'"Run this on your minimaCore node.*?for you\."','resyncWarning'),
        (r'"Not imported — the proof did not validate.*?try again\."','coinProofInvalid')]:block(pattern,msg(key))
    change('Design.label()', '''Design.mode() == Design.Mode.ORIGINAL_LIGHT ? '''+msg('designOriginalLight')+'''
                : Design.mode() == Design.Mode.ORIGINAL_DARK ? '''+msg('designOriginalDark')+' : '+msg('designCurrent'))
    # The translated instructions surround unchanged command syntax.
    block(r'"How to get this: on a MegaMMR node run.*?paste the result here\."',
        'Language.join('+msg('getCoinExport')+', "\\n\\ncoins address:<Mx> megammr:true\\ncoinexport coinid:<id>")')
    change('"=== Minima Wallet diagnostics ==="', 'Language.join("=== ", '+msg('walletDiagnostics')+', " ===")')
    change('"[1] PAIRING"','Language.join("[1] ", '+msg('nodeConnection')+')')
    block(r'"  " \+ \(paired.*?NodeApi.ERR_NOT_ENABLED \+ "\."\)',
        '(paired ? '+msg('nodeConnectedHelp')+' : '+msg('nodeDisconnectedHelp')+')')
    change('"[2] OUR RECEIVE ADDRESS"','Language.join("[2] ", '+msg('yourAddress')+')')
    change('"[4] " + cmd + " (cross-check ALL " + AddressBook.SIZE + " base addresses)"',
        'Language.join("[4] ", cmd, " — ", '+msg('checkBaseAddresses','AddressBook.SIZE')+')')
    change('"  NOT tracked (key index): " + missing',msg('notTracked','missing.toString()'))
    change('diagLine("  -> relaunch the wallet while paired to re-register, THEN resync the");\n                    diagLine("     node if you expect coins that pre-date the tracking.");',
        'diagLine('+msg('trackingHelp')+');')
    change('private static String megammrFlag(', 'private static CharSequence megammrFlag(')
    change('"  megammr flag: " + megammrFlag(r)', 'Language.join("  megammr: ", megammrFlag(r))')
    # Keep technical field names and raw node JSON as literal data, matching their response keys.
    change('"  derived locally : " + derived + " / " + AddressBook.SIZE',
        'Language.join("  ", '+msg('derivedAddresses','derived, AddressBook.SIZE')+')')
    change('"  tracked on node : " + hits + " / " + derived',
        'Language.join("  ", '+msg('trackedAddresses','hits, derived')+')')
    change('"  coin count: " + count', msg('diagnosticCoinCount','count'))
    change('"  first coin: amount=" + c0.optString("amount", "?")\n                        + " tokenid=" + c0.optString("tokenid", "0x00")\n                        + " coinid=" + c0.optString("coinid", "?")',
        'Language.join('+msg('firstCoin')+', ": amount=", c0.optString("amount", "?"), " tokenid=", c0.optString("tokenid", "0x00"), " coinid=", c0.optString("coinid", "?"))')
    for old,key,arg,count in [('"  raw: " + raw','rawResponse','raw',2),
        ('"  ERROR: " + message','nodeError','message',3),('"  error: " + err','nodeError','err',1),
        ('"  node: " + err','nodeError','err',1),('"  node: " + e2','nodeError','e2',1),
        ('"  valid: " + valid','proofValid','valid',1),('"  imported: " + imported','coinImported','imported',1)]:
        change(old,'Language.join('+msg(key)+', ": ", '+arg+')',count)
    for name in ['mDiag','mImportLog']:
        change('private StringBuilder '+name+';', 'private Language.TextBuilder '+name+';')
        change(name+' = new StringBuilder();', name+' = new Language.TextBuilder();')
        s=s.replace(name+'.toString()',name+'.build().toString()')
    change('private void diagLine(String line)', 'private void diagLine(CharSequence line)')
    change('Log.i(DIAG_TAG, line);', 'Log.i(DIAG_TAG, line.toString());')
    change('mDiagText.setText(mDiag.build().toString());', 'Language.setText(mDiagText, mDiag.build());')
    change('private void importLog(String line)', 'private void importLog(CharSequence line)')
    change('mImportText.setText(mImportLog.build().toString());', 'Language.setText(mImportText, mImportLog.build());')
    change('multilineField(String hint)', 'multilineField(CharSequence hint)')
    path.write_text(s)

if __name__=='__main__':
    import sys
    adapt(Path(sys.argv[1]))
