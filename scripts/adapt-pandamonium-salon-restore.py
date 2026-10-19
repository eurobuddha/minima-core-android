"""Own hosted Salon restores and reuse the durable profile store."""
from pathlib import Path
import shutil

def adapt(root):
    source=Path(__file__).parent/'pandamonium-sources/salon'
    target=root/'salon/src/main/java/com/eurobuddha/salon'
    shutil.copy2(source/'SalonStore.java',target/'SalonStore.java')
    p=target/'MainActivity.java';s=p.read_text()
    snippet=(source/'HostedRestore.java.inc').read_text()
    keys={'NO_URL':'salon_setup_no_hosted_url','BUSY':'salon_restore_busy','CHANGED':'salon_restore_changed',
          'STORAGE':'salon_restore_storage','RESTORING':'salon_setup_restoring','UNAVAILABLE':'salon_restore_unavailable',
          'UNREACHABLE':'salon_setup_unreachable','RESTORED':'salon_setup_restored','EMPTY':'salon_setup_empty_hosted','INVALID':'salon_restore_invalid','RECOVERY_HELP':'salon_restore_source_help','RECOVERY_URL':'salon_editor_url','RECOVERY_TITLE':'salon_settings_restore_page','RECOVERY_ACTION':'wallet_restore','RECOVERY_INVALID_URL':'salon_restore_source_https'}
    for marker,key in keys.items():snippet=snippet.replace('MSG_'+marker,'Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_'+key+')')
    a=s.index('    /** A reinstall wipes the local draft');b=s.index('    private void publishSalon(',a)
    s=s[:a]+snippet+'\n'+s[b:]
    old='            hydrateFromHosted(meta.optString("url"), false);   // reinstall: pull content back from the hosted page\n            if (screen == Screen.HOME || screen == Screen.ONBOARD || screen == Screen.FEED) go(Screen.HOME);'
    assert s.count(old)==1
    s=s.replace(old,'            if (screen == Screen.HOME || screen == Screen.ONBOARD || screen == Screen.FEED) go(Screen.HOME);\n            hydrateFromHosted(meta.optString("url"), false);   // capture the final screen after adoption')
    old='        cancelPublication();\n        io.shutdownNow();';assert s.count(old)==1
    s=s.replace(old,'        cancelPublication();\n        cancelHostedRestore();\n        io.shutdownNow();')
    old='if (screen != s) { cancelMediaRequests(); cancelPublication(); }';assert s.count(old)==1
    s=s.replace(old,'if (screen != s) { cancelMediaRequests(); cancelPublication(); cancelHostedRestore(); }')
    old='() -> hydrateFromHosted(SalonStore.get(this, "profileUrl"), true)';assert old in s
    s=s.replace(old,'this::promptHostedRestore')
    p.write_text(s)
