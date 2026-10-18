"""Own Salon publication callbacks and reuse directory hosting for IPFS pages."""
from pathlib import Path
import shutil

def adapt(root):
    source=Path(__file__).parent/'pandamonium-sources/salon'
    target=root/'salon/src/main/java/com/eurobuddha/salon'
    shutil.copy2(source/'ProfileHosting.java',target/'ProfileHosting.java')
    shutil.copy2(source/'SalonStore.java',target/'SalonStore.java')
    p=target/'MainActivity.java';s=p.read_text()
    src=(source/'ProfilePublishing.java.inc').read_text()+'\n'
    for name,key in [('BUSY','busy'),('HOST','host'),('STORAGE','storage'),('PREPARING','preparing'),('UPLOADING','uploading'),('CHANGED','changed'),('FAILED','failed'),('UPLOADED','uploaded')]:
        args=', mediaError(error)' if name=='FAILED' else ''
        src=src.replace('MSG_'+name, 'Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_'+key+args+')')
    a=s.index('    /** Re-host profile.json');b=s.index('    // ---- Maxima mesh budget',a)
    s=s[:a]+src+s[b:]
    a=s.index('    /** Before publishing, stamp a FRESH');b=s.index('    /* ---------------- prove & showcase',a)
    s=s[:a]+s[b:]
    assert s.count('        cancelBackup();') == 1
    assert s.count('if (screen != s) cancelMediaRequests();') == 1
    s=s.replace('        cancelBackup();','        cancelBackup();\n        cancelPublication();')
    s=s.replace('if (screen != s) cancelMediaRequests();','if (screen != s) { cancelMediaRequests(); cancelPublication(); }')
    old='hostProfile(profStatus, () -> { publishSalon(profStatus); toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_published)); go(Screen.HOME); });'
    assert old in s;s=s.replace(old,'hostProfile(profStatus, () -> publishSalon(profStatus));')
    # Use the same transport for initial hosting, retaining the existing claim workflow.
    a=s.index('                final String profileUrl;');b=s.index('                runOnUiThread(() -> { SalonStore.put(this, "profileUrl", profileUrl);',a)
    s=s[:a]+'                final String profileUrl = ProfileHosting.upload(def, h, profile.toString(), salonHtml());\n'+s[b:]
    a=s.index('    private void publishSalon(TextView status) {');b=s.index('    /** Burn any salon identity',a)
    s=s[:a]+(source/'ProfileAnnouncement.java.inc').read_text()+'\n'+s[b:]
    p.write_text(s)
