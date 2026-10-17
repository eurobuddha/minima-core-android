"""Expose only minimaWallet public keys and mirrored counters to the existing Future Cash audit."""
from pathlib import Path
import shutil

def adapt(root):
    def change(relative, old, new, count=1):
        p=root/relative;s=p.read_text()
        assert s.count(old)==count, ('Wallet audit source drift',relative,old,s.count(old))
        p.write_text(s.replace(old,new))
    wallet='wallet/src/main/java/com/eurobuddha/wallet/'
    vault=wallet+'SeedVault.java'
    change(vault, 'void write(String hex);        // durable; throws IllegalStateException on failure',
        """void write(String hex);        // durable; throws IllegalStateException on failure
        default void writeAudited(String hex, VaultBlob blob, KeyUses counters) { write(hex); }""")
    change(vault, '"classic_" + org.minima.utils.Crypto.getInstance().hashObject(\n                        org.minima.utils.BIP39.convertStringToSeed(phrase)).to0xString().substring(2)',
        'PublicAuditKeys.namespace(phrase)')
    change(vault, 'private static final class PrefsBlobStore', 'static final class PrefsBlobStore')
    change(vault, 'mPrefs.edit().putString(KEY_BLOB, zHex).commit()',
        'mPrefs.edit().putString(KEY_BLOB, zHex).remove(PublicAuditKeys.RECORD).commit()')
    change(vault, '        @Override public void write(String zHex) {', """        @Override public void writeAudited(String hex, VaultBlob blob, KeyUses counters) {
            String descriptor = PublicAuditKeys.describe(blob, counters);
            // Same atomic commit as the encrypted blob: a replaced seed cannot retain old metadata.
            if (!mPrefs.edit().putString(KEY_BLOB, hex).putString(PublicAuditKeys.RECORD, descriptor).commit())
                throw new IllegalStateException("Failed to persist wallet vault and public audit keys");
        }
        @Override public void write(String zHex) {""")
    change(vault,'persist(blob, zPassphrase);','persist(blob, zPassphrase, candidate);',3)
    change(vault,'persist(blob, passphrase);','persist(blob, passphrase, candidate);')
    change(vault,'persist(blob, zNewWalletPassphrase);','persist(blob, zNewWalletPassphrase, candidate);')
    change(vault,'            openState(blob.getPhrase(), zPassphrase, blob.isKeyUsesTrusted(), candidate);',
        """            mStore.writeAudited(mStore.read(), blob, candidate);
            openState(blob.getPhrase(), zPassphrase, blob.isKeyUsesTrusted(), candidate);""")
    change(vault,'try { mStore.write(hex); } catch (RuntimeException failure)',
        'try { mStore.writeAudited(hex, blob, mKeyUses); } catch (RuntimeException failure)')
    change(vault,'    private void persist(VaultBlob zBlob, String zPassphrase) {',"""    private void persist(VaultBlob zBlob, String zPassphrase) {
        persist(zBlob, zPassphrase, mKeyUses);
    }
    private void persist(VaultBlob zBlob, String zPassphrase, KeyUses counters) {""")
    change(vault,'            mStore.write(hex);','            mStore.writeAudited(hex, zBlob, counters);')

    fc='futurecash-next/src/main/java/com/eurobuddha/futurecashnext/'
    change('futurecash-next/build.gradle','dependencies {',"dependencies {\n    implementation project(':integrated-wallet')")
    change(fc+'Cfg.java','    private final SharedPreferences sp;',"""    private final SharedPreferences sp;
    private final Context appContext;
    public static final String AUDIT_WALLET_USES = "audit_wallet_uses_v1";
    public JSONArray walletAuditKeys() {
        return com.eurobuddha.wallet.PublicAuditKeys.snapshot(appContext);
    }""")
    change(fc+'Cfg.java','    public Cfg(Context ctx) {','    public Cfg(Context ctx) {\n        appContext = ctx.getApplicationContext();')
    change(fc+'Audit.java','"dual-key-v1|"','"wallet-keys-v1|"')
    change(fc+'Audit.java','        if (age < FRESH_MS) return new Verdict(true, at, Mode.FRESH);',"""        final JSONArray walletKeys;
        try {
            walletKeys = cfg.walletAuditKeys();
            if (walletKeys == null || !usesFingerprint(walletKeys).equals(cfg.get(Cfg.AUDIT_WALLET_USES, "")))
                return new Verdict(false, at, Mode.STALE);
        } catch (RuntimeException unavailable) { return new Verdict(false, at, Mode.STALE); }
        if (age < FRESH_MS) return new Verdict(true, at, Mode.FRESH);""")
    change(fc+'Audit.java','        return usesFingerprint(keys).equals(fp)',"""        final JSONArray combined;
        try { combined = AuditKeySet.combine(keys, walletKeys); }
        catch (RuntimeException invalid) { return new Verdict(false, at, Mode.STALE); }
        return usesFingerprint(combined).equals(fp)""")
    change(fc+'Audit.java', '                      String badDestAddr, long badDestCount) {',"""                      String badDestAddr, long badDestCount) {
        store(atRiskAddrs, localKeys, complete, badDestAddr, badDestCount, new JSONArray());
    }
    public void store(List<String> atRiskAddrs, JSONArray localKeys, boolean complete,
                      String badDestAddr, long badDestCount, JSONArray walletKeys) {""")
    change(fc+'Audit.java','        cfg.set(Cfg.AUDIT_USES, usesFingerprint(localKeys));',
        '        cfg.set(Cfg.AUDIT_USES, usesFingerprint(localKeys));\n        cfg.set(Cfg.AUDIT_WALLET_USES, usesFingerprint(walletKeys));')
    change(fc+'AuditRunner.java','        final JSONArray keys = resp == null ? null : resp.optJSONArray("keys");',
        '        final JSONArray nodeKeys = resp == null ? null : resp.optJSONArray("keys");')
    change(fc+'AuditRunner.java','if (!Node.ok(r) || keys == null || keys.length() == 0)',
        'if (!Node.ok(r) || nodeKeys == null)')
    change(fc+'AuditRunner.java','        final List<KeyAudit.LocalKey> local = new ArrayList<>();',"""        final JSONArray walletKeys;
        final JSONArray keys;
        try {
            walletKeys = cfg.walletAuditKeys();
            keys = AuditKeySet.combine(nodeKeys, walletKeys);
        } catch (RuntimeException unavailable) {
            String message = "Could not read minimaWallet public audit keys. Unlock minimaWallet and retry.";
            audit.recordFailure(message);
            cb.onError(message);
            return;
        }
        final List<KeyAudit.LocalKey> local = new ArrayList<>();""")
    change(fc+'AuditRunner.java','                audit.store(atRisk, keys, complete, badAddr, badCount);',"""                // Seed replacement, trust changes and signing during the request revoke completeness.
                boolean walletUnchanged;
                try {
                    JSONArray now = cfg.walletAuditKeys();
                    walletUnchanged = now != null && Audit.usesFingerprint(walletKeys).equals(Audit.usesFingerprint(now));
                } catch (RuntimeException unavailable) { walletUnchanged = false; }
                complete &= walletUnchanged;
                audit.store(atRisk, keys, complete, badAddr, badCount, walletKeys);
                if (!walletUnchanged) {
                    String message = "minimaWallet changed during the audit. Run the audit again.";
                    audit.recordFailure(message);
                    cb.onError(message);
                    return;
                }""")
    src=Path(__file__).with_name('pandamonium-sources')/'futurecash-next/AuditKeySet.java'
    shutil.copy2(src,root/fc/'AuditKeySet.java')
