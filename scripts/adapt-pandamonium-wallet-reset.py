"""Port standalone 0.2.24 Settings reset; retain integrated shared counters and worker guards."""
from pathlib import Path

def adapt(root):
    base=root/'wallet/src/main/java/com/eurobuddha/wallet'
    source=Path(__file__).with_name('pandamonium-sources')/'wallet'
    p=base/'SeedVault.java';s=p.read_text()
    marker='        void write(String hex);        // durable; throws IllegalStateException on failure'
    assert s.count(marker)==1
    s=s.replace(marker,marker+'\n        default void delete() { throw new IllegalStateException("Vault deletion unavailable"); }')
    marker='        @Override public void write(String zHex) {'
    assert s.count(marker)==1
    s=s.replace(marker,'''        @Override public void delete() {
            String previous = read();
            String audit = mPrefs.getString(PublicAuditKeys.RECORD, null);
            if (!mPrefs.edit().remove(KEY_BLOB).remove(PublicAuditKeys.RECORD).commit()) {
                // SharedPreferences can change memory despite a failed disk commit.
                mPrefs.edit().putString(KEY_BLOB, previous).putString(PublicAuditKeys.RECORD, audit).commit();
                throw new IllegalStateException("Failed to erase wallet vault");
            }
        }
'''+marker)
    marker='    /** Lock the session, wiping the in-memory seed + passphrase. */'
    assert s.count(marker)==1
    s=s.replace(marker,'''    /** Standalone wipe flow; shared seed-scoped counters must survive an integrated reset. */
    public synchronized void wipe() {
        requireOpen();
        try { mStore.delete(); }
        finally { lock(); } // Revoke old signing handles even if durable deletion fails.
    }

'''+marker);p.write_text(s)
    p=base/'WalletSession.java';s=p.read_text();marker='    // --- ProcessLifecycleOwner (whole-app foreground/background)'
    assert s.count(marker)==1
    s=s.replace(marker,'''    /** Reset only this vault. Other apps and previously used seed counters remain intact. */
    public void resetWallet() {
        mVault.wipe();
        mBackgroundedAt = 0L;
    }

'''+marker);p.write_text(s)
    p=base/'MainActivity.java';s=p.read_text();marker='    /** Reuse the wallet\'s existing import/generate forms after explicit reauthentication. */'
    assert s.count(marker)==1
    s=s.replace(marker,(source/'reset-activity.inc').read_text()+'\n'+marker);p.write_text(s)
    p=base/'SettingsView.java';s=p.read_text()
    marker='        // --- Seed backup '
    pos=s.index(marker)
    s=s[:pos]+'''        container.addView(dangerButton(Language.msg(l10n_wallet_reset), v -> resetWallet()));

'''+s[pos:]
    marker='    // Actions\n'
    pos=s.index('    /**',s.index(marker))
    s=s[:pos]+(source/'reset-settings.inc').read_text()+'\n'+s[pos:];p.write_text(s)
