package com.eurobuddha.wallet;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.minima.utils.json.JSONObject;
import org.minima.utils.json.parser.JSONParser;

/**
 * VaultBlob — the <b>plaintext</b> payload that {@link VaultCrypto} encrypts: the wallet seed phrase,
 * a snapshot of the per-key WOTS {@code keyuses} map, and a {@code keyusesTrusted} flag.
 *
 * <p><b>Why the keyuses snapshot lives here too.</b> The live counter is the plaintext, crash-safe,
 * two-mirror {@link PrefsKeyUses} (unchanged from M0/M2 — the encryption <i>wraps</i> it, it does not
 * replace or weaken it). The seedless mirrors are app-private (they may travel to a new device via
 * Android Auto Backup, which is safe since they can only RAISE the counter). The seed VAULT, however, is
 * deliberately EXCLUDED from Auto Backup, so the vault also carries a keyuses SNAPSHOT, encrypted with the
 * seed-derived key, that travels ONLY via the user's manual encrypted export. On restore, the snapshot is
 * folded into the live counter under the MAX rule — it can only ever RAISE the counter, never lower it
 * ({@link #reconcileInto}). The snapshot is refreshed after every signature so an export stays current.
 *
 * <p><b>keyusesTrusted (the fail-safe flag).</b> {@code true} means "this keyuses record is a
 * trustworthy account of how many one-time leaves have been consumed" — set for a freshly generated
 * seed (0 uses, nothing spent yet) or for an imported keyuses backup. {@code false} means a bare seed
 * was imported with no uses history: the true count is UNKNOWN, and signing MUST be blocked (a reuse
 * would expose the key and lose funds) until the user either confirms the seed is brand-new or imports
 * a keyuses backup. See {@link SeedVault#assertSigningAllowed()}.
 *
 * <p>Pure Java (bundled {@code org.minima.utils.json}) — unit-testable off-device.
 */
public final class VaultBlob {

    /** Blob schema version. */
    public static final int VERSION = 1;

    private final String mPhrase;
    private final boolean mKeyUsesTrusted;
    /** keyIndex → uses snapshot. */
    private final LinkedHashMap<Integer, Integer> mKeyUses;

    public VaultBlob(String zPhrase, boolean zKeyUsesTrusted, Map<Integer, Integer> zKeyUses) {
        if (zPhrase == null || zPhrase.trim().isEmpty() || zKeyUses == null)
            throw new IllegalArgumentException("Missing vault phrase or key uses");
        mPhrase         = zPhrase;
        mKeyUsesTrusted = zKeyUsesTrusted;
        mKeyUses        = new LinkedHashMap<>();
        if (zKeyUses != null) {
            for (Map.Entry<Integer, Integer> e : zKeyUses.entrySet()) {
                if (e.getKey() == null || e.getValue() == null || e.getKey() < 0 || e.getValue() < 0)
                    throw new IllegalArgumentException("Invalid vault key uses");
                mKeyUses.put(e.getKey(), e.getValue());
            }
        }
    }

    public String getPhrase()          { return mPhrase; }
    public boolean isKeyUsesTrusted()  { return mKeyUsesTrusted; }

    /** A copy of the keyuses snapshot (keyIndex → uses). */
    public Map<Integer, Integer> getKeyUses() {
        return new LinkedHashMap<>(mKeyUses);
    }

    /** This blob with the trust flag flipped (immutably). */
    public VaultBlob withTrusted(boolean zTrusted) {
        return new VaultBlob(mPhrase, zTrusted, mKeyUses);
    }

    /** This blob carrying a new keyuses snapshot (immutably). */
    public VaultBlob withKeyUses(Map<Integer, Integer> zKeyUses) {
        return new VaultBlob(mPhrase, mKeyUsesTrusted, zKeyUses);
    }

    // ---------------------------------------------------------------------------------------------
    // Serialization (UTF-8 JSON)
    // ---------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public byte[] toBytes() {
        JSONObject root = new JSONObject();
        root.put("v", VERSION);
        root.put("phrase", mPhrase);
        root.put("trusted", mKeyUsesTrusted);
        JSONObject uses = new JSONObject();
        for (Map.Entry<Integer, Integer> e : mKeyUses.entrySet()) {
            uses.put(String.valueOf(e.getKey()), e.getValue());
        }
        root.put("keyuses", uses);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static VaultBlob fromBytes(byte[] zPlain) {
        try {
            if (zPlain == null || zPlain.length > VaultCrypto.MAX_PLAIN_BYTES)
                throw new IllegalArgumentException("Vault payload too large or missing");
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(zPlain)).toString();
            // Reuse the bundled parser's container hook. Never let Map.put silently
            // overwrite a duplicate counter, trust flag or recovery phrase.
            JSONObject root = (JSONObject) new JSONParser().parse(json,
                    new org.minima.utils.json.parser.ContainerFactory() {
                private int objects;
                @Override public java.util.Map createObjectContainer() {
                    if (++objects > 2) throw new IllegalArgumentException("Unexpected vault object");
                    return new JSONObject() {
                        @Override public Object put(Object key, Object value) {
                            if (containsKey(key)) throw new IllegalArgumentException("Duplicate vault field");
                            return super.put(key, value);
                        }
                    };
                }
                @Override public java.util.List creatArrayContainer() {
                    throw new IllegalArgumentException("Unexpected vault array");
                }
            });
            if (root == null || root.size() != 4 || !(root.get("v") instanceof Long)
                    || ((Long) root.get("v")) != VERSION || !(root.get("phrase") instanceof String)
                    || !(root.get("trusted") instanceof Boolean) || !(root.get("keyuses") instanceof JSONObject))
                throw new IllegalArgumentException("Unsupported or malformed vault schema");
            String phrase = (String) root.get("phrase");
            boolean trusted = (Boolean) root.get("trusted");

            LinkedHashMap<Integer, Integer> uses = new LinkedHashMap<>();
            Object u = root.get("keyuses");
            if (u instanceof JSONObject) {
                JSONObject um = (JSONObject) u;
                for (Object k : um.keySet()) {
                    String index = String.valueOf(k);
                    if (!index.matches("0|[1-9][0-9]*"))
                        throw new IllegalArgumentException("Invalid vault key index");
                    int idx = Integer.parseInt(index);
                    Object count = um.get(k);
                    if (!(count instanceof Long) || (Long) count < 0 || (Long) count > Integer.MAX_VALUE)
                        throw new IllegalArgumentException("Invalid vault key count");
                    int val = ((Long) count).intValue();
                    uses.put(idx, val);
                }
            }
            return new VaultBlob(phrase, trusted, uses);
        } catch (Exception e) {
            throw new IllegalArgumentException("Corrupt vault payload", e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Keyuses reconcile (MAX rule — never lowers the counter)
    // ---------------------------------------------------------------------------------------------

    /**
     * Fold this blob's keyuses snapshot into a live {@link KeyUses} store under the MAX rule: for every
     * (keyIndex, uses) in the snapshot, {@code zLive.recordExternalUses(keyIndex, uses)} which raises the
     * live counter to at least {@code uses} but never lowers it. This is the restore path — after it,
     * the live counter is ≥ both what it was and what the backup recorded, so a partial or stale restore
     * can never cause a one-time leaf to be reused.
     */
    public void reconcileInto(KeyUses zLive) {
        for (Map.Entry<Integer, Integer> e : mKeyUses.entrySet()) {
            zLive.recordExternalUses(e.getKey(), e.getValue());
        }
    }

}
