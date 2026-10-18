package com.eurobuddha.salon;

import java.util.*;
import org.json.*;
import org.junit.Test;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.eurobuddha.minimacore.i18n.Language;

public class HostingSecretsTest {
    @Test public void failedEditKeepsOldCiphertextAndRetriesTheExactDraftAfterRecovery() throws Exception {
        Hosting.Profile profile=Hosting.Profile.fresh("sftp");JSONObject cfg=profile.cfg();cfg.put("password","gcm:old");
        HostingSecrets edits=new HostingSecrets();
        try(MockedStatic<Crypt> crypt=mockStatic(Crypt.class)) {
            crypt.when(()->Crypt.encrypt("typed secret")).thenThrow(new Crypt.Unavailable());
            assertFalse(edits.write(cfg,"password","typed secret"));
            assertEquals("gcm:old",cfg.getString("password"));assertEquals("typed secret",edits.value(cfg,"password",false));
            assertFalse(edits.retry(profile));assertEquals("gcm:old",cfg.getString("password"));
            crypt.when(()->Crypt.encrypt("typed secret")).thenReturn("gcm:new");
            assertTrue(edits.retry(profile));assertEquals("gcm:new",cfg.getString("password"));assertFalse(edits.pending(cfg,"password"));
        }
    }
    @Test public void failuresAreScopedToTheirProfileAndBlankReplacementCanBeDiscarded() throws Exception {
        Hosting.Profile first=Hosting.Profile.fresh("sftp"),second=Hosting.Profile.fresh("sftp");
        HostingSecrets edits=new HostingSecrets();first.cfg().put("password","gcm:old");
        try(MockedStatic<Crypt> crypt=mockStatic(Crypt.class)) {
            crypt.when(()->Crypt.encrypt("draft")).thenThrow(new Crypt.Unavailable());
            assertFalse(edits.write(first.cfg(),"password","draft"));assertTrue(edits.retry(second));
            edits.discard(first.cfg(),"password");assertTrue(edits.retry(first));assertEquals("gcm:old",first.cfgStr("password"));
        }
    }
    @Test public void restorePreparesActiveAndInactiveSecretsWithoutChangingInput() throws Exception {
        JSONObject profile=new JSONObject().put("type","sftp").put("sftp",new JSONObject().put("password","one"))
                .put("github",new JSONObject().put("token","two"));
        JSONArray input=new JSONArray().put(profile);String before=input.toString();
        try(MockedStatic<Crypt> crypt=mockStatic(Crypt.class)) {
            crypt.when(()->Crypt.encrypt(anyString())).thenAnswer(i->"gcm:"+i.getArgument(0));
            crypt.when(()->Crypt.decrypt(anyString())).thenAnswer(i->((String)i.getArgument(0)).substring(4));
            JSONObject prepared=HostingSecrets.prepareRestore(input).getJSONObject(0);
            assertEquals("gcm:one",prepared.getJSONObject("sftp").getString("password"));
            assertEquals("gcm:two",prepared.getJSONObject("github").getString("token"));
            JSONObject exported=HostingSecrets.copySecrets(prepared,false);
            assertEquals("one",exported.getJSONObject("sftp").getString("password"));
            assertEquals("two",exported.getJSONObject("github").getString("token"));assertEquals(before,input.toString());
        }
    }
    @Test public void partialEncryptionAndMalformedSecretsNeverReturnARestorableBatch() throws Exception {
        JSONArray input=new JSONArray().put(new JSONObject().put("sftp",new JSONObject().put("password","one")))
                .put(new JSONObject().put("github",new JSONObject().put("token","two")));
        String before=input.toString();
        try(MockedStatic<Crypt> crypt=mockStatic(Crypt.class)) {
            crypt.when(()->Crypt.encrypt("one")).thenReturn("gcm:one");
            crypt.when(()->Crypt.encrypt("two")).thenThrow(new Crypt.Unavailable());
            assertThrows(Crypt.Unavailable.class,()->HostingSecrets.prepareRestore(input));assertEquals(before,input.toString());
            assertThrows(IllegalArgumentException.class,()->HostingSecrets.prepareRestore(new JSONArray().put(JSONObject.NULL)));
            assertThrows(IllegalArgumentException.class,()->HostingSecrets.prepareRestore(new JSONArray().put(
                new JSONObject().put("sftp",new JSONObject().put("password",true)))));
        }
    }
    @Test public void unreadableSelectedCredentialStopsUploaderCreationButInactiveAuthDoesNot() throws Exception {
        Hosting.Profile profile=Hosting.Profile.fresh("sftp");profile.cfg().put("auth","key").put("privateKey","gcm:bad").put("password","gcm:unused");
        Language.Message notice=mock(Language.Message.class);when(notice.toString()).thenReturn("Secure storage unavailable");
        try(MockedStatic<Crypt> crypt=mockStatic(Crypt.class);MockedStatic<Language> language=mockStatic(Language.class)) {
            language.when(()->Language.msg(anyInt(),any(Object[].class))).thenReturn(notice);
            crypt.when(()->Crypt.decrypt("gcm:bad")).thenThrow(new Crypt.Unavailable());
            assertThrows(Hosting.HostingException.class,()->Hosting.forProfile(profile));
            crypt.when(()->Crypt.decrypt("gcm:bad")).thenReturn("PEM");
            assertTrue(Hosting.forProfile(profile) instanceof SftpUploader);
            crypt.verify(()->Crypt.decrypt("gcm:unused"),never());
        }
    }
}
