"""Translate existing wallet session/backup dialogs without rebuilding their inputs."""
from pathlib import Path
import json
import re


def adapt(root):
    folder = root/'wallet/src/main/java/com/eurobuddha/wallet'
    catalog = json.loads((Path(__file__).resolve().parents[1]/'localization/catalog.json').read_text())
    def msg(key, args=''):
        if '.' not in key: key = 'wallet.'+key
        assert key in catalog, key
        resource = 'l10n_'+re.sub(r'([a-z0-9])([A-Z])', r'\1_\2', key).replace('.', '_').lower()
        return 'Language.msg('+resource+(', '+args if args else '')+')'
    def change(old, new, count=1):
        nonlocal s
        assert s.count(old) == count, (name, old, s.count(old))
        s = s.replace(old, new)
    # Match an entire Java constant string expression, including adjacent literal concatenations.
    # Never edit variables, export contents, passwords or protocol identifiers.
    string = r'"(?:[^"\\]|\\.)*"'
    def label(old, key):
        nonlocal s
        hits = 0
        def replace(match):
            nonlocal hits
            value = ''.join(json.loads(part.group()) for part in re.finditer(string, match.group()))
            if value != old: return match.group()
            hits += 1
            return msg(key)
        s = re.sub(string+r'(?:\s*\+\s*'+string+r')*', replace, s)
        assert hits > 0, (name, old)
    def block(pattern, replacement):
        nonlocal s
        s, count = re.subn(pattern, lambda _: replacement, s, flags=re.S)
        assert count == 1, (name, pattern, count)

    name = 'SettingsView.java';s = (folder/name).read_text()
    for old,key in [('SEED & BACKUP','seedBackupHeading'),('Reveal seed phrase','revealSeed'),('Reveal','reveal'),('Your seed phrase','yourSeed'),('Close','language.close'),('Anyone with this phrase controls your funds. Make sure no one is watching.','revealSeedWarning'),('Write these words down on paper. Do NOT screenshot or copy them.','writeSeed'),('UNLOCK & SESSION','sessionHeading'),('Biometric unlock','bioLabel'),
        ('Not available on this device','bioUnavailable'),('Enabled','bioEnabled'),
        ('Disable biometric unlock','bioDisable'),('Enable biometric unlock','biometric.enable'),
        ('Auto-lock','lock.autoLock'),('Change auto-lock timeout','changeTimeout'),
        ('Auto-lock timeout','timeoutTitle'),('Change unlock passphrase','changePassphrase'),
        ('Lock now','lockNow'),('Wallet passphrase','walletPassphrase'),('Continue','continue'),
        ('Wrong passphrase','wrongPassphrase'),('Biometric unlock enabled','bioSuccess'),
        ('Disable','disable'),('Biometric unlock disabled','bioDisabled'),
        ('Current passphrase','currentPassphrase'),('New passphrase (min 12)','newPassphrase'),
        ('Confirm new passphrase','confirmNewPassphrase'),('Change','change'),
        ('New passphrases do not match','newPassphraseMismatch'),('Current passphrase is wrong','currentPassphraseWrong'),
        ('Passphrase changed; biometric updated','passphraseBioUpdated'),
        ('Passphrase changed. Biometric unlock was turned off — re-enable it if you want it.','passphraseBioOff'),
        ('Passphrase changed. Biometric unlock was turned off.','passphraseBioOff'),
        ('Passphrase changed','passphraseChanged'),('Export encrypted backup','exportBackup'),
        ('Backup passphrase (min 12)','backupPassphraseHint'),('Backup passphrase','backupPassphrase'),
        ('Choose file…','chooseFile'),('Restore from backup','restoreBackup'),('Restore','restore'),
        ('Enter the passphrase that protects this backup file.','restoreBackupBody'),
        ('Restore failed: wrong passphrase or corrupt file','restoreFailed')]:label(old,key)
    for old,key in [('Export encrypted backup…','exportBackup'),('Restore from backup…','restoreBackup')]:
        change(json.dumps(old,ensure_ascii=False),'Language.join('+msg(key)+', "…")')
    blocks = [
        (r'"The backup is a small file holding the seed.*?lost funds\."', 'backupHelp'),
        (r'"Biometric unlock stores your passphrase.*?automatically\."', 'bioHelp'),
        (r'"How long the wallet stays unlocked.*?the app\."', 'timeoutHelp'),
        (r'"Confirm your wallet passphrase\. It will be stored.*?fallback\."', 'bioConfirm'),
        (r'"This wipes the securely stored passphrase.*?Continue\?"', 'bioDisableBody'),
        (r'"Your seed and keyuses stay the same.*?to restore\."', 'changePassphraseBody'),
        (r'"Choose a passphrase to protect the backup file.*?steal your funds\."', 'exportBackupBody'),
        (r'"Restored\. Keyuses reconciled \(MAX\).*?Security\)\."', 'restoredUntrusted')]
    for pattern,key in blocks:block(pattern,msg(key))
    for old,key,args,count in [
        ('"Could not enable: " + message','bioFailed','message',1),
        ('"Vault is damaged: " + e.getMessage()','vaultDamaged','e.getMessage()',2),
        ('"Vault is damaged: " + corrupt.getMessage()','vaultDamaged','corrupt.getMessage()',1),
        ('"New passphrase too short (min " + SessionPolicy.MIN_PASSPHRASE\n                                + ")"','newPassphraseShort','SessionPolicy.MIN_PASSPHRASE',1),
        ('"Change failed (vault unchanged): " + e.getMessage()','passphraseChangeFailed','e.getMessage()',1),
        ('"Passphrase too short (min 12)"','newPassphraseShort','12',1),
        ('"Backup saved (" + bytes.length + " bytes)"','backupSaved','bytes.length',1),
        ('"Backup failed: " + e.getMessage()','backupFailed','e.getMessage()',1),
        ('"Could not read file: " + e.getMessage()','readBackupFailed','e.getMessage()',1)]:change(old,msg(key,args),count)
    change('SessionPolicy.TIMEOUT_LABELS[tIdx]', 'timeoutLabels()[tIdx]')
    change('setSingleChoiceItems(SessionPolicy.TIMEOUT_LABELS,', 'setSingleChoiceItems(timeoutLabels(),')
    change('    private void pickAutoLock() {', '''    private static CharSequence[] timeoutLabels() {
        return new CharSequence[] {'''+', '.join(msg(k) for k in ['lock.immediately','lock.oneMinute','lock.fiveMinutes','lock.fifteenMinutes','thirtyMinutes','lock.oneHour'])+'''};
    }

    private void pickAutoLock() {''')
    # Shared helpers accept live messages; literal addresses and user content remain literal.
    for old,new in [('sectionTitle(String s)','sectionTitle(CharSequence s)'),
        ('kv(String label, String value)','kv(CharSequence label, CharSequence value)'),
        ('kvMono(String label, String value)','kvMono(CharSequence label, CharSequence value)'),
        ('hint(String s)','hint(CharSequence s)'),('warnBox(String title, String body)','warnBox(CharSequence title, CharSequence body)'),
        ('dangerButton(String label,','dangerButton(CharSequence label,'),
        ('passwordField(String hint)','passwordField(CharSequence hint)')]:change(old,new)
    for view in ['t','l','v','d']:
        s=re.sub(r'\b'+view+r'\.setText\(', 'Language.setText('+view+', ',s)
    change('e.setHint(hint);','Language.hint(e, hint);',2)
    (folder/name).write_text(s)

    name='BiometricUnlock.java';s=(folder/name).read_text()
    change('package com.eurobuddha.wallet;', '''package com.eurobuddha.wallet;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;''')
    def text(key,args=''):
        return msg(key,args).replace('Language.msg(', 'Language.text(zActivity, ')
    for old,key,count in [('"Could not initialise secure key: " + e.getMessage()','biometric.keyError',2),
        ('"Could not store passphrase: " + e.getMessage()','bioStoreError',1),
        ('"Could not decrypt passphrase: " + e.getMessage()','bioDecryptError',1),
        ('"Biometric prompt failed: " + e.getMessage()','biometric.promptError',1)]:change(old,text(key,'e.getMessage()'),count)
    for old,key in [('Enable biometric unlock','biometric.enable'),
        ("Confirm it's you to protect your wallet passphrase",'bioProtectPassphrase'),
        ('Biometric unlock is not set up','biometric.notSetUp'),
        ('Biometric key missing — re-enable biometric unlock','biometric.keyMissing'),
        ('Unlock wallet','unlockTitle'),("Confirm it's you to unlock your wallet",'biometric.confirm')]:
        change(json.dumps(old,ensure_ascii=False),text(key))
    block(r'"Biometrics changed — biometric unlock was reset\. ".*?re-enable it\."',text('bioReset'))
    change('promptInfo(\n','promptInfo(zActivity,\n',2)
    change('promptInfo(String zTitle, String zSubtitle)', 'promptInfo(Context context, String zTitle, String zSubtitle)')
    change('"Use passphrase"',text('usePassphrase').replace('zActivity,','context,'))
    (folder/name).write_text(s)

if __name__ == '__main__':
    import sys
    adapt(Path(sys.argv[1]))
