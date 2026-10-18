package com.eurobuddha.salon;

import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Test;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class SalonSecureCryptTest {
    @Test public void unavailableKeystoreNeverFallsBackToPlaintextForIdentity() throws Exception {
        try(MockedStatic<KeyStore> keys=mockStatic(KeyStore.class)) {
            keys.when(()->KeyStore.getInstance("AndroidKeyStore")).thenThrow(new java.security.KeyStoreException("locked"));
            assertThrows(SalonComms.IdentityUnavailable.class,()->Crypt.encryptSecure("ab".repeat(32)));
        }
    }
    @Test public void missingDecryptKeyIsNotRegenerated() throws Exception {
        KeyStore store=mock(KeyStore.class);
        try(MockedStatic<KeyStore> keys=mockStatic(KeyStore.class);
            MockedStatic<KeyGenerator> generators=mockStatic(KeyGenerator.class);
            MockedStatic<Base64> base64=mockStatic(Base64.class)) {
            keys.when(()->KeyStore.getInstance("AndroidKeyStore")).thenReturn(store);
            base64.when(()->Base64.decode(anyString(),anyInt())).thenReturn(new byte[40]);
            assertThrows(SalonComms.IdentityUnavailable.class,()->Crypt.decryptSecure("gcm:fixture"));
            verify(store).getEntry("pandamonium_salon_hosting_key",null);
            generators.verifyNoInteractions();
        }
    }
    @Test public void existingAesGcmFormatRoundTripsAndRejectsTampering() throws Exception {
        KeyStore store=mock(KeyStore.class);SecretKey secret=new SecretKeySpec(new byte[32],"AES");
        when(store.getEntry("pandamonium_salon_hosting_key",null)).thenReturn(new KeyStore.SecretKeyEntry(secret));
        try(MockedStatic<KeyStore> keys=mockStatic(KeyStore.class);MockedStatic<Base64> base64=mockStatic(Base64.class)) {
            keys.when(()->KeyStore.getInstance("AndroidKeyStore")).thenReturn(store);
            base64.when(()->Base64.encodeToString(any(byte[].class),anyInt())).thenAnswer(i->java.util.Base64.getEncoder().encodeToString(i.getArgument(0)));
            base64.when(()->Base64.decode(anyString(),anyInt())).thenAnswer(i->java.util.Base64.getDecoder().decode((String)i.getArgument(0)));
            String value="ab".repeat(32),wrapped=Crypt.encryptSecure(value);
            assertTrue(wrapped.startsWith("gcm:"));assertEquals(value,Crypt.decryptSecure(wrapped));
            byte[] bytes=java.util.Base64.getDecoder().decode(wrapped.substring(4));bytes[bytes.length-1]^=1;
            String tampered="gcm:"+java.util.Base64.getEncoder().encodeToString(bytes);
            assertThrows(SalonComms.IdentityUnavailable.class,()->Crypt.decryptSecure(tampered));
        }
    }
    @Test public void legacyStoredSeedsRemainReadable() {
        assertEquals("ab".repeat(32),Crypt.decryptSecure("plain:"+"ab".repeat(32)));
        assertEquals("ab".repeat(32),Crypt.decryptSecure("ab".repeat(32)));
    }
}
