package com.eurobuddha.statenft;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Existing AndroidKeyStore AES/GCM envelope. New secrets require secure storage;
 * legacy plain values remain readable so owners can migrate their saved profiles. */
final class Crypt {

    private static final String ALIAS = "pandamonium_atelier_hosting_key";

    private Crypt() {}

    static final class Unavailable extends IllegalStateException {
        @Override public String getMessage() {
            return com.eurobuddha.minimacore.i18n.Language.msg(
                    com.eurobuddha.minimacore.i18n.R.string.l10n_creative_secret_unavailable).toString();
        }
    }

    static String encrypt(String value) {
        try { return encryptSecure(value); }
        catch (Unavailable unavailable) { throw new Unavailable(); }
    }

    /** Identity storage must never fall back to plaintext. */
    static String encryptSecure(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            SecretKey key = key(true);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return "gcm:" + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception t) {
            throw new Unavailable();
        }
    }

    static String decrypt(String stored) {
        try { return decryptSecure(stored); }
        catch (Unavailable unavailable) { throw new Unavailable(); }
    }

    /** Distinguish an unavailable encrypted value from an absent one. */
    static String decryptSecure(String stored) {
        if (stored == null || stored.isEmpty()) return "";
        if (stored.startsWith("plain:")) return stored.substring(6);
        if (!stored.startsWith("gcm:")) return stored;   // legacy/unknown: as-is
        try {
            byte[] all = Base64.decode(stored.substring(4), Base64.NO_WRAP);
            byte[] iv = new byte[12];
            byte[] ct = new byte[all.length - 12];
            System.arraycopy(all, 0, iv, 0, 12);
            System.arraycopy(all, 12, ct, 0, ct.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception t) {
            throw new Unavailable();
        }
    }

    private static synchronized SecretKey key(boolean create) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry e = ks.getEntry(ALIAS, null);
        if (e instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        }
        if (!create || e != null) throw new Unavailable();
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return kg.generateKey();
    }
}
