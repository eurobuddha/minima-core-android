"""Retained Salon holdings/proof labels; leave published data and node commands literal."""
from pathlib import Path
import json,re

def adapt(root):
    source=Path(__file__).parent/'pandamonium-sources/salon'
    base=root/'salon/src/main/java/com/eurobuddha/salon'
    def resource(key):
        if key.startswith('l10n_'):return key
        return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()
    def msg(key,args=''):return 'Language.msg(com.eurobuddha.minimacore.i18n.R.string.'+resource(key)+args+')'
    reasons=json.loads((source/'proof-language-labels.json').read_text())
    message='''package com.eurobuddha.salon;
import com.eurobuddha.minimacore.i18n.Language;

/** Fixed diagnostic lookup reused from NFT Wallet; never translate public profile content. */
final class HoldingMessages {
    private HoldingMessages() {}
    static CharSequence message(String reason) {
        if (reason == null) return "";
        switch (reason) {
'''
    for literal,key in reasons.items():message+='            case '+json.dumps(literal,ensure_ascii=False)+': return '+msg(key)+';\n'
    message+='            default: return reason;\n        }\n    }\n}\n'
    (base/'HoldingMessages.java').write_text(message)
    p=base/'MainActivity.java';s=p.read_text()
    old='java.util.Map<String, String> mVerifyText';assert old in s;s=s.replace(old,'java.util.Map<String, CharSequence> mVerifyText')
    a=s.index('    private void renderNftShowcase(');b=s.index('    private String firstNonEmpty(',a);part=s[a:b]
    def change(old,new):
        nonlocal part
        assert old in part,('Salon holdings source drift',old);part=part.replace(old,new)
    change('private void setVerify(String vkey, TextView tv, String text, int color)', 'private void setVerify(String vkey, TextView tv, CharSequence text, int color)')
    change('tv.setText(', 'Language.setText(tv, ')
    change('ok ? "✓ VERIFIED HOLDING" : "✕ " + reason','ok ? "✓ VERIFIED HOLDING" : Language.join("✕ ", HoldingMessages.message(reason))')
    change('final java.util.List<JSONObject> editions, final String label)', 'final java.util.List<JSONObject> editions, final CharSequence label)')
    change('eds.size() + " STATE IMAGE" + (eds.size() == 1 ? "" : "S") + " IN THIS COLLECTION · TAP TO ENLARGE"',msg('salonHoldings.collectionImages',', eds.size()'))
    change('editions.size() + " STATE IMAGE" + (editions.size() == 1 ? "" : "S") + " YOU HOLD · TAP TO ENLARGE"',msg('salonHoldings.heldImages',', editions.size()'))
    change('"Balance failed: " + m',msg('salonHoldings.balanceFailed',', HoldingMessages.message(m)'))
    change('"Couldn\'t prove: " + msg',msg('salonHoldings.proveFailed',', HoldingMessages.message(msg)'))
    change('new android.app.AlertDialog.Builder(this)', 'new com.eurobuddha.minimacore.i18n.LanguageDialog(this)')
    # Existing Language-bound button grows with the translated label at narrow widths.
    change('lph(44, 0, 4, 0, 0)', 'lp(0, 4, 0, 0)')
    part=part.replace('node check ("Verify again")', 'node check on explicit retry')
    labels=json.loads((source/'holdings-language-labels.json').read_text())
    for literal,key in labels.items():change(json.dumps(literal,ensure_ascii=False),msg(key))
    s=s[:a]+part+s[b:];p.write_text(s)
