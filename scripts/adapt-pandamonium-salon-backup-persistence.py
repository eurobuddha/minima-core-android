"""Commit Salon identity, draft and hosting destinations as one preference transaction."""
from pathlib import Path
import shutil

def adapt(root):
    source=Path(__file__).parent/'pandamonium-sources/salon'
    target=root/'salon/src/main/java/com/eurobuddha/salon'
    # SalonStore/SalonComms are copied by the existing identity adapter. Preserve its
    # later checkedSeed visibility adaptation rather than recopying SalonComms here.
    shutil.copy2(source/'HostingStore.java',target/'HostingStore.java')
    p=target/'MainActivity.java';s=p.read_text()
    a=s.index('    private String buildBackupJson()');b=s.index('    /** Restore an account backup:',a)
    part=s[a:b].replace('SalonStore.me(this)', 'SalonStore.identityRecord(this)').replace('HostingStore.list(this)', 'HostingStore.listForBackup(this)')
    part=part.replace('        } catch (Crypt.Unavailable unavailable)', '        } catch (SalonStore.BackupStorageUnavailable unavailable) { throw unavailable; }\n        catch (Crypt.Unavailable unavailable)')
    part=part.replace('            b.put("hosting", hosts);', '            b.put("hosting", hosts);\n            try { BackupValidation.check(b); }\n            catch (IllegalArgumentException invalid) { throw new SalonStore.BackupStorageUnavailable(); }')
    s=s[:a]+part+s[b:]
    old='                } catch (Crypt.Unavailable unavailable) { runOnUiThread(() -> finishBackup(operation, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_backup_unavailable))); }'
    new='                } catch (SalonStore.BackupStorageUnavailable unavailable) { runOnUiThread(() -> finishBackup(operation, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_backup_storage_failed))); }\n                catch (Crypt.Unavailable unavailable) { runOnUiThread(() -> finishBackup(operation, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_backup_unavailable))); }'
    assert s.count(old)==1;s=s.replace(old,new)
    a=s.index('            boolean msgOk = false, seedKept = false;',s.index('    private void restoreFromBackup(String json, BackupOperation operation)'))
    b=s.index('            final String restoredOwner = backupOwner();',a)
    s=s[:a]+'''            final SalonComms.BackupResult restored;
            try { restored = SalonComms.restoreBackup(this, b, preparedHosts, () -> operation == null || backupCurrent(operation)); }
            catch (SalonComms.BackupCancelled cancelled) {
                runOnUiThread(() -> finishBackup(operation, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_backup_changed)));
                return;
            } catch (SalonComms.IdentityUnavailable unavailable) {
                runOnUiThread(() -> backupNotice(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_identity_restore_failed)));
                return;
            } catch (SalonStore.BackupStorageUnavailable unavailable) {
                runOnUiThread(() -> backupNotice(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_backup_storage_failed)));
                return;
            }
            final int hn = restored.destinations;
            final boolean mo = restored.keyRestored, sk = restored.keyKept, restoredDraft = restored.draftRestored;
'''+s[b:]
    p.write_text(s)
