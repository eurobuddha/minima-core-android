"""Retained Atelier hosting messages, using Salon's bindings and native dialog helper."""
from pathlib import Path
import json,re

def adapt(root):
    p=root/'atelier/src/main/java/com/eurobuddha/statenft/MainActivity.java';s=p.read_text()
    spec=json.loads((Path(__file__).parent/'pandamonium-sources/atelier/hosting-language-labels.json').read_text())
    def msg(key,args=''):
        resource='l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()
        return 'Language.msg(com.eurobuddha.minimacore.i18n.R.string.'+resource+args+')'
    def change(old,new):
        nonlocal s
        assert old in s,('Atelier hosting language drift',old)
        s=s.replace(old,new)
    change('private EditText fieldInto(LinearLayout parent, String label, String hint, String value)',
           'private EditText fieldInto(LinearLayout parent, CharSequence label, CharSequence hint, String value)')
    change('private EditText input(String hint)', 'private EditText input(CharSequence hint)')
    start=s.index('    private EditText input(CharSequence hint)');end=s.index('\n    }',start)
    s=s[:start]+s[start:end].replace('e.setHint(hint);','Language.hint(e, hint);')+s[end:]
    change('private void toast(String s)', 'private void toast(CharSequence s)')
    change('toast("Copied");', 'toast('+msg('history.copied')+');')
    start=s.index('    private void renderHosting()');end=s.index('    /* ================= SCAN',start)
    part=s[start:end]
    for old,key in spec['notes'].items():
        assert old in part,('Atelier hosting note drift',old)
        part=part.replace(old,msg(key))
    for literal,key in spec['labels'].items():
        old=json.dumps(literal,ensure_ascii=False)
        assert old in part,('Atelier hosting label drift',literal)
        part=part.replace(old,msg(key))
    replacements={
      'private static final String[] HOST_TYPE_LABELS':'private static final CharSequence[] HOST_TYPE_LABELS',
      'String key, String label, String hint, boolean secret':'String key, CharSequence label, CharSequence hint, boolean secret',
      'String key, String label)':'String key, CharSequence label)',
      'String key, String label, String emptyHint)':'String key, CharSequence label, CharSequence emptyHint)',
      'new android.app.AlertDialog.Builder(this)':'new com.eurobuddha.minimacore.i18n.LanguageDialog(this)',
      '"Delete " + p.name() + "?"':msg('atelierHosting.delete',', p.name()'),
      '"Testing " + p.name() + "…"':msg('creativeHosting.testingName',', p.name()'),
      '"Test — " + p.name()':msg('atelierHosting.testTitle',', p.name()'),
      '"Wallet generation failed: " + e.getClass().getSimpleName()':msg('atelierHosting.generateFailed',', e.getClass().getSimpleName()'),
      '"uploaded ✓ · fetched ✓\\n" + url':msg('atelierHosting.tested',', url'),
      '"uploaded ✓ — Turbo accepted it (free tier)\\n" + url\n                            + "\\n\\nFresh Arweave URLs take ~5–10 min to start serving."':msg('atelierHosting.turboTested',', url'),
      '"failed: " + e.getMessage()':msg('creativeHosting.failed',', e.getMessage()'),
      '"failed: " + t.getClass().getSimpleName()':msg('creativeHosting.failed',', t.getClass().getSimpleName()'),
      '"First connection to this server. Confirm its fingerprint matches your server:\\n\\n" + fp\n                        + "\\n\\nOnly accept if this is YOUR server."':msg('atelierHosting.verifyBody',', fp'),
      'String result;':'CharSequence result;',
      'final String r = result;':'final CharSequence r = result;',
      'String addr;':'CharSequence addr;',
      'final String addrF = addr;':'final String addrF = addr.toString();',
      'addr = unavailable.getMessage();':'addr = '+msg('creative.secretUnavailable')+';',
    }
    for old,new in replacements.items():
        assert old in part,('Atelier hosting callback drift',old)
        part=part.replace(old,new)
    part=part.replace('gen.setText(', 'Language.setText(gen, ').replace('e.setHint(', 'Language.hint(e, ')
    part=part.replace('weight(31,', 'weight(48,')
    # Reuse the core Clip.copySensitive path locally: the library cannot depend on its host app.
    marker='                hostFieldMulti(cfg, "jwk",'
    idx=part.index(marker)
    part=part[:idx]+'''                if (!cfg.optString("jwk", "").isEmpty()) {
                    TextView backup = Design.button(this, '''+msg('atelierHosting.copyBackup')+''', false);
                    backup.setMinHeight(dp(48)); backup.setPadding(dp(12), dp(8), dp(12), dp(8));
                    backup.setOnClickListener(v -> copyArweaveBackup(cfg));
                    body.addView(backup, lpm(0, 4, 0, 4));
                }
'''+part[idx:]
    # A failed probe must close the same uploader as a successful one.
    old='''                String url;
                if (u instanceof Hosting.DirUploader && (Hosting.TYPE_KUBO.equals(p.type()) || Hosting.TYPE_PINATA.equals(p.type()))) {
                    url = u.putFile(nonce.getBytes(java.nio.charset.StandardCharsets.UTF_8), rel, "text/plain");
                } else {
                    url = u.putFile(nonce.getBytes(java.nio.charset.StandardCharsets.UTF_8), rel, "text/plain");
                }
                if (u instanceof AutoCloseable) { try { ((AutoCloseable) u).close(); } catch (Exception ignored) { } }'''
    new='''                String url;
                try {
                    url = u.putFile(nonce.getBytes(java.nio.charset.StandardCharsets.UTF_8), rel, "text/plain");
                } finally {
                    if (u instanceof AutoCloseable) { try { ((AutoCloseable) u).close(); } catch (Exception ignored) { } }
                }'''
    assert old in part,'Atelier hosting probe drift';part=part.replace(old,new)
    s=s[:start]+part+s[end:]
    marker='    private java.util.Map<String, String> mapOf('
    helper='''    /** Explicit wallet backup; same sensitive-clipboard handling as minimaCore utils/Clip. */
    private void copyArweaveBackup(JSONObject cfg) {
        try {
            String jwk = Crypt.decrypt(cfg.optString("jwk", ""));
            Ans104.parseJwk(jwk); // Never claim a malformed value is a usable wallet backup.
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) { toast('''+msg('core.wallet.clipboard')+'''); return; }
            ClipData clip = ClipData.newPlainText("Arweave JWK", jwk);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                android.os.PersistableBundle extras = new android.os.PersistableBundle();
                extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
                clip.getDescription().setExtras(extras);
            }
            cm.setPrimaryClip(clip);
            if (android.os.Build.VERSION.SDK_INT < 33) toast('''+msg('history.copied')+''');
        } catch (Crypt.Unavailable unavailable) { hostingSecretUnavailable(); }
        catch (Exception invalid) { toast('''+msg('atelierHosting.unreadable')+'''); }
    }

'''
    assert marker in s;s=s.replace(marker,helper+marker)
    p.write_text(s)
