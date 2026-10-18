package com.eurobuddha.salon;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.*;

/** Embedded delivery must not create a Salon identity for someone who never opened it. */
public class SalonEmbeddedEventsTest {
    @Test public void unusedSalonIgnoresWalletEventsWithoutCreatingKeys() throws Exception {
        Context context=mock(Context.class);when(context.getApplicationContext()).thenReturn(context);
        try (MockedStatic<SalonStore> store=mockStatic(SalonStore.class);
             MockedStatic<SalonComms> crypto=mockStatic(SalonComms.class)) {
            store.when(()->SalonStore.get(context,"msgseed")).thenReturn("");
            new SalonNotifyReceiver(context).onEvent(dm());
            crypto.verifyNoInteractions();
        }
    }
    @Test public void configuredInboxStillUsesTheExistingSignatureVerifiedIntake() throws Exception {
        Context context=mock(Context.class);when(context.getApplicationContext()).thenReturn(context);
        try (MockedStatic<SalonStore> store=mockStatic(SalonStore.class);
             MockedStatic<SalonComms> crypto=mockStatic(SalonComms.class)) {
            store.when(()->SalonStore.get(context,"msgseed")).thenReturn("fixture-wrapped-seed");
            store.when(()->SalonStore.get(context,"tipaddr")).thenReturn("");
            new SalonNotifyReceiver(context).onEvent(dm());
            crypto.verify(()->SalonComms.crypto(context));
        }
    }
    private JSONObject dm() throws Exception {
        JSONObject coin=new JSONObject().put("coinid","0x12").put("state",new JSONArray()
                .put(new JSONObject().put("port",99).put("data","0x00")));
        return new JSONObject().put("event","NEWCOIN").put("data",coin);
    }
}
