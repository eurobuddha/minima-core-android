"""Fill remaining setup/inbox display messages using the shared plural catalog."""
from pathlib import Path

def adapt(root):
    p=root/'salon/src/main/java/com/eurobuddha/salon/MainActivity.java';s=p.read_text()
    old='toast("💬 " + (fc == 1 ? "@" + lf.replaceFirst("^@", "") + ": " + lb : fc + " new messages"));'
    new='toast(Language.join("💬 ", fc == 1 ? "@" + lf.replaceFirst("^@", "") + ": " + lb : Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_chat_new_messages, fc)));'
    assert s.count(old)==1;s=s.replace(old,new)
    old='toast("Blossom hosting ready — now claim your handle.");'
    new='toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_setup_blossom_ready));'
    assert s.count(old)==1;s=s.replace(old,new)
    p.write_text(s)
