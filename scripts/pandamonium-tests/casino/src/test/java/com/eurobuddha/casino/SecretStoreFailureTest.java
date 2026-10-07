package com.eurobuddha.casino;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class SecretStoreFailureTest {
    @Test public void outageCannotCreatePlaintextCommitmentsAndLegacySecretsSurviveRecovery() throws Exception {
        Context context=mock(Context.class); MasterKey key=mock(MasterKey.class);
        SharedPreferences legacy=mock(SharedPreferences.class), secure=mock(SharedPreferences.class);
        when(context.getSharedPreferences("casino_secrets_plain",Context.MODE_PRIVATE)).thenReturn(legacy);
        when(legacy.getString("casino_secret_for_old",null)).thenReturn("old-preimage");
        when(secure.getString("casino_psecret_for_new",null)).thenReturn("encrypted-preimage");
        try(MockedConstruction<MasterKey.Builder> builders=mockConstruction(MasterKey.Builder.class,(b,c)->{
            when(b.setKeyScheme(any())).thenReturn(b); when(b.build()).thenReturn(key);
        }); MockedStatic<EncryptedSharedPreferences> encrypted=mockStatic(EncryptedSharedPreferences.class)) {
            encrypted.when(()->EncryptedSharedPreferences.create(eq(context),eq("casino_secrets"),eq(key),any(),any()))
                    .thenThrow(new java.security.GeneralSecurityException("temporarily unavailable")).thenReturn(secure);
            SecretStore unavailable=new SecretStore(context);
            assertFalse(unavailable.putHouseSecret("new","unsafe"));
            assertFalse(unavailable.putPlayerSecret("new","unsafe"));
            unavailable.putHistory("[]");
            assertEquals("old-preimage",unavailable.houseSecret("old"));
            verify(legacy,never()).edit();
            SecretStore recovered=new SecretStore(context);
            assertEquals("old-preimage",recovered.houseSecret("old"));
            assertEquals("encrypted-preimage",recovered.playerSecret("new"));
            verify(context,never()).deleteSharedPreferences(anyString());
        }
    }
}
