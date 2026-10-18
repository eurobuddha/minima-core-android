"""Scope Salon file results and key operations to their originating identity."""
from pathlib import Path
import re

def adapt(root):
    p=root/'salon/src/main/java/com/eurobuddha/salon/MainActivity.java';s=p.read_text()
    def msg(key):return 'Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()+')'
    def change(old,new):
        nonlocal s
        assert old in s,('Salon backup drift',old);s=s.replace(old,new)
    # Keep the established serializer, clipboard handling and encrypted identity implementation.
    def remove(begin,end):
        nonlocal s
        a=s.index(begin);b=s.index(end,a);s=s[:a]+s[b:]
    remove('    private void backupAccount()', '    private void copyMessagingKey(')
    remove('    /** Save text to a file', '    /** A full account backup file:')
    remove('    private void restoreFromFile()', '    /** Restore an account backup:')
    remove('    private void restoreMsgKey()', '    /** GET-touch')
    change('    private String pendingSaveText;   // content queued for a SAF "Save file" (ACTION_CREATE_DOCUMENT)\n','')
    snippet=(Path(__file__).parent/'pandamonium-sources/salon/BackupOperations.java.inc').read_text()
    labels={'BUSY':'salonBackup.busy','CHANGED':'salonBackup.changed','UNAVAILABLE':'salonBackup.unavailable','IDENTITY':'salonIdentity.unavailable','NO_KEY':'salonSettings.noKey','KEY_HELP':'salonSettings.keyHelp','BACKUP_KEY':'salonSettings.backupKey','SAVE_FILE':'salonSettings.saveFile','COPY':'wallet.copy','CLOSE':'language.close','NO_SAVE_APP':'salonSettings.noSaveApp','NO_OPEN_APP':'salonSettings.noOpenApp','RESELECT':'salonBackup.reselect','DOCUMENT':'salonBackup.document','SAVED':'salonSettings.saved','SAVE_INCOMPLETE':'salonBackup.saveIncomplete','TOO_LARGE':'salonBackup.tooLarge','READ_FAILED':'salonBackup.readFailed','PASTE_KEY':'salonSettings.pasteKey','RESTORE_KEY':'salonSettings.restoreKey','RESTORE':'wallet.restore','RESTORED_KEY':'salonSettings.restoredKeyHelp','RESTORE_FAILED':'salonIdentity.restoreFailed'}
    snippet=re.sub(r'\bMSG_([A-Z_]+)\b',lambda m:msg(labels[m[1]]),snippet)
    change('    private void copyMessagingKey(',snippet+'    private void copyMessagingKey(')
    change('        cancelMediaRequests();\n        io.shutdownNow();','        cancelMediaRequests();\n        cancelBackup();\n        io.shutdownNow();')
    a=s.index('        if (res != RESULT_OK',s.index('    @Override protected void onActivityResult('));b=s.index('    private void promptMediaTitle(',a)
    s=s[:a]+'''        if (req == SAVE_TEXT || req == OPEN_BACKUP || (req >= 1000 && req <= 65535)) receiveBackupFile(req, res, data);
    }

'''+s[b:]
    # Reuse the existing bounded read for backups and ensure all paths close their stream.
    a=s.index('    private byte[] readCapped(');b=s.index('    private byte[] readScaledJpeg(',a)
    s=s[:a]+'''    private byte[] readCapped(Uri uri, int maxMB) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("cannot read file");
            ByteArrayOutputStream bos = new ByteArrayOutputStream(); byte[] buf = new byte[16384];
            int n; long total = 0, cap = (long) maxMB * 1024 * 1024;
            while ((n = in.read(buf)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                if (n == 0) { int one = in.read(); if (one == -1) break; buf[0] = (byte) one; n = 1; }
                total += n;
                if (total > cap) throw new Exception("file too large (max " + maxMB + " MB)");
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

'''+s[b:]
    # Reject malformed typed fields and unusable keys before mutating profile/hosting records.
    a=s.index('    private void restoreFromBackup(');b=s.index('    /** GET-touch',a);part=s[a:b]
    old='''            JSONArray preparedHosts = HostingSecrets.prepareRestore(b.optJSONArray("hosting"));'''
    new='''            if ((b.has("hosting") && !(b.opt("hosting") instanceof JSONArray))
                    || (b.has("me") && !(b.opt("me") instanceof JSONObject))
                    || (b.has("msgseed") && !(b.opt("msgseed") instanceof String))) throw new IllegalArgumentException("Invalid backup fields");
            JSONArray preparedHosts = HostingSecrets.prepareRestore(b.optJSONArray("hosting"));'''
    assert old in part;part=part.replace(old,new)
    start=part.index('            boolean msgOk = false, seedKept = false;');end=part.index('            final int hn',start);key=part[start:end];part=part[:start]+part[end:]
    key=key.replace('String seed = b.optString("msgseed", "");','String rawSeed = b.optString("msgseed", "");\n            String seed = rawSeed.isEmpty() ? "" : Hex.to(SalonComms.checkedSeed(rawSeed));')
    key=key.replace('''                } else if (SalonComms.importSeed(this, seed)) {
                    SalonStore.put(this, "msgpk", SalonComms.publicId(this)); msgOk = true;
                }''','''                } else {
                    if (!SalonComms.importSeed(this, seed)) {
                        runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) toast(FAILED); });
                        return;
                    }
                    msgOk = true;
                }'''.replace('FAILED',msg('salonIdentity.restoreFailed')))
    marker='            JSONObject me = b.optJSONObject("me");';assert marker in part;part=part.replace(marker,key+marker)
    part=part.replace('runOnUiThread(() -> { toast(Language.join(', 'runOnUiThread(() -> { if (backupClosed || isFinishing() || isDestroyed()) return; toast(Language.join(')
    part=part.replace('    private void restoreFromBackup(String json) {', '    private void restoreFromBackup(String json) { restoreFromBackup(json, null); }\n\n    private void restoreFromBackup(String json, BackupOperation operation) {')
    marker='            boolean msgOk = false, seedKept = false;'
    part=part.replace(marker, '            // Stage credential encryption before taking the profile-edit lock.\n            synchronized (SalonComms.class) { synchronized (SalonStore.class) {\n                if (operation != null && !backupCurrent(operation)) {\n                    runOnUiThread(() -> finishBackup(operation, '+msg('salonBackup.changed')+')); return;\n                }\n'+marker)
    part=part.replace('        } catch (Exception e) { runOnUiThread(() -> toast(', '            } }\n        } catch (Exception e) { runOnUiThread(() -> toast(')
    part=part.replace('runOnUiThread(() -> toast(', 'runOnUiThread(() -> backupNotice(')
    s=s[:a]+part+s[b:];p.write_text(s)
    p=root/'salon/src/main/java/com/eurobuddha/salon/SalonComms.java';s=p.read_text();assert 'private static byte[] checkedSeed(String hex)' in s;s=s.replace('private static byte[] checkedSeed(String hex)','static byte[] checkedSeed(String hex)');p.write_text(s)
