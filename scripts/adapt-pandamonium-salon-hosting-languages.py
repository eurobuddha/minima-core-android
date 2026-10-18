"""Translate Salon hosting controls in place, retaining profile drafts and callbacks."""
from pathlib import Path
import json,re

def adapt(root):
    p=root/'salon/src/main/java/com/eurobuddha/salon/MainActivity.java'
    s=p.read_text()
    spec=json.loads((Path(__file__).parent/'pandamonium-sources/salon/hosting-language-labels.json').read_text())
    def msg(key,args=''):
        resource='l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()
        return 'Language.msg(com.eurobuddha.minimacore.i18n.R.string.'+resource+args+')'
    def change(old,new):
        nonlocal s
        assert old in s,('Salon hosting language drift',old)
        s=s.replace(old,new)
    change('private void masthead(String title)', 'private void masthead(CharSequence title)')
    change('private void cfgField(LinearLayout parent, JSONObject cfg, String key, String label, String hint, boolean secret)',
           'private void cfgField(LinearLayout parent, JSONObject cfg, String key, CharSequence label, CharSequence hint, boolean secret)')
    change('private void cfgSecret(LinearLayout parent, JSONObject cfg, String key, String label)',
           'private void cfgSecret(LinearLayout parent, JSONObject cfg, String key, CharSequence label)')
    change('private void cfgSecretMulti(LinearLayout parent, JSONObject cfg, String key, String label)',
           'private void cfgSecretMulti(LinearLayout parent, JSONObject cfg, String key, CharSequence label)')
    start=s.index('    private void cfgSecretMulti(');end=s.index('    private TextView btn(',start)
    s=s[:start]+s[start:end].replace('label.toUpperCase()','Language.upper(label)')+s[end:]
    change('private void copyRow(LinearLayout parent, String k, String full)',
           'private void copyRow(LinearLayout parent, CharSequence k, String full)')
    start=s.index('    private void copyRow(');end=s.index('    private void copyOnTap(',start)
    s=s[:start]+s[start:end].replace('k.toUpperCase()','Language.upper(k)').replace('"TAP TO COPY"',msg('creativeHosting.tapCopy'))+s[end:]
    # This shared callback also serves the identity/public-key copy rows.
    change('toast("Copied");', 'toast('+msg('history.copied')+');')
    start=s.index('    private void renderHosting()');end=s.index('    /* ================= node key',start)
    part=s[start:end]
    for key,expr in spec['longNotes'].items():
        assert expr in part,('Salon hosting note drift',key)
        part=part.replace(expr,msg('creativeHosting.'+key))
    for literal,key in spec['labels']:
        if key=='creativeHosting.tapCopy':continue
        old=json.dumps(literal,ensure_ascii=False)
        assert old in part,('Salon hosting label drift',literal)
        part=part.replace(old,msg(key))
    # Three translated actions need room for two lines on narrow screens.
    part=part.replace('weight(31,', 'weight(48,')
    part=part.replace('private static final String[] HLABELS', 'private static final CharSequence[] HLABELS')
    substitutions={
      '"Testing " + p.name() + "…"':msg('creativeHosting.testingName',', p.name()'),
      '"OK — uploaded and served: " + url':msg('creativeHosting.tested',', url'),
      '"Failed: " + e.getMessage()':msg('creativeHosting.failed',', e.getMessage()'),
      'String msg;':'CharSequence msg;',
      'final String m = msg;':'final CharSequence m = msg;',
      'status.setText(m)':'Language.setText(status, m)',
      'new android.app.AlertDialog.Builder(this).setTitle':'new com.eurobuddha.minimacore.i18n.LanguageDialog(this).setTitle',
      '"First connection to " + p.cfgStr("host") + ".\\n\\nSSH host-key fingerprint:\\n" + fp + "\\n\\nTrust it only if this is your server. It\'ll be pinned."':msg('creativeHosting.trustBody',', p.cfgStr("host"), fp'),
    }
    for old,new in substitutions.items():
        assert old in part,('Salon hosting callback drift',old)
        part=part.replace(old,new)
    part=part.replace('status.setText(', 'Language.setText(status, ')
    s=s[:start]+part+s[end:]
    p.write_text(s)
