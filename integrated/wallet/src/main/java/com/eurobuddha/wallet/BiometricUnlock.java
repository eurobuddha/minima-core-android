package com.eurobuddha.wallet;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import java.security.KeyStore;
import java.util.concurrent.Executor;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * BiometricUnlock — the OPTIONAL, device-bound convenience layer that lets the user re-open the vault
 * with a fingerprint / face / device PIN instead of typing the passphrase. It is strictly additive:
 *
 * <ul>
 *   <li>It NEVER replaces the passphrase. The typed passphrase always remains available on the unlock
 *       screen, and it stays the authoritative recovery (via the portable {@link SeedVault} export).</li>
 *   <li>What it stores is the vault PASSPHRASE, encrypted by an AndroidKeyStore AES-256-GCM key that
 *       requires user authentication ({@code setUserAuthenticationRequired(true)}) and is invalidated if
 *       the device's biometrics change ({@code setInvalidatedByBiometricEnrollment(true)}). Only the
 *       ciphertext + IV are kept in app-private prefs — NEVER the plaintext passphrase, and this prefs
 *       file is NOT in the backup include-list, so it is not cloud/device-portable.</li>
 *   <li>The Keystore key material is hardware-bound and non-exportable, so a new / lost device cannot use
 *       it — the passphrase (or the encrypted export) is still required there.</li>
 * </ul>
 *
 * <p>On success the recovered passphrase is handed to {@link SeedVault#open(String)} — the SAME code path
 * as a typed unlock — so the "vault is open (seed in memory)" state that gates signing is reached
 * identically whether the user unlocked by passphrase or biometric.
 */
public final class BiometricUnlock {

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "wallet_bio_passphrase_v1";

    private static final String PREFS = "wallet_biometric";
    private static final String K_CT = "bio_ct";
    private static final String K_IV = "bio_iv";

    private static final String TRANSFORMATION =
            KeyProperties.KEY_ALGORITHM_AES + "/" + KeyProperties.BLOCK_MODE_GCM
                    + "/" + KeyProperties.ENCRYPTION_PADDING_NONE;

    private static final int GCM_TAG_BITS = 128;

    /** Crypto-backed prompts (we pass a CryptoObject) must use BIOMETRIC_STRONG only — pairing a CryptoObject
     *  with DEVICE_CREDENTIAL is rejected by BiometricPrompt on many devices. The typed passphrase is the fallback. */
    private static int allowedAuthenticators() {
        return BiometricManager.Authenticators.BIOMETRIC_STRONG;
    }

    /** Result callbacks for enable / unlock. */
    public interface Callback {
        void onSuccess();
        /** {@code zPermanentlyInvalidated} is true when enrolment changed and the entry was cleared. */
        void onError(String zMessage, boolean zPermanentlyInvalidated);
        void onCancelled();
    }

    /** A callback that also yields the recovered passphrase (unlock). */
    public interface UnlockCallback {
        void onPassphrase(String zPassphrase);
        void onError(String zMessage, boolean zPermanentlyInvalidated);
        void onCancelled();
    }

    private BiometricUnlock() {}
    private static final java.util.concurrent.atomic.AtomicLong requests = new java.util.concurrent.atomic.AtomicLong();
    private static boolean current(FragmentActivity activity, long request, java.util.function.BooleanSupplier authorized) {
        try { return request == requests.get() && !activity.isFinishing() && !activity.isDestroyed()
                && activity.getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                && authorized.getAsBoolean(); }
        catch (RuntimeException unavailable) { return false; }
    }

    // --- availability / state --------------------------------------------------------------------

    /** True when the device has usable strong biometric or device-credential auth. */
    public static boolean isAvailable(Context zContext) {
        BiometricManager bm = BiometricManager.from(zContext);
        int res = bm.canAuthenticate(allowedAuthenticators());
        return res == BiometricManager.BIOMETRIC_SUCCESS;
    }

    /** True when the user has enabled biometric unlock (a stored ciphertext exists). */
    public static boolean isEnabled(Context zContext) {
        SharedPreferences p = prefs(zContext);
        return p.contains(K_CT) && p.contains(K_IV);
    }

    /** Disable: wipe the Keystore key and the stored ciphertext + IV. */
    public static synchronized void disable(Context zContext) {
        requests.incrementAndGet();
        clearStored(zContext);
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS);
        } catch (Exception ignore) {
            // Best-effort: the ciphertext is already gone, so nothing can be decrypted regardless.
        }
    }

    // --- enable ----------------------------------------------------------------------------------

    /**
     * Enable biometric unlock: (re)create the Keystore key, prompt for authentication to authorise an
     * ENCRYPT cipher, then store the encrypted passphrase. Requires the caller to already hold the
     * verified plaintext passphrase (i.e. the vault is open / just re-authenticated).
     */
    public static void enable(FragmentActivity zActivity, String zPassphrase, Callback zCallback) {
        enable(zActivity, zPassphrase, () -> true, zCallback);
    }
    public static synchronized void enable(FragmentActivity zActivity, String zPassphrase,
                              java.util.function.BooleanSupplier authorized, Callback zCallback) {
        final long request = requests.incrementAndGet();
        if (!current(zActivity, request, authorized)) return;
        clearStored(zActivity);
        final Cipher cipher;
        try {
            SecretKey key = createOrReplaceKey();
            cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key);
        } catch (Exception e) {
            zCallback.onError(Language.text(zActivity, l10n_biometric_key_error, e.getMessage()), false);
            return;
        }

        BiometricPrompt.PromptInfo info = promptInfo(zActivity,
                Language.text(zActivity, l10n_biometric_enable),
                Language.text(zActivity, l10n_wallet_bio_protect_passphrase));

        prompt(zActivity, cipher, info, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                synchronized (BiometricUnlock.class) {
                if (!current(zActivity, request, authorized)) return;
                try {
                    Cipher c = result.getCryptoObject().getCipher();
                    byte[] ct = c.doFinal(zPassphrase.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    store(zActivity, ct, c.getIV());
                    zCallback.onSuccess();
                } catch (Exception e) {
                    disable(zActivity);
                    zCallback.onError(Language.text(zActivity, l10n_wallet_bio_store_error, e.getMessage()), false);
                }
            
                }
            }

            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                synchronized (BiometricUnlock.class) {
                if (!current(zActivity, request, authorized)) return;
                if (isCancel(errorCode)) zCallback.onCancelled();
                else zCallback.onError(errString.toString(), false);
            
                }
            }
        });
    }

    // --- unlock ----------------------------------------------------------------------------------

    /**
     * Prompt for biometric auth and, on success, decrypt the stored passphrase and hand it back. If the
     * key was invalidated by a biometric-enrolment change ({@link KeyPermanentlyInvalidatedException}),
     * the stored entry is cleared and the error reports {@code permanentlyInvalidated=true} so the caller
     * can tell the user to re-enable and fall back to the typed passphrase.
     */
    public static void unlock(FragmentActivity zActivity, UnlockCallback zCallback) {
        unlock(zActivity, () -> true, zCallback);
    }
    public static synchronized void unlock(FragmentActivity zActivity, java.util.function.BooleanSupplier authorized, UnlockCallback zCallback) {
        final long request = requests.incrementAndGet();
        if (!current(zActivity, request, authorized)) return;
        SharedPreferences p = prefs(zActivity);
        String ctB64 = p.getString(K_CT, null);
        String ivB64 = p.getString(K_IV, null);
        if (ctB64 == null || ivB64 == null) {
            zCallback.onError(Language.text(zActivity, l10n_biometric_not_set_up), false);
            return;
        }

        final byte[] ct = Base64.decode(ctB64, Base64.NO_WRAP);
        final byte[] iv = Base64.decode(ivB64, Base64.NO_WRAP);

        final Cipher cipher;
        try {
            SecretKey key = loadKey();
            if (key == null) {
                clearStored(zActivity);
                zCallback.onError(Language.text(zActivity, l10n_biometric_key_missing), true);
                return;
            }
            cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        } catch (KeyPermanentlyInvalidatedException invalidated) {
            disable(zActivity);
            zCallback.onError(Language.text(zActivity, l10n_wallet_bio_reset), true);
            return;
        } catch (Exception e) {
            zCallback.onError(Language.text(zActivity, l10n_biometric_key_error, e.getMessage()), false);
            return;
        }

        BiometricPrompt.PromptInfo info = promptInfo(zActivity,
                Language.text(zActivity, l10n_wallet_unlock_title), Language.text(zActivity, l10n_biometric_confirm));

        prompt(zActivity, cipher, info, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                synchronized (BiometricUnlock.class) {
                if (!current(zActivity, request, authorized)) return;
                try {
                    Cipher c = result.getCryptoObject().getCipher();
                    byte[] plain = c.doFinal(ct);
                    zCallback.onPassphrase(new String(plain, java.nio.charset.StandardCharsets.UTF_8));
                } catch (Exception e) {
                    zCallback.onError(Language.text(zActivity, l10n_wallet_bio_decrypt_error, e.getMessage()), false);
                }
            
                }
            }

            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                synchronized (BiometricUnlock.class) {
                if (!current(zActivity, request, authorized)) return;
                if (isCancel(errorCode)) zCallback.onCancelled();
                else zCallback.onError(errString.toString(), false);
            
                }
            }
        });
    }

    // --- internals -------------------------------------------------------------------------------

    private static void prompt(FragmentActivity zActivity, Cipher zCipher,
                               BiometricPrompt.PromptInfo zInfo,
                               BiometricPrompt.AuthenticationCallback zCb) {
        Executor exec = ContextCompat.getMainExecutor(zActivity);
        BiometricPrompt bp = new BiometricPrompt(zActivity, exec, zCb);
        try {
            bp.authenticate(zInfo, new BiometricPrompt.CryptoObject(zCipher));
        } catch (Exception e) {   // e.g. a device rejecting the crypto+authenticator combo — report instead of crashing
            zCb.onAuthenticationError(BiometricPrompt.ERROR_HW_UNAVAILABLE, Language.text(zActivity, l10n_biometric_prompt_error, e.getMessage()));
        }
    }

    private static BiometricPrompt.PromptInfo promptInfo(Context context, String zTitle, String zSubtitle) {
        BiometricPrompt.PromptInfo.Builder b = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(zTitle)
                .setSubtitle(zSubtitle)
                .setAllowedAuthenticators(allowedAuthenticators())
                .setNegativeButtonText(Language.text(context, l10n_wallet_use_passphrase));   // required whenever DEVICE_CREDENTIAL isn't an allowed authenticator
        return b.build();
    }

    private static SecretKey createOrReplaceKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS);

        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Allow both a strong biometric and the device credential to authorise a use.
            spec.setUserAuthenticationParameters(0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL);
        }
        kg.init(spec.build());
        return kg.generateKey();
    }

    private static SecretKey loadKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        return (SecretKey) ks.getKey(KEY_ALIAS, null);
    }

    private static void store(Context zContext, byte[] zCt, byte[] zIv) {
        prefs(zContext).edit()
                .putString(K_CT, Base64.encodeToString(zCt, Base64.NO_WRAP))
                .putString(K_IV, Base64.encodeToString(zIv, Base64.NO_WRAP))
                .apply();
    }

    private static void clearStored(Context zContext) {
        prefs(zContext).edit().remove(K_CT).remove(K_IV).apply();
    }

    private static SharedPreferences prefs(Context zContext) {
        return zContext.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean isCancel(int zErrorCode) {
        return zErrorCode == BiometricPrompt.ERROR_USER_CANCELED
                || zErrorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                || zErrorCode == BiometricPrompt.ERROR_CANCELED;
    }
}
