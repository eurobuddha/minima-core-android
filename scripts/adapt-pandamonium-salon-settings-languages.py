"""Translate Salon settings and backup presentation without rewriting stored data."""
from pathlib import Path
import json,re

def adapt(root):
    p=root/'salon/src/main/java/com/eurobuddha/salon/MainActivity.java';s=p.read_text()
    def msg(key,args=''):
        return 'Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()+args+')'
    def change(old,new):
        nonlocal s
        assert old in s,('Salon settings drift',old);s=s.replace(old,new)
    change('private void showQr(String title, String data)', 'private void showQr(CharSequence title, String data)')
    change('"Tip @" + SalonStore.get(this, "handle")',msg('salonSettings.tipFor',', SalonStore.get(this, "handle")'))
    change('"Background publish: " + (bg ? "ON" : "OFF")','bg ? '+msg('salonSettings.backgroundOn')+' : '+msg('salonSettings.backgroundOff'))
    change('''toast("Restored " + hn + " destination" + (hn == 1 ? "" : "s") + (mo ? " + messaging key." : ".")
                    + (sk ? " Kept your current messaging key (use Settings → Restore messaging key to change it)." : ""))''',
           'toast(Language.join('+msg('salonSettings.restoredDestinations',', hn')+', mo ? '+msg('salonSettings.keyRestoredSuffix')+' : "", sk ? '+msg('salonSettings.keyKeptSuffix')+' : ""))')
    change('"Save failed: " + e.getMessage()',msg('salonSettings.saveFailed',', HoldingMessages.message(e.getMessage())'))
    change('"Couldn\'t read file: " + e.getMessage()',msg('salonSettings.readFailed',', HoldingMessages.message(e.getMessage())'))
    # Keep the restore input on the UI thread; the worker receives the exact confirmed value.
    change('''showDialog("Restore messaging key", box, "Restore", () -> io.execute(() -> {
            final boolean ok = SalonComms.importSeed(this, text(f));''','''showDialog("Restore messaging key", box, "Restore", () -> {
            final String seed = text(f);
            io.execute(() -> {
            final boolean ok = SalonComms.importSeed(this, seed);''')
    change('''        }));
    }

    /** GET-touch''','''            });
        });
    }

    /** GET-touch''')
    a=s.index('    private void backupMsgKey()');b=s.index('    /** Save text',a);part=s[a:b]
    part=part.replace('''            runOnUiThread(() -> {
                if (seed.isEmpty())''','''            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (seed.isEmpty())''')
    part=part.replace('copyOnTap(t, seed);','t.setOnClickListener(v -> copyMessagingKey(seed));')
    part=part.replace('new android.app.AlertDialog.Builder(this)','new com.eurobuddha.minimacore.i18n.LanguageDialog(this)')
    part=part.replace('''{ ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("salon-msgkey", seed)); toast("Copied — store it safely."); }''','copyMessagingKey(seed)')
    s=s[:a]+part+s[b:]
    # Same sensitive clipboard metadata as Atelier.copyArweaveBackup and MinimaCore Clip.
    helper='''    private void copyMessagingKey(String seed) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) { toast(CLIPBOARD_ERROR); return; }
        ClipData clip = ClipData.newPlainText("salon-msgkey", seed);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.os.PersistableBundle extras = new android.os.PersistableBundle();
            extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        cm.setPrimaryClip(clip);
        toast(COPIED);
    }

'''.replace('CLIPBOARD_ERROR',msg('coreWallet.clipboard')).replace('COPIED',msg('salonSettings.copied'))
    change('    /** Save text to a file',helper+'    /** Save text to a file')
    labels=json.loads((Path(__file__).parent/'pandamonium-sources/salon/settings-language-labels.json').read_text());found=set()
    for begin,end in [('    private void backupMsgKey()', '    /** GET-touch'),('    private void renderSettings()', '    private void renderHosting()'),('    @Override protected void onActivityResult(', '    private void promptMediaTitle('),('    private void showQr(', '    private void toast(')]:
        a=s.index(begin);b=s.index(end,a);part=s[a:b]
        for literal,key in labels.items():
            old=json.dumps(literal,ensure_ascii=False)
            if old in part:part=part.replace(old,msg(key));found.add(literal)
        if 'renderSettings' in begin:part=re.sub(r'lph\((44|48), ', 'lp(',part)
        s=s[:a]+part+s[b:]
    assert found==set(labels),('Unused settings labels',set(labels)-found)
    p.write_text(s)
