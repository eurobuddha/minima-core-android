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

    /** Restore from a hex seed; returns true on success and re-derives the identity. */
    static synchronized boolean importSeed(Context c, String hex) {
        try {
            byte[] seed = checkedSeed(hex);
            // Derive and wrap before changing any persisted or cached identity.
            CommsIdentity id = CommsIdentity.fromSeed(Sodium.get(), seed);
            LocalEcCryptoProvider next = new LocalEcCryptoProvider(Sodium.get(), id);
            String wrapped = Crypt.encryptSecure(Hex.to(seed));
            if (!SalonStore.writeIdentity(c, wrapped, id.publicId())) return false;
            CRYPTO = next;
            MSGPK = id.publicId();
            NostrKeys.invalidate();
            return true;
        } catch (Exception unavailable) { return false; }
    }

    private SalonComms() {}
}
