"""Preserve Salon messaging identity when encrypted storage is unavailable."""
from pathlib import Path
import shutil

def adapt(root):
    project=Path(__file__).resolve().parents[1]
    java=root/'salon/src/main/java/com/eurobuddha/salon'
    for name in ['SalonComms.java','SalonStore.java','Crypt.java','NostrKeys.java']:
        shutil.copy2(project/'scripts/pandamonium-sources/salon'/name,java/name)
    p=java/'MainActivity.java';s=p.read_text()
    def change(old,new):
        nonlocal s
        assert old in s, ('Salon identity caller drift',old)
        s=s.replace(old,new)
    change('        if (!SalonStore.get(this, "msgpk").isEmpty()) return;\n        io.execute(() -> {\n            String pk = SalonComms.publicId(MainActivity.this);   // generates+stores the seed if absent\n            runOnUiThread(() -> SalonStore.put(MainActivity.this, "msgpk", pk));\n        });','        io.execute(() -> {\n            try { SalonComms.publicId(MainActivity.this); }\n            catch (SalonComms.IdentityUnavailable unavailable) { identityUnavailable(); }\n        });')
    change('MinimaMail.scan(node, SalonComms.crypto(this), addr,', 'final LocalEcCryptoProvider identity;\n        try { identity = SalonComms.crypto(this); }\n        catch (SalonComms.IdentityUnavailable unavailable) { identityUnavailable(); return; }\n        MinimaMail.scan(node, identity, addr,')
    change('        } catch (Exception e) { sealedHex = null; }','        } catch (SalonComms.IdentityUnavailable unavailable) {\n            mInFlight.remove(msgId); identityUnavailable(); return;\n        } catch (Exception e) { sealedHex = null; }')
    change('        MinimaMail.send(node, SalonComms.crypto(this), ct.addr, ct.peerpk, msg, new MinimaMail.Cb() {','        final LocalEcCryptoProvider identity;\n        try { identity = SalonComms.crypto(this); }\n        catch (SalonComms.IdentityUnavailable unavailable) {\n            mInFlight.remove(msgId); identityUnavailable(); return;\n        }\n        MinimaMail.send(node, identity, ct.addr, ct.peerpk, msg, new MinimaMail.Cb() {')
    change('            final String seed = SalonComms.exportSeed(this);','            final String seed;\n            try { seed = SalonComms.exportSeed(this); }\n            catch (SalonComms.IdentityUnavailable unavailable) { identityUnavailable(); return; }')
    start=s.index('    private String buildBackupJson() {');end=s.index('    private void restoreFromFile()',start)
    section=s[start:end]
    assert 'catch (Exception ignored) {}' in section
    section=section.replace('catch (Exception ignored) {}','catch (Exception unavailable) { throw new SalonComms.IdentityUnavailable(); }')
    section=section.replace('Crypt.decrypt(enc)','Crypt.decryptSecure(enc)')
    s=s[:start]+section+s[end:]
    change('() -> saveTextFile("salon-backup.json", buildBackupJson())', 'this::backupAccount')
    change('else toast("Invalid key.");', 'else toast(Language.text(this, com.eurobuddha.minimacore.i18n.R.string.l10n_salon_identity_restore_failed));')
    change('                copyRow(card, "public key (hex)", NostrKeys.pubkeyHex());\n                copyRow(card, "npub", NostrKeys.npub());','                try {\n                    copyRow(card, "public key (hex)", NostrKeys.pubkeyHex());\n                    copyRow(card, "npub", NostrKeys.npub());\n                } catch (SalonComms.IdentityUnavailable unavailable) {\n                    TextView error = Design.note(this, "");\n                    Language.setText(error, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_identity_unavailable));\n                    card.addView(error);\n                }')
    change('    private void backupMsgKey() {', '    private void identityUnavailable() {\n        runOnUiThread(() -> {\n            if (!isFinishing() && !isDestroyed()) toast(Language.text(this,\n                    com.eurobuddha.minimacore.i18n.R.string.l10n_salon_identity_unavailable));\n        });\n    }\n\n    private void backupAccount() {\n        io.execute(() -> {\n            try {\n                String backup = buildBackupJson();\n                runOnUiThread(() -> {\n                    if (!isFinishing() && !isDestroyed()) saveTextFile("salon-backup.json", backup);\n                });\n            } catch (SalonComms.IdentityUnavailable unavailable) { identityUnavailable(); }\n        });\n    }\n\n    private void backupMsgKey() {')
    p.write_text(s)
