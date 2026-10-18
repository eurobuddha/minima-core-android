package com.eurobuddha.salon;

import java.lang.reflect.*;
import org.json.*;
import org.junit.Test;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class HostingRestoreBoundaryTest {
    @Test public void secondCredentialEncryptionFailureCannotPartiallyReplaceSavedProfilesOrIdentity() throws Exception {
        MainActivity activity=mock(MainActivity.class,CALLS_REAL_METHODS);
        doNothing().when(activity).runOnUiThread(any());
        JSONObject backup=new JSONObject().put("salonBackup",1).put("me",new JSONObject().put("handle","imported"))
                .put("hosting",new JSONArray().put(new JSONObject().put("id","first").put("type","sftp").put("sftp",new JSONObject().put("password","first")))
                        .put(new JSONObject().put("id","second").put("type","github").put("github",new JSONObject().put("token","second"))));
        try(MockedStatic<Crypt> crypt=mockStatic(Crypt.class);MockedStatic<HostingStore> hosts=mockStatic(HostingStore.class);
            MockedStatic<SalonStore> store=mockStatic(SalonStore.class);MockedStatic<SalonComms> identity=mockStatic(SalonComms.class)) {
            crypt.when(()->Crypt.encrypt("first")).thenReturn("gcm:first");
            crypt.when(()->Crypt.encrypt("second")).thenThrow(new Crypt.Unavailable());
            Method restore=MainActivity.class.getDeclaredMethod("restoreFromBackup",String.class);restore.setAccessible(true);restore.invoke(activity,backup.toString());
            crypt.verify(()->Crypt.encrypt("second"));
            hosts.verifyNoInteractions();store.verifyNoInteractions();identity.verifyNoInteractions();
        }
    }
    @Test public void unreadableInactiveCredentialAbortsFullBackupInsteadOfExportingAnEmptySecret() throws Exception {
        MainActivity activity=mock(MainActivity.class,CALLS_REAL_METHODS);
        Hosting.Profile profile=new Hosting.Profile(new JSONObject().put("type","sftp")
                .put("sftp",new JSONObject().put("password","plain:first"))
                .put("github",new JSONObject().put("token","gcm:unreadable")));
        try(MockedStatic<Crypt> crypt=mockStatic(Crypt.class);MockedStatic<HostingStore> hosts=mockStatic(HostingStore.class);
            MockedStatic<SalonStore> store=mockStatic(SalonStore.class);MockedStatic<SalonComms> identity=mockStatic(SalonComms.class)) {
            store.when(()->SalonStore.me(activity)).thenReturn(new JSONObject());identity.when(()->SalonComms.exportSeed(activity)).thenReturn("");
            hosts.when(()->HostingStore.list(activity)).thenReturn(java.util.Arrays.asList(profile));
            crypt.when(()->Crypt.decrypt("plain:first")).thenReturn("first");
            crypt.when(()->Crypt.decrypt("gcm:unreadable")).thenThrow(new Crypt.Unavailable());
            Method backup=MainActivity.class.getDeclaredMethod("buildBackupJson");backup.setAccessible(true);
            InvocationTargetException failure=assertThrows(InvocationTargetException.class,()->backup.invoke(activity));
            assertTrue(failure.getCause() instanceof Crypt.Unavailable);
            assertEquals("gcm:unreadable",profile.j.getJSONObject("github").getString("token"));
        }
    }
}
