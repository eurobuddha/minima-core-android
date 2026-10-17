package com.eurobuddha.wallet;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

/** Disposable backup fixtures; never uses an owner seed or node. */
public class VaultInputValidationTest {
    private static final String PASS = "disposable backup passphrase";
    private static final String VALID = "{\"v\":1,\"phrase\":\"disposable backup phrase\",\"trusted\":true,\"keyuses\":{\"0\":7}}";
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void rejects(String json) {
        assertThrows(IllegalArgumentException.class, () -> VaultBlob.fromBytes(bytes(json)));
    }
    @Test public void existingV1BackupsPreserveExactCountsIncludingExhaustedKeys() {
        VaultBlob blob = VaultBlob.fromBytes(bytes(VALID.replace("7}", "2147483647,\"63\":262144,\"64\":9}")));
        assertEquals(Integer.valueOf(Integer.MAX_VALUE), blob.getKeyUses().get(0));
        assertEquals(Integer.valueOf(262144), blob.getKeyUses().get(63));
        assertEquals(Integer.valueOf(9), blob.getKeyUses().get(64));
        VaultBlob roundTrip = VaultBlob.fromBytes(blob.toBytes());
        assertEquals(blob.getKeyUses(), roundTrip.getKeyUses());
        assertEquals(blob.getPhrase(), roundTrip.getPhrase());
        assertTrue(roundTrip.isKeyUsesTrusted());
    }
    @Test public void malformedOrMissingSchemaCannotBecomeTrusted() {
        for (String version : new String[]{"0", "2", "null", "\"1\"", "1.0"})
            rejects(VALID.replace("\"v\":1", "\"v\":" + version));
        for (String flag : new String[]{"null", "\"true\"", "1"})
            rejects(VALID.replace("true", flag));
        for (String phrase : new String[]{"null", "0", "\"\"", "\"  \""})
            rejects(VALID.replace("\"disposable backup phrase\"", phrase));
        rejects(VALID.replace("\"v\":1,", ""));
        rejects(VALID.replace(",\"trusted\":true", ""));
        rejects(VALID.replace(",\"keyuses\":{\"0\":7}", ""));
        rejects(VALID.replace("{\"0\":7}", "null"));
        rejects(VALID.replace("{\"0\":7}", "[]"));
        rejects("null");
        rejects("[]");
        rejects(VALID + "{}");
    }
    @Test public void badCountsAndAmbiguousIndicesNeverDefaultToZero() {
        for (String count : new String[]{"null", "\"7\"", "true", "-1", "0.5", "2.0000000000000001", "2147483648", "9223372036854775808", "{}", "[]"})
            rejects(VALID.replace(":7}", ":" + count + "}"));
        for (String index : new String[]{"-1", "+0", "00", "01", "1.0", "2147483648", "a", ""})
            rejects(VALID.replace("\"0\":7", "\"" + index + "\":7"));
    }
    @Test public void duplicateFieldsCannotOverwriteHigherCounterOrPhrase() {
        rejects(VALID.replace("\"0\":7", "\"0\":7,\"0\":0"));
        rejects(VALID.replace("\"0\":7", "\"0\":7,\"\\u0030\":0"));
        rejects(VALID.replace("\"v\":1", "\"v\":2,\"v\":1"));
        rejects(VALID.replace("\"trusted\":true", "\"trusted\":false,\"trusted\":true"));
        rejects(VALID.replace("\"phrase\":", "\"phrase\":\"other disposable phrase\",\"phrase\":"));
    }
    @Test public void invalidUtf8AndOversizedPayloadAreRejected() {
        byte[] invalid = bytes(VALID);
        invalid[VALID.indexOf("disposable")] = (byte) 0xFF;
        assertThrows(IllegalArgumentException.class, () -> VaultBlob.fromBytes(invalid));
        assertThrows(IllegalArgumentException.class, () -> VaultBlob.fromBytes(new byte[VaultCrypto.MAX_PLAIN_BYTES + 1]));
        assertThrows(IllegalArgumentException.class, () -> new VaultBlob("", true, Collections.emptyMap()));
        assertThrows(IllegalArgumentException.class, () -> new VaultBlob("fixture", true, Collections.singletonMap(-1, 0)));
        assertThrows(IllegalArgumentException.class, () -> new VaultBlob("fixture", true, Collections.singletonMap(0, -1)));
    }
    @Test public void malformedAuthenticatedBackupCannotReplaceVaultOrChangeCounters() throws Exception {
        SeedCounterLifecycleTest.Store store = new SeedCounterLifecycleTest.Store();
        SeedCounterLifecycleTest.Counters counters = new SeedCounterLifecycleTest.Counters();
        SeedVault vault = new SeedVault(store, counters);
        vault.importSeed("existing disposable phrase", PASS, 12);
        String stored = store.value;
        KeyUses signer = vault.keyUses();
        byte[] malformed = VaultCrypto.encrypt(PASS, bytes(VALID.replace("7}", "900,\"1\":-1}")), 1);
        assertThrows(IllegalArgumentException.class, () -> vault.importVault(malformed, PASS, PASS));
        assertEquals(stored, store.value);
        assertEquals("existing disposable phrase", vault.phrase());
        assertEquals(1, counters.seeds.size());
        assertEquals(12, signer.currentUses(0));
        assertEquals(12, signer.reserveNextUse(0));
    }
    @Test public void truncatedAndInvalidHeadersRefuseBeforePasswordWork() throws Exception {
        byte[] container = VaultCrypto.encrypt(PASS, bytes(VALID), 1);
        for (int n = 0; n < 56; n++) {
            byte[] truncated = Arrays.copyOf(container, n);
            assertThrows(VaultCrypto.BadPassphraseException.class, () -> VaultCrypto.decrypt(PASS, truncated));
        }
        for (int offset : new int[]{10, 27}) {
            for (int length : new int[]{0, 1, 255}) {
                byte[] malformed = container.clone(); malformed[offset] = (byte) length;
                assertThrows(VaultCrypto.BadPassphraseException.class, () -> VaultCrypto.decrypt(PASS, malformed));
            }
        }
        for (int iterations : new int[]{0, -1, VaultCrypto.MAX_ITERATIONS + 1, Integer.MAX_VALUE}) {
            byte[] malformed = container.clone();
            for (int i=0;i<4;i++) malformed[6+i]=(byte)(iterations >>> (24-i*8));
            assertThrows(VaultCrypto.BadPassphraseException.class, () -> VaultCrypto.decrypt(PASS, malformed));
            assertThrows(IllegalArgumentException.class, () -> VaultCrypto.encrypt(PASS, bytes(VALID), iterations));
        }
        assertArrayEquals(bytes(VALID), VaultCrypto.decrypt(PASS, container));
    }
    @Test public void inputReaderAndCryptoEnforceTheSameByteLimit() throws Exception {
        byte[] maximum = new byte[VaultCrypto.MAX_CONTAINER_BYTES];
        assertEquals(maximum.length, VaultCrypto.readContainer(new ByteArrayInputStream(maximum)).length);
        byte[] tooLarge = new byte[VaultCrypto.MAX_CONTAINER_BYTES + 1];
        assertThrows(IOException.class, () -> VaultCrypto.readContainer(new ByteArrayInputStream(tooLarge)));
        assertThrows(VaultCrypto.BadPassphraseException.class, () -> VaultCrypto.decrypt(PASS, tooLarge));
        assertThrows(IllegalArgumentException.class, () -> VaultCrypto.encrypt(PASS, new byte[VaultCrypto.MAX_PLAIN_BYTES + 1], 1));
        byte[] plain = new byte[VaultCrypto.MAX_PLAIN_BYTES];
        byte[] maximumEncrypted = VaultCrypto.encrypt(PASS, plain, 1);
        assertEquals(VaultCrypto.MAX_CONTAINER_BYTES, maximumEncrypted.length);
        assertArrayEquals(plain, VaultCrypto.decrypt(PASS, maximumEncrypted));
    }
    @Test public void settingsReauthenticationPreservesActiveSigningGeneration() {
        SeedCounterLifecycleTest.Store store = new SeedCounterLifecycleTest.Store();
        SeedVault vault = new SeedVault(store, new SeedCounterLifecycleTest.Counters());
        vault.importSeed("existing disposable phrase", PASS, 12);
        KeyUses signer = vault.keyUses();
        String stored = store.value;
        assertTrue(vault.verifyPassphrase(PASS));
        assertFalse(vault.verifyPassphrase("wrong fixture passphrase"));
        assertEquals(stored, store.value);
        assertEquals(12, signer.reserveNextUse(0));
        vault.changePassphrase("new disposable passphrase");
        assertTrue(vault.verifyPassphrase("new disposable passphrase"));
        assertFalse(vault.verifyPassphrase(PASS));
        assertEquals(13, signer.reserveNextUse(0));
        vault.lock();
        assertThrows(IllegalStateException.class, () -> vault.verifyPassphrase("new disposable passphrase"));
    }
    @Test public void settingsReauthenticationCannotAuthorizeAReplacedStoredSeed() throws Exception {
        SeedCounterLifecycleTest.Store store = new SeedCounterLifecycleTest.Store();
        SeedVault vault = new SeedVault(store, new SeedCounterLifecycleTest.Counters());
        vault.importSeed("existing disposable phrase", PASS, 12);
        byte[] other = VaultCrypto.encrypt(PASS, bytes(VALID), 1);
        store.value = org.minima.utils.BaseConverter.encode16(other);
        assertFalse(vault.verifyPassphrase(PASS));
        assertEquals("existing disposable phrase", vault.phrase());
        assertEquals(12, vault.keyUses().currentUses(0));
    }

    @Test public void unavailableNodeDoesNotMisreportValidVaultAsCorrupt() {
        SeedCounterLifecycleTest.Store store = new SeedCounterLifecycleTest.Store();
        SeedCounterLifecycleTest.Counters counters = new SeedCounterLifecycleTest.Counters();
        boolean[] ready = {true};
        SeedVault vault = new SeedVault(store, phrase -> {
            if (!ready[0]) throw new IllegalStateException("fixture node stopped");
            return counters.forPhrase(phrase);
        });
        vault.importSeed("existing disposable phrase", PASS, 12);
        vault.lock();
        String saved = store.value;
        ready[0] = false;
        assertThrows(SeedVault.VaultUnavailableException.class, () -> vault.open(PASS));
        assertFalse(vault.isOpen());
        assertEquals(saved, store.value);
        ready[0] = true;
        assertTrue(vault.open(PASS));
        assertEquals(12, vault.keyUses().currentUses(0));
    }

}
