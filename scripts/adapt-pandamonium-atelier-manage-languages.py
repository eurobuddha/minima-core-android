"""Retained management, engine-log and wallet-scan labels for embedded Atelier."""
from pathlib import Path
import json,re

def adapt(root):
    p=root/'atelier/src/main/java/com/eurobuddha/statenft/MainActivity.java';s=p.read_text()
    labels=json.loads((Path(__file__).parent/'pandamonium-sources/atelier/manage-language-labels.json').read_text())
    def msg(key,args=''):
        name='l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()
        return 'Language.msg(com.eurobuddha.minimacore.i18n.R.string.'+name+args+')'
    def change(old,new):
        nonlocal s
        assert old in s,('Atelier manage language drift',old);s=s.replace(old,new)
    change('private void setInlineStatus(String s)', 'private void setInlineStatus(CharSequence s)')
    change('if (inlineStatus != null) inlineStatus.setText(s);','if (inlineStatus != null) Language.setText(inlineStatus, s);')
    change('private LinearLayout kvRow(String k, String v)', 'private LinearLayout kvRow(CharSequence k, CharSequence v)')
    a=s.index('    private LinearLayout kvRow(');b=s.index('    private float collectionProgress(',a)
    s=s[:a]+s[a:b].replace('k.toUpperCase()','Language.upper(k)')+s[b:]
    found=set()
    for begin,end in [('    private void renderEngineLog(', '    /* ================= HOSTING'),('    private void renderScan()', '    /* ================= shared node helpers')]:
        a=s.index(begin);b=s.index(end,a);part=s[a:b]
        for literal,key in labels.items():
            old=json.dumps(literal,ensure_ascii=False)
            if old in part:part=part.replace(old,msg(key));found.add(literal)
        if 'renderEngineLog' in begin:
            old='''(beat == 0 ? '''+msg('atelierManage.noTick')+'''
                : "Last tick " + ((System.currentTimeMillis() - beat) / 1000) + "s ago")
                + " · " + log.length() + " entries · refreshes every 25s"'''
            assert old in part,'Engine summary drift'
            part=part.replace(old,msg('atelierManage.logSummary',', beat == 0 ? '+msg('atelierManage.noTick')+' : '+msg('atelierManage.lastTick',', (System.currentTimeMillis() - beat) / 1000')+', log.length()'))
        else:
            old='scanFound.size() + " collections in the catalogue"';assert old in part
            part=part.replace(old,msg('atelierManage.catalogueCount',', scanFound.size()'))
            old='"Checking token " + (idx + 1) + " of " + bal.length() + "…"';assert old in part
            part=part.replace(old,msg('atelierManage.checking',', idx + 1, bal.length()'))
            # Reuse the successful-status/array gate from Salon IdentityBalance.
            old='''                JSONArray bal = json.optJSONArray("response");
                scanBalance(bal == null ? new JSONArray() : bal, 0);'''
            new='''                if (screen != Screen.SCAN) return;
                if (json == null || !Boolean.TRUE.equals(json.opt("status"))
                        || !(json.opt("response") instanceof JSONArray)) {
                    setInlineStatus('''+msg('atelierManage.scanFailed')+'''); return;
                }
                scanBalance(json.optJSONArray("response"), 0);'''
            assert old in part,'Scan response gate drift';part=part.replace(old,new)
            old='@Override public void onError(String message) { setInlineStatus(message); }'
            new='@Override public void onError(String message) { if (screen == Screen.SCAN) setInlineStatus('+msg('atelierManage.scanFailed')+'); }'
            assert old in part;part=part.replace(old,new)
        s=s[:a]+part+s[b:]
    assert found==set(labels),('Unused Atelier manage labels',set(labels)-found)
    p.write_text(s)
