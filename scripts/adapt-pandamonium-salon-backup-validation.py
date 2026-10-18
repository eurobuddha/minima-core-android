"""Validate complete Salon backups and discard editor widgets only after draft restore."""
from pathlib import Path
import shutil

def adapt(root):
    source=Path(__file__).parent/'pandamonium-sources/salon'
    target=root/'salon/src/main/java/com/eurobuddha/salon'
    shutil.copy2(source/'BackupValidation.java',target/'BackupValidation.java')
    p=target/'MainActivity.java';s=p.read_text()
    a=s.index('            if (b.optInt("salonBackup", 0) != 1)')
    b=s.index('            JSONArray preparedHosts =',a)
    s=s[:a]+'''            try { BackupValidation.check(b); }
            catch (IllegalArgumentException invalid) {
                runOnUiThread(() -> backupNotice(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_settings_invalid_backup)));
                return;
            }
'''+s[b:]
    old='            if (me != null && !SalonStore.hasIdentity(this)) {   // don\'t clobber a live identity'
    new='            final boolean restoredDraft = me != null && !SalonStore.hasIdentity(this);\n            if (restoredDraft) {   // don\'t clobber a live identity'
    assert s.count(old)==1;s=s.replace(old,new)
    old='            final int hn = hostN; final boolean mo = msgOk, sk = seedKept;'
    new=old+'\n            final String restoredOwner = backupOwner();'
    assert s.count(old)==1;s=s.replace(old,new)
    old='runOnUiThread(() -> { if (backupClosed || isFinishing() || isDestroyed()) return; toast(Language.join('
    new='''runOnUiThread(() -> {
                if (backupClosed || isFinishing() || isDestroyed()) return;
                try { if (!restoredOwner.equals(backupOwner())) return; }
                catch (SalonComms.IdentityUnavailable unavailable) { return; }
                // Match hosted restore: old Edit widgets must not reapply the previous draft.
                if (restoredDraft) edName = edBio = edAbout = edAvatar = edBanner = edWebvalidate = null;
                toast(Language.join('''
    assert s.count(old)==1;s=s.replace(old,new)
    p.write_text(s)
