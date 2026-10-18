"""Retain Salon messaging controls while translating presentation, not payloads."""
from pathlib import Path
import json,re

def adapt(root):
    p=root/'salon/src/main/java/com/eurobuddha/salon/MainActivity.java';s=p.read_text()
    def msg(key,args=''):
        return 'Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()+args+')'
    a=s.index('    private void openThread(');b=s.index('    private void attachDmMedia()',a);part=s[a:b]
    def replace(old,new):
        nonlocal part
        assert old in part,('Salon chat drift',old);part=part.replace(old,new)
    replace('"@" + (ct.handle == null ? "someone" : ct.handle.replaceFirst("^@", ""))','ct.handle == null || ct.handle.isEmpty() ? '+msg('salonChat.unknownContact')+' : "@" + ct.handle.replaceFirst("^@", "")')
    replace('ct.handle.replaceFirst("^@", "") : "chat"','ct.handle.replaceFirst("^@", "") : ""')
    replace('masthead("@" + handle);','masthead(handle.isEmpty() ? '+msg('salonChat.unknownContact')+' : "@" + handle);')
    replace('boolean usingMaxima = !"chain".equals(xport) && hasMxAddr && MaximaLink.isReady(this);','boolean usingMaxima = "maxima".equals(xport) || (!"chain".equals(xport) && hasMxAddr);')
    replace('String forced = "maxima".equals(xport) || "chain".equals(xport) ? " · forced" : "";','CharSequence forced = "maxima".equals(xport) || "chain".equals(xport) ? '+msg('salonChat.forced')+' : "";')
    replace('(usingMaxima ? "⚡ Maxima · off-chain" : "⛓ Minima · on-chain") + forced + "  ▾"','Language.join(usingMaxima ? (!hasMxAddr ? '+msg('salonChat.maximaNoAddress')+' : MaximaLink.isReady(this) ? '+msg('salonChat.maxima')+' : '+msg('salonChat.maximaWaiting')+') : '+msg('salonChat.chain')+', forced, "  ▾")')
    replace('"↑ Load earlier (" + (total - msgs.size()) + " more)"',msg('salonChat.earlier',', total - msgs.size()'))
    replace('"↩ Replying to " + replyFrom',msg('salonChat.replying',', replyAuthor(replyFrom)'))
    replace('"↩ " + (m.replyFrom == null || m.replyFrom.isEmpty() ? "reply" : m.replyFrom)','Language.join("↩ ", replyAuthor(m.replyFrom))')
    replace('String meta = m.mine && m.status == MailDb.PENDING ? "sending…" : Util.ago(m.ts);','CharSequence meta = m.mine && m.status == MailDb.PENDING ? '+msg('salonChat.sending')+' : socialTime(m.ts);')
    replace('if (!meta.isEmpty())','if (meta.length() != 0)')
    replace('"Send failed — queued: " + m',msg('salonChat.sendFailed',', HoldingMessages.message(m)'))
    # Maxima-only means no chain fallback, including a later retry without a cached route.
    replace('''            boolean forceChain = "chain".equals(xport);
            if (!forceChain''','''            boolean forceChain = "chain".equals(xport);
            boolean forceMaxima = "maxima".equals(xport);
            if (!forceChain''')
    replace('else if ((forceChain || !hasMx) && nodeUp', 'else if (!forceMaxima && (forceChain || !hasMx) && nodeUp')
    # Snapshot a chooser before showing it, just as editor dialogs bind their original owner.
    replace('''    private void transportDialog() {
        final MailDb.Contact ct = MailDb.get(this).contact(threadPeer);''','''    private void transportDialog() {
        final String peer = threadPeer, owner = mediaIdentity();
        if (peer.isEmpty()) return;
        final MailDb.Contact ct = MailDb.get(this).contact(peer);''')
    replace('final String[] labels = {','final CharSequence[] labels = {')
    replace('"Prefer Maxima — off-chain, instant" + (hasMx ? "" : "  (no address yet)")','Language.join('+msg('salonChat.maximaChoice')+', hasMx ? "" : '+msg('salonChat.noAddressSuffix')+')')
    replace('new android.app.AlertDialog.Builder(this)','new com.eurobuddha.minimacore.i18n.LanguageDialog(this)')
    replace('''                    SalonStore.put(this, XPORT_PREF + threadPeer, vals[which]);''','''                    if (isFinishing() || isDestroyed() || screen != Screen.THREAD
                            || !peer.equals(threadPeer) || !owner.equals(mediaIdentity())) {
                        d.dismiss(); if (!isFinishing() && !isDestroyed()) toast('''+msg('salonChat.selectionChanged')+'''); return;
                    }
                    SalonStore.put(this, XPORT_PREF + peer, vals[which]);''')
    part=part.replace('weight(46,','weight(-2,').replace('new LinearLayout.LayoutParams(dp(54), dp(46))','new LinearLayout.LayoutParams(dp(54), -2)')
    labels=json.loads((Path(__file__).parent/'pandamonium-sources/salon/chat-language-labels.json').read_text())
    for literal,key in labels.items():replace(json.dumps(literal,ensure_ascii=False),msg(key))
    # The stored/wire sentinel "You" stays literal; only its displayed author is translated.
    helper='''    private CharSequence replyAuthor(String value) {
        if (value == null || value.isEmpty()) return REPLY;
        return "You".equals(value) ? YOU : value;
    }

'''.replace('REPLY',msg('mail.reply')).replace('YOU',msg('salonChat.you'))
    part=helper+part
    s=s[:a]+part+s[b:];p.write_text(s)
