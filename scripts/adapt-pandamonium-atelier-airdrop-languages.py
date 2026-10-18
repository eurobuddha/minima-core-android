"""Retain airdrop translations and require a preview of the current recipients."""
import re

def adapt(root):
    p=root/'atelier/src/main/java/com/eurobuddha/statenft/MainActivity.java';s=p.read_text()
    a=s.index('    private void renderAirdrop(');b=s.index('    private EditText inputMulti(',a);part=s[a:b]
    def msg(key,args=''):
        key='atelierAirdrop.'+key if '.' not in key else key
        name='l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()
        return 'Language.msg(com.eurobuddha.minimacore.i18n.R.string.'+name+args+')'
    def change(old,new):
        nonlocal part
        assert old in part,('Atelier airdrop source drift',old)
        part=part.replace(old,new)
    change('        appbar("Airdrop", true, false);','        final long generation = screenGeneration;\n        appbar('+msg('title')+', true, false);')
    change('"Dispatch — " + transferable.size() + " lots in custody"',msg('dispatch',', transferable.size()'))
    change('"Send the whole collection"',msg('whole'))
    change('''"All " + transferable.size() + " lots to one recipient — "
                + "delivered as " + transferable.size() + " sealed transfers, one per coin, resumable."''',msg('wholeHelp',', transferable.size()'))
    change('input("Mx… or 0x…")','input('+msg('send.addressHint')+')')
    change('"Send all " + transferable.size() + " lots"',msg('sendAll',', transferable.size()'))
    change('"That address does not parse — Mx… or 0x… format"',msg('invalidAddress'))
    change('"Nothing deliverable"',msg('nothing'))
    change('transferable.size() + " lots queued to " + Util.shorten(a)',msg('queued',', AirdropEngine.progress(job)[1], Util.shorten(a)'))
    change('"Or map lots to a list"',msg('list'))
    change('''"One address per line. Lots are delivered in order — lot " +
                (transferable.isEmpty() ? "—" : String.format(Locale.US, "%03d", transferable.get(0).index)) +
                " to the first line. Every transfer replays the sealed identity."''',msg('listHelp',', transferable.isEmpty() ? "—" : String.format(Locale.US, "%03d", transferable.get(0).index)'))
    change('"Paste recipients, then preview."',msg('paste'))
    change('        final List<String> valid = new ArrayList<>();','''        final List<String> valid = new ArrayList<>();
        final String[] previewed = {null};
        addrs.addTextChangedListener(watch(value -> {
            valid.clear();
            previewed[0] = null;
            mapping.removeAllViews();
            Language.setText(status, '''+msg('previewFirst')+''');
        }));''')
    change('"Preview mapping"',msg('preview'))
    change('''            String[] lines = addrs.getText().toString().split("\\n");''','''            previewed[0] = addrs.getText().toString();
            String[] lines = previewed[0].split("\\n");''')
    change('String.format(Locale.US, "Lot %03d", transferable.get(i).index)',msg('atelierGallery.lot',', String.format(Locale.US, "%03d", transferable.get(i).index)'))
    change('''            String msg = n + " deliveries ready";
            if (bad > 0) msg += " · " + bad + " invalid lines skipped";
            if (valid.size() > transferable.size()) msg += " · " + (valid.size() - transferable.size()) + " recipients beyond your lots";
            if (transferable.size() > valid.size() && n > 0) msg += " · " + (transferable.size() - valid.size()) + " lots stay with you";
            status.setText(msg);''','''            Language.TextBuilder summary = new Language.TextBuilder().append('''+msg('ready',', n')+''');
            if (bad > 0) summary.append(" · ").append('''+msg('invalidLines',', bad')+''');
            if (valid.size() > transferable.size()) summary.append(" · ").append('''+msg('extraRecipients',', valid.size() - transferable.size()')+''');
            if (transferable.size() > valid.size() && n > 0) summary.append(" · ").append('''+msg('remaining',', transferable.size() - valid.size()')+''');
            Language.setText(status, summary.build());''')
    change('"Begin delivery"',msg('begin'))
    change('''            if (valid.isEmpty()) { status.setText("Preview the mapping first."); return; }''','''            if (valid.isEmpty() || !addrs.getText().toString().equals(previewed[0])) {
                Language.setText(status, '''+msg('previewFirst')+'''); return;
            }''')
    change('status.setText("Nothing deliverable.")','Language.setText(status, '+msg('nothing')+')')
    change('''            toast("Airdrop armed — the engine delivers one lot per cycle while the app is open");''','''            engageEngine();
            toast('''+msg('armed')+''');''')
    # Reuse the existing screen generation and engine startup from collection actions.
    for action in ['sendAll','preview','go']:
        change(action+'.setOnClickListener(v -> {',action+'''.setOnClickListener(v -> {
            if (isFinishing() || isDestroyed() || screen != Screen.AIRDROP || generation != screenGeneration) return;''')
    change('''            AirdropEngine.saveJob(this, job);''','''            if (AirdropEngine.job(this, m.localId) != null) {
                toast('''+msg('existing')+'''); return;
            }
            AirdropEngine.saveJob(this, job);''')
    # Retained controls wrap translated text; no rerender on a language change.
    for parent,name,height in [('wholeCard','oneAddr',48),('wholeCard','sendAll',50),('body','preview',48),('body','go',54)]:
        pattern=r'(?m)^([ \t]*)'+parent+r'\.addView\('+name+r', lph\('+str(height)+r', ([^\n]+)\)\);'
        part,n=re.subn(pattern,lambda m:m[1]+name+'.setMinHeight(dp('+str(height)+'));\n'+m[1]+parent+'.addView('+name+', lpm('+m[2]+'));',part);assert n==1
    s=s[:a]+part+s[b:];p.write_text(s)
