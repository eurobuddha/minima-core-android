"""Localize the embedded wallet setup without replacing views or touching wallet values."""
from pathlib import Path
import json
import re


def adapt(root):
    path = root/'wallet/src/main/java/com/eurobuddha/wallet/MainActivity.java'
    s = path.read_text()
    catalog = json.loads((Path(__file__).resolve().parents[1]/'localization/catalog.json').read_text())

    def change(old, new, count=1):
        nonlocal s
        assert s.count(old) == count, ('Wallet localization drift', old, s.count(old))
        s = s.replace(old, new)

    def message(key, args=''):
        name = 'l10n_' + re.sub(r'([a-z0-9])([A-Z])', r'\1_\2', key).replace('.', '_').lower()
        return 'Language.msg(' + name + (', ' + args if args else '') + ')'

    def block(pattern, value):
        nonlocal s
        s, count = re.subn(pattern, lambda _: value, s, flags=re.S)
        assert count == 1, ('Wallet localization block drift', pattern, count)

    change('package com.eurobuddha.wallet;', '''package com.eurobuddha.wallet;

import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.LanguageDialog;
import static com.eurobuddha.minimacore.i18n.R.string.*;''')

    # Entire composed messages must remain dynamic; Java string concatenation would freeze them.
    block(r'col.addView\(body\("Write these words down and keep them safe\..*?\)\);',
          'col.addView(body('+message('wallet.backupSeedBody')+'));')
    block(r'col.addView\(body\("Prove you wrote the seed down:.*?\)\);',
          'col.addView(body('+message('wallet.confirmBackupBody')+'));')
    block(r'col.addView\(body\("How many one-time \(WOTS\) signatures.*?\)\);',
          'col.addView(body('+message('wallet.usedBody','Util.WOTS_MAX_USES')+'));')
    block(r'String msg = zUsed == 0.*?;\n        new androidx',
          'CharSequence msg = zUsed == 0 ? '+message('wallet.confirmZero')+' : '
          +message('wallet.confirmUsed','zUsed')+';\n        new androidx')
    block(r'\.setMessage\(\(useNodeSeed \? "Use the Pandamonium node seed.*?separate\."\)',
          '.setMessage(Language.join(useNodeSeed ? Language.join('+message('wallet.nodeSeedConfirm')
          +', "\\n\\n") : "", msg, "\\n\\n", '+message('wallet.floorScope')+'))')
    change('"WORD #" + (idx + 1)', message('wallet.wordNumber','idx + 1'))
    change('"Word #" + (pick.get(i) + 1) + " does not match — check your written copy"',
           message('wallet.wordMismatch','pick.get(i) + 1'))
    change('"That exceeds this key\'s lifetime of " + Util.WOTS_MAX_USES + " signatures"',
           message('wallet.usedExhausted','Util.WOTS_MAX_USES'))
    change('"Deriving wallet addresses… " + derived + " of " + AddressBook.SIZE\n                    + " — keep the app open"',
           message('wallet.deriving','derived, AddressBook.SIZE'))
    change('"Registering addresses with the node… " + mTrackOk + " of " + AddressBook.SIZE',
           message('wallet.tracking','mTrackOk, AddressBook.SIZE'))
    change('failure instanceof IllegalArgumentException ? failure.getMessage()\n                    : "Wallet operation failed. Check the embedded node and try again."',
           '"Wrong wallet passphrase".equals(failure.getMessage()) ? '+message('wallet.wrongPassphrase')
           +' : '+message('wallet.operationFailed'))

    keys = '''busy preparing preparingBody changeSeed continue wrongPassphrase chooseSeed useNodeSeed
importDifferent createSeed sessionChanged verifyFailed setupIntro importExisting backupSeed passphraseLabel
confirmPassphrase passphraseShort passphraseMismatch confirmBackup createWallet showSeedAgain yourSeed
seedPrivacy nodeSeedTitle importSeedTitle importSeedBody seedHint usedLabel usedHint importWallet seedShort
usedRequired usedInteger usedNegative confirmFloor import unlockTitle unlockBody'''.split()
    overrides = {
        'changeSeedBody': "Export an encrypted backup first. The old seed's counters will be kept. Enter your current wallet passphrase to continue.",
        'nodeSeedBody': "The seed is read directly from the embedded node when you confirm. It will be stored in this wallet's encrypted backup format.",
        'selfCustodial': 'SELF-CUSTODIAL',
        'nodeUnavailable': 'This wallet is not enabled yet',
        'nodeRetry': 'Open Minima Core → Apps and enable “Minima Wallet” (no admin needed), then come back.',
        'balances': 'BALANCES', 'send': 'SEND', 'receive': 'RECEIVE', 'settings': 'SETTINGS',
    }
    for key in keys + list(overrides):
        literal = json.dumps(overrides.get(key, catalog['wallet.'+key]['en']), ensure_ascii=False)
        assert literal in s, ('Missing wallet label', key)
        s = s.replace(literal, message('wallet.'+key))
    for literal, key in [('Cancel','common.cancel'),('Close','language.close'),('Unlock','lock.unlock'),('HISTORY','tab.history')]:
        assert key in catalog, key
        literal = json.dumps(literal)
        assert literal in s, literal
        s = s.replace(literal, message(key))

    s = s.replace('new androidx.appcompat.app.AlertDialog.Builder(this)', 'new LanguageDialog(this)')
    change('new String[]{Language.msg', 'new CharSequence[]{Language.msg')
    for helper in ['bigTitle', 'body', 'label', 'toast']:
        change(helper+'(String s)', helper+'(CharSequence s)')
    for helper in ['primary','secondary','tab']:
        change(helper+'(String label,', helper+'(CharSequence label,')
    change('t.setText(s);','Language.setText(t, s);',3)
    change('t.setText(label);','Language.setText(t, label);')
    change('b.setText(label);','Language.setText(b, label);')
    for view in ['sub', 't1', 't2', 'mStatusBanner']:
        s = s.replace(view+'.setText(Language.msg(', 'Language.setText('+view+', Language.msg(')
    for view in ['phrase', 'used']:
        s = s.replace(view+'.setHint(Language.msg(', 'Language.hint('+view+', Language.msg(')
    path.write_text(s)

    path = root/'wallet/src/main/java/com/eurobuddha/wallet/SettingsView.java'
    s = path.read_text()
    change('package com.eurobuddha.wallet;', "package com.eurobuddha.wallet;\nimport com.eurobuddha.minimacore.i18n.Language;\nimport com.eurobuddha.minimacore.i18n.LanguageDialog;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;")
    for old,key in [('Set classic key-use floor…','setFloor'),('Classic key-use floor saved','floorSaved'),
                    ('Wallet session changed. Unlock again.','sessionChanged'),
                    ('minimum signatures already used per classic key','floorHint'),
                    ('Signatures already used','floorTitle'),('Set & enable signing','floorEnable'),
                    ('Enter a number','enterNumber'),('Whole number only','wholeNumber')]:
        change(json.dumps(old,ensure_ascii=False),message('wallet.'+key))
    change('"Change wallet seed…"','Language.join('+message('wallet.changeSeed')+', "…")')
    block(r'\.setMessage\("Set a lower bound for all 64 classic keys\..*?over-estimate\."\)',
          '.setMessage('+message('wallet.floorBody','current, Util.WOTS_MAX_USES')+')')
    change('"Out of range (0 … " + Util.WOTS_MAX_USES + ")"',message('wallet.floorRange','Util.WOTS_MAX_USES - 1'))
    s=s.replace('new androidx.appcompat.app.AlertDialog.Builder(act)','new LanguageDialog(act)')
    s=s.replace('"Cancel"',message('common.cancel'))
    change('outlineButton(String label,','outlineButton(CharSequence label,')
    change('btn.setText(label);','Language.setText(btn, label);')
    change('field.setHint(Language.msg(', 'Language.hint(field, Language.msg(')
    path.write_text(s)


if __name__ == '__main__':
    import sys
    adapt(Path(sys.argv[1]))
