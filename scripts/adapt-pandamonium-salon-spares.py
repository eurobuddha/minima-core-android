"""Reuse strict Salon identity lookup and retained language dialogs for spare cleanup."""
from pathlib import Path
import shutil

def adapt(root):
    source=Path(__file__).parent/'pandamonium-sources/salon'
    target=root/'salon/src/main/java/com/eurobuddha/salon'
    shutil.copy2(source/'IdentityBalance.java',target/'IdentityBalance.java')
    p=target/'MainActivity.java';s=p.read_text()
    snippet=(source/'SpareIdentityCleanup.java.inc').read_text()
    for name,key,args in [
        ('NODE','node',''),('BUSY','busy',''),('IDENTITY','identity',''),('LOOKUP','lookup',''),('EMPTY','empty',''),
        ('TITLE','title',', spares.size()'),('CONFIRM','confirm',', op.keep, ids.toString()'),('BURN','burn',''),
        ('CHANGED','changed',''),('SUBMITTED','submitted',', tid'),('UNKNOWN','unknown',', tid')]:
        snippet=snippet.replace('MSG_'+name,'Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_spares_'+key+args+')')
    snippet=snippet.replace('MSG_CANCEL','Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_common_cancel)')
    a=s.index('    /** Burn any salon identity');b=s.index('    /* ================= SETTINGS',a)
    s=s[:a]+snippet+'\n'+s[b:]
    old='        cancelHostedRestore();\n        io.shutdownNow();';assert s.count(old)==1
    s=s.replace(old,'        cancelHostedRestore();\n        cancelSpareBurn();\n        io.shutdownNow();')
    old='cancelPublication(); cancelHostedRestore(); }';assert s.count(old)==1
    s=s.replace(old,'cancelPublication(); cancelHostedRestore(); cancelSpareBurn(); }')
    p.write_text(s)
