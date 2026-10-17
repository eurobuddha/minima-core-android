"""AtomiX notice display and safe node-derived-key boundary; reuse existing locale/ETH patterns."""
from pathlib import Path
import importlib.util,json

def adapt(root):
 project=Path(__file__).resolve().parents[1]
 spec=importlib.util.spec_from_file_location('wallet_messages_builder',Path(__file__).with_name('adapt-pandamonium-wallet-error-languages.py'))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
 source=module.build_source(json.loads((project/'localization/atomix-messages.json').read_text()),json.loads((project/'localization/catalog.json').read_text()))
 source=source.replace('package com.eurobuddha.wallet;','package com.eurobuddha.atomix;').replace('WalletMessages','AtomixMessages').replace('Explicit wallet failures only.','Explicit AtomiX notifications and key errors only.').replace('adapt-pandamonium-wallet-error-languages.py from localization/wallet-errors.json','adapt-pandamonium-atomix-notices.py from localization/atomix-messages.json')
 extra='''
    /** Rename existing channels without changing the user's sound, vibration or importance settings. */
    public static void ensureChannels(android.content.Context context) {
        android.app.NotificationManager manager = context.getSystemService(android.app.NotificationManager.class);
        if (manager == null) return;
        channel(context, manager, "swap_fg", l10n_atomix_notice_watcher, android.app.NotificationManager.IMPORTANCE_LOW);
        channel(context, manager, "atomix", l10n_atomix_notice_alerts, android.app.NotificationManager.IMPORTANCE_DEFAULT);
        channel(context, manager, "swap_offline", l10n_atomix_notice_offline, android.app.NotificationManager.IMPORTANCE_HIGH);
    }
    private static void channel(android.content.Context context, android.app.NotificationManager manager, String id, int label, int importance) {
        android.app.NotificationChannel channel = manager.getNotificationChannel(id);
        if (channel == null) channel = new android.app.NotificationChannel(id, Language.text(context, label), importance);
        channel.setName(Language.text(context, label));
        manager.createNotificationChannel(channel);
    }
'''
 source=source.rsplit('}',1)[0]+extra+'}\n'
 folder=root/'atomix/src/main/java/com/eurobuddha/atomix';(folder/'AtomixMessages.java').write_text(source)
 p=folder/'IdentityWatch.java';s=p.read_text()
 old='''"Your node's seed no longer matches this app. Trading is stopped. Open AtomiX to rescue funds, "
                + "then reinstall AND clear app storage — a reinstall alone can restore the old keys from a "
                + "phone/cloud backup."'''
 new=json.dumps(json.loads((project/'localization/catalog.json').read_text())['atomixNotice.mismatchBody']['en'])
 assert old in s;s=s.replace(old,new).replace('rescue-then-reinstall, which is the one reset that provably puts every piece back in line.','review and rescue funds. Pandamonium shares node storage, so clearing app storage is not a recovery action.')
 p.write_text(s)
 p=folder/'MainActivity.java';s=p.read_text()
 s=s.replace('super.onConfigurationChanged(config); Language.configurationChanged(this);','super.onConfigurationChanged(config); Language.configurationChanged(this); AtomixMessages.ensureChannels(this);')
 start=s.index('    private void ensureChannel() {');end=s.index('    private void requestNotifPermission()',start)
 s=s[:start]+'    private void ensureChannel() { AtomixMessages.ensureChannels(this); }\n\n'+s[end:]
 start=s.index('    private void postNotification(String title, String body) {');end=s.index('    private void ensureChannel()',start);part=s[start:end]
 part=part.replace('toast(title + " — " + body);','toast(Language.join(AtomixMessages.message(title), " — ", AtomixMessages.message(body)));')
 for old,new in [('.setContentTitle(title)','.setContentTitle(AtomixMessages.message(title))'),('.setContentText(body)','.setContentText(AtomixMessages.message(body))'),('.bigText(body)','.bigText(AtomixMessages.message(body))')]:part=part.replace(old,new)
 s=s[:start]+part+s[end:]
 assert 'Language.setText(err, "⚠ " + ethErr);' in s;s=s.replace('Language.setText(err, "⚠ " + ethErr);','Language.setText(err, Language.join("⚠ ", AtomixMessages.message(ethErr)));')
 s=s.replace('// msg may echo key material — screen only, never logcat','// key errors are fixed safe messages; never expose raw node material')
 # Display only: preserve balances and stored diagnostics; identity exceptions must not echo key material.
 for old,new in {'toast("Identity error: " + e.getMessage());': 'toast(Language.msg(l10n_atomix_notice_identity_failed));', '    private void pushOrderEdit(Order o) {\n        if (IdentityWatch.halted()) { toast("Halted: app keys don\'t match your node");': '    private void pushOrderEdit(Order o) {\n        if (IdentityWatch.halted()) { toast(Language.msg(l10n_atomix_otc_halted));', 'Language.setText(e, "⚠ " + err);': 'Language.setText(e, Language.join("⚠ ", AtomixMessages.message(err)));', 'Util.fmt5(minimaBal) + " " + ccy()': 'Language.join(AtomixMessages.message(Util.fmt5(minimaBal)), " ", ccy())'}.items():
  assert s.count(old)==1,old
  s=s.replace(old,new)
 p.write_text(s)
 p=folder/'SwapService.java';s=p.read_text()
 s=s.replace('    private String fgText;', '    private boolean foregroundActive;\n    private String fgText;')
 s=s.replace('    @Override public void onDestroy() {','    @Override public void onConfigurationChanged(android.content.res.Configuration config) {\n        super.onConfigurationChanged(config);\n        createChannels();\n        if (foregroundActive && fgText != null) {\n            NotificationManager manager = getSystemService(NotificationManager.class);\n            if (manager != null) manager.notify(FG_ID, fgNotification(fgText));\n        }\n    }\n\n    @Override public void onDestroy() {\n        foregroundActive = false;')
 s=s.replace('if (nm != null) nm.notify(FG_ID, fgNotification(text));','if (foregroundActive && nm != null) nm.notify(FG_ID, fgNotification(text));')
 for old,new in [('.setContentTitle(title)','.setContentTitle(AtomixMessages.message(title))'),('.setContentTitle("Market offline")','.setContentTitle(AtomixMessages.message("Market offline"))'),('.setContentText(body)','.setContentText(AtomixMessages.message(body))'),('.bigText(body)','.bigText(AtomixMessages.message(body))'),('.setContentText(text)','.setContentText(AtomixMessages.message(text))')]:s=s.replace(old,new)
 start=s.index('    private void createChannels() {');end=s.index('    /** Title of the persistent notification',start)
 s=s[:start]+'    private void createChannels() { AtomixMessages.ensureChannels(this); }\n\n'+s[end:]
 # Only a successful foreground start permits language-triggered notification replacement.
 old='            return true;\n        } catch (Exception e) {';assert s.count(old)==1
 s=s.replace(old,'            foregroundActive = true;\n'+old)
 s=s.replace('    private void stopGracefully() {','    private void stopGracefully() {\n        foregroundActive = false;')
 p.write_text(s)
 # Reuse ETH Wallet's exact key width/hex check. Stage credentials until all checks pass;
 # the probe shares validation but never changes the active key. No raw error escapes either callback.
 p=folder/'eth/EthWallet.java';s=p.read_text()
 start=s.index('    /** Derive the ETH key from the node seed');end=s.index('    // MA-7:',start)
 s=s[:start]+(project/'scripts/pandamonium-sources/atomix/eth-key-boundary.inc').read_text()+s[end:]
 p.write_text(s)
