"""Retain gallery translations and scope collection callbacks to their visible request."""
from pathlib import Path
import json,re

def adapt(root):
    p=root/'atelier/src/main/java/com/eurobuddha/statenft/MainActivity.java';s=p.read_text()
    def msg(key,args=''):
        key='atelierGallery.'+key if '.' not in key else key
        name='l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()
        return 'Language.msg(com.eurobuddha.minimacore.i18n.R.string.'+name+args+')'
    def change(old,new):
        nonlocal s
        assert old in s,('Atelier gallery drift',old);s=s.replace(old,new)
    change('toast("Engine engaged — sealing continues even with the screen off");', 'toast('+msg('engineEngaged')+');')
    change('    private StateNft.Meta openMeta;','    private StateNft.Meta openMeta;\n    private long detailRequest;\n    private boolean detailLoading;')
    change('        openMeta = m;\n        openOwned', '        openMeta = m;\n        detailPartial = false;\n        detailLoading = false;\n        openOwned')
    change('screen == Screen.COLLECTION && openMeta != null && activeMint(openMeta)', 'screen == Screen.COLLECTION && openMeta != null && activeMint(openMeta) && !detailLoading')
    change('        maybeSelfHeal(m);', '        if (!detailPartial) maybeSelfHeal(m);')
    a=s.index('    private void refreshDetail()');b=s.index('    private void persistCounts(',a)
    s=s[:a]+'''    private boolean collectionCurrent(StateNft.Meta m, long generation) {
        return !isFinishing() && !isDestroyed() && generation == screenGeneration
                && screen == Screen.COLLECTION && openMeta != null
                && openMeta.localId == m.localId && java.util.Objects.equals(openMeta.tokenid, m.tokenid);
    }

    private boolean detailCurrent(StateNft.Meta m, long generation, long request) {
        return request == detailRequest && openMeta == m && collectionCurrent(m, generation);
    }

    private void refreshDetail() {
        StateNft.Meta m = openMeta;
        final long generation = screenGeneration, request = ++detailRequest;
        if (m == null || !collectionCurrent(m, generation)) return;
        detailLoading = true;
        if (m.tokenid == null || m.tokenid.isEmpty()) {
            openOwned = new JSONArray();
            openAll = new JSONArray();
            detailPartial = false;
            detailLoading = false;
            renderDetail();
            return;
        }
        MintEngine.tokenCoinsBounded(node, m.tokenid, true, (coins, partial) -> {
            if (!detailCurrent(m, generation, request)) return;
            MintEngine.tokenCoinsBounded(node, m.tokenid, false, (all, partial2) -> {
                if (!detailCurrent(m, generation, request)) return;
                applyDetailCoins(m, coins, all.length() == 0 ? coins : all, partial || partial2);
            }, e -> {
                if (!detailCurrent(m, generation, request)) return;
                applyDetailCoins(m, coins, coins, true);
            });
        }, e -> {
            if (!detailCurrent(m, generation, request)) return;
            detailLoading = false;
            detailPartial = true;
            renderDetail();
        });
    }

    /** Commit both reads together so overlapping refreshes cannot mix their coins. */
    private void applyDetailCoins(StateNft.Meta m, JSONArray owned, JSONArray all, boolean partial) {
        detailLoading = false;
        openOwned = owned;
        openAll = all;
        detailPartial = partial;
        m.owned = owned.length();
        m.totalSeen = all.length();
        m.minted = distinctStamped(all);
        if (m.size <= 0) m.size = Math.max(m.minted, Math.max(m.totalSeen, m.owned));
        persistCounts(m);
        renderDetail();
    }

'''+s[b:]
    # Reuse the existing screenGeneration gate for both recovery entry points.
    change('''        MintEngine.recover(this, node, row, lastTip, msg -> runOnUiThread(() -> {
            // a failed recover''','''        final long generation = screenGeneration;
        MintEngine.recover(this, node, row, lastTip, msg -> runOnUiThread(() -> {
            if (!collectionCurrent(m, generation)) return;
            // a failed recover''')
    change('''                MintEngine.recover(this, node, row, lastTip, msg -> runOnUiThread(() -> {
                    toast(msg);''','''                final long generation = screenGeneration;
                MintEngine.recover(this, node, row, lastTip, msg -> runOnUiThread(() -> {
                    if (!collectionCurrent(m, generation)) return;
                    toast(msg);''')
    a=s.index('    private View collectionCard(');b=s.index('    private int phaseIndex(',a);part=s[a:b]
    def replace(old,new):
        nonlocal part
        assert old in part,('Atelier gallery label drift',old);part=part.replace(old,new)
    replace('m.size > 0 ? String.format(Locale.US, "Lot 001 — %03d", m.size) : "Lot —"',
            'm.size > 0 ? '+msg('lotRange',', String.format(Locale.US, "%03d", m.size)')+' : '+msg('lotPending'))
    replace('''(m.tokenid.isEmpty() ? "token pending" : Util.shorten(m.tokenid))
                + " · sealed " + Math.max(0, m.minted) + "/" + Math.max(m.size, 0)
                + " · owned " + Math.max(0, m.owned)''', msg('collectionSummary',', m.tokenid.isEmpty() ? '+msg('tokenPending')+' : Util.shorten(m.tokenid), Math.max(0, m.minted), Math.max(m.size, 0), Math.max(0, m.owned)'))
    replace('private String statusWord(', 'private CharSequence statusWord(')
    replace('private String modeWord(', 'private CharSequence modeWord(')
    replace('"ed. " + row.optString("total", "1") + " · " + Util.shorten(tid)',msg('editionsSummary',', row.optString("total", "1"), Util.shorten(tid)'))
    replace('"supply " + row.optString("total", "?") + " · " + Util.shorten(tid)',msg('supplySummary',', row.optString("total", "?"), Util.shorten(tid)'))
    replace('toast("Adopted “" + m.name + "” — found in your wallet");','toast('+msg('adopted',', m.name')+');')
    replace('toast("Chain shows " + m.minted + "/" + m.size + " — resuming this mint");','toast('+msg('resuming',', m.minted, m.size')+');')
    replace('m.size > 0 ? String.format(Locale.US, "Lot 001 — %03d · %s", m.size, modeWord(m)) : modeWord(m)',
            'm.size > 0 ? Language.join('+msg('lotRange',', String.format(Locale.US, "%03d", m.size)')+', " · ", modeWord(m)) : modeWord(m)')
    replace('"  ticked " + age + "s ago · tap for the engine log"',msg('tick',', age'))
    replace('prog[0] + " / " + prog[1] + " lots delivered"',msg('delivered',', prog[0], prog[1]'))
    replace('"Lot " + String.format(Locale.US, "%03d", e.optInt("idx"))',msg('lot',', String.format(Locale.US, "%03d", e.optInt("idx"))'))
    replace('("FAIL".equals(st) && !err.isEmpty()) ? "FAIL — " + err : st + " → " + Util.shorten(e.optString("addr"))',
            '("FAIL".equals(st) && !err.isEmpty()) ? Language.join(dropStatus(st), " — ", err) : Language.join(dropStatus(st), " → ", Util.shorten(e.optString("addr")))')
    replace('"Send " + transferable.size() + " lots — one address or a list"',msg('sendLots',', transferable.size()'))
    replace('String[] names = {"Create", "Gather", "Split", "Stamp", "Done"};',
            'CharSequence[] names = {'+', '.join(msg(k) for k in ['create','gather','split','stamp','complete'])+'};')
    replace('names[i].toUpperCase()', 'Language.upper(names[i])')
    labels=json.loads((Path(__file__).parent/'pandamonium-sources/atelier/gallery-language-labels.json').read_text())
    for literal,key in labels.items():
        old=json.dumps(literal,ensure_ascii=False);replace(old,msg(key))
    # Proven minimum-height/wrap-content adaptation from the collection wizard.
    for name in ['refresh','resume','doctor','bury']:
        part,n=re.subn(r'(?m)^([ \t]*)actions.addView\('+name+r', weight\(46, (\d+), (\d+)\)\);',
            lambda m:m[1]+name+'.setMinHeight(dp(48));\n'+m[1]+'LinearLayout.LayoutParams '+name+'Lp = weight(48, '+m[2]+', '+m[3]+'); '+name+'Lp.height = -2;\n'+m[1]+'actions.addView('+name+', '+name+'Lp);',part);assert n==1
    for name in ['clear','drop']:
        part,n=re.subn(r'(?m)^([ \t]*)(panel|body).addView\('+name+r', lph\((42|46), ([^\n]+)\)\);',lambda m:m[1]+name+'.setMinHeight(dp(48));\n'+m[1]+m[2]+'.addView('+name+', lpm('+m[4]+'));',part);assert n==1
    helper='''    private CharSequence dropStatus(String state) {
'''+''.join('        if ("'+state+'".equals(state)) return '+msg(key)+';\n' for state,key in [('PENDING','pending'),('POSTED','posted'),('DONE','done'),('FAIL','failed')])+'''        return state;
    }

'''
    s=s[:a]+part+helper+s[b:]
    p.write_text(s)
