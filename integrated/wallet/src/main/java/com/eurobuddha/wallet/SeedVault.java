package com.eurobuddha.wallet;

import android.content.Context;
import android.content.SharedPreferences;

import org.minima.objects.base.MiniData;
import org.minima.utils.BaseConverter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SeedVault — the portable, seed-derived-encrypted store for the wallet seed + WOTS keyuses snapshot.
 * This REPLACES the interim device-bound {@code SeedStore} (Android-Keystore {@code
 * EncryptedSharedPreferences}, which does not restore across devices) with a container that is
 * decryptable on ANY device given the wallet passphrase (see {@link VaultCrypto}).
 *
 * <h3>Two-tier keyuses persistence (the fund-critical invariant preserved from M0/M2)</h3>
 * <ul>
 *   <li><b>Live counter</b> — {@link PrefsKeyUses}, UNCHANGED: plaintext, app-private, two mirrors,
 *       commit-before-return, MAX-on-read. Every signature advances THIS first (crash-safe). The vault
 *       wraps it; it never bypasses or weakens it.</li>
 *   <li><b>Portable snapshot</b> — a copy of the keyuses map inside the encrypted vault blob. Refreshed
 *       after every send ({@link #syncKeyUses}). On {@link #open}/{@link #importVault} it is folded into
 *       the live counter under the MAX rule ({@link VaultBlob#reconcileInto}) — raising, never lowering.
 *       The snapshot enumerates EVERY key index the live store knows about, so a higher index can never
 *       be silently dropped (and reset to 0) on a later restore.</li>
 * </ul>
 *
 * <h3>Fail-safe</h3>
 * If a seed is imported with no trustworthy keyuses history the blob is stored {@code trusted=false}.
 * While untrusted, {@link #assertSigningAllowed()} throws, so the Send/Split/Consolidate flows (the ONLY
 * code paths that reach {@link WalletCore#signTransactionID}) refuse to sign until the user either
 * confirms the seed is brand-new ({@link #confirmBrandNewSeed}) or, after a backup restore, explicitly
 * attests the restored counter is up to date ({@link #attestKeyUsesUpToDate}).
 *
 * <h3>Restore never silently re-trusts (C1)</h3>
 * A restored backup may be STALE — its keyuses snapshot could lag the true on-chain uses count if the
 * seed signed anything after the backup was taken. Trusting such a counter would risk reusing a
 * one-time WOTS leaf and exposing the key. Therefore {@link #importVault} always installs the restored
 * vault {@code trusted=false}; signing stays blocked by {@link #assertSigningAllowed()} until the user
 * makes the explicit WOTS-reuse attestation in the UI. Only {@link #createNew} (a freshly generated
 * seed with provably zero prior spends) is {@code trusted=true} without an attestation. The Auto-Backup
 * restore path is additionally neutralised by EXCLUDING the vault file from cloud/device backup (see
 * {@code backup_rules.xml} / {@code data_extraction_rules.xml}), so a stale cloud copy can never
 * silently restore-and-trust.
 *
 * <h3>Session model</h3>
 * The vault is unlocked once per session ({@link #open} with the passphrase, or {@link #createNew}/
 * {@link #importSeed} at onboarding), caching the seed phrase + passphrase in memory. Re-encryption on
 * {@link #syncKeyUses}/{@link #confirmBrandNewSeed} reuses the cached passphrase (a fresh salt+nonce are
 * generated each time). Nothing here derives an address or signs — that stays in {@link WalletCore}.
 */
public class SeedVault {

    private static final String FILE = "wallet_vault_v1";
    private static final String KEY_BLOB = "vault_blob_hex";

    /** Thrown when a signature is attempted while the keyuses record is not trustworthy. */
    public static final class SigningNotAllowedException extends IllegalStateException {
        public SigningNotAllowedException(String zMsg) { super(zMsg); }
    }

    /**
     * Thrown by {@link #open} when the passphrase was CORRECT (or the failure is not a passphrase
     * failure at all) but the vault could not be applied — a corrupt/unreadable blob or a failed
     * mirror commit. Distinct from a wrong passphrase (which {@link #open} reports by returning false)
     * so the UI can tell the user "wrong passphrase" apart from "your vault is damaged".
     */
    public static final class VaultCorruptException extends IllegalStateException {
        public VaultCorruptException(String zMsg, Throwable zCause) { super(zMsg, zCause); }
    }

    /**
     * Minimal durable string store for the single vault blob. Abstracted so the fund-critical state
     * machine is unit-testable off-device (Android impl wraps app-private {@link SharedPreferences};
     * tests use an in-memory impl). Writes MUST be durable (committed) or throw.
     */
    interface BlobStore {
        boolean contains();
        String read();                 // null if none
        void write(String hex);        // durable; throws IllegalStateException on failure
        default void delete() { throw new IllegalStateException("Vault deletion unavailable"); }
        default void writeAudited(String hex, VaultBlob blob, KeyUses counters) { write(hex); }
    }

    public static final class VaultUnavailableException extends IllegalStateException {
        public VaultUnavailableException(String message, Throwable cause) { super(message, cause); }
    }

    private final BlobStore mStore;
    interface CounterFactory { KeyUses forPhrase(String phrase); }
    private final CounterFactory mCounterFactory;
    private KeyUses mKeyUses;
    private long mGeneration;
    private volatile Object mSessionToken = new Object();

    public SeedVault(Context context) {
        this(new PrefsBlobStore(context), phrase -> new NodeKeyUses(context, phrase, new PrefsKeyUses(context,
                PublicAuditKeys.namespace(phrase))));
    }

    // Session state (only while unlocked).
    private String mPhrase;        // decrypted seed phrase
    private String mPassphrase;    // wallet passphrase (to re-encrypt on sync)
    private boolean mTrusted;      // keyuses trust flag from the open blob
    private volatile boolean mOpen;

    public SeedVault(Context zContext, PrefsKeyUses zKeyUses) {
        this(new PrefsBlobStore(zContext), zKeyUses);
    }

    /** Package-private: inject a store (Android or in-memory) and any {@link KeyUses} — for tests. */
    SeedVault(BlobStore zStore, KeyUses zKeyUses) {
        this(zStore, phrase -> zKeyUses);
    }

    SeedVault(BlobStore store, CounterFactory factory) {
        mStore = store;
        mCounterFactory = factory;
    }

    /** Android-backed {@link BlobStore} over app-private SharedPreferences. */
    static final class PrefsBlobStore implements BlobStore {
        private final SharedPreferences mPrefs;
        PrefsBlobStore(Context zContext) {
            mPrefs = zContext.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        }
        @Override public boolean contains() { return mPrefs.contains(KEY_BLOB); }
        @Override public String read() { return mPrefs.getString(KEY_BLOB, null); }
        @Override public void writeAudited(String hex, VaultBlob blob, KeyUses counters) {
            String descriptor = PublicAuditKeys.describe(blob, counters);
            // Same atomic commit as the encrypted blob: a replaced seed cannot retain old metadata.
            if (!mPrefs.edit().putString(KEY_BLOB, hex).putString(PublicAuditKeys.RECORD, descriptor).commit())
                throw new IllegalStateException("Failed to persist wallet vault and public audit keys");
        }
        @Override public void delete() {
            String previous = read();
            String audit = mPrefs.getString(PublicAuditKeys.RECORD, null);
            if (!mPrefs.edit().remove(KEY_BLOB).remove(PublicAuditKeys.RECORD).commit()) {
                // SharedPreferences can change memory despite a failed disk commit.
                mPrefs.edit().putString(KEY_BLOB, previous).putString(PublicAuditKeys.RECORD, audit).commit();
                throw new IllegalStateException("Failed to erase wallet vault");
            }
        }
        @Override public void write(String zHex) {
            if (!mPrefs.edit().putString(KEY_BLOB, zHex).remove(PublicAuditKeys.RECORD).commit()) {
                throw new IllegalStateException("Failed to persist wallet vault");
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Existence / session
    // ---------------------------------------------------------------------------------------------

    /** True once a vault blob has been created ON THIS DEVICE. It is never restored from a backup: this app's
     *  backup rules are include-only and name just the two seedless keyuses mirrors, so the vault is excluded
     *  by omission. (The fleet-wide 2026-08-09 sweep deliberately left this app's rules alone — those mirrors
     *  are a WOTS key-reuse safeguard, MAX-reconciled so a restore can only ever raise the counter.) */
    public synchronized boolean exists() {
        return mStore.contains();
    }

    /** True while the vault is unlocked this session (seed available in memory). */
    public synchronized boolean isOpen() {
        return mOpen;
    }

    /** The decrypted seed phrase — only valid while {@link #isOpen()}. */
    public synchronized String phrase() {
        requireOpen();
        return mPhrase;
    }

    /** Whether the current keyuses record is trustworthy (drives the signing fail-safe). */
    public synchronized boolean isKeyUsesTrusted() {
        return mOpen && mTrusted;
    }

    /** Standalone wipe flow; shared seed-scoped counters must survive an integrated reset. */
    public synchronized void wipe() {
        requireOpen();
        try { mStore.delete(); }
        finally { lock(); } // Revoke old signing handles even if durable deletion fails.
    }

    /** Lock the session, wiping the in-memory seed + passphrase. */
    public synchronized void lock() {
        mGeneration++;
        mSessionToken = new Object();
        mKeyUses = null;
        mPhrase = null;
        mPassphrase = null;
        mTrusted = false;
        mOpen = false;
    }

    // ---------------------------------------------------------------------------------------------
    // Onboarding: create new / import
    // ---------------------------------------------------------------------------------------------

    /**
     * Create a brand-new wallet: a freshly generated seed with NO prior spends, so keyuses is a
     * trustworthy 0 for every key ({@code trusted=true}). Encrypts under {@code zPassphrase} and opens
     * the session. Previously recorded counters for this seed are preserved.
     */
    public synchronized void createNew(String zPhrase, String zPassphrase) {
        KeyUses candidate = mCounterFactory.forPhrase(zPhrase);
        VaultBlob blob = new VaultBlob(zPhrase, true, snapshot(candidate));
        persist(blob, zPassphrase, candidate);
        openState(zPhrase, zPassphrase, true, candidate);
    }

    /**
     * Import an existing seed. {@code zBrandNew=true} means the user asserts this seed has NEVER been
     * used to sign anywhere (safe to start at uses=0, trusted). {@code zBrandNew=false} is the dangerous
     * bare-seed case: uses is UNKNOWN → {@code trusted=false} → signing is blocked until confirmed or a
     * keyuses backup is imported.
     */
    public synchronized void importSeed(String zPhrase, String zPassphrase, boolean zBrandNew) {
        KeyUses candidate = mCounterFactory.forPhrase(zPhrase);
        // Never lower an existing local mirror if one somehow exists (snapshot reads MAX-on-read).
        VaultBlob blob = new VaultBlob(zPhrase, zBrandNew, snapshot(candidate));
        persist(blob, zPassphrase, candidate);
        openState(zPhrase, zPassphrase, zBrandNew, candidate);
    }

    /**
     * Import an existing seed, ATTESTING how many one-time (WOTS) signatures it has already made on key
     * indices 0..63 (a shared lower bound for all classic base keys). This is the honest import path: a restored wallet almost never starts at 0, and there is no
     * safe way to derive the count automatically — only the user knows it (from their previous keyuses
     * backup, or this app's "Signatures used" figure on the old device).
     *
     * <p>The attested count is folded into the live counter under the MAX rule
     * ({@link KeyUses#recordExternalUses}) — it can only RAISE the counter, never lower it — and the vault
     * is stored {@code trusted=true}, because the user has explicitly stated the count. This is the same
     * class of user attestation as {@link #confirmBrandNewSeed} (which asserts 0), just with an explicit
     * value instead of an implicit zero.
     *
     * <p><b>Safety:</b> over-stating the count is safe (it merely skips some of the 262,144 one-time
     * leaves); under-stating it reuses a leaf and exposes the key, so the caller MUST warn the user to
     * over-estimate when unsure. {@code zIndex0Uses == 0} is the brand-new case (equivalent to
     * {@code importSeed(..., true)}) and the caller should confirm it explicitly.
     */
    public synchronized void importSeed(String zPhrase, String zPassphrase, int zIndex0Uses) {
        if (zIndex0Uses < 0) {
            throw new IllegalArgumentException("signatures used must be >= 0");
        }
        KeyUses candidate = mCounterFactory.forPhrase(zPhrase);
        raiseClassicFloor(candidate, zIndex0Uses);
        // currentSnapshot() now reflects the raised count; store trusted (the user explicitly attested it).
        VaultBlob blob = new VaultBlob(zPhrase, true, snapshot(candidate));
        persist(blob, zPassphrase, candidate);
        openState(zPhrase, zPassphrase, true, candidate);
    }

    // ---------------------------------------------------------------------------------------------
    // Unlock
    // ---------------------------------------------------------------------------------------------

    /**
     * Unlock the stored vault with the passphrase. On success: caches the seed, reconciles the vault's
     * keyuses snapshot into the live counter under the MAX rule (never lowers), and returns true. A
     * WRONG passphrase (or tampered blob) returns false with no state change. A blob that decrypts with
     * the RIGHT passphrase but has an invalid payload throws {@link VaultCorruptException};
     * unavailable node/counters throw {@link VaultUnavailableException} — so the caller can distinguish the two situations for the user.
     */
    public synchronized boolean open(String zPassphrase) {
        VaultBlob blob = decryptStored(zPassphrase);
        if (blob == null) return false;
        try {
            // Restore: fold the snapshot into the live counter (MAX rule) BEFORE anyone can sign.
            KeyUses candidate = mCounterFactory.forPhrase(blob.getPhrase());
            blob.reconcileInto(candidate);
            mStore.writeAudited(mStore.read(), blob, candidate);
            openState(blob.getPhrase(), zPassphrase, blob.isKeyUsesTrusted(), candidate);
            return true;
        } catch (Exception applyErr) {
            // The payload is valid. Counter/node availability must not be reported as corruption.
            throw new VaultUnavailableException(
                    "Wallet counters are unavailable. Start the embedded node and retry.", applyErr);
        }
    }

    /** Same snapshot guard used by PandaDEX: old work never becomes current again. */
    public synchronized java.util.function.BooleanSupplier authorization() {
        requireOpen();
        final Object token = mSessionToken;
        return () -> mOpen && mSessionToken == token;
    }

    public synchronized void importNodeSeed(String passphrase, int floor) {
        importNodeSeed(passphrase, floor, EmbeddedWallet.NODE);
    }

    synchronized void importNodeSeed(String passphrase, int floor, EmbeddedWallet.Source source) {
        if (floor < 0 || floor >= Util.WOTS_MAX_USES)
            throw new IllegalArgumentException("Classic key-use floor is out of range");
        final String phrase = source.withWallet(EmbeddedWallet::phrase);
        KeyUses candidate = mCounterFactory.forPhrase(phrase);
        // Derivation happens outside the node monitor. Counter coordination takes
        // the monitor only for each short verified query/update.
        raiseClassicFloor(candidate, floor);
        VaultBlob blob = new VaultBlob(phrase, true, snapshot(candidate));
        source.withWallet(wallet -> {
            if (!phrase.equals(EmbeddedWallet.phrase(wallet)))
                throw new IllegalStateException("The node seed changed. Review setup again");
            persist(blob, passphrase, candidate);
            openState(phrase, passphrase, true, candidate);
            return null;
        });
    }

    /** Verify sensitive Settings actions without replacing the active signing generation. */
    public synchronized boolean verifyPassphrase(String passphrase) {
        requireOpen();
        VaultBlob blob = decryptStored(passphrase);
        return blob != null && mPhrase.equals(blob.getPhrase());
    }

    private VaultBlob decryptStored(String zPassphrase) {
        String hex = mStore.read();
        if (hex == null) return null;

        byte[] plain;
        try {
            if (hex.length() > VaultCrypto.MAX_CONTAINER_BYTES * 2 + 2)
                throw new IllegalArgumentException("Stored vault too large");
            byte[] container = new MiniData(hex).getBytes();
            plain = VaultCrypto.decrypt(zPassphrase, container);
        } catch (VaultCrypto.BadPassphraseException wrongPass) {
            // GCM tag failure: genuinely the wrong passphrase (or a tampered blob) — caller reprompts.
            return null;
        } catch (Exception decodeErr) {
            // Not even a decodable container (bad hex / truncated) — not a passphrase problem.
            throw new VaultCorruptException("Wallet vault is unreadable: " + decodeErr.getMessage(), decodeErr);
        }

        try { return VaultBlob.fromBytes(plain); }
        catch (IllegalArgumentException corrupt) {
            throw new VaultCorruptException("Wallet vault payload is unreadable", corrupt);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Fail-safe gate + keyuses sync
    // ---------------------------------------------------------------------------------------------

    /**
     * The single chokepoint the Send/Split/Consolidate controller MUST call before building a
     * transaction. Throws if the vault is locked or the keyuses record is not trustworthy — so a
     * signature (which would consume a one-time WOTS leaf) can never be produced against an unknown
     * uses count, which would risk a key-exposing reuse.
     */
    public synchronized void assertSigningAllowed() {
        if (!mOpen) {
            throw new SigningNotAllowedException("Wallet is locked");
        }
        if (!mTrusted) {
            throw new SigningNotAllowedException(
                    "Signing is blocked: the number of signatures already used by this seed is unknown. "
                  + "Reusing a one-time key EXPOSES it and can lose your funds. Enter your signatures-used "
                  + "count, confirm this seed is brand-new, or import a keyuses backup, before sending.");
        }
    }

    /**
     * After a successful send has advanced the live counter, refresh the vault's portable keyuses
     * snapshot to the current live values and re-encrypt, so a manual export stays current. Cheap
     * AES-GCM re-encrypt (a new PBKDF2 salt is generated, matching {@link VaultCrypto}). Enumerates
     * EVERY key index the live store knows about (plus any explicitly named, plus index 0), so no
     * higher index is ever dropped from the snapshot.
     */
    public synchronized void syncKeyUses(int... zKeyIndices) {
        if (!mOpen) return;
        LinkedHashMap<Integer, Integer> uses = currentSnapshot();
        if (zKeyIndices != null) {
            for (int idx : zKeyIndices) uses.put(idx, mKeyUses.currentUses(idx));
        }
        VaultBlob blob = new VaultBlob(mPhrase, mTrusted, uses);
        persist(blob, mPassphrase);
    }

    /**
     * User confirms the (previously bare/untrusted) seed is brand-new: mark keyuses trustworthy at the
     * current (0) count. Only call after an explicit, warned user confirmation.
     */
    public synchronized void confirmBrandNewSeed() {
        requireOpen();
        trustAndPersist();
    }

    /**
     * User explicitly attests, AFTER a backup restore, that the restored keyuses counter is their
     * most-recent state — i.e. they have NOT signed any transaction from this seed since the backup was
     * made. This is the ONLY way a restored (initially untrusted) vault becomes trusted. Only call
     * after the loud WOTS-reuse warning dialog. Semantically identical to {@link #confirmBrandNewSeed}
     * (flip trusted + persist) but named for the restore intent.
     */
    public synchronized void attestKeyUsesUpToDate() {
        requireOpen();
        trustAndPersist();
    }

    /**
     * Attest, for an already-open but untrusted vault (a bare import left blocked, or a just-restored
     * backup), a lower bound on signatures used by each of the 64 classic base keys — then trust + persist.
     * The count is MAX-folded into the live counter (raise-only), so it can never lower a higher existing
     * value. Over-stating is safe; under-stating risks a WOTS reuse, so the UI MUST warn accordingly.
     * This is the Settings-side twin of the numeric {@link #importSeed(String, String, int)}.
     */
    public synchronized void setStartingKeyUses(int zIndex0Uses) {
        requireOpen();
        if (zIndex0Uses < 0) {
            throw new IllegalArgumentException("signatures used must be >= 0");
        }
        raiseClassicFloor(mKeyUses, zIndex0Uses);
        trustAndPersist();
    }

    /**
     * Re-encrypt the vault under a NEW wallet passphrase (Settings → Change unlock passphrase). The
     * seed, the {@code trusted} flag and the keyuses snapshot are carried over UNCHANGED — this only
     * rotates the passphrase-derived key (a fresh PBKDF2 salt + GCM nonce are generated by
     * {@link VaultCrypto#encrypt}).
     *
     * <p><b>Atomicity (never lose the vault on failure).</b> The new container is built and then
     * VERIFIED to decrypt back to the same seed + trust flag <i>before</i> the single stored blob is
     * overwritten. If encryption or the verify fails we throw and the existing (old-passphrase) blob is
     * left untouched, so a failed change can never brick the wallet. The store write itself is a single
     * atomic {@code commit()} of one key (SharedPreferences writes via a temp file + rename), so there is
     * no half-written intermediate state. Only after the durable write succeeds is the in-memory session
     * passphrase updated to the new one.
     *
     * <p>Requires the vault to be OPEN (the caller having proven the current passphrase by unlocking).
     * Signing / keyuses / trust state are not touched.
     */
    public synchronized void changePassphrase(String zNewPassphrase) {
        requireOpen();

        // Build the new blob from the exact current in-memory state.
        VaultBlob blob = new VaultBlob(mPhrase, mTrusted, currentSnapshot());

        final byte[] container;
        try {
            container = VaultCrypto.encrypt(zNewPassphrase, blob.toBytes());
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt vault with new passphrase", e);
        }

        // VERIFY the new container decrypts to the same seed + trust BEFORE replacing the stored blob.
        try {
            VaultBlob check = VaultBlob.fromBytes(VaultCrypto.decrypt(zNewPassphrase, container));
            if (!mPhrase.equals(check.getPhrase()) || check.isKeyUsesTrusted() != mTrusted) {
                throw new IllegalStateException("Re-encrypted vault did not verify");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Re-encrypted vault failed verification", e);
        }

        // Only now overwrite the single stored blob (atomic commit; throws on failure, old blob intact).
        String hex = BaseConverter.encode16(container);
        try { mStore.writeAudited(hex, blob, mKeyUses); } catch (RuntimeException failure) { lock(); throw failure; }

        // Session now uses the new passphrase for subsequent syncKeyUses re-encrypts.
        mPassphrase = zNewPassphrase;
    }

    // ---------------------------------------------------------------------------------------------
    // Manual backup / restore (the authoritative recovery path)
    // ---------------------------------------------------------------------------------------------

    /**
     * Produce a portable backup file's bytes: the current {seed + keyuses snapshot}, encrypted under
     * {@code zExportPassphrase} (which may differ from the wallet passphrase). The result is a
     * self-describing {@link VaultCrypto} container — restore it on any device with only this passphrase.
     * The snapshot enumerates every known key index (not just index 0).
     */
    public synchronized byte[] exportBytes(String zExportPassphrase) throws Exception {
        requireOpen();
        VaultBlob blob = new VaultBlob(mPhrase, mTrusted, currentSnapshot());
        return VaultCrypto.encrypt(zExportPassphrase, blob.toBytes());
    }

    /**
     * Decrypt a backup file for preview WITHOUT committing it (used to show the user what will be
     * restored). Throws on a wrong passphrase.
     */
    public static VaultBlob peekImport(byte[] zContainer, String zPassphrase) throws Exception {
        return VaultBlob.fromBytes(VaultCrypto.decrypt(zPassphrase, zContainer));
    }

    /**
     * Restore from a backup file: decrypts under {@code zFilePassphrase}, folds its keyuses snapshot
     * into the live counter (MAX — never lowers), and installs it as the local vault encrypted under
     * {@code zNewWalletPassphrase}. The restored vault is installed {@code trusted=false} (C1): a backup
     * can be STALE, so signing stays blocked by {@link #assertSigningAllowed()} until the user makes the
     * explicit up-to-date attestation ({@link #attestKeyUsesUpToDate}) in the UI. Opens the session.
     */
    public synchronized void importVault(byte[] zContainer, String zFilePassphrase, String zNewWalletPassphrase)
            throws Exception {
        VaultBlob restored = VaultBlob.fromBytes(VaultCrypto.decrypt(zFilePassphrase, zContainer));
        KeyUses candidate = mCounterFactory.forPhrase(restored.getPhrase());
        VaultBlob blob = reconcileRestored(restored, candidate);   // MAX-reconciles; returns trusted=false
        persist(blob, zNewWalletPassphrase, candidate);
        openState(restored.getPhrase(), zNewWalletPassphrase, false, candidate);   // untrusted until attested
    }

    /**
     * Build the vault blob to persist after a restore. MAX-reconciles the restored snapshot into the
     * live counter (raising, never lowering — the fund-critical restore invariant) and returns an
     * UNTRUSTED blob carrying the reconciled counts for every index the restore or the live store knew
     * about. Package-private + static so the C1 behaviour is directly unit-testable off-device.
     */
    static VaultBlob reconcileRestored(VaultBlob zRestored, KeyUses zLive) {
        // Raise the live counter to the restored snapshot (never lower).
        zRestored.reconcileInto(zLive);

        // Persist the reconciled (>=) values for every index seen in the restore OR already live, so a
        // later restore can never silently reset a higher index's counter.
        LinkedHashMap<Integer, Integer> uses = new LinkedHashMap<>();
        for (Integer idx : zRestored.getKeyUses().keySet()) {
            uses.put(idx, zLive.currentUses(idx));
        }
        for (Map.Entry<Integer, Integer> e : zLive.snapshotAllUses().entrySet()) {
            uses.put(e.getKey(), e.getValue());
        }
        if (!uses.containsKey(0)) uses.put(0, zLive.currentUses(0));
        return new VaultBlob(zRestored.getPhrase(), false, uses);   // C1: restore is NOT auto-trusted
    }

    // ---------------------------------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------------------------------

    /** A snapshot of the live counter for every known index, always including index 0 (MAX-on-read). */
    private LinkedHashMap<Integer, Integer> currentSnapshot() {
        requireOpen();
        return snapshot(mKeyUses);
    }

    private static LinkedHashMap<Integer, Integer> snapshot(KeyUses counter) {
        LinkedHashMap<Integer, Integer> uses = new LinkedHashMap<>(counter.snapshotAllUses());
        uses.put(0, counter.currentUses(0));   // primary key always present, even at 0 uses
        return uses;
    }

    private void persist(VaultBlob zBlob, String zPassphrase) {
        persist(zBlob, zPassphrase, mKeyUses);
    }
    private void persist(VaultBlob zBlob, String zPassphrase, KeyUses counters) {
        try {
            byte[] container = VaultCrypto.encrypt(zPassphrase, zBlob.toBytes());
            String hex = BaseConverter.encode16(container);   // already 0x-prefixed
            mStore.writeAudited(hex, zBlob, counters);
        } catch (IllegalStateException e) {
            lock();
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt/persist wallet vault", e);
        }
    }

    private void openState(String zPhrase, String zPassphrase, boolean zTrusted, KeyUses counter) {
        mGeneration++;
        mSessionToken = new Object();
        mKeyUses = counter;
        mPhrase = zPhrase;
        mPassphrase = zPassphrase;
        mTrusted = zTrusted;
        mOpen = true;
    }

    private static void raiseClassicFloor(KeyUses counter, int floor) {
        if (floor < 0) throw new IllegalArgumentException("Negative key uses");
        // One creation/restoration floor applies to the whole classic base-key set.
        for (int i = 0; i < WalletCore.NUM_BASE_KEYS; i++) counter.recordExternalUses(i, floor);
    }

    private void trustAndPersist() {
        persist(new VaultBlob(mPhrase, true, currentSnapshot()), mPassphrase);
        mTrusted = true; // failed persistence must never enable signing in memory
    }

    /** A handle for this unlocked generation. Lock/reopen/seed changes revoke old signers. */
    public synchronized KeyUses keyUses() {
        requireOpen();
        final long generation = mGeneration;
        final KeyUses counter = mKeyUses;
        return new KeyUses() {
            private void check() {
                requireOpen();
                if (generation != mGeneration) throw new SigningNotAllowedException("Wallet session changed");
            }
            @Override public int currentUses(int index) {
                synchronized (SeedVault.this) { check(); return counter.currentUses(index); }
            }
            @Override public int reserveNextUse(int index) {
                synchronized (SeedVault.this) {
                    check(); assertSigningAllowed(); return counter.reserveNextUse(index);
                }
            }
            @Override public void recordExternalUses(int index, int uses) {
                synchronized (SeedVault.this) { check(); counter.recordExternalUses(index, uses); }
            }
            @Override public Map<Integer,Integer> snapshotAllUses() {
                synchronized (SeedVault.this) { check(); return counter.snapshotAllUses(); }
            }
        };
    }

    private void requireOpen() {
        if (!mOpen) throw new IllegalStateException("Vault is locked");
    }
}
