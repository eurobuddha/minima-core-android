package com.eurobuddha.salon;

import android.content.Context;

import java.security.SecureRandom;
import org.json.JSONObject;

/**
 * The device's messaging identity for DMs — an APP-managed libsodium keypair (X25519 box
 * + Ed25519 sign), independent of the Minima wallet seed and Maxima transport identity. The 32-byte
 * seed is stored encrypted at rest (Android Keystore via {@link Crypt}); the published
 * identity (msgpk = boxPk‖signPk) goes in profile.json. Because it's app-managed, it needs
 * an explicit backup/restore — a reinstall without a backup starts a fresh inbox.
 */
final class SalonComms {

    private static LocalEcCryptoProvider CRYPTO;
    private static String MSGPK = "";

    static synchronized LocalEcCryptoProvider crypto(Context c) {
        try {
            if (CRYPTO == null) {
                CommsIdentity id = CommsIdentity.fromSeed(Sodium.get(), loadOrCreateSeed(c));
                LocalEcCryptoProvider next = new LocalEcCryptoProvider(Sodium.get(), id);
                JSONObject stored = SalonStore.identityRecord(c);
                String published = stored.optString("msgpk", "");
                if (!published.isEmpty() && !published.equals(id.publicId())) throw new IdentityUnavailable();
                if (published.isEmpty() && !SalonStore.writeIdentity(c, stored.optString("msgseed"), id.publicId()))
                    throw new IdentityUnavailable();
                CRYPTO = next;
                MSGPK = id.publicId();
            }
            return CRYPTO;
        } catch (Exception unavailable) { throw new IdentityUnavailable(); }
    }

    static synchronized String publicId(Context c) { crypto(c); return MSGPK; }

    /** The raw 32-byte seed — also the root of the derived nostr key (NostrKeys). */
    static synchronized byte[] seed(Context c) { return loadOrCreateSeed(c); }

    static final class IdentityUnavailable extends IllegalStateException {
        IdentityUnavailable() { super("Salon messaging identity unavailable; stored identity preserved"); }
    }

    private static byte[] checkedSeed(String hex) {
        if (hex == null) throw new IdentityUnavailable();
        String value = hex.trim();
        if (value.startsWith("0x") || value.startsWith("0X")) value = value.substring(2);
        if (!value.matches("[0-9a-fA-F]{64}")) throw new IdentityUnavailable();
        return Hex.from(value);
    }

    private static byte[] loadOrCreateSeed(Context c) {
        try {
            JSONObject record = SalonStore.identityRecord(c);
            String enc = record.optString("msgseed", "");
            if (!enc.isEmpty()) return checkedSeed(Crypt.decryptSecure(enc));
            // A published key without its seed is an unavailable inbox, never a new identity.
            if (!record.optString("msgpk", "").isEmpty()) throw new IdentityUnavailable();
            byte[] seed = new byte[32];
            new SecureRandom().nextBytes(seed);
            String wrapped = Crypt.encryptSecure(Hex.to(seed));
            if (!SalonStore.writeIdentity(c, wrapped, "")) throw new IdentityUnavailable();
            return seed;
        } catch (Exception unavailable) { throw new IdentityUnavailable(); }
    }

    /** Backup: the 32-byte messaging seed as hex (guard it — it IS your inbox). */
    static synchronized String exportSeed(Context c) {
        try {
            JSONObject record = SalonStore.identityRecord(c);
            String enc = record.optString("msgseed", "");
            if (enc.isEmpty()) {
                if (!record.optString("msgpk", "").isEmpty()) throw new IdentityUnavailable();
                return "";
            }
            return Hex.to(checkedSeed(Crypt.decryptSecure(enc)));
        } catch (Exception unavailable) { throw new IdentityUnavailable(); }
    }

    /** Derive/wrap first; activation is a separate step after durable persistence. */
    static final class PreparedIdentity {
        final String wrapped, publicId;
        final LocalEcCryptoProvider crypto;
        PreparedIdentity(String wrapped, String publicId, LocalEcCryptoProvider crypto) {
            this.wrapped = wrapped; this.publicId = publicId; this.crypto = crypto;
        }
    }

    static synchronized PreparedIdentity prepareSeed(String hex) {
        try {
            byte[] seed = checkedSeed(hex);
            CommsIdentity id = CommsIdentity.fromSeed(Sodium.get(), seed);
            LocalEcCryptoProvider next = new LocalEcCryptoProvider(Sodium.get(), id);
            return new PreparedIdentity(Crypt.encryptSecure(Hex.to(seed)), id.publicId(), next);
        } catch (Exception unavailable) { throw new IdentityUnavailable(); }
    }

    private static void activate(PreparedIdentity next) {
        CRYPTO = next.crypto; MSGPK = next.publicId;
        NostrKeys.invalidate();
    }

    /** Restore from a hex seed; returns true only after persistence and activation. */
    static synchronized boolean importSeed(Context c, String hex) {
        try {
            PreparedIdentity next = prepareSeed(hex);
            if (!SalonStore.writeIdentity(c, next.wrapped, next.publicId)) return false;
            activate(next);
            return true;
        } catch (Exception unavailable) { return false; }
    }

    static final class BackupResult {
        final int destinations;
        final boolean draftRestored, keyRestored, keyKept;
        BackupResult(int destinations, boolean draftRestored, boolean keyRestored, boolean keyKept) {
            this.destinations = destinations; this.draftRestored = draftRestored;
            this.keyRestored = keyRestored; this.keyKept = keyKept;
        }
    }

    static final class BackupCancelled extends IllegalStateException { }

    /** Stage encryption first; recheck the owner's lifetime immediately before the save. */
    static synchronized BackupResult restoreBackup(Context c, JSONObject backup, org.json.JSONArray preparedHosts,
            java.util.function.BooleanSupplier currentOwner) throws Exception {
        synchronized (SalonStore.class) { synchronized (HostingStore.class) {
            JSONObject current = SalonStore.identityRecord(c);
            org.json.JSONArray hosts = HostingStore.mergeForRestore(c, preparedHosts);
            PreparedIdentity next = null;
            boolean keyKept = false;
            String rawSeed = backup.optString("msgseed", "");
            if (!rawSeed.isEmpty()) {
                String seed = Hex.to(checkedSeed(rawSeed));
                if (!MailDb.get(c).contacts().isEmpty() && !seed.equalsIgnoreCase(exportSeed(c))) keyKept = true;
                else {
                    next = prepareSeed(seed);
                    if (next == null) throw new IdentityUnavailable();
                }
            }
            boolean draftRestored = backup.has("me") && (current.optString("tokenid").isEmpty() || current.optString("handle").isEmpty());
            JSONObject account = new JSONObject((draftRestored ? backup.getJSONObject("me") : current).toString());
            for (String key : new String[]{"msgseed", "msgpk"}) {
                if (current.has(key)) account.put(key, current.get(key)); else account.remove(key);
            }
            if (next != null) account.put("msgseed", next.wrapped).put("msgpk", next.publicId);
            BackupResult result = new BackupResult(preparedHosts.length(), draftRestored, next != null, keyKept);
            if (!currentOwner.getAsBoolean()) throw new BackupCancelled();
            if (!SalonStore.commitBackup(c, account, hosts)) throw new SalonStore.BackupStorageUnavailable();
            if (next != null) activate(next);
            return result;
        } }
    }

    private SalonComms() {}
}
