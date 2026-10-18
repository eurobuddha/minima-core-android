"""Persist Salon's sender, wire payload and chosen transport before sending."""
from pathlib import Path
import shutil

def adapt(root):
    source=Path(__file__).parent/'pandamonium-sources/salon';target=root/'salon/src/main/java/com/eurobuddha/salon'
    for name in ['MailDb.java','MinimaMail.java']:shutil.copy2(source/name,target/name)
    p=target/'MainActivity.java';s=p.read_text()
    snippet=(source/'OutgoingMessages.java.inc').read_text()
    keys={'EMPTY':'salon_chat_type_message','CONTACT':'salon_chat_no_contact','NO_MAXIMA':'salon_chat_no_maxima','NO_DELIVERY':'salon_chat_no_delivery',
          'STORAGE':'salon_outbox_storage','QUEUED':'salon_chat_queued','SEAL':'salon_chat_seal_failed','HELD':'salon_chat_sent_held','SENT':'salon_chat_sent',
          'RETRY':'salon_chat_retry','MINING':'salon_chat_mining','UNKNOWN':'salon_outbox_unknown','SENDING':'salon_chat_sending','OWNER':'salon_outbox_owner',
          'REVIEW':'salon_outbox_review','WARNING':'salon_outbox_warning','CANCEL':'common_cancel','COPY':'salon_outbox_copy','CHANGED':'salon_chat_selection_changed'}
    for marker,key in sorted(keys.items(),key=lambda x:-len(x[0])):
        args=', "chain".equals(delivery)?Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_chain):"Maxima"' if marker=='WARNING' else ''
        snippet=snippet.replace('MSG_'+marker,'Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_'+key+args+')')
    a=s.index('    private void sendDm(');b=s.index('    /** Let the user force the transport',a);s=s[:a]+snippet+'\n'+s[b:]
    a=s.index('    /** The classic path:');b=s.index('    private void attachDmMedia()',a);s=s[:a]+s[b:]
    a=s.index('    /** msgIds currently');b=s.index('    /** One tip in flight',a);s=s[:a]+s[b:]
    old='CharSequence meta = m.mine && m.status == MailDb.PENDING ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_chat_sending) : socialTime(m.ts);'
    assert s.count(old)==1;s=s.replace(old,'CharSequence meta = outgoingStatus(m);\n        if(m.mine && (m.status==MailDb.UNCERTAIN || (m.status==MailDb.PENDING && !outgoingOwner(m)))) b.setOnClickListener(v->reviewOutgoing(m));')
    for old in ['        renderEpoch++;','        io.shutdownNow();']:
        assert s.count(old)==1;s=s.replace(old,'        cancelOutboxDialog();\n'+old)
    p.write_text(s)
